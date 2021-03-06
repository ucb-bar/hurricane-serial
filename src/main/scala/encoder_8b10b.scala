import Chisel._

package Serial {
  // A single 8b/10b encoder/decoder, attached to the phy (for actual
  // transmission) and to the rest of the chip (via the "controller"
  // IO bundle).
  class Encoder8b10bIO extends Bundle {
    val decoded = Bits(INPUT,  width = 8)
    val encoded = Bits(OUTPUT, width = 10)
    val balance = Bool(OUTPUT)
    val control = Bool(INPUT)
  }

  class Encoder8b10b extends Module {
    val io = new Encoder8b10bIO()

    // Generates lookup tables that do the encoding, by turning the
    // strings I copied from Wikipedia to Vec[Bits] -- this is kind of
    // shitty, because Vec[Vec[UInt]] (which is what I really wanted)
    // doesn't generate efficient ROMs.
    private def generate_lookup(description: Seq[String]) = {
      Vec(description
        .map(_.split(" +"))
        .map(vec => vec.map(element => element.trim))
        .map(vec => vec.size match {
          case 3 => Seq(vec(2), vec(2), vec(2), vec(2))
          case 4 => Seq(vec(2), vec(3), vec(2), vec(3))
          case 6 => Seq(vec(2), vec(3), vec(4), vec(5))
        })
        .map(vec => vec.map(element => UInt("b" + element)))
        .flatten
        )
    }
    private val lookup_5b6b_d = generate_lookup(Consts8b10b.mapping_5b6b)
    private val lookup_3b4b_d = generate_lookup(Consts8b10b.mapping_3b4b)
    // NOTE: We don't need 12 control bits, so we are intentionally omitting the following symbols:
    //  K.32.7
    //  K.27.7
    //  K.29.7
    //  K.30.7
    // This results in all control bits having the form K.28.x* which simplifies the control
    // * K.28.7 should NOT be used as it complicates comma detection.
    private val lookup_5b6b_c = generate_lookup(Consts8b10b.control_5b6b)
    private val lookup_3b4b_c = generate_lookup(Consts8b10b.control_3b4b)

    // This helper function hides the exact indexing order from my
    // user below, so I don't have to have a huge line in there.
    private def lookup(table: Vec[UInt], decoded_word: UInt,
                       rd: UInt, run: UInt) = {
      table(decoded_word * UInt(Consts8b10b.max_mapping_word_width) +
            rd ^ UInt(1) +
            run * UInt(2)
          )
    }

    // Checks to see if there's the same number of 1's and 0's, or a
    // different number.
    private def mismatched(value: UInt) = {
      PopCount(value) != PopCount(~value)
    }

    // Checks to see if the possibility of a long run length exists
    // for the given input/rd combination -- this presumes some amount
    // of encoding table information, so you can't change those
    // without changing this!
    private def check_run(x: UInt, rd: UInt) = {
      // FIXME: This shouldn't just be "10", but I figure that's long
      // enough?  If I set it to something shorter then the indexing
      // code actually truncates this, which is a pain.
      val run = UInt(width = 10)
      run := UInt(0)
      when (rd === UInt(0)) {
        when ((x === UInt(11)) || (x === UInt(13)) || (x === UInt(14))) {
          run := UInt(1)
        }
      }
      when (rd === UInt(1)) {
        when ((x === UInt(17)) || (x === UInt(18)) || (x === UInt(20))) {
          run := UInt(1)
        }
      }
      run
    }

    // This encodes the running disparity, where "0" means a RD of
    // "-1", and "1" means a RD of "1".
    private val rd = Reg(init = UInt(0, width = 1))

    private val EDCBA = io.decoded(4, 0)
    private val HGF   = io.decoded(7, 5)

    private val abcdei = lookup(lookup_5b6b_d,
                                EDCBA,
                                rd,
                                UInt(0))
    private val rd_after_abcdei = rd ^ mismatched(abcdei)
    private val fgjh   = lookup(lookup_3b4b_d,
                                HGF,
                                rd_after_abcdei,
                                check_run(EDCBA, rd_after_abcdei))

    private val abcdei_c = lookup(lookup_5b6b_c,
                                  EDCBA,
                                  rd,
                                  UInt(0))
    private val rd_after_abcdei_c = rd ^ mismatched(abcdei_c)
    private val fgjh_c   = lookup(lookup_3b4b_c,
                                  HGF,
                                  rd_after_abcdei_c,
                                  check_run(EDCBA, rd_after_abcdei_c))

    private val encoded = UInt(width = 10)
    when(io.control) {
      // FIXME: Enforce a valid control symbol (K.28.x)
      // For now we'll just assume EDCBA === K.28 and require code not to use illegal codes
      //assert(EDCBA === UInt("11100"))
      //assert(Cat(HGF,EDCBA) != UInt("11111100")
      encoded := Cat(abcdei_c, fgjh_c)
    } .otherwise {
      encoded := Cat(abcdei, fgjh)
    }

    rd := rd ^ mismatched(encoded)

    io.encoded := encoded
    io.balance := rd
  }
}

// Tests for the 8b10b encoder, which attempts to ensure that the
// encoding meets some of the requirements of the encoding without
// actually depending on the actual instruction tables.
package SerialTests {
  import scala.collection.mutable.HashMap
  import scala.collection.mutable.MultiMap
  import scala.collection.mutable.Set

  class Encoder8b10bTester(dut: Serial.Encoder8b10b) extends Tester(dut) {
    // Another property of the encoded bitstream is that there is a
    // limit of 5 consecutive bits of the same value
    def encoded_to_bitstring(encoded: BigInt): String = {
      (0 until 10).map{i =>
        if (i < encoded.bitLength && encoded.testBit(i))
          "1"
        else
          "0"
      }.mkString("").reverse
    }

    // Here we just go ahead and encode a bunch of stuff, with the
    // goal that we eventually get good coverage.
    val d2e = new HashMap[BigInt, Set[BigInt]] with MultiMap[BigInt, BigInt]
    val e2d = new HashMap[BigInt, Set[BigInt]] with MultiMap[BigInt, BigInt]

    // Count the numbers of zeros and ones, to ensure they stay even.
    var zeroes = 1
    var ones   = 0

    // Contains the previous bit pattern, which for simplicity's sake
    // we assume starts by encoding 0x00.
    var prev_bits = ""

    for (t <- 0 until (1 << 20)) {
      val decoded = BigInt(8, rnd)
      poke(dut.io.decoded, decoded)
      step(1)
      val encoded = peek(dut.io.encoded)

      // Check to make sure the code is almost a bijection (there can
      // be up to two encoded values for each decoded value).
      d2e.addBinding(decoded, encoded)
      require(d2e.get(decoded).get.size <= 2)
      e2d.addBinding(encoded, decoded)
      require(e2d.get(encoded).get.size == 1)

      // Ensure that there's a DC line balance, which is the whole
      // point of 8b/10b.
      for (i <- 0 until 10) {
        if (i < encoded.bitLength && encoded.testBit(i))
          ones += 1
        else
          zeroes += 1
      }
      require(math.abs(zeroes - ones) < 2,
              s"Incorrect DC balance, ${zeroes} zeroes and ${ones} ones after ${encoded_to_bitstring(encoded)}")
      if (zeroes > ones)
        require(peek(dut.io.balance) == 0)
      else
        require(peek(dut.io.balance) == 1)

      def string_trunc_or_dont(in: String, end: Int): String = {
        if (in.length <= end)
          return in
        else
          return in.substring(0, end)
      }

      prev_bits = prev_bits + encoded_to_bitstring(encoded)
      prev_bits = string_trunc_or_dont(prev_bits, 99) // 99 is
                                                      // arbitrary,
                                                      // but hopefully
                                                      // safe
      require(prev_bits.contains("000000") == false)
      require(prev_bits.contains("111111") == false)

      // The additional logic inside check_run() should ensure that
      // these patterns also can't exist.
      require(prev_bits.contains("0011111") == false,
              "Detected a long run in ${encoded_to_bitstring(encoded)}")
      require(prev_bits.contains("1100000") == false,
              "Detected a long run in ${encoded_to_bitstring(encoded)}")
    }
  }

  object Encoder8b10bTester {
    def main(args: Array[String]): Unit = {
      chiselMainTest(args,
                     () => Module(new Serial.Encoder8b10b()))
      { dut => new Encoder8b10bTester(dut) }
    }
  }
}

package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Bytes} / {@code Bytes.Encode} / {@code Bytes.Decode} library. */
class BytesLibraryTest {

  private static final String BYTES = Resources.read("/elm/lib/Bytes.elm");
  private static final String ENCODE = Resources.read("/elm/lib/Bytes/Encode.elm");
  private static final String DECODE = Resources.read("/elm/lib/Bytes/Decode.elm");

  private static final String SRC =
      """
      module Main exposing (widths, raw, roundTrip8, roundTrip16BE, roundTrip16LE, roundTrip32, pair, overrun)

      import Bytes exposing (Endianness(..))
      import Bytes.Encode as E
      import Bytes.Decode as D

      sample : Bytes.Bytes
      sample = E.encode (E.sequence [ E.unsignedInt8 1, E.unsignedInt16 BE 515 ])

      widths : Int
      widths = Bytes.width sample

      raw : List Int
      raw = Bytes.toByteValues sample

      roundTrip8 : Maybe Int
      roundTrip8 = D.decode D.unsignedInt8 (E.encode (E.unsignedInt8 200))

      roundTrip16BE : Maybe Int
      roundTrip16BE = D.decode (D.unsignedInt16 BE) (E.encode (E.unsignedInt16 BE 4096))

      roundTrip16LE : Maybe Int
      roundTrip16LE = D.decode (D.unsignedInt16 LE) (E.encode (E.unsignedInt16 LE 4096))

      roundTrip32 : Maybe Int
      roundTrip32 = D.decode (D.unsignedInt32 BE) (E.encode (E.unsignedInt32 BE 70000))

      pair : Maybe ( Int, Int )
      pair = D.decode (D.map2 Tuple.pair (D.unsignedInt16 BE) D.unsignedInt8) sample

      overrun : Maybe Int
      overrun = D.decode (D.unsignedInt32 BE) (E.encode (E.unsignedInt8 1))
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, BYTES, ENCODE, DECODE).value("Main", name));
  }

  @Test
  void encodesWidthAndRawBytes() {
    assertEquals("3", value("widths"));
    assertEquals("[1,2,3]", value("raw")); // 1, then 515 = 0x0203 big-endian
  }

  @Test
  void roundTripsFixedWidthIntegers() {
    assertEquals("Just 200", value("roundTrip8"));
    assertEquals("Just 4096", value("roundTrip16BE"));
    assertEquals("Just 4096", value("roundTrip16LE"));
    assertEquals("Just 70000", value("roundTrip32"));
  }

  @Test
  void combinesDecodersAndFailsOnOverrun() {
    assertEquals("Just (258,3)", value("pair")); // bytes [1,2,3]: unsignedInt16 BE [1,2]=258, then 3
    assertEquals("Nothing", value("overrun")); // a 32-bit read on 1 byte runs off the end
  }
}

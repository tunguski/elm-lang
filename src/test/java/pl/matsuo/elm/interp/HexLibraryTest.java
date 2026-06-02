package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Hex} encode/decode library through the interpreter. */
class HexLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Hex.elm");

  private static final String SRC =
      """
      module Main exposing (a, b, c, neg, roundTrip, bad)

      import Hex

      a : String
      a = Hex.toString 255

      b : String
      b = Hex.toString 16

      c : String
      c = Hex.toString 0

      neg : String
      neg = Hex.toString -255

      roundTrip : Result String Int
      roundTrip = Hex.fromString "deadBEEF"

      bad : Result String Int
      bad = Hex.fromString "xy"
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void toStringRendersLowercaseHex() {
    assertEquals("ff", value("a"));
    assertEquals("10", value("b"));
    assertEquals("0", value("c"));
    assertEquals("-ff", value("neg"));
  }

  @Test
  void fromStringParsesCaseInsensitivelyAndReportsErrors() {
    assertEquals("Ok 3735928559", value("roundTrip")); // 0xdeadbeef
    assertEquals("Err \"Hex.fromString: invalid digit 'x'\"", value("bad"));
  }
}

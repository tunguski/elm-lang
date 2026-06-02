package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Base64} encode/decode library through the interpreter. */
class Base64LibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Base64.elm");

  private static final String SRC =
      """
      module Main exposing (e3, e2, e1, d3, bad, roundTrip)

      import Base64

      e3 : String
      e3 = Base64.encode [ 77, 97, 110 ]

      e2 : String
      e2 = Base64.encode [ 77, 97 ]

      e1 : String
      e1 = Base64.encode [ 77 ]

      d3 : Result String (List Int)
      d3 = Base64.decode "TWFu"

      bad : Result String (List Int)
      bad = Base64.decode "T@"

      roundTrip : Result String (List Int)
      roundTrip = Base64.decode (Base64.encode [ 0, 255, 16, 200, 5, 1 ])
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void encodesWithPadding() {
    assertEquals("TWFu", value("e3")); // "Man"
    assertEquals("TWE=", value("e2")); // one pad
    assertEquals("TQ==", value("e1")); // two pads
  }

  @Test
  void decodesAndRoundTrips() {
    assertEquals("Ok [77,97,110]", value("d3"));
    assertEquals("Ok [0,255,16,200,5,1]", value("roundTrip"));
    assertEquals("Err \"Base64.decode: invalid character '@'\"", value("bad"));
  }
}

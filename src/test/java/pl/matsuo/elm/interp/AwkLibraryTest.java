package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Awk} text-processing library through the interpreter. */
class AwkLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Awk.elm");

  private static final String SRC =
      """
      module Main exposing (col1, cols, grepC, total, counted, withEnds)

      import Awk exposing (..)

      input : String
      input = "alice 30 nyc\\nbob 25 sf\\ncarol 41 la\\n"

      col1 : String
      col1 = column 1 input

      cols : String
      cols = columns [ 2, 1 ] input

      grepC : String
      grepC = matching "carol" input

      total : Float
      total = sumColumn 2 input

      counted : String
      counted = run (\\r -> [ String.fromInt (nr r) ++ ": " ++ String.fromInt (nf r) ]) input

      withEnds : String
      withEnds =
          runWith
              { fs = " "
              , begin = [ "== names ==" ]
              , action = \\r -> [ field 1 r ]
              , end = [ "== done ==" ]
              }
              input
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void columnsFieldsAndPatterns() {
    assertEquals("alice\nbob\ncarol", value("col1"));
    assertEquals("30 alice\n25 bob\n41 carol", value("cols"));
    assertEquals("carol 41 la", value("grepC"));
  }

  @Test
  void nrNfAndSum() {
    assertEquals("96", value("total")); // 30 + 25 + 41
    assertEquals("1: 3\n2: 3\n3: 3", value("counted"));
  }

  @Test
  void beginAndEndBlocks() {
    assertEquals("== names ==\nalice\nbob\ncarol\n== done ==", value("withEnds"));
  }
}

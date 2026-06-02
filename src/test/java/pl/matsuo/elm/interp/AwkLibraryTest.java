package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Awk} builder — which composes awk *program text* to embed in a shell
 *  script or pass to awk (it does not run awk). */
class AwkLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Awk.elm");

  private static final String SRC =
      """
      module Main exposing (col, sum, grep, fields, oneline, argv)

      import Awk exposing (..)

      -- awk '{ print $1 }'
      col : String
      col = program [ eachLine [ print [ field 1 ] ] ]

      -- BEGIN { FS="," } { s += $2 } END { print s }
      sum : String
      sum =
          program
              [ begin [ assign "FS" (str ",") ]
              , eachLine [ addTo "s" (field 2) ]
              , end [ print [ var "s" ] ]
              ]

      -- /error/ { print NR, $0 }
      grep : String
      grep = program [ matchLine "error" [ print [ nr, field 0 ] ] ]

      -- { print toupper(substr($1, 1, 3)) }
      fields : String
      fields = program [ eachLine [ print [ call "toupper" [ call "substr" [ field 1, "1", "3" ] ] ] ] ]

      oneline : String
      oneline = oneLiner [ eachLine [ print [ field 2 ] ] ]

      argv : List String
      argv = invocation [ eachLine [ print [ field 1 ] ] ] [ "a.txt", "b.txt" ]
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void buildsRuleProgramText() {
    assertEquals("{ print $1 }", value("col"));
    assertEquals("BEGIN { FS = \",\" }\n{ s += $2 }\nEND { print s }", value("sum"));
    assertEquals("/error/ { print NR, $0 }", value("grep"));
  }

  @Test
  void expressionHelpers() {
    assertEquals("{ print toupper(substr($1, 1, 3)) }", value("fields"));
  }

  @Test
  void oneLinerAndInvocation() {
    assertEquals("'{ print $2 }'", value("oneline")); // shell-quoted, single line
    // The awk argv: program text then the file arguments.
    assertEquals("[\"{ print $1 }\",\"a.txt\",\"b.txt\"]", value("argv"));
  }
}

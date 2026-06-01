package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code M4} macro-processor library through the interpreter. */
class M4LibraryTest {

  private static final String LIB = Resources.read("/elm/lib/M4.elm");

  private static final String SRC =
      """
      module Main exposing (greet, quoted, ifYes, ifNo, counts, recursive, dnlComment, pre)

      import M4 exposing (..)

      greet : String
      greet = expand "define(greet, Hello $1!)greet(world)"

      quoted : String
      quoted = expand "define(a,X)`a' a"

      ifYes : String
      ifYes = expand "ifelse(x, x, yes, no)"

      ifNo : String
      ifNo = expand "ifelse(x, y, yes, no)"

      counts : String
      counts = expand "define(f, $# args: $*)f(a, b, c)"

      recursive : String
      recursive = expand "define(a, b)define(b, done)a"

      dnlComment : String
      dnlComment = expand "foo dnl this is a comment\\nbar"

      pre : String
      pre = expandWith [ define "name" "Bob" ] "Hi name"
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void definesAndExpandsWithArguments() {
    assertEquals("Hello world!", value("greet"));
    assertEquals("3 args: a,b,c", value("counts"));
    assertEquals("Hi Bob", value("pre"));
  }

  @Test
  void quotingSuppressesExpansion() {
    // The quoted `a' stays literal; the unquoted a expands to X.
    assertEquals("a X", value("quoted"));
  }

  @Test
  void ifelseAndRecursiveRescan() {
    assertEquals("yes", value("ifYes"));
    assertEquals("no", value("ifNo"));
    assertEquals("done", value("recursive")); // a -> b -> done (re-scanned)
  }

  @Test
  void dnlDeletesToNewline() {
    assertEquals("foo bar", value("dnlComment"));
  }
}

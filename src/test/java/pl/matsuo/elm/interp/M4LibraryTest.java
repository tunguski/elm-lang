package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code M4} macro-processor library through the interpreter. */
class M4LibraryTest {

  private static final String LIB = Resources.read("/elm/lib/M4.elm");

  private static final String SRC =
      """
      module Main exposing (greet, quoted, ifYes, ifNo, counts, recursive, dnlComment, pre, arith, strs, undef, defp)

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

      arith : String
      arith = expand "incr(5) decr(5) eval(2 + 3 * 4) eval((2 + 3) * 4)"

      strs : String
      strs = expand "len(hello) index(hello, ll) index(hello, z) substr(hello, 1, 3) translit(hello, el, ip)"

      undef : String
      undef = expand "define(a, X)undefine(a)a"

      defp : String
      defp = expand "define(a, X)ifdef(a, yes, no) ifdef(b, yes, no)"
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

  @Test
  void arithmeticBuiltins() {
    assertEquals("6 4 14 20", value("arith")); // incr, decr, eval (precedence), eval (parens)
  }

  @Test
  void stringBuiltins() {
    // len, index (0-based), index absent (-1), substr (0-based start,len), translit (tr)
    assertEquals("5 2 -1 ell hippo", value("strs"));
  }

  @Test
  void undefineAndIfdef() {
    assertEquals("a", value("undef")); // a undefined -> emitted literally
    assertEquals("yes no", value("defp"));
  }
}

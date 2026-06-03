package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code String.Extra} library through the interpreter. */
class StringExtraLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/String/Extra.elm");

  private static final String SRC =
      """
      module Main exposing (sentence, decap, title, blankT, blankF, cleaned, occ, surr, unsurr, ellip, ellipShort, neJust, neNothing, nbNothing, ins, lo, ro, rs, dash, under, q, uq)

      import String.Extra as SE

      sentence = SE.toSentenceCase "hello"
      decap = SE.decapitalize "Hello"
      title = SE.toTitleCase "the quick fox"
      blankT = SE.isBlank "   "
      blankF = SE.isBlank " x "
      cleaned = SE.clean "  a   b  c "
      occ = SE.countOccurrences "ab" "ababab"
      surr = SE.surround "*" "hi"
      unsurr = SE.unsurround "*" "*hi*"
      ellip = SE.ellipsis 8 "a long string"
      ellipShort = SE.ellipsis 20 "short"
      neJust = SE.nonEmpty "x"
      neNothing = SE.nonEmpty ""
      nbNothing = SE.nonBlank "  "
      ins = SE.insertAt "X" 2 "abcd"
      lo = SE.leftOf "=" "a=b=c"
      ro = SE.rightOf "=" "a=b=c"
      rs = SE.replaceSlice "X" 1 3 "abcd"
      dash = SE.dasherize "Foo Bar_baz"
      under = SE.underscored "Foo-Bar baz"
      q = SE.quote "hi"
      uq = SE.unquote "\\"hi\\""
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void casingAndBlanks() {
    assertEquals("Hello", value("sentence"));
    assertEquals("hello", value("decap"));
    assertEquals("The Quick Fox", value("title"));
    assertEquals("True", value("blankT"));
    assertEquals("False", value("blankF"));
  }

  @Test
  void cleanCountSurroundEllipsisInsert() {
    assertEquals("a b c", value("cleaned"));
    assertEquals("3", value("occ"));
    assertEquals("*hi*", value("surr"));
    assertEquals("hi", value("unsurr"));
    assertEquals("a lon...", value("ellip")); // 5 + "..." = 8 chars
    assertEquals("short", value("ellipShort"));
    assertEquals("Just \"x\"", value("neJust"));
    assertEquals("Nothing", value("neNothing"));
    assertEquals("Nothing", value("nbNothing"));
    assertEquals("abXcd", value("ins"));
  }

  @Test
  void slicingDasherizeQuote() {
    assertEquals("a", value("lo")); // before first "="
    assertEquals("b=c", value("ro")); // after first "="
    assertEquals("aXd", value("rs"));
    assertEquals("foo-bar-baz", value("dash"));
    assertEquals("foo_bar_baz", value("under"));
    assertEquals("\"hi\"", value("q"));
    assertEquals("hi", value("uq"));
  }
}

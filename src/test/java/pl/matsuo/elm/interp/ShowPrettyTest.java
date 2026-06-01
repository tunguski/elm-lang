package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests the multi-line pretty-printer ({@link Show#pretty}): small values stay on one line, large
 * ones break across indented lines. */
class ShowPrettyTest {

  private static String pretty(String expr) {
    return Show.pretty(Interpreter.eval(expr));
  }

  @Test
  void smallValuesStayOnOneLine() {
    assertEquals("[1,2,3]", pretty("[ 1, 2, 3 ]"));
    assertEquals("{ x = 1, y = 2 }", pretty("{ x = 1, y = 2 }"));
    assertEquals("(1,\"a\")", pretty("( 1, \"a\" )"));
    // No newline introduced for anything that fits the width.
    assertFalse(pretty("[ 1, 2, 3, 4, 5 ]").contains("\n"));
  }

  @Test
  void longListsBreakOntoIndentedLines() {
    // A 40-element list far exceeds the width, so it's laid out one element per line.
    String out = pretty("List.range 1 40");
    assertTrue(out.startsWith("[ 1\n"), out);
    assertTrue(out.contains("\n, 2"), out);
    assertTrue(out.endsWith("\n]"), out);
  }

  @Test
  void largeRecordsBreakByField() {
    String out =
        pretty(
            "{ alpha = 111111, beta = 222222, gamma = 333333, delta = 444444, epsilon = 555555 }");
    assertTrue(out.startsWith("{ alpha = "), out);
    assertTrue(out.contains("\n, beta = "), out);
    assertTrue(out.contains("\n, epsilon = "), out);
    assertTrue(out.endsWith("\n}"), out);
  }

  @Test
  void nestedStructuresReflowWithoutChangingContent() {
    String expr = "{ items = List.range 1 30, label = \"the items\" }";
    String out = pretty(expr);
    assertTrue(out.contains("\n"), out);
    // The reflowed layout has exactly the same content as the compact form, just with whitespace.
    assertEquals(
        Show.debug(Interpreter.eval(expr)).replaceAll("\\s", ""),
        out.replaceAll("\\s", ""),
        "pretty layout is the compact value reflowed");
  }
}

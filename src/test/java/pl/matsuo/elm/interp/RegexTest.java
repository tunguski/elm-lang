package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.types.TypeChecker;

/** The Regex (elm/regex) stdlib module on the interpreter, plus a type-check. */
class RegexTest {

  /** Evaluates `e` with a `digits` regex in scope (Regex.fromString unwrapped). */
  private String eval(String e) {
    String src =
        "digits = Maybe.withDefault Regex.never (Regex.fromString \"[0-9]+\")\n"
            + "comma = Maybe.withDefault Regex.never (Regex.fromString \",\")\n"
            + "result = "
            + e
            + "\n";
    return Show.plain(Interpreter.load(src).value("result"));
  }

  @Test
  void contains() {
    assertEquals("True", eval("Regex.contains digits \"abc123\""));
    assertEquals("False", eval("Regex.contains digits \"abc\""));
  }

  @Test
  void split() {
    assertEquals("[\"a\",\"b\",\"c\"]", eval("Regex.split comma \"a,b,c\""));
  }

  @Test
  void findReturnsMatches() {
    // Each match's `.match`; two runs of digits in "a12b345".
    assertEquals("[\"12\",\"345\"]", eval("List.map .match (Regex.find digits \"a12b345\")"));
    // The match record carries a 1-based index and number.
    assertEquals("1", eval("Maybe.withDefault (-1) (List.head (List.map .index (Regex.find digits \"a12b\")))"));
  }

  @Test
  void replace() {
    assertEquals("a#b#c#", eval("Regex.replace digits (\\m -> \"#\") \"a1b22c333\""));
    // The replacement can use the matched text.
    assertEquals("[1] [2]", eval("Regex.replace digits (\\m -> \"[\" ++ m.match ++ \"]\") \"1 2\""));
  }

  @Test
  void typeChecks() {
    Map<String, String> t =
        TypeChecker.checkModule(
            "re = Regex.fromString \"x\"\n"
                + "n = List.length (Regex.find Regex.never \"\")\n"
                + "main = Regex.replace Regex.never (\\m -> m.match) \"hi\"\n");
    assertEquals("String", t.get("main"));
    assertTrue(t.containsKey("n"), t.toString());
  }
}

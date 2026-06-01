package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** The bundled Parser combinator library (lib/Parser.elm), exercised through the interpreter. */
class ParserLibTest {

  private static final String PARSER = Resources.read("/elm/lib/Parser.elm");

  private String value(String program, String name) {
    return Show.plain(Project.load(program, PARSER).value("Main", name));
  }

  private static final String PROGRAM =
      "module Main exposing (..)\n"
          + "import Parser exposing (..)\n"
          + "point =\n"
          + "    succeed (\\x y -> ( x, y ))\n"
          + "        |. symbol \"(\"\n"
          + "        |. spaces\n"
          + "        |= int\n"
          + "        |. spaces\n"
          + "        |. symbol \",\"\n"
          + "        |. spaces\n"
          + "        |= int\n"
          + "        |. spaces\n"
          + "        |. symbol \")\"\n"
          + "pointOk = run point \"( 3 , 4 )\"\n"
          + "intOk = run int \"42\"\n"
          + "intBad = run int \"xyz\"\n"
          + "word = run (getChompedString (chompWhile (\\c -> c /= ' '))) \"hello world\"\n";

  @Test
  void parsesAnIntegerAndReportsFailure() {
    assertEquals("Ok 42", value(PROGRAM, "intOk"));
    assertTrue(value(PROGRAM, "intBad").startsWith("Err"), value(PROGRAM, "intBad"));
  }

  @Test
  void keepAndIgnoreOperatorsBuildAStructuredValue() {
    // `|=` keeps, `|.` ignores: a (Int, Int) point from "( 3 , 4 )".
    String r = value(PROGRAM, "pointOk");
    assertTrue(r.startsWith("Ok"), r);
    assertTrue(r.contains("3") && r.contains("4"), r);
  }

  @Test
  void getChompedStringReturnsTheConsumedText() {
    assertEquals("Ok \"hello\"", value(PROGRAM, "word"));
  }
}

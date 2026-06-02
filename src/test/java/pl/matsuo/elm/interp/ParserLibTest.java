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
          + "word = run (getChompedString (chompWhile (\\c -> c /= ' '))) \"hello world\"\n"
          + "floatOk = run float \"3.14\"\n"
          + "chompIfOk = run (getChompedString (chompIf Char.isDigit)) \"7x\"\n"
          + "chompIfBad = run (chompIf Char.isDigit) \"x\"\n"
          + "tokenOk = run (token \"let\") \"let\"\n"
          + "digits =\n"
          + "    loop [] (\\acc -> oneOf\n"
          + "        [ succeed (\\d -> Loop (d :: acc)) |= (getChompedString (chompIf Char.isDigit) |> andThen (\\s -> succeed s))\n"
          + "        , succeed (Done (List.reverse acc)) ])\n"
          + "loopOk = run digits \"123\"\n"
          + "commentBody = run (getChompedString (chompUntil \"-}\")) \"abc-}rest\"\n"
          + "chompUntilBad = run (chompUntil \"xx\") \"abc\"\n"
          + "countAs = run as_ \"aaa\"\n"
          + "as_ =\n"
          + "    oneOf\n"
          + "        [ succeed (\\n -> n + 1) |. symbol \"a\" |= lazy (\\_ -> as_)\n"
          + "        , succeed 0\n"
          + "        ]\n";

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

  @Test
  void newCombinators() {
    assertEquals("Ok 3.14", value(PROGRAM, "floatOk"));
    assertEquals("Ok \"7\"", value(PROGRAM, "chompIfOk"));
    assertTrue(value(PROGRAM, "chompIfBad").startsWith("Err"), value(PROGRAM, "chompIfBad"));
    assertEquals("Ok ()", value(PROGRAM, "tokenOk"));
    assertEquals("Ok [\"1\",\"2\",\"3\"]", value(PROGRAM, "loopOk")); // loop collects each digit
  }

  @Test
  void lazyEnablesRecursionAndChompUntilStopsAtASubstring() {
    assertEquals("Ok \"abc\"", value(PROGRAM, "commentBody")); // chompUntil stops before "-}"
    assertTrue(value(PROGRAM, "chompUntilBad").startsWith("Err"), value(PROGRAM, "chompUntilBad"));
    assertEquals("Ok 3", value(PROGRAM, "countAs")); // lazy lets the parser recurse on itself
  }
}

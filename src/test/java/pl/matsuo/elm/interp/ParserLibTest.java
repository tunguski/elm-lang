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
      """
      module Main exposing (..)
      import Parser exposing (..)
      point =
          succeed (\\x y -> ( x, y ))
              |. symbol "("
              |. spaces
              |= int
              |. spaces
              |. symbol ","
              |. spaces
              |= int
              |. spaces
              |. symbol ")"
      pointOk = run point "( 3 , 4 )"
      intOk = run int "42"
      intBad = run int "xyz"
      word = run (getChompedString (chompWhile (\\c -> c /= ' '))) "hello world"
      floatOk = run float "3.14"
      chompIfOk = run (getChompedString (chompIf Char.isDigit)) "7x"
      chompIfBad = run (chompIf Char.isDigit) "x"
      tokenOk = run (token "let") "let"
      digits =
          loop [] (\\acc -> oneOf
              [ succeed (\\d -> Loop (d :: acc)) |= (getChompedString (chompIf Char.isDigit) |> andThen (\\s -> succeed s))
              , succeed (Done (List.reverse acc)) ])
      loopOk = run digits "123"
      commentBody = run (getChompedString (chompUntil "-}")) "abc-}rest"
      chompUntilBad = run (chompUntil "xx") "abc"
      countAs = run as_ "aaa"
      as_ =
          oneOf
              [ succeed (\\n -> n + 1) |. symbol "a" |= lazy (\\_ -> as_)
              , succeed 0
              ]
      """;

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

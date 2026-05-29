package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * Differential tests for the JavaScript backend: compiled-and-run-under-Node output must match the
 * Truffle interpreter's result for the same Elm source.
 */
class JsBackendTest {

  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-js-", ".js");
      Files.writeString(file, program, StandardCharsets.UTF_8);
      Process p =
          new ProcessBuilder("node", file.toString())
              .redirectErrorStream(false)
              .start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("node timed out");
      }
      Files.deleteIfExists(file);
      if (p.exitValue() != 0) {
        throw new IllegalStateException("node failed: " + err + "\n--- program ---\n" + program);
      }
      return out;
    } catch (IOException | InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }

  /** Compiles the expression to JS, runs it, and asserts it equals the interpreter's value. */
  private void same(String elmExpression) {
    String expected = Show.plain(Interpreter.eval(elmExpression));
    String actual = runNode(JsCompiler.expressionProgram(elmExpression));
    assertEquals(expected, actual, "expression: " + elmExpression);
  }

  @Test
  void arithmeticAndOperators() {
    same("1 + 2 * 3");
    same("7 / 2");
    same("2 ^ 10");
    same("7 // 2");
    same("modBy 3 17");
    same("-5 + 3");
  }

  @Test
  void comparisonsAndBooleans() {
    same("3 < 5 && 2 == 2");
    same("\"abc\" < \"abd\"");
    same("[1,2,3] == [1,2,3]");
    same("compare 3 5");
  }

  @Test
  void listsAndStrings() {
    same("List.map (\\x -> x * x) [1, 2, 3]");
    same("List.foldl (+) 0 [1, 2, 3, 4]");
    same("List.range 1 5 |> List.filter (\\x -> modBy 2 x == 0)");
    same("List.reverse [1, 2, 3]");
    same("[1, 2] ++ [3, 4]");
    same("String.toUpper (String.reverse \"abc\")");
    same("String.join \", \" [\"a\", \"b\", \"c\"]");
    same("String.fromInt 42 ++ \"!\"");
  }

  @Test
  void compoundExpressions() {
    same("(1, 2, 3)");
    same("if True then 1 else 2");
    same("let x = 5 in x + 1");
    same("(\\x -> x * 2) 21");
    same("5 |> (\\x -> x + 1)");
    same("((\\x -> x + 1) << (\\x -> x * 2)) 3");
  }

  @Test
  void maybeAndRecords() {
    same("Maybe.withDefault 0 (Just 5)");
    same("Maybe.withDefault 0 Nothing");
    same("Maybe.map (\\x -> x + 1) (Just 5)");
    same("{ x = 1, y = 2 }.x");
    same("(\\r -> { r | x = 9 }) { x = 1, y = 2 }");
  }

  // --- whole modules -----------------------------------------------------

  private void sameModule(String source) {
    String expected = Show.plain(Interpreter.load(source).value("main"));
    String actual = runNode(JsCompiler.moduleProgram(source));
    assertEquals(expected, actual);
  }

  @Test
  void factorialModule() {
    sameModule(
        """
        factorial n =
            if n <= 1 then 1 else n * factorial (n - 1)
        main = factorial 5
        """);
  }

  @Test
  void mutualRecursionModule() {
    sameModule(
        """
        isEven n = if n == 0 then True else isOdd (n - 1)
        isOdd n = if n == 0 then False else isEven (n - 1)
        main = isEven 10
        """);
  }

  @Test
  void customTypeModule() {
    sameModule(
        """
        type Shape = Circle Float | Rect Float Float
        area shape =
            case shape of
                Circle r -> pi * r * r
                Rect w h -> w * h
        main = area (Rect 3.0 4.0)
        """);
  }

  @Test
  void caseAndListPatternsModule() {
    sameModule(
        """
        describe xs =
            case xs of
                [] -> "empty"
                [ _ ] -> "one"
                _ :: _ -> "many"
        main = describe [1, 2, 3]
        """);
  }
}

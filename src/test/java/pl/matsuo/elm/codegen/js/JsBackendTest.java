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
  void jsonEncode() {
    same("Json.Encode.encode 0 (Json.Encode.int 42)");
    same("Json.Encode.encode 0 (Json.Encode.list Json.Encode.int [1, 2, 3])");
    same("Json.Encode.encode 0 (Json.Encode.object [(\"x\", Json.Encode.bool True)])");
    same("Json.Encode.encode 0 (Json.Encode.string \"hi\")");
  }

  @Test
  void optimizeDropsUnusedDefinitionsButKeepsResult() {
    String src = "used n = n + 1\nunusedHelper n = n * 999\nmain = used 41\n";
    String bundle = JsCompiler.moduleProgram(src);
    String optimized = JsCompiler.optimize(bundle);
    // The unreachable `unusedHelper` declaration is gone; `used` (reachable from main) stays.
    org.junit.jupiter.api.Assertions.assertTrue(bundle.contains("_$unusedHelper"), "present before");
    org.junit.jupiter.api.Assertions.assertFalse(optimized.contains("_$unusedHelper"), "dropped");
    org.junit.jupiter.api.Assertions.assertTrue(optimized.contains("_$used"), "reachable kept");
    assertEquals("42", runNode(optimized)); // still correct after tree-shaking
  }

  @Test
  void minifiedProgramIsSmallerAndStillRuns() {
    String full = JsCompiler.moduleProgram("main = List.sum (List.range 1 10)\n");
    String min = JsCompiler.minify(full);
    org.junit.jupiter.api.Assertions.assertTrue(min.length() < full.length(), "minified is smaller");
    assertEquals("55", runNode(min)); // still evaluates correctly
    assertEquals(runNode(full), runNode(min)); // identical result to the unminified program
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

  /** Runs the Elm-in-Elm editor's `Eval.eval` under Node, to catch interpreter/JS-kernel divergence. */
  private String editorEval(String input) {
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = pl.matsuo.elm.util.Resources.read(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    String escaped = input.replace("\\", "\\\\").replace("\"", "\\\"");
    // The DOM kernel attaches to `window`; under Node we alias it to the global object. The bundle
    // is loaded without mounting, so we can call the (module-qualified) `Eval.eval` directly.
    String program =
        "globalThis.window = globalThis;\n"
            + JsCompiler.declarationsScriptWithDomProject(modules)
            + "\nprocess.stdout.write(_$Eval$eval(\""
            + escaped
            + "\"));\n";
    return runNode(program);
  }

  @Test
  void editorInterpreterRunsUnderNode() {
    // The editor uses Char.isAlphaNum (identifier lexing) — a builtin that must exist in the JS
    // kernel too, not only the interpreter. These would throw "Unbound" if the kernel lacked it.
    assertEquals("6", editorEval("2 + 3 * (4 - 1) // 2"));
    assertEquals("5", editorEval("let abc1 = 5 in abc1")); // identifier with a digit -> isAlphaNum
    assertEquals("42", editorEval("(\\x y -> x * y) 6 7"));
    assertEquals("True", editorEval("1 < 2 && 2 < 3"));
  }
}

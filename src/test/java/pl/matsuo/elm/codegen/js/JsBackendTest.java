package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * Differential tests for the JavaScript backend: compiled-and-run-under-Node output must match the
 * Truffle interpreter's result for the same Elm source.
 *
 * <p>{@code @Execution(CONCURRENT)}: every test compiles an independent snippet to a unique temp file
 * and runs it in its own {@code node} subprocess (see {@link #runNode}), so the methods are
 * independent and run in parallel to overlap the subprocess waits.
 */
@Execution(ExecutionMode.CONCURRENT)
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

  /** A self-tail-recursive function must run in constant JS stack space (loop, not deep recursion):
   * a million-deep recursion would blow Node's call stack without the optimisation. */
  @Test
  void selfTailRecursionRunsAsALoop() {
    String src =
        """
        module M exposing (run)
        sumTo : Int -> Int -> Int
        sumTo n acc =
            if n == 0 then
                acc
            else
                sumTo (n - 1) (acc + n)
        run : Int
        run = sumTo 1000000 0
        """;
    String program =
        JsCompiler.declarationsScript(src) + "\nprocess.stdout.write(String(_$run));\n";
    // The if-based tail call is compiled to a while/continue loop.
    assertTrue(program.contains("while(true)") && program.contains("continue $tco"),
        "tail-recursive function compiled to a loop");
    assertEquals("500000500000", runNode(program));
  }

  /** Tail recursion through a `case` (the common list-walk shape) is also looped, not stacked. */
  @Test
  void caseTailRecursionRunsAsALoop() {
    String src =
        """
        module M exposing (run)
        len : List a -> Int -> Int
        len xs acc =
            case xs of
                [] ->
                    acc
                _ :: rest ->
                    len rest (acc + 1)
        run : Int
        run = len (List.range 1 200000) 0
        """;
    String program =
        JsCompiler.declarationsScript(src) + "\nprocess.stdout.write(String(_$run));\n";
    assertTrue(program.contains("continue $tco"), "case tail call compiled to a loop");
    assertEquals("200000", runNode(program));
  }

  /** A `let`-bound recursive function is tail-call optimised too (parity with the interpreter and
   * bytecode VM): a million-deep recursion runs as a loop rather than overflowing. */
  @Test
  void letBoundSelfTailRecursionRunsAsALoop() {
    String src =
        """
        module M exposing (run)
        run : Int
        run =
            let
                go n acc =
                    if n == 0 then
                        acc

                    else
                        go (n - 1) (acc + 1)
            in
            go 1000000 0
        """;
    String program =
        JsCompiler.declarationsScript(src) + "\nprocess.stdout.write(String(_$run));\n";
    assertTrue(program.contains("continue $tco"), "let-bound tail recursion compiled to a loop");
    assertEquals("1000000", runNode(program));
  }

  /** A binding that shadows the function name must NOT be mistaken for a self-tail-call. */
  @Test
  void tailCallDetectionRespectsShadowing() {
    String src =
        """
        module M exposing (run)
        apply2 : (Int -> Int) -> Int
        apply2 f =
            let
                g = \\x -> x + 1
            in
            case f of
                apply2 ->
                    apply2 (apply2 0)
        run : Int
        run = apply2 (\\x -> x + 5)
        """;
    String program =
        JsCompiler.declarationsScript(src) + "\nprocess.stdout.write(String(_$run));\n";
    // `apply2` inside the case is the bound pattern variable (the passed function), not the
    // top-level function: applying it twice to 0 gives (0+5)+5 = 10. A misfired self-tail-call
    // would instead loop/throw, so 10 confirms shadowing is respected.
    assertEquals("10", runNode(program));
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
  void dictOnTheJsBackend() {
    // Dict now exists in the JS kernel; results (sorted) must match the interpreter.
    same("Dict.toList (Dict.fromList [ ( 3, \"c\" ), ( 1, \"a\" ), ( 2, \"b\" ) ])");
    same("Dict.get 2 (Dict.fromList [ ( 1, \"a\" ), ( 2, \"b\" ) ])");
    same("Dict.size (Dict.insert 9 \"z\" (Dict.fromList [ ( 1, \"a\" ) ]))");
    same("Dict.keys (Dict.remove 1 (Dict.fromList [ ( 1, \"a\" ), ( 2, \"b\" ) ]))");
    same("Dict.values (Dict.map (\\k v -> v + 1) (Dict.fromList [ ( 1, 10 ), ( 2, 20 ) ]))");
    same("Dict.toList (Dict.union (Dict.fromList [ ( 1, \"a\" ) ]) (Dict.fromList [ ( 1, \"x\" ), ( 2, \"b\" ) ]))");
    same("Dict.fromList [ ( 2, \"b\" ), ( 1, \"a\" ) ]"); // shows sorted via $show
  }

  @Test
  void arrayOnTheJsBackend() {
    same("Array.toList (Array.fromList [ 1, 2, 3 ])");
    same("Array.get 1 (Array.fromList [ 1, 2, 3 ])");
    same("Array.get 9 (Array.fromList [ 1 ])"); // Nothing
    same("Array.length (Array.push 4 (Array.fromList [ 1, 2, 3 ]))");
    same("Array.toList (Array.map (\\x -> x * 2) (Array.fromList [ 1, 2, 3 ]))");
    same("Array.foldl (\\x acc -> acc + x) 0 (Array.fromList [ 1, 2, 3, 4 ])");
    same("Array.toList (Array.set 0 9 (Array.fromList [ 1, 2, 3 ]))");
    same("Array.toList (Array.slice 1 3 (Array.fromList [ 0, 1, 2, 3, 4 ]))");
    same("Array.toList (Array.slice 1 -1 (Array.fromList [ 0, 1, 2, 3, 4 ]))"); // negative end
    same("Array.toList (Array.initialize 3 (\\i -> i * i))");
    same("Array.fromList [ 1, 2, 3 ]"); // shows as Array.fromList [...]
  }

  @Test
  void setOnTheJsBackend() {
    same("Set.toList (Set.fromList [ 3, 1, 2, 1, 3 ])");
    same("Set.member 2 (Set.fromList [ 1, 2, 3 ])");
    same("Set.size (Set.union (Set.fromList [ 1, 2 ]) (Set.fromList [ 2, 3 ]))");
    same("Set.toList (Set.diff (Set.fromList [ 1, 2, 3 ]) (Set.fromList [ 2 ]))");
    same("Set.fromList [ 3, 1, 2 ]"); // shows sorted
  }

  @Test
  void optimizePrunesUnusedKernelEntries() {
    // A program that uses List.map but not Dict: after optimize, Dict's kernel entries are gone,
    // List.map's are kept, and the optimized bundle is smaller than the unoptimized one.
    String src = "module Main exposing (main)\nimport Html exposing (text)\n"
        + "main = text (String.fromInt (List.sum (List.map (\\x -> x * x) [ 1, 2, 3 ])))\n";
    String bundle = JsCompiler.appBundle(src);
    String optimized = JsOptimizer.optimize(bundle);
    assertTrue(bundle.contains("Dict.insert"), "kernel defines Dict.insert");
    assertTrue(!optimized.contains("Dict.insert"), "unused Dict entry pruned: ");
    assertTrue(!optimized.contains("Bitwise.and"), "unused Bitwise entry pruned");
    assertTrue(optimized.contains("List.map"), "used List.map kept");
    assertTrue(optimized.length() < bundle.length(), "optimized is smaller");
  }

  @Test
  void listPartitionUnzipIntersperseAgreeAcrossBackends() {
    same("List.partition (\\n -> n > 2) [ 1, 2, 3, 4 ]");
    same("List.unzip [ ( 1, \"a\" ), ( 2, \"b\" ) ]");
    same("List.intersperse 0 [ 1, 2, 3 ]");
    same("List.intersperse 0 []");
  }

  @Test
  void jsonEncodeDictAndSetAgreeAcrossBackends() {
    same("Json.Encode.encode 0 (Json.Encode.dict identity Json.Encode.int "
        + "(Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ]))");
    same("Json.Encode.encode 0 (Json.Encode.set Json.Encode.int (Set.fromList [ 3, 1, 2 ]))");
  }

  @Test
  void maybeResultDictSetFoldAdditionsAgreeAcrossBackends() {
    same("Maybe.map3 (\\a b c -> a + b + c) (Just 1) (Just 2) (Just 3)");
    same("Maybe.map3 (\\a b c -> a + b + c) (Just 1) Nothing (Just 3)"); // Nothing
    same("Maybe.map4 (\\a b c d -> a + b + c + d) (Just 1) (Just 2) (Just 3) (Just 4)");
    same("Maybe.map5 (\\a b c d e -> a + b + c + d + e) (Just 1) (Just 2) (Just 3) (Just 4) (Just 5)");
    same("Result.map4 (\\a b c d -> a + b + c + d) (Ok 1) (Ok 2) (Ok 3) (Ok 4)");
    same("Result.map5 (\\a b c d e -> a + b + c + d + e) (Ok 1) (Ok 2) (Ok 3) (Ok 4) (Err \"x\")"); // Err
    same("List.unzip3 [ ( 1, \"a\", True ), ( 2, \"b\", False ) ]");
    same("Result.map2 (\\a b -> a + b) (Ok 1) (Ok 2)");
    same("Result.map2 (\\a b -> a + b) (Err \"e\") (Ok 2)"); // short-circuits to the Err
    same("Result.map3 (\\a b c -> a + b + c) (Ok 1) (Ok 2) (Ok 3)");
    same("Result.fromMaybe \"err\" (Just 5)"); // Ok 5
    same("Result.fromMaybe \"err\" Nothing"); // Err "err"
    same("Dict.foldr (\\k v acc -> k ++ acc) \"\" (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ])");
    same("Set.foldr (\\x acc -> x :: acc) [] (Set.fromList [ 3, 1, 2 ])");
  }

  @Test
  void dictSetFilterPartitionAndMapAgreeAcrossBackends() {
    String d = "(Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ), ( \"c\", 3 ) ])";
    same("Dict.toList (Dict.filter (\\_ v -> v > 1) " + d + ")");
    same("Dict.toList (Tuple.first (Dict.partition (\\_ v -> v > 1) " + d + "))");
    same("Dict.toList (Tuple.second (Dict.partition (\\_ v -> v > 1) " + d + "))");
    String s = "(Set.fromList [ 1, 2, 3, 4 ])";
    same("Set.toList (Set.filter (\\x -> modBy 2 x == 0) " + s + ")");
    same("Set.toList (Set.map (\\x -> x * 10) " + s + ")");
    same("Set.toList (Tuple.first (Set.partition (\\x -> x > 2) " + s + "))");
    same("Set.toList (Tuple.second (Set.partition (\\x -> x > 2) " + s + "))");
  }

  @Test
  void backfilledStdlibSignaturesCompileAndAgreeAcrossBackends() {
    // Functions that previously ran in the interpreter but lacked a type scheme (so they could not
    // be compiled): now they type-check and agree interp-vs-JS.
    String s1 = "(Set.fromList [ 1, 2, 3 ])";
    String s2 = "(Set.fromList [ 2, 3, 4 ])";
    same("Set.toList (Set.union " + s1 + " " + s2 + ")");
    same("Set.toList (Set.intersect " + s1 + " " + s2 + ")");
    same("Set.toList (Set.diff " + s1 + " " + s2 + ")");
    String d1 = "(Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ])";
    String d2 = "(Dict.fromList [ ( \"b\", 9 ), ( \"c\", 3 ) ])";
    same("Dict.toList (Dict.union " + d1 + " " + d2 + ")");
    same("Dict.toList (Dict.intersect " + d1 + " " + d2 + ")");
    same("Dict.toList (Dict.diff " + d1 + " " + d2 + ")");
    same("String.concat [ \"a\", \"b\", \"c\" ]");
    same("String.fromList (List.reverse (String.toList \"abc\"))");
    same("String.slice 1 3 \"hello\"");
    same("String.replace \"a\" \"X\" \"banana\"");
    same("Tuple.mapBoth (\\x -> x + 1) (\\y -> y ++ \"!\") ( 1, \"a\" )");
    same("Array.toList (Array.slice 1 3 (Array.fromList [ 0, 1, 2, 3, 4 ]))");
    same("Array.foldl (\\x acc -> x + acc) 0 (Array.fromList [ 1, 2, 3 ])");
    same("List.map (\\c -> Char.isUpper c) (String.toList \"aBcD\")");
    // Char.toLocaleUpper/toLocaleLower (ASCII, so the default locale doesn't diverge interp-vs-JS).
    same("String.fromChar (Char.toLocaleUpper 'a')");
    same("String.fromChar (Char.toLocaleLower 'A')");
    same("String.map Char.toLocaleUpper \"abc\"");
    // Json.Encode.array f arr must encode identically to Json.Encode.list f over the same elements
    // (compared within each backend, so the interp-vs-JS JSON spacing difference doesn't matter).
    same(
        "Json.Encode.encode 0 (Json.Encode.array Json.Encode.int (Array.fromList [ 1, 2, 3 ])) "
            + "== Json.Encode.encode 0 (Json.Encode.list Json.Encode.int [ 1, 2, 3 ])");
    // Json.Decode.array: decoders run in the DOM runtime, not the pure-expression bundle `same`
    // compiles, so check the interpreter directly (the JS derive reuses the tested list/map path).
    assertEquals(
        "6",
        Show.plain(
            Interpreter.eval(
                "List.sum (Array.toList (Result.withDefault Array.empty "
                    + "(Json.Decode.decodeString (Json.Decode.array Json.Decode.int) \"[1,2,3]\")))")));
  }

  /** A positional record-alias constructor whose fields include a List (even a List of records, with
   * inner commas/braces) must build the record with every field set — not leave the List field
   * undefined (which surfaced elsewhere as $listToArray(undefined)). */
  @Test
  void positionalRecordAliasConstructorWithListFieldBuildsEveryField() {
    String src =
        """
        module M exposing (run)
        type alias StatusInfo =
            { status : String, items : List { id : Int, name : String }, code : Int }
        build : StatusInfo
        build = StatusInfo "NEW" [ { id = 1, name = "a" }, { id = 2, name = "b" } ] 7
        run : Int
        run = List.length build.items + build.code
        """;
    String program = JsCompiler.declarationsScript(src) + "\nprocess.stdout.write(String(_$run));\n";
    assertEquals("9", runNode(program));
  }

  @Test
  void arrayAndListAdditionsAgreeAcrossBackends() {
    same("Array.toList (Array.filter (\\x -> x > 2) (Array.fromList [ 1, 2, 3, 4 ]))");
    same("Array.toIndexedList (Array.fromList [ \"a\", \"b\", \"c\" ])");
    same("List.map4 (\\a b c d -> a + b + c + d) [ 1, 2 ] [ 10, 20 ] [ 100, 200 ] [ 1000, 2000 ]");
    same(
        "List.map5 (\\a b c d e -> a + b + c + d + e) "
            + "[ 1, 2 ] [ 10, 20 ] [ 100, 200 ] [ 1000, 2000 ] [ 10000, 20000 ]");
    same("List.map4 (\\a b c d -> ( a, b, c, d )) [ 1 ] [ 2 ] [ 3 ] [ 4, 9 ]"); // ragged: truncates
  }

  @Test
  void numericBasicsAgreeAcrossBackends() {
    same("clamp 0 10 15");
    same("clamp 0 10 -3");
    same("clamp 0 10 7");
    same("remainderBy 4 -7"); // remainder keeps the dividend's sign (unlike modBy)
    same("truncate 3.9");
    same("truncate -3.9");
    same("if isNaN (0 / 0) then 1 else 0");
    same("if isInfinite (1 / 0) then 1 else 0");
    same("round (degrees 180 * 1000)"); // pi*1000
    same("round (turns 1 * 1000)"); // 2*pi*1000
  }

  @Test
  void bitwiseModuleAgreesAcrossBackends() {
    same("Bitwise.and 12 10");
    same("Bitwise.or 12 10");
    same("Bitwise.xor 12 10");
    same("Bitwise.complement 0");
    same("Bitwise.shiftLeftBy 2 1");
    same("Bitwise.shiftRightBy 1 8");
    same("Bitwise.shiftRightZfBy 1 -2"); // unsigned 32-bit shift
  }

  @Test
  void stringAndCharAdditionsAgreeAcrossBackends() {
    same("String.padLeft 5 '0' \"42\"");
    same("String.padRight 5 '.' \"ab\"");
    same("String.pad 5 '*' \"ab\"");
    same("String.indexes \"a\" \"banana\"");
    same("String.map Char.toUpper \"abc\"");
    same("String.filter Char.isDigit \"a1b2c3\"");
    same("String.slice 1 3 \"hello\"");
    same("String.slice 1 -1 \"hello\"");
    same("String.uncons \"hi\"");
    same("String.cons 'h' \"i\"");
    same("if Char.isHexDigit 'f' then 1 else 0");
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
  void customOperatorsAndInfixDeclarations() {
    // A user-defined operator compiles to a call of its function in JS.
    sameModule("(+++) a b = a + b * 2\nmain = 1 +++ 2\n");
    // An `infix` declaration binds an operator to a function with a declared precedence; the JS
    // backend must agree with the interpreter (1 ^^ 2 ~~ 3 = 1 + 2*3 = 7 with (~~) tighter).
    sameModule(
        "add a b = a + b\nmul a b = a * b\n"
            + "infix left 6 (^^) = add\ninfix left 7 (~~) = mul\n"
            + "main = 1 ^^ 2 ~~ 3\n");
    // An operator passed as a value (a section/reference) also resolves.
    sameModule("(+++) a b = a + b\nmain = List.foldl (+++) 0 [1, 2, 3, 4]\n");
  }

  @Test
  void regexAgreesWithInterpreter() {
    // The Regex module must give the same results compiled to JS as on the interpreter.
    sameModule(
        "re = Maybe.withDefault Regex.never (Regex.fromString \"[0-9]+\")\n"
            + "main = Regex.replace re (\\m -> \"[\" ++ m.match ++ \"]\") \"a1b22c333\"\n");
    sameModule(
        "re = Maybe.withDefault Regex.never (Regex.fromString \",\")\n"
            + "main = String.join \"|\" (Regex.split re \"a,b,c\")\n");
    sameModule(
        "re = Maybe.withDefault Regex.never (Regex.fromString \"[0-9]+\")\n"
            + "main = List.map .match (Regex.find re \"x12y345\")\n");
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
    String optimized = JsOptimizer.optimize(bundle);
    // The unreachable `unusedHelper` declaration is gone; `used` (reachable from main) stays.
    org.junit.jupiter.api.Assertions.assertTrue(bundle.contains("_$unusedHelper"), "present before");
    org.junit.jupiter.api.Assertions.assertFalse(optimized.contains("_$unusedHelper"), "dropped");
    org.junit.jupiter.api.Assertions.assertTrue(optimized.contains("_$used"), "reachable kept");
    assertEquals("42", runNode(optimized)); // still correct after tree-shaking
  }

  @Test
  void minifiedProgramIsSmallerAndStillRuns() {
    String full = JsCompiler.moduleProgram("main = List.sum (List.range 1 10)\n");
    String min = JsOptimizer.minify(full);
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
  void customInfixOperatorModule() {
    // A user-defined infix operator, used infix and (as `(+++)`) as a higher-order value.
    sameModule("(+++) a b = a + b * 2\nmain = 1 +++ 2\n"); // -> 5
    sameModule("(+++) a b = a + b\nmain = List.foldl (+++) 0 [ 1, 2, 3, 4 ]\n"); // -> 10
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
    org.junit.jupiter.api.Assumptions.assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules;
    try {
      modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
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
    assertEquals("3", editorEval("case ( 1, 2 ) of ( a, b ) -> a + b")); // tuples + tuple patterns
  }

  @Test
  void editorRunsTextFieldAndElementPrograms() {
    // onInput: a text field whose view echoes the model (empty initially).
    String field =
        editorRender(
            "main = Browser.sandbox { init = init, update = update, view = view }\n"
                + "init = \"\"\n"
                + "update msg model = case msg of\n    SetText s ->\n        s\n"
                + "view model = div [] [ input [ onInput SetText ] [], div [] [ text (\"You typed: \" ++ model) ] ]\n");
    assertTrue(field.contains("<input"), field);
    assertTrue(field.contains("You typed:"), field);

    // Browser.element: init/update return (model, Cmd) tuples; the editor unwraps the model.
    String el =
        editorRender(
            "main = Browser.element { init = init, update = update, view = view, subscriptions = subs }\n"
                + "init flags = ( 0, Cmd.none )\n"
                + "update msg model = case msg of\n    Bump ->\n        ( model + 1, Cmd.none )\n"
                + "subs model = Sub.none\n"
                + "view model = div [] [ button [ onClick Bump ] [ text \"bump\" ], div [] [ text (String.fromInt model) ] ]\n");
    assertTrue(el.contains(">bump<"), el);
    assertTrue(el.contains("<div>0</div>"), el); // initial model unwrapped from (0, Cmd.none)
  }

  /** Runs the editor's `Eval.renderProgram` on a single-file source, returning the serialized view. */
  private String editorRender(String source) {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules;
    try {
      modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
    String escaped = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    String program =
        "globalThis.window = globalThis;\n"
            + JsCompiler.declarationsScriptWithDomProject(modules)
            + "\nprocess.stdout.write(_$Eval$renderProgram(\""
            + escaped
            + "\"));\n";
    return runNode(program);
  }

  @Test
  void editorRunsTheElmLangButtonsExample() {
    // The elm-lang.org "buttons" example: a Browser.sandbox app with a layout-based `case` (no
    // explicit branch separators), qualified String.fromInt, and Html via onClick/div/button/text.
    // The editor's interpreter must parse and render its view (init model = 0).
    String buttons =
        String.join(
            "\n",
            "module Main exposing (main)",
            "",
            "import Browser",
            "import Html exposing (Html, button, div, text)",
            "import Html.Events exposing (onClick)",
            "",
            "main = Browser.sandbox { init = init, update = update, view = view }",
            "",
            "init = 0",
            "",
            "update msg model =",
            "    case msg of",
            "        Increment ->",
            "            model + 1",
            "",
            "        Decrement ->",
            "            model - 1",
            "",
            "view model =",
            "    div []",
            "        [ button [ onClick Decrement ] [ text \"-\" ]",
            "        , div [] [ text (String.fromInt model) ]",
            "        , button [ onClick Increment ] [ text \"+\" ]",
            "        ]");
    String html = editorRender(buttons);
    // The view renders to nested nodes with the initial model (0) and click handlers wired.
    assertTrue(html.contains("<button"), html);
    assertTrue(html.contains(">-<") && html.contains(">+<"), html);
    assertTrue(html.contains("<div>0</div>"), html);
    assertTrue(html.contains("onclick=Decrement") && html.contains("onclick=Increment"), html);
  }

  @Test
  void editorRendersStaticHtmlValuesAndComputations() {
    // Static Html `main`.
    assertEquals("<div>Hello, Elm!</div>", editorRender("main = div [] [ text \"Hello, Elm!\" ]\n"));
    // A computed String through a helper.
    assertEquals(
        "Hello, world!",
        editorRender("main = text (greet \"world\")\ngreet name = \"Hello, \" ++ name ++ \"!\"\n"));
    // Recursion + String.fromInt.
    assertEquals(
        "120",
        editorRender("main = text (String.fromInt (fact 5))\nfact n = if n <= 1 then 1 else n * fact (n - 1)\n"));
    // List builtins: range + sum.
    assertEquals("5050", editorRender("main = text (String.fromInt (List.sum (List.range 1 100)))\n"));
    // Higher-order List.map building Html.
    String squares =
        editorRender(
            "main = div [] (List.map sq (List.range 1 4))\nsq n = div [] [ text (String.fromInt (n * n)) ]\n");
    assertTrue(squares.contains("<div>1</div>") && squares.contains("<div>16</div>"), squares);
  }

  @Test
  void editorSkipsLineCommentsAndSupportsPipeOperators() {
    // `--` comments are dropped by the lexer; `|>` desugars to application (lowest precedence, so
    // it applies to the whole `++` chain).
    assertEquals(
        "HELLO, WORLD",
        editorRender(
            String.join(
                "\n",
                "main = text shout   -- render the greeting",
                "shout = \"Hello, \" ++ name |> String.toUpper",
                "name = \"world\"   -- who to greet")));
  }

  @Test
  void editorSupportsMultiBindingLet() {
    assertEquals(
        "30",
        editorRender(
            String.join(
                "\n",
                "main = text (String.fromInt total)",
                "total =",
                "    let",
                "        a = 10",
                "        b = 20",
                "    in",
                "    a + b")));
  }

  @Test
  void editorSupportsRecordTypeAliasConstructors() {
    // `type alias Point = { ... }` doubles as a positional constructor `Point 3 4`.
    assertEquals(
        "7",
        editorRender(
            String.join(
                "\n",
                "type alias Point = { x : Int, y : Int }",
                "main = text (String.fromInt (add (Point 3 4)))",
                "add p = p.x + p.y")));
  }

  @Test
  void editorSupportsHtmlAttributeBuiltins() {
    String html = editorRender("main = input [ placeholder \"hi\", type_ \"text\", value \"v\" ] []\n");
    assertTrue(html.contains("placeholder=hi"), html);
    assertTrue(html.contains("type=text"), html);
    assertTrue(html.contains("value=v"), html);
  }

  @Test
  void editorRendersInlineSvg() {
    String html =
        editorRender(
            "main = svg [ viewBox \"0 0 100 100\", width \"100\" ]"
                + " [ circle [ cx \"50\", cy \"50\", r \"40\", fill \"red\" ] []"
                + " , rect [ x \"10\", y \"10\", strokeWidth \"2\" ] [] ]\n");
    assertTrue(html.contains("<svg"), html);
    assertTrue(html.contains("<circle") && html.contains("cx=50") && html.contains("fill=red"), html);
    assertTrue(html.contains("<rect") && html.contains("stroke-width=2"), html); // camelCase → hyphen
  }

  @Test
  void editorSupportsTimeAndMathBuiltins() {
    // Time: a Posix is its milliseconds; 7,200,000 ms = 2 hours (UTC).
    assertEquals(
        "2",
        editorRender(
            "main = text (String.fromInt (Time.toHour zone (Time.millisToPosix 7200000)))\nzone = Time.utc\n"));
    // Math: cos 0 = 1.
    assertEquals("100", editorRender("main = text (String.fromInt (round (100 * cos 0)))\n"));
  }

  @Test
  void editorRendersTheGallerySvgExamples() {
    // The elm-lang.org shapes and clock examples render as inline SVG (clock also needs Time + trig).
    String shapes = editorRender(read("/elm/examples/Shapes.elm"));
    assertTrue(shapes.contains("<svg") && shapes.contains("<circle") && shapes.contains("<rect"), shapes);
    String clock = editorRender(read("/elm/examples/Clock.elm"));
    assertTrue(clock.contains("<svg") && clock.contains("<circle") && clock.contains("<line"), clock);
  }

  @Test
  void editorRendersElmPlaygroundPictureAndAnimation() {
    // evancz/elm-playground: `picture` draws shapes to SVG; `animation` draws its initial frame.
    String picture = editorRender(read("/elm/examples/Picture.elm"));
    assertTrue(picture.contains("<svg") && picture.contains("<rect") && picture.contains("<ellipse"), picture);
    String animation = editorRender(read("/elm/examples/Animation.elm"));
    assertTrue(animation.contains("<svg") && animation.contains("<path"), animation); // octagons as paths
  }

  @Test
  void editorLexesNegativeLiteralsInArgumentPosition() {
    // `f -2` applies f to the negative literal; `a - b` (spaces) stays subtraction.
    assertEquals("-2", editorRender("main = text (String.fromInt (id -2))\nid x = x\n"));
    assertEquals("3", editorRender("main = text (String.fromInt (5 - 2))\n"));
  }

  @Test
  void editorResolvesRandomGenerateCommands() {
    // Drives the `numbers` app: a Roll click produces a `Random.generate` command, which the editor
    // samples with its seed and feeds back as a NewFace message — landing a die face in 1..6.
    String out =
        editorScript(
            read("/elm/examples/Numbers.elm"),
            "var roll=$data('VCtor',['Roll',$nil]);"
                + "var init=_$Eval$appInitCmd(files); var model=init._[0].vs[0];"
                + "var up=_$Eval$appUpdateCmd(files)(roll)(model);"
                + "var cmd=up._[0].vs[1], model2=up._[0].vs[0];"
                + "var rc=_$Eval$randomCmd(files)(12345)(cmd);"
                + "if(rc.$==='Just'){var up2=_$Eval$appUpdateCmd(files)(rc._[0].vs[0])(model2);"
                + "process.stdout.write(_$Eval$renderValue(up2._[0].vs[0]));}"
                + "else process.stdout.write('NO-RANDOM');");
    assertTrue(out.matches(".*dieFace = [1-6].*"), out);
  }

  @Test
  void editorRendersElmPlaygroundGames() {
    // turtle/keyboard/mario use the interactive `game` API; their initial frame renders to SVG.
    assertTrue(editorRender(read("/elm/examples/Turtle.elm")).contains("<svg"), "turtle");
    assertTrue(editorRender(read("/elm/examples/Keyboard.elm")).contains("<rect"), "keyboard square");
  }

  @Test
  void editorGameStepsOnKeyboardInput() {
    // Drives the `keyboard` game: with the right arrow held, one update step moves the square's x
    // from 0 toward +1 (toX keyboard = 1), and the rendered square follows.
    String out =
        editorScript(
            read("/elm/examples/Keyboard.elm"),
            "var mem=_$Eval$gameInitMem(files)._[0];"
                + "var keys=$cons('ArrowRight',$nil);"
                + "var mem2=_$Eval$gameStep(files)(keys)(0)(mem)._[0];"
                + "process.stdout.write(_$Eval$renderValue(mem2));");
    // memory is the tuple (x, y); x should now be positive.
    assertTrue(out.startsWith("(1") || out.startsWith("( 1"), out);
  }

  @Test
  void editorIssuesAndResolvesAnHttpGet() {
    // The book example's init returns an Http.get command; the editor extracts (url, toMsg), and
    // feeding a response body through httpResult + update renders the fetched text. (No real network
    // — the body is supplied directly, exercising the full response path.)
    String out =
        editorScript(
            read("/elm/examples/Book.elm"),
            "var init=_$Eval$appInitCmd(files); var model=init._[0].vs[0]; var cmd=init._[0].vs[1];"
                + "var h=_$Eval$httpCmd(cmd);" // Just (url, toMsg)
                + "var url=h._[0].vs[0], toMsg=h._[0].vs[1];"
                + "var mr=_$Eval$httpResult(files)(toMsg)($maybe('Hello, book!'));" // Ok msg
                + "var up=_$Eval$appUpdateCmd(files)(mr._[0])(model);"
                + "var view=_$Eval$appView(files)(up._[0].vs[0]);"
                + "process.stdout.write(url+'||'+_$Eval$htmlToString(view._[0]));");
    assertTrue(out.startsWith("https://"), out); // the request URL was extracted
    assertTrue(out.contains("Hello, book!"), out); // the response body rendered in the view
  }

  @Test
  void editorDecodesAnHttpJsonResponse() {
    // The quotes example fetches JSON and decodes it with map4/field/string/int. Feeding a JSON body
    // through the expect's decoder renders the decoded quote (full path, no network).
    String body =
        "{\\\"quote\\\":\\\"Be the change\\\",\\\"source\\\":\\\"Speech\\\",\\\"author\\\":\\\"Gandhi\\\",\\\"year\\\":1947}";
    String out =
        editorScript(
            read("/elm/examples/Quotes.elm"),
            "var init=_$Eval$appInitCmd(files); var model=init._[0].vs[0]; var cmd=init._[0].vs[1];"
                + "var h=_$Eval$httpCmd(cmd); var expect=h._[0].vs[1];"
                + "var mr=_$Eval$httpResult(files)(expect)($maybe(\""
                + body
                + "\"));"
                + "var up=_$Eval$appUpdateCmd(files)(mr._[0])(model);"
                + "var view=_$Eval$appView(files)(up._[0].vs[0]);"
                + "process.stdout.write(_$Eval$htmlToString(view._[0]));");
    assertTrue(out.contains("Be the change"), out); // decoded quote text
    assertTrue(out.contains("Gandhi") && out.contains("1947"), out); // author + year (Int) decoded
  }

  @Test
  void editorExtractsTimeEverySubscription() {
    // The clock subscribes via `Time.every 1000 Tick`; the editor reads (interval, toMsg) to wire a
    // live tick.
    String out =
        editorScript(
            read("/elm/examples/Clock.elm"),
            "var init=_$Eval$appInitCmd(files); var model=init._[0].vs[0];"
                + "var sub=_$Eval$appSubscription(files)(model);"
                + "process.stdout.write(sub.$+':'+(sub.$==='Just'?String(sub._[0].vs[0]):''));");
    assertEquals("Just:1000", out);
  }

  @Test
  void editorDecodesAListWithOneOfFallback() {
    // `list (oneOf [ field "n" int, succeed 0 ])`: each element tries `field "n" int`, falling back
    // to 0 when that field is absent — exercises list, oneOf, field and succeed together.
    String src =
        editorDecoderProgram(
            "list (oneOf [ field \"n\" int, succeed 0 ])",
            "Result Http.Error (List Int)",
            "Ok xs -> ( String.fromInt (List.sum xs), Cmd.none )");
    String out = editorScript(src, jsonDriver("[{\\\"n\\\":5},{\\\"m\\\":1},{\\\"n\\\":2}]"));
    assertTrue(out.contains("7"), out); // 5 + 0 (fallback) + 2
  }

  @Test
  void editorDecodesWithAndThen() {
    // `andThen` chooses the next decoder from an already-decoded value.
    String src =
        editorDecoderProgram(
            "andThen pick (field \"kind\" string)\npick k = if k == \"double\" then field \"val\" int else succeed 0",
            "Result Http.Error Int",
            "Ok n -> ( String.fromInt n, Cmd.none )");
    String out = editorScript(src, jsonDriver("{\\\"kind\\\":\\\"double\\\",\\\"val\\\":21}"));
    assertTrue(out.contains("21"), out);
  }

  @Test
  void editorEncodesJson() {
    // Json.Encode in the editor: object / int / bool / list / encode produce a compact JSON string.
    String src =
        "main = Encode.encode 0 (Encode.object "
            + "[ ( \"n\", Encode.int 42 ), ( \"ok\", Encode.bool True ), ( \"xs\", Encode.list Encode.int [ 1, 2 ] ) ])\n";
    String out =
        editorScript(
            src,
            "var mv = _$Eval$mainValue(files);"
                + "process.stdout.write(mv.$==='Ok' ? _$Eval$renderValue(mv._[0]) : ('ERR '+mv._[0]));");
    assertTrue(out.contains("\\\"n\\\":42") || out.contains("\"n\":42"), out);
    assertTrue(out.contains("\"ok\":true"), out);
    assertTrue(out.contains("\"xs\":[1,2]"), out);
  }

  @Test
  void editorDecodesWithMap5() {
    // The decoder runtime handles any mapN arity; map5 over five fields sums them.
    String src =
        editorDecoderProgram(
            "map5 (\\a b c d e -> a + b + c + d + e) (field \"a\" int) (field \"b\" int) (field \"c\" int) (field \"d\" int) (field \"e\" int)",
            "Result Http.Error Int",
            "Ok n -> ( String.fromInt n, Cmd.none )");
    String out = editorScript(src, jsonDriver("{\\\"a\\\":1,\\\"b\\\":2,\\\"c\\\":3,\\\"d\\\":4,\\\"e\\\":5}"));
    assertTrue(out.contains("15"), out);
  }

  @Test
  void editorDecodesWithMap8() {
    // map8 over eight fields, exercising the highest decoder arity end-to-end.
    String src =
        editorDecoderProgram(
            "map8 (\\a b c d e f g h -> a + b + c + d + e + f + g + h)"
                + " (field \"a\" int) (field \"b\" int) (field \"c\" int) (field \"d\" int)"
                + " (field \"e\" int) (field \"f\" int) (field \"g\" int) (field \"h\" int)",
            "Result Http.Error Int",
            "Ok n -> ( String.fromInt n, Cmd.none )");
    String out =
        editorScript(
            src,
            jsonDriver(
                "{\\\"a\\\":1,\\\"b\\\":2,\\\"c\\\":3,\\\"d\\\":4,\\\"e\\\":5,\\\"f\\\":6,\\\"g\\\":7,\\\"h\\\":8}"));
    assertTrue(out.contains("36"), out);
  }

  @Test
  void editorDecodesNullable() {
    // `nullable int` yields Nothing for a JSON null (and Just n otherwise).
    String src =
        editorDecoderProgram(
            "field \"x\" (nullable int)\ndescribe m = case m of\n        Just n -> String.fromInt n\n        Nothing -> \"null\"",
            "Result Http.Error (Maybe Int)",
            "Ok m -> ( describe m, Cmd.none )");
    String out = editorScript(src, jsonDriver("{\\\"x\\\":null}"));
    assertTrue(out.contains("null"), out);
  }

  /** A minimal editor TEA program whose Http request decodes JSON with {@code decoderBody}; the
   * {@code okBranch} renders the decoded value into the view. */
  private static String editorDecoderProgram(String decoderBody, String msgType, String okBranch) {
    return "import Browser\n"
        + "import Html exposing (text)\n"
        + "import Http\n"
        + "import Json.Decode exposing (..)\n"
        + "main = Browser.element { init = init, update = update, view = view, subscriptions = subs }\n"
        + "subs model = Sub.none\n"
        + "init flags = ( \"loading\", Http.get { url = \"u\", expect = Http.expectJson Got decoder } )\n"
        + "decoder = " + decoderBody + "\n"
        + "type Msg = Got (" + msgType + ")\n"
        + "update msg model =\n"
        + "    case msg of\n"
        + "        Got result ->\n"
        + "            case result of\n"
        + "                " + okBranch + "\n"
        + "                Err e -> ( \"err\", Cmd.none )\n"
        + "view model = text model\n";
  }

  /** Drives an editor program's Http path: extracts the request's expect, feeds it {@code body} as
   * the response, runs update, and returns the rendered view text. */
  private static String jsonDriver(String body) {
    return "var init=_$Eval$appInitCmd(files); var model=init._[0].vs[0]; var cmd=init._[0].vs[1];"
        + "var h=_$Eval$httpCmd(cmd); var expect=h._[0].vs[1];"
        + "var mr=_$Eval$httpResult(files)(expect)($maybe(\"" + body + "\"));"
        + "var up=_$Eval$appUpdateCmd(files)(mr._[0])(model);"
        + "var view=_$Eval$appView(files)(up._[0].vs[0]);"
        + "process.stdout.write(_$Eval$htmlToString(view._[0]));";
  }

  /** Reads a classpath resource, normalising CRLF so the editor's `\n`-based escaping is correct. */
  private static String read(String path) {
    return pl.matsuo.elm.util.Resources.read(path).replace("\r", "");
  }

  /** Runs a Node script with the editor bundle loaded and {@code files} bound to a single-file
   * project, returning its stdout — lets a test drive the editor's interpreter directly. */
  private String editorScript(String source, String body) {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        pl.matsuo.elm.site.SiteGenerator.editorAvailable(),
        "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
    String[] modules;
    try {
      modules = pl.matsuo.elm.site.SiteGenerator.editorSources();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
    String escaped = source.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    String program =
        "globalThis.window = globalThis;\n"
            + JsCompiler.declarationsScriptWithDomProject(modules)
            + "\nvar SRC=\""
            + escaped
            + "\";\nvar files=$cons({$:'#',vs:['Main.elm',SRC]},$nil);\n"
            + body
            + "\n";
    return runNode(program);
  }
}

package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.util.Resources;

/**
 * The Elm-in-Elm interpreter (the Lang/Lexer/Parser/Eval/Main modules, written in Elm) must itself
 * evaluate correctly when run by this project's interpreter — i.e. Elm interpreting Elm — and
 * compile via the JS backend (so the editor runs in the browser). Covers a functional subset:
 * numbers, strings, booleans, lists, operators, if/let, lambdas with closures, custom types/case,
 * cross-file top-level definitions and the time-travel debugger.
 */
class EditorInterpreterTest {

  private static final String[] MODULE_PATHS = {
    "/elm/editor/Lang.elm",
    "/elm/editor/Lexer.elm",
    "/elm/editor/Parser.elm",
    "/elm/editor/Eval.elm",
    "/elm/editor/Highlight.elm",
    "/elm/editor/Assist.elm",
    "/elm/editor/Share.elm",
    "/elm/editor/Main.elm",
  };

  private static String[] moduleSources() {
    String[] s = new String[MODULE_PATHS.length];
    for (int i = 0; i < MODULE_PATHS.length; i++) {
      s[i] = Resources.read(MODULE_PATHS[i]);
    }
    return s;
  }

  private static final Project EDITOR = Project.load(moduleSources());

  /** Calls the Elm-written `Eval.eval : String -> String` on a source expression. */
  private String eval(String expression) {
    return Show.plain(Apply.apply(EDITOR.value("Eval", "eval"), expression));
  }

  /** Builds the Elm `List (String, String)` of (filename, content) from alternating args. */
  private static ElmList files(String... nameThenContent) {
    List<Object> pairs = new ArrayList<>();
    for (int i = 0; i + 1 < nameThenContent.length; i += 2) {
      pairs.add(new ElmTuple(new Object[] {nameThenContent[i], nameThenContent[i + 1]}));
    }
    return ElmList.fromJava(pairs);
  }

  /** Calls `Eval.evalProject : List (String,String) -> String -> String`. */
  private String evalProject(ElmList files, String entry) {
    return Show.plain(Apply.applyAll(EDITOR.value("Eval", "evalProject"), files, entry));
  }

  /** Calls `Eval.debugSteps : List (String,String) -> List String -> List String`. */
  @SuppressWarnings("unchecked")
  private List<Object> debugSteps(ElmList files, String... messages) {
    Object r =
        Apply.applyAll(EDITOR.value("Eval", "debugSteps"), files, ElmList.fromJava(List.of(messages)));
    return ((ElmList) r).toJava();
  }

  @Test
  void interpretsArithmeticWithPrecedence() {
    assertEquals("14", eval("2 + 3 * 4"));
    assertEquals("6", eval("2 + 3 * (4 - 1) // 2")); // 3*3//2 = 4, then 2+4
    assertEquals("20", eval("(100 - 60) / 2"));
    assertEquals("-1", eval("2 - 3"));
  }

  @Test
  void interpretsBooleansAndComparison() {
    assertEquals("True", eval("1 < 2"));
    assertEquals("False", eval("3 <= 2"));
    assertEquals("True", eval("1 < 2 && 2 < 3"));
    assertEquals("True", eval("2 == 2"));
    assertEquals("True", eval("1 /= 2"));
  }

  @Test
  void interpretsStringsAndLists() {
    assertEquals("\"hello world\"", eval("\"hello \" ++ \"world\""));
    assertEquals("[1, 2, 3]", eval("[1, 2] ++ [3]"));
    assertEquals("True", eval("[1, 2] == [1, 2]"));
  }

  @Test
  void interpretsMoreStringAndListBuiltins() {
    // (the editor's string lexer doesn't process \\n escapes, so build the newline from a char)
    assertEquals("[\"a\", \"b\"]", eval("String.lines (String.fromList [ 'a', '\\n', 'b' ])"));
    assertEquals("\"AXBXC\"", eval("String.replace \" \" \"X\" \"A B C\""));
    assertEquals("\"--7\"", eval("String.padLeft 3 '-' \"7\""));
    assertEquals("\"ABC\"", eval("String.map Char.toUpper \"abc\""));
    assertEquals("\"ac\"", eval("String.filter (\\c -> c /= 'b') \"abc\""));
    assertEquals("6", eval("String.foldl (\\c acc -> acc + Char.toCode c - Char.toCode 'a' + 1) 0 \"abc\""));
    assertEquals("([2, 4], [1, 3])", eval("List.partition (\\n -> modBy 2 n == 0) [ 1, 2, 3, 4 ]"));
    assertEquals("[1, 0, 2, 0, 3]", eval("List.intersperse 0 [ 1, 2, 3 ]"));
    assertEquals("([1, 2], [\"a\", \"b\"])", eval("List.unzip [ ( 1, \"a\" ), ( 2, \"b\" ) ]"));
    assertEquals("[5, 7, 9]", eval("List.map3 (\\a b c -> a + b + c) [ 1, 2, 3 ] [ 1, 2, 3 ] [ 3, 3, 3 ]"));
  }

  @Test
  void gameKeyboardMovesStateOnArrowKeys() {
    // A playground `game`: stepping with ArrowUp held must change the memory (proving the keyboard
    // -> computer.keyboard -> toY path works end to end in the editor's game loop).
    String src =
        "module Main exposing (main)\n"
            + "import Playground exposing (..)\n"
            + "main = game view update { y = 0 }\n"
            + "view computer mem = [ rectangle red 10 10 |> moveUp mem.y ]\n"
            + "update computer mem = { y = mem.y + toY computer.keyboard }\n";
    ElmList fs = files("Main.elm", src);
    Object mem0 = unwrapJust(Apply.apply(EDITOR.value("Eval", "gameInitMem"), fs));
    // Step once with ArrowUp held.
    String stepped =
        Show.plain(
            Apply.applyAll(
                EDITOR.value("Eval", "gameStep"),
                fs,
                ElmList.fromJava(List.of("ArrowUp")),
                16.0,
                mem0));
    assertTrue(stepped.contains("Ok"), stepped);
    assertTrue(stepped.contains("1"), "ArrowUp moved the memory's y to 1: " + stepped);
  }

  private static Object unwrapJust(Object maybe) {
    return ((pl.matsuo.elm.runtime.ElmData) maybe).arg(0); // Just x -> x
  }

  private static Object okValue(Object result) {
    return ((pl.matsuo.elm.runtime.ElmData) result).arg(0); // Ok x -> x
  }

  private static int countMatches(String s, String sub) {
    int c = 0;
    for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
      c++;
    }
    return c;
  }

  private String renderGame(ElmList fs, List<String> keys, double time, Object mem) {
    return Show.plain(
        okValue(
            Apply.applyAll(
                EDITOR.value("Eval", "gameView"),
                fs,
                ElmList.fromJava(new ArrayList<Object>(keys)),
                time,
                mem)));
  }

  @Test
  void lifeExampleEvolvesAGliderAndSwitchesSetups() throws Exception {
    // Conway's Game of Life (life.elm) must run under the editor's game loop: a glider conserves its
    // five cells across a generation, time advances `gen`, and a number key swaps the starting setup.
    String src =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("src/test/resources/examples/life.elm"));
    ElmList fs = files("Main.elm", src);
    ElmList none = ElmList.fromJava(new ArrayList<>());

    Object mem = unwrapJust(Apply.apply(EDITOR.value("Eval", "gameInitMem"), fs));
    // Initial frame: 5 glider cells + 1 background rectangle = 6 <rect>.
    String frame0 = renderGame(fs, List.of(), 0.0, mem);
    assertEquals(6, countMatches(frame0, "VStr \"rect\""), "glider draws 5 cells: " + frame0);

    // stepEvery (5) frames advance exactly one Conway generation.
    for (int i = 0; i < 5; i++) {
      mem = okValue(Apply.applyAll(EDITOR.value("Eval", "gameStep"), fs, none, (double) (i * 16), mem));
    }
    String afterGen = Show.plain(mem);
    assertTrue(afterGen.contains("(\"gen\",VNum 1)"), "one generation elapsed: " + afterGen);
    assertEquals(
        6,
        countMatches(renderGame(fs, List.of(), 80.0, mem), "VStr \"rect\""),
        "a glider still has 5 live cells after a generation");

    // Holding "3" loads the pulsar setup: many cells, generation reset to 0.
    Object pulsar =
        okValue(
            Apply.applyAll(
                EDITOR.value("Eval", "gameStep"), fs, ElmList.fromJava(List.of("3")), 96.0, mem));
    assertTrue(Show.plain(pulsar).contains("(\"gen\",VNum 0)"), "loading a setup resets gen");
    assertTrue(
        countMatches(renderGame(fs, List.of(), 0.0, pulsar), "VStr \"rect\"") > 30, "pulsar has many cells");
  }

  @Test
  void animationProgramRendersAndIsDrivable() {
    // The editor's builtin `animation` must render an initial frame AND be drivable by the frame
    // loop (gameInitMem returns a memory so the editor keeps advancing time) — not a static one-shot.
    String src =
        "module Main exposing (main)\n"
            + "import Playground exposing (..)\n"
            + "main = animation view\n"
            + "view time =\n"
            + "    [ rectangle red 100 100 |> moveUp (wave 0 50 2 time) ]\n";
    String rendered = Show.plain(Apply.apply(EDITOR.value("Eval", "renderProgram"), src));
    assertTrue(rendered.contains("<svg"), rendered); // an SVG frame, not "animation error"
    String mem = Show.plain(Apply.apply(EDITOR.value("Eval", "gameInitMem"), files("Main.elm", src)));
    assertTrue(mem.startsWith("Just"), "animation has a driving memory so frames advance: " + mem);
  }

  @Test
  void interpretsDict() {
    assertEquals("Just 2", eval("Dict.get \"b\" (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ])"));
    assertEquals("Nothing", eval("Dict.get \"z\" (Dict.fromList [ ( \"a\", 1 ) ])"));
    assertEquals("2", eval("Dict.size (Dict.insert \"b\" 2 (Dict.singleton \"a\" 1))"));
    assertEquals("1", eval("Dict.size (Dict.insert \"a\" 9 (Dict.singleton \"a\" 1))")); // key replaced
    assertEquals("0", eval("Dict.size (Dict.remove \"a\" (Dict.singleton \"a\" 1))"));
    assertEquals("True", eval("Dict.member \"a\" Dict.empty == False"));
    assertEquals("[1, 2]", eval("Dict.values (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ])"));
    assertEquals("6", eval("Dict.foldl (\\k v acc -> acc + v) 0 (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ), ( \"c\", 3 ) ])"));
    // union (left-biased), diff, intersect, update
    assertEquals("Just 1", eval("Dict.get \"a\" (Dict.union (Dict.singleton \"a\" 1) (Dict.singleton \"a\" 99))"));
    assertEquals("2", eval("Dict.size (Dict.union (Dict.singleton \"a\" 1) (Dict.singleton \"b\" 2))"));
    assertEquals("[\"a\"]", eval("Dict.keys (Dict.diff (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ]) (Dict.singleton \"b\" 0))"));
    assertEquals("[\"b\"]", eval("Dict.keys (Dict.intersect (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ) ]) (Dict.singleton \"b\" 0))"));
    assertEquals("Just 6", eval("Dict.get \"a\" (Dict.update \"a\" (Maybe.map (\\n -> n + 1)) (Dict.singleton \"a\" 5))"));
    assertEquals("0", eval("Dict.size (Dict.update \"a\" (\\_ -> Nothing) (Dict.singleton \"a\" 5))"));
    // foldr and partition
    assertEquals("6", eval("Dict.foldr (\\k v acc -> acc + v) 0 (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ), ( \"c\", 3 ) ])"));
    assertEquals("([1, 3], [2])", eval("(\\( yes, no ) -> ( Dict.values yes, Dict.values no )) (Dict.partition (\\k v -> modBy 2 v == 1) (Dict.fromList [ ( \"a\", 1 ), ( \"b\", 2 ), ( \"c\", 3 ) ]))"));
  }

  @Test
  void interpretsMoreStringBuiltins() {
    assertEquals("\"abc\"", eval("String.concat [ \"a\", \"b\", \"c\" ]"));
    assertEquals("\"hi  \"", eval("String.trimLeft \"  hi  \""));
    assertEquals("\"  hi\"", eval("String.trimRight \"  hi  \""));
    assertEquals("True", eval("String.all Char.isDigit \"12345\""));
    assertEquals("False", eval("String.all Char.isDigit \"12a45\""));
    assertEquals("True", eval("String.any Char.isUpper \"abcD\""));
    assertEquals("False", eval("String.any Char.isUpper \"abcd\""));
    assertEquals("\"--7--\"", eval("String.pad 5 '-' \"7\"")); // both sides
    assertEquals("\"-12--\"", eval("String.pad 5 '-' \"12\"")); // odd padding: extra on the right
  }

  @Test
  void letMayDestructureTuplesAndRecords() {
    assertEquals("3", eval("let\n    ( a, b ) = ( 1, 2 )\nin\na + b"));
    assertEquals("5", eval("let\n    { x } = { x = 5, y = 9 }\nin\nx"));
    // a destructure binding alongside a normal one, with later use
    assertEquals("30", eval("let\n    ( a, b ) = ( 10, 20 )\n    c = a + b\nin\nc"));
  }

  @Test
  void letBindingsMayCarryTypeAnnotations() {
    // A type annotation on a let binding is parsed and skipped (the binding still evaluates).
    assertEquals("6", eval("let\n    x : Int\n    x = 5\nin\nx + 1"));
    // Multiple annotated bindings.
    assertEquals("12", eval("let\n    a : Int\n    a = 5\n    b : Int\n    b = 7\nin\na + b"));
    // A function binding with an annotation.
    assertEquals("9", eval("let\n    sq : Int -> Int\n    sq n = n * n\nin\nsq 3"));
  }

  @Test
  void interpretsCharHexOctPredicates() {
    assertEquals("True", eval("Char.isHexDigit 'f'"));
    assertEquals("True", eval("Char.isHexDigit 'F'"));
    assertEquals("True", eval("Char.isHexDigit '9'"));
    assertEquals("False", eval("Char.isHexDigit 'g'"));
    assertEquals("True", eval("Char.isOctDigit '7'"));
    assertEquals("False", eval("Char.isOctDigit '8'"));
  }

  @Test
  void lexesTripleQuotedStrings() {
    assertEquals("3", eval("String.length \"\"\"abc\"\"\""));
    assertEquals("5", eval("String.length \"\"\"ab\ncd\"\"\"")); // a real newline is kept verbatim
    assertEquals("3", eval("String.length \"\"\"a\"b\"\"\"")); // a lone quote inside is allowed
    assertEquals("True", eval("\"\"\"hi\"\"\" == \"hi\""));
  }

  @Test
  void divisionByZeroFollowsElmSemantics() {
    assertEquals("True", eval("isInfinite (1 / 0)")); // float / 0 -> Infinity, not an error
    assertEquals("True", eval("isNaN (0 / 0)")); // 0 / 0 -> NaN
    assertEquals("0", eval("7 // 0")); // Elm defines integer // 0 as 0
    assertEquals("3", eval("7 // 2")); // normal integer division still truncates
  }

  @Test
  void interpretsPowerOperator() {
    assertEquals("8", eval("2 ^ 3"));
    assertEquals("512", eval("2 ^ 3 ^ 2")); // right-associative: 2 ^ (3 ^ 2) = 2 ^ 9
    assertEquals("18", eval("2 * 3 ^ 2")); // ^ binds tighter than *: 2 * 9
    assertEquals("True", eval("2 ^ 10 == 1024"));
  }

  @Test
  void interpretsTupleMappers() {
    assertEquals("(2, \"a\")", eval("Tuple.mapFirst (\\n -> n + 1) ( 1, \"a\" )"));
    assertEquals("(1, \"A\")", eval("Tuple.mapSecond String.toUpper ( 1, \"a\" )"));
    assertEquals("(2, \"A\")", eval("Tuple.mapBoth (\\n -> n + 1) String.toUpper ( 1, \"a\" )"));
  }

  @Test
  void interpretsCompareSortWithAndMapN() {
    assertEquals("LT", eval("compare 1 2"));
    assertEquals("GT", eval("compare \"b\" \"a\""));
    assertEquals("EQ", eval("compare 3 3"));
    // sortWith a custom descending comparator (flip compare).
    assertEquals("[3, 2, 1]", eval("List.sortWith (\\a b -> compare b a) [ 1, 3, 2 ]"));
    assertEquals("[6, 15]", eval("List.map4 (\\a b c d -> a + b + c + d) [ 1, 4 ] [ 2, 5 ] [ 3, 6 ] [ 0, 0 ]"));
    assertEquals("[15]", eval("List.map5 (\\a b c d e -> a + b + c + d + e) [ 1 ] [ 2 ] [ 3 ] [ 4 ] [ 5 ]"));
  }

  @Test
  void matchesNegativeLiteralPatterns() {
    assertEquals("100", eval("case -1 of\n  -1 -> 100\n  _ -> 0"));
    assertEquals("0", eval("case 5 of\n  -1 -> 100\n  _ -> 0"));
    assertEquals("22", eval("case -2 of\n  -2 -> 22\n  _ -> 0"));
  }

  @Test
  void interpretsCompositionAndOperatorArguments() {
    assertEquals("12", eval("((\\x -> x + 1) >> (\\x -> x * 2)) 5")); // (5+1)*2
    assertEquals("12", eval("((\\x -> x * 2) << (\\x -> x + 1)) 5")); // 2*(5+1)
    assertEquals("[3, 2, 1]", eval("List.foldl (::) [] [ 1, 2, 3 ]")); // cons as a function
    assertEquals("6", eval("List.foldl (+) 0 [ 1, 2, 3 ]")); // (+) as a function
  }

  @Test
  void interpretsXor() {
    assertEquals("True", eval("xor True False"));
    assertEquals("False", eval("xor True True"));
    assertEquals("False", eval("xor False False"));
  }

  @Test
  void interpretsBitwise() {
    assertEquals("12", eval("Bitwise.and 14 13")); // 1110 & 1101 = 1100
    assertEquals("15", eval("Bitwise.or 12 3")); // 1100 | 0011 = 1111
    assertEquals("6", eval("Bitwise.xor 5 3")); // 0101 ^ 0011 = 0110
    assertEquals("32", eval("Bitwise.shiftLeftBy 2 8")); // 8 << 2
    assertEquals("2", eval("Bitwise.shiftRightBy 2 8")); // 8 >> 2
    assertEquals("True", eval("Bitwise.complement 0 == -1"));
  }

  @Test
  void interpretsBasicsMath() {
    assertEquals("True", eval("atan2 1 1 > 0.78 && atan2 1 1 < 0.79")); // pi/4 ≈ 0.785
    assertEquals("2", eval("round (logBase 2 4)")); // log2(4) = 2
    assertEquals("0", eval("round (asin 0)"));
    assertEquals("0", eval("round (acos 1)"));
    assertEquals("0", eval("round (atan 0)"));
    assertEquals("True", eval("radians 3 == 3")); // radians is the identity
    assertEquals("True", eval("turns 1 > 6.28 && turns 1 < 6.29")); // 2*pi
    assertEquals("True", eval("isNaN (sqrt (negate 1))")); // sqrt of a negative is NaN
    assertEquals("False", eval("isNaN 1.0"));
    assertEquals("False", eval("isInfinite 1.0"));
  }

  @Test
  void interpretsSet() {
    assertEquals("3", eval("Set.size (Set.fromList [ 3, 1, 2, 1, 3 ])")); // duplicates collapse
    assertEquals("True", eval("Set.member 2 (Set.fromList [ 1, 2, 3 ])"));
    assertEquals("[1, 2, 3]", eval("Set.toList (Set.insert 1 (Set.fromList [ 2, 3 ]))"));
    assertEquals("[1, 2, 3]", eval("Set.toList (Set.union (Set.fromList [ 1, 2 ]) (Set.fromList [ 2, 3 ]))"));
    assertEquals("[2]", eval("Set.toList (Set.intersect (Set.fromList [ 1, 2 ]) (Set.fromList [ 2, 3 ]))"));
    assertEquals("[1]", eval("Set.toList (Set.diff (Set.fromList [ 1, 2 ]) (Set.fromList [ 2, 3 ]))"));
    assertEquals("6", eval("Set.foldl (+) 0 (Set.fromList [ 1, 2, 3 ])"));
    assertEquals("[2, 4]", eval("Set.toList (Set.filter (\\n -> modBy 2 n == 0) (Set.fromList [ 1, 2, 3, 4 ]))"));
    assertEquals("Set.fromList [1,2]", eval("Set.fromList [ 2, 1, 2 ]"));
  }

  @Test
  void lexesHexAndScientificNumbers() {
    assertEquals("255", eval("0xFF"));
    assertEquals("16", eval("0x10"));
    assertEquals("True", eval("0xff == 255")); // lowercase hex digits
    assertEquals("1500", eval("1.5e3"));
    assertEquals("True", eval("2e9 == 2000000000"));
    assertEquals("True", eval("1.5e-3 == 0.0015"));
    assertEquals("510", eval("0xFF + 0xFF")); // hex in arithmetic
    assertEquals("-16", eval("List.sum [ -0x10 ]")); // negative hex literal
  }

  @Test
  void lexesStringAndCharEscapes() {
    assertEquals("3", eval("String.length \"a\\nb\"")); // \n is one (newline) character
    assertEquals("2", eval("List.length (String.lines \"a\\nb\")")); // the \n really splits lines
    assertEquals("3", eval("String.length \"x\\ty\"")); // \t tab
    assertEquals("1", eval("String.length \"\\\\\"")); // \\ -> a single backslash
    // Unicode brace-escapes: built by concatenation so the Java source never contains the escape.
    assertEquals("True", eval("\"\\" + "u{41}\" == \"A\"")); // code 0x41 is 'A'
    assertEquals("True", eval("'\\" + "u{42}' == 'B'")); // code 0x42 is 'B'
  }

  @Test
  void interpretsResultCombinators() {
    assertEquals("Err \"BOOM\"", eval("Result.mapError String.toUpper (Err \"boom\")"));
    assertEquals("Ok 5", eval("Result.mapError String.toUpper (Ok 5)"));
    assertEquals("Ok 1", eval("Result.fromMaybe \"none\" (Just 1)"));
    assertEquals("Err \"none\"", eval("Result.fromMaybe \"none\" Nothing"));
    assertEquals("Ok 3", eval("Result.map2 (+) (Ok 1) (Ok 2)"));
    assertEquals("Err \"e\"", eval("Result.map2 (+) (Ok 1) (Err \"e\")"));
    assertEquals("Ok 6", eval("Result.map3 (\\a b c -> a + b + c) (Ok 1) (Ok 2) (Ok 3)"));
    assertEquals("Ok 10", eval("Result.map4 (\\a b c d -> a + b + c + d) (Ok 1) (Ok 2) (Ok 3) (Ok 4)"));
    assertEquals("Err \"e\"", eval("Result.map5 (\\a b c d e -> a) (Ok 1) (Ok 2) (Err \"e\") (Ok 4) (Ok 5)"));
  }

  @Test
  void interpretsMaybeMapN() {
    assertEquals("Just 6", eval("Maybe.map3 (\\a b c -> a + b + c) (Just 1) (Just 2) (Just 3)"));
    assertEquals("Nothing", eval("Maybe.map3 (\\a b c -> a + b + c) (Just 1) Nothing (Just 3)"));
    assertEquals("Just 10", eval("Maybe.map4 (\\a b c d -> a + b + c + d) (Just 1) (Just 2) (Just 3) (Just 4)"));
    assertEquals("Just 15", eval("Maybe.map5 (\\a b c d e -> a + b + c + d + e) (Just 1) (Just 2) (Just 3) (Just 4) (Just 5)"));
    assertEquals("Nothing", eval("Maybe.map5 (\\a b c d e -> a) (Just 1) (Just 2) (Just 3) (Just 4) Nothing"));
  }

  @Test
  void interpretsDebug() {
    assertEquals("\"42\"", eval("Debug.toString 42"));
    assertEquals("\"[1, 2, 3]\"", eval("Debug.toString [ 1, 2, 3 ]"));
    assertEquals("\"\"hi\"\"", eval("Debug.toString \"hi\"")); // strings are quoted, like Elm
    assertEquals("7", eval("Debug.log \"x\" 7")); // log returns its value (no console here)
    assertTrue(eval("Debug.todo \"unfinished\"").startsWith("Error"), eval("Debug.todo \"unfinished\""));
  }

  @Test
  void interpretsArray() {
    assertEquals("Just 30", eval("Array.get 2 (Array.fromList [ 10, 20, 30 ])"));
    assertEquals("Nothing", eval("Array.get 5 (Array.fromList [ 10, 20, 30 ])"));
    assertEquals("3", eval("Array.length (Array.push 3 (Array.fromList [ 1, 2 ]))"));
    assertEquals("[1, 9, 3]", eval("Array.toList (Array.set 1 9 (Array.fromList [ 1, 2, 3 ]))"));
    assertEquals("[0, 1, 2, 3]", eval("Array.toList (Array.initialize 4 identity)"));
    assertEquals("[7, 7]", eval("Array.toList (Array.repeat 2 7)"));
    assertEquals("[2, 4, 6]", eval("Array.toList (Array.map (\\x -> x * 2) (Array.fromList [ 1, 2, 3 ]))"));
    assertEquals("[20, 30]", eval("Array.toList (Array.slice 1 3 (Array.fromList [ 10, 20, 30, 40 ]))"));
    assertEquals("[10, 20]", eval("Array.toList (Array.slice 0 -2 (Array.fromList [ 10, 20, 30, 40 ]))"));
    assertEquals("6", eval("Array.foldl (+) 0 (Array.fromList [ 1, 2, 3 ])"));
    assertEquals("Array.fromList [1,2]", eval("Array.fromList [ 1, 2 ]"));
  }

  @Test
  void interpretsAsPatterns() {
    // `as` binds the whole matched value alongside the inner pattern.
    assertEquals("10", eval("case 5 of\n    n as m -> n + m\n    _ -> 0"));
    assertEquals(
        "4",
        eval("case [ 1, 2, 3 ] of\n    (x :: _) as whole -> x + List.length whole\n    _ -> 0"));
  }

  @Test
  void interpretsOperatorsAsFunctions() {
    assertEquals("6", eval("List.foldl (+) 0 [ 1, 2, 3 ]"));
    assertEquals("[2, 4, 6]", eval("List.map ((*) 2) [ 1, 2, 3 ]"));
    assertEquals("[1, 2, 3]", eval("(::) 1 [ 2, 3 ]"));
    assertEquals("\"ab\"", eval("(++) \"a\" \"b\""));
    assertEquals("6", eval("((|>) 5) ((+) 1)")); // 5 |> (1 +)  ==  (1 +) 5  ==  6
  }

  @Test
  void interpretsCharLiteralsAndOperations() {
    assertEquals("'a'", eval("'a'"));
    assertEquals("97", eval("Char.toCode 'a'"));
    assertEquals("'A'", eval("Char.toUpper 'a'"));
    assertEquals("True", eval("Char.isDigit '7'"));
    assertEquals("['a', 'b', 'c']", eval("String.toList \"abc\""));
    assertEquals("\"abc\"", eval("String.fromList ['a', 'b', 'c']"));
    assertEquals("\"hi\"", eval("String.cons 'h' \"i\""));
    assertEquals("True", eval("'a' < 'b'"));
    // case over a char literal pattern
    assertEquals("1", eval("case 'x' of\n    'x' -> 1\n    _ -> 0"));
  }

  @Test
  void interpretsIfLetAndLambdas() {
    assertEquals("3", eval("if 1 < 2 then 3 else 4"));
    assertEquals("42", eval("let double = \\x -> x * 2 in double 21"));
    assertEquals("7", eval("(\\x y -> x + y) 3 4")); // multi-arg lambda + application
    assertEquals("10", eval("let add = \\a b -> a + b in add 4 6"));
  }

  @Test
  void interpretsCustomTypesAndCase() {
    // Constructors are any capitalised name; case branches are ';'-separated.
    assertEquals("14", eval("case Just (3 + 4) of Just n -> n * 2 ; Nothing -> 0"));
    assertEquals("0", eval("case Nothing of Just n -> n ; Nothing -> 0"));
    // Nested constructor pattern + a value that renders as a constructor application.
    assertEquals("Pair 1 2", eval("Pair 1 2"));
    assertEquals("3", eval("case Pair 1 2 of Pair a b -> a + b"));
    // List patterns in case.
    assertEquals("10", eval("case [10, 20] of [] -> 0 ; h :: t -> h"));
    // Wildcard fallthrough.
    assertEquals("99", eval("case Blue of Red -> 1 ; _ -> 99"));
  }

  @Test
  void interpretsUnitRecordAndDestructuringPatterns() {
    // Unit pattern `()` as a function parameter (e.g. `init () = …`).
    assertEquals("42", evalProject(files("Main.elm", "f () = 42"), "f ()"));
    // A `let` function binding whose parameter is a tuple pattern (e.g. crate's `transformTriangle`).
    assertEquals("7", eval("let g (a, b) = a + b in g (3, 4)"));
    // A record pattern binds the named fields (e.g. thwomp's `GotViewport { viewport } ->`).
    assertEquals("5", eval("case { x = 5, y = 6 } of { x } -> x"));
    // A constructor whose argument is a tuple pattern (e.g. thwomp's `Just (face, side) ->`).
    assertEquals("9", eval("case Just (4, 5) of Just (a, b) -> a + b ; Nothing -> 0"));
  }

  @Test
  void lambdasAcceptDestructuringParameters() {
    // A lambda with a record pattern parameter (e.g. first-person's `\{viewport} -> …`).
    assertEquals("5", eval("(\\{ x } -> x) { x = 5, y = 6 }"));
    // A lambda with a tuple pattern parameter.
    assertEquals("7", eval("(\\( a, b ) -> a + b) ( 3, 4 )"));
    // Mixed: a plain parameter and a record-pattern parameter.
    assertEquals("11", eval("(\\n { x } -> n + x) 6 { x = 5 }"));
  }

  @Test
  void resolvesWebglAndBrowserEventsQualifiedNames() {
    // Names that previously errored with "unknown qualified name" in the WebGL examples now resolve
    // (to opaque values the WebGL bridge / editor handle) rather than failing.
    for (String expr :
        new String[] {
          "Vec3.scale 2 (vec3 1 1 1)",
          "Vec3.normalize (vec3 1 2 3)",
          "Vec3.i",
          "Texture.load \"u\"",
          "Texture.nearest",
          "Dom.getViewport",
          "Time.now",
          "Time.here",
          "E.onAnimationFrameDelta",
          "E.onResize",
          "E.onMouseMove",
          // Json.Decode under an import alias (image-previews uses `import Json.Decode as D`).
          "D.succeed 1",
          "D.map (\\x -> x) (D.field \"a\" D.int)",
          "D.at [ \"a\", \"b\" ] D.string",
          "D.oneOrMore (\\h t -> h) D.int",
          // Generic event handlers used by image-previews' drag target (was: undefined variable).
          "preventDefaultOn \"dragover\" (D.succeed ( 1, True ))",
          "on \"drop\" (D.succeed 1)",
          "D.at [ \"dataTransfer\", \"files\" ] (D.oneOrMore (\\f fs -> f) File.decoder)",
          "Select.files [ \"image/*\" ] (\\f fs -> 1)",
          // A builtin referenced under its module name when the file exposes only the type
          // (thwomp does `import Html exposing (Html)` then `Html.text "Loading textures..."`).
          "Html.text \"Loading textures...\"",
          "Html.div [] [ Html.text \"hi\" ]"
        }) {
      assertFalse(eval(expr).startsWith("Error"), expr + " => " + eval(expr));
    }
  }

  @Test
  void backwardPipeIsRightAssociative() {
    // `<|` is infixr: `a <| b <| c` is `a (b c)`. Chained `<|` is how the Cube example builds its
    // mesh (`WebGL.triangles <| List.concat <| [ … ]`); left-associative parsing made it garbage.
    assertEquals("12", eval("List.sum <| List.map (\\x -> x * 2) <| [ 1, 2, 3 ]"));
    assertEquals("[1, 2, 3]", eval("identity <| List.concat <| [ [ 1 ], [ 2, 3 ] ]"));
    // `::` is also right-associative: `1 :: 2 :: []` is `1 :: (2 :: [])`.
    assertEquals("[1, 2]", eval("1 :: 2 :: []"));
  }

  @Test
  void parsesTheWebglExamples() throws Exception {
    // triangle/crate/thwomp/cube use unit, record and tuple-destructuring patterns plus `[glsl| … |]`
    // shader literals; the editor must at least parse them and evaluate `main` to a program record.
    for (String slug : new String[] {"triangle", "crate", "thwomp", "cube"}) {
      String src =
          java.nio.file.Files.readString(
              java.nio.file.Path.of("src/test/resources/examples/" + slug + ".elm"));
      String out = evalProject(files("Main.elm", src), "main");
      assertTrue(out.startsWith("{ init ="), slug + " did not parse/evaluate: " + out);
    }
  }

  @Test
  void interpretsRecursiveCustomTypeViaCase() {
    // A recursive tree summed by a recursive function with case — closures + ctors + matching.
    assertEquals(
        "6",
        eval(
            "let sum = \\t -> case t of Leaf n -> n ; Node l r -> sum l + sum r "
                + "in sum (Node (Node (Leaf 1) (Leaf 2)) (Leaf 3))"));
  }

  @Test
  void reportsErrors() {
    assertTrue(eval("1 +").startsWith("Error"), eval("1 +")); // truncated input
    assertTrue(eval("(1 + 2").startsWith("Error"), eval("(1 + 2")); // missing )
    assertTrue(eval("nope").contains("undefined variable"), eval("nope"));
  }

  @Test
  void evaluatesTopLevelDefinitionsAcrossFiles() {
    // `main` in one file calls a helper defined in another — one shared, mutually-recursive scope.
    ElmList project =
        files(
            "Lib.elm", "double x = x * 2\n\ntriple x = x * 3",
            "Main.elm", "main = double 21 + triple 0");
    assertEquals("42", evalProject(project, "main"));
    assertEquals("63", evalProject(project, "triple 21"));
    // Mutual recursion across the project (isEven/isOdd in separate files).
    ElmList recur =
        files(
            "A.elm", "isEven n = if n == 0 then True else isOdd (n - 1)",
            "B.elm", "isOdd n = if n == 0 then False else isEven (n - 1)");
    assertEquals("True", evalProject(recur, "isEven 10"));
  }

  @Test
  void timeTravelDebuggerStepsThroughModelChanges() {
    ElmList counter =
        files(
            "Counter.elm",
            "init = 0\n"
                + "update msg model = case msg of Inc -> model + 1 ; Dec -> model - 1 ; _ -> model\n"
                + "view model = \"count = \" ++ toString model");
    List<Object> steps = debugSteps(counter, "Inc", "Inc", "Dec");
    assertEquals(4, steps.size()); // initial + one per message
    assertTrue(String.valueOf(steps.get(0)).contains("model: 0"), steps.toString());
    assertTrue(String.valueOf(steps.get(0)).contains("count = 0"), "initial view rendered");
    // 0 -> 1 -> 2 -> 1
    assertTrue(String.valueOf(steps.get(3)).contains("model: 1"), steps.toString());
    assertTrue(String.valueOf(steps.get(3)).contains("count = 1"), "final view rendered");
  }

  @Test
  void debuggerWithoutProgramExplainsWhat() {
    ElmList notProgram = files("Main.elm", "main = 1");
    assertTrue(String.valueOf(debugSteps(notProgram).get(0)).contains("init"), "guidance shown");
  }

  @Test
  void interpretsRecordsLiteralsAccessAndUpdate() {
    assertEquals("2", eval("{ x = 1, y = 2 }.y"));
    assertEquals("{ x = 1, y = 2 }", eval("{ x = 1, y = 2 }"));
    assertEquals("{}", eval("{}"));
    // Record update via a project (the base record is a top-level binding).
    ElmList project =
        files("M.elm", "point = { x = 1, y = 2 }\nmoved = { point | x = 9 }");
    assertEquals("9", evalProject(project, "moved.x"));
    assertEquals("2", evalProject(project, "moved.y")); // untouched field preserved
    assertEquals("True", evalProject(project, "{ a = 1, b = 2 } == { b = 2, a = 1 }")); // order-independent
  }

  @Test
  void debuggerWithARecordModel() {
    ElmList app =
        files(
            "App.elm",
            "init = { count = 0, log = [] }\n"
                + "update msg model = case msg of"
                + " Inc -> { model | count = model.count + 1 } ;"
                + " _ -> model\n"
                + "view model = \"count = \" ++ toString model.count");
    List<Object> steps = debugSteps(app, "Inc", "Inc", "Inc");
    assertEquals(4, steps.size());
    assertTrue(String.valueOf(steps.get(3)).contains("count = 3"), steps.toString());
    assertTrue(String.valueOf(steps.get(3)).contains("count = 3"), "record field accessed in view");
  }

  @Test
  void interpretsExpandedListLibrary() {
    assertEquals("[3, 2, 1]", eval("List.reverse [1, 2, 3]"));
    assertEquals("[2, 4]", eval("List.filter (\\x -> x > 1) [1, 2, 1, 4]"));
    assertEquals("10", eval("List.foldl (\\x acc -> x + acc) 0 [1, 2, 3, 4]"));
    assertEquals("[1, 2, 3, 4]", eval("List.append [1, 2] [3, 4]"));
    assertEquals("True", eval("List.member 3 [1, 2, 3]"));
    assertEquals("False", eval("List.member 9 [1, 2, 3]"));
    assertEquals("[2, 4, 6]", eval("List.map2 (\\a b -> a + b) [1, 2, 3] [1, 2, 3]"));
    assertEquals("[2, 4]", eval("List.filterMap (\\x -> if x > 1 then Just x else Nothing) [1, 2, 1, 4]"));
    assertEquals("[1, 2]", eval("List.take 2 [1, 2, 3, 4]"));
    assertEquals("[3, 4]", eval("List.drop 2 [1, 2, 3, 4]"));
    assertEquals("[1, 2, 3]", eval("List.sort [3, 1, 2]"));
    assertEquals("4", eval("Maybe.withDefault 0 (List.maximum [1, 4, 2])"));
    assertEquals("True", eval("List.all (\\x -> x > 0) [1, 2, 3]"));
    assertEquals("True", eval("List.any (\\x -> x > 2) [1, 2, 3]"));
    assertEquals("[1, 2, 3, 4]", eval("List.concat [[1, 2], [3, 4]]"));
  }

  @Test
  void interpretsExpandedStringAndMaybeLibrary() {
    assertEquals("\"abc\"", eval("String.append \"ab\" \"c\""));
    assertEquals("True", eval("String.contains \"ell\" \"hello\""));
    assertEquals("\"hel\"", eval("String.left 3 \"hello\""));
    assertEquals("\"llo\"", eval("String.right 3 \"hello\""));
    assertEquals("[\"a\", \"b\", \"c\"]", eval("String.split \",\" \"a,b,c\""));
    assertEquals("Just 42", eval("String.toInt \"42\""));
    assertEquals("Nothing", eval("String.toInt \"x\""));
    assertEquals("Just 11", eval("Maybe.map (\\x -> x + 1) (Just 10)"));
    assertEquals("Nothing", eval("Maybe.map (\\x -> x + 1) Nothing"));
    assertEquals("7", eval("Result.withDefault 0 (Ok 7)"));
    assertEquals("0", eval("Result.withDefault 0 (Err \"boom\")"));
    assertEquals("3", eval("clamp 0 3 9"));
    assertEquals("1", eval("modBy 3 7"));
    assertEquals("1", eval("Tuple.first (1, 2)"));
    assertEquals("2", eval("Tuple.second (1, 2)"));
  }

  @Test
  void parsesGlslShaderLiteralsAndEvaluatesWebgl() {
    // A multi-line GLSL literal is collapsed to a string so the program parses; WebGL.toHtml
    // evaluates to a structured `WebGL.scene` value (rendered live in the browser by the editor's
    // WebGL bridge) carrying its entities — each with shaders, a mesh and uniforms.
    ElmList project =
        files(
            "Scene.elm",
            "vert =\n"
                + "    [glsl|\n"
                + "        attribute vec3 position;\n"
                + "        void main () { gl_Position = vec4(position, 1.0); }\n"
                + "    |]\n"
                + "frag =\n"
                + "    [glsl| void main () { gl_FragColor = vec4(1.0); } |]\n"
                + "mesh = WebGL.triangles []\n"
                + "scene = WebGL.toHtml [] [ WebGL.entity vert frag mesh {}, WebGL.entity vert frag mesh {} ]");
    String scene = evalProject(project, "scene");
    assertTrue(scene.contains("WebGL.scene"), scene); // a structured scene, not a text preview
    // Both entities are present in the scene's entity list.
    assertEquals(2, countOccurrences(scene, "WebGL.entity"), scene);
    // The shader literal itself evaluates to its (flattened) source string.
    assertTrue(evalProject(project, "frag").contains("gl_FragColor"), "shader body preserved");
  }

  @Test
  void evaluatesFileEffectsToStructuredCommands() {
    // `File.Select.file` is a command the editor runs by opening a real browser picker; the
    // interpreter evaluates it (and the rest of the File flow) to structured values.
    assertTrue(eval("File.Select.file [\"text/*\"] GotFile").contains("Cmd.fileSelect"), "select -> a command");
    // `Task.perform` wraps a task into a command the editor resolves; `File.toString` on a picked
    // file yields a task carrying the text.
    assertTrue(
        eval("Task.perform GotText (File.toString (File \"n.txt\" \"hi\"))").contains("Cmd.task"),
        "Task.perform -> command");
    // `File.name`/`File.toString` work on a picked file value (what the editor delivers).
    ElmList project =
        files(
            "M.elm",
            "f = File \"notes.txt\" \"hello world\"\n"
                + "fileName = File.name f\n"
                + "fileSize = File.size f\n");
    assertEquals("\"notes.txt\"", evalProject(project, "fileName"));
    assertEquals("11", evalProject(project, "fileSize"));
    // A File program (select -> read -> store) loads and its initial model is interpretable.
    ElmList app =
        files(
            "Upload.elm",
            "init = { text = \"\" }\n"
                + "loadCmd = File.Select.file [ \"text/*\" ] Got\n"
                + "view model = text model.text");
    assertEquals("\"\"", evalProject(app, "init.text"));
    assertTrue(evalProject(app, "loadCmd").contains("Cmd.fileSelect"), "the load command evaluates");
  }

  private static int countOccurrences(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
      n++;
    }
    return n;
  }

  /** Calls `Highlight.segments : String -> List (String, String)`. */
  @SuppressWarnings("unchecked")
  private List<ElmTuple> segments(String src) {
    Object r = Apply.apply(EDITOR.value("Highlight", "segments"), src);
    return (List<ElmTuple>) (List<?>) ((ElmList) r).toJava();
  }

  @Test
  void highlighterIsCharacterFaithfulAndClassifies() {
    String src = "-- a comment\nadd : Int -> Int\nadd n = n + 42 -- tail\nname = \"Bob\"";
    List<ElmTuple> segs = segments(src);
    // Faithful: concatenating every segment's text reproduces the source exactly.
    StringBuilder sb = new StringBuilder();
    for (ElmTuple seg : segs) {
      sb.append((String) seg.get(1));
    }
    assertEquals(src, sb.toString(), "highlighter must preserve every character");
    // Classification: at least one of each expected class is produced.
    assertTrue(hasClassWith(segs, "com", "-- a comment"), "line comment");
    assertTrue(hasClassWith(segs, "type", "Int"), "upper-case type name");
    assertTrue(hasClassWith(segs, "num", "42"), "number literal");
    assertTrue(hasClassWith(segs, "str", "\"Bob\""), "string literal");
    assertTrue(hasClassWith(segs, "op", "->"), "operator");
  }

  @Test
  void highlighterTagsKeywords() {
    List<ElmTuple> segs = segments("case x of\n  _ -> if a then b else c");
    assertTrue(hasClassWith(segs, "kw", "case"), "case keyword");
    assertTrue(hasClassWith(segs, "kw", "of"), "of keyword");
    assertTrue(hasClassWith(segs, "kw", "if"), "if keyword");
    assertTrue(hasClassWith(segs, "kw", "else"), "else keyword");
  }

  private static boolean hasClassWith(List<ElmTuple> segs, String cls, String text) {
    return segs.stream().anyMatch(s -> cls.equals(s.get(0)) && text.equals(s.get(1)));
  }

  /** Calls `Assist.completions : String -> String -> List String`, resolving each element. */
  private List<String> completions(String source, String prefix) {
    Object r = Apply.applyAll(EDITOR.value("Assist", "completions"), source, prefix);
    List<String> out = new ArrayList<>();
    for (Object o : ((ElmList) r).toJava()) {
      out.add(String.valueOf(pl.matsuo.elm.interp.Thunk.resolve(o)));
    }
    return out;
  }

  @Test
  void autocompleteSuggestsKeywordsBuiltinsAndBufferIdentifiers() {
    // From a buffer that defines `mapper`, typing "map" offers the in-buffer identifier.
    assertTrue(completions("mapper xs = negate xs\nother = 1", "map").contains("mapper"),
        completions("mapper xs = negate xs\nother = 1", "map").toString());

    // Qualified built-ins complete on their module prefix (completion is case-sensitive).
    List<String> qualified = completions("x = 1", "List.");
    assertTrue(qualified.contains("List.map") && qualified.contains("List.filter"), qualified.toString());

    // Keyword completion, and the prefix itself is never echoed back.
    assertTrue(completions("x = 1", "ca").contains("case"), "case keyword");
    assertTrue(completions("x = 1", "case").isEmpty(), "exact match isn't re-offered");

    // An empty prefix yields nothing (no popup on every keystroke).
    assertTrue(completions("anything = 1", "").isEmpty(), "empty prefix → no suggestions");
  }

  @Test
  void autocompleteExtractsTheWordAtTheCaret() {
    // wordAt source offset -> the (qualified) identifier ending at the caret (offset is an Elm Int).
    String src = "main = List.ma";
    assertEquals("List.ma",
        Show.plain(Apply.applyAll(EDITOR.value("Assist", "wordAt"), src, (long) src.length())));
    // Caret right after a non-identifier char -> empty word.
    assertEquals("", Show.plain(Apply.applyAll(EDITOR.value("Assist", "wordAt"), "a + ", 4L)));
  }

  @Test
  void acceptInsertsACompletionAtTheCaret() {
    // accept source caret completion -> (newSource, newCaret): the half-typed word is replaced.
    String src = "main = List.ma";
    Object r = Apply.applyAll(EDITOR.value("Assist", "accept"), src, (long) src.length(), "List.map");
    ElmTuple t = (ElmTuple) pl.matsuo.elm.interp.Thunk.resolve(r);
    assertEquals("main = List.map", String.valueOf(pl.matsuo.elm.interp.Thunk.resolve(t.get(0))));
    assertEquals(15L, pl.matsuo.elm.interp.Thunk.resolve(t.get(1)));
  }

  @Test
  void errorNameExtractsTheOffendingIdentifier() {
    assertTrue(
        Show.plain(Apply.apply(EDITOR.value("Assist", "errorName"), "undefined variable: nope"))
            .contains("nope"),
        "names the variable after the colon");
    assertEquals(
        "Nothing",
        Show.plain(Apply.apply(EDITOR.value("Assist", "errorName"), "all is well, no name here")));
  }

  @Test
  void squiggleLocatesAnOffendingIdentifier() {
    // The error "undefined variable: nope" should point at `nope` on line 1 (0-based), column 8.
    String src = "x = 1\ny = nope + x";
    String loc = Show.plain(Apply.applyAll(EDITOR.value("Assist", "squiggleFor"), src, "nope"));
    assertTrue(loc.contains("line = 1"), loc);
    assertTrue(loc.contains("column = 4"), loc);
    assertTrue(loc.contains("length = 4"), loc);
    // A name that doesn't occur as a whole word isn't located (no false squiggle inside `nope`).
    assertEquals("Nothing", Show.plain(Apply.applyAll(EDITOR.value("Assist", "squiggleFor"), src, "op")));
  }

  @Test
  void offsetOfMapsASquiggleLocationToACharacterOffset() {
    // The character offset the editor's squiggle overlay slices at: line 1, column 4 of
    // "x = 1\ny = nope" is the 'n' of nope, at offset 5 (line 0) + 1 (newline) + 4 = 10.
    Object offsetOf = EDITOR.value("Assist", "offsetOf");
    assertEquals("10", Show.plain(Apply.applyAll(offsetOf, 1L, 4L, "x = 1\ny = nope")));
    assertEquals("0", Show.plain(Apply.applyAll(offsetOf, 0L, 0L, "abc")));
    assertEquals("2", Show.plain(Apply.applyAll(offsetOf, 0L, 2L, "abcdef")));
  }

  @Test
  void compilesToJavaScriptForTheBrowser() {
    // The editor is a multi-module Browser.sandbox program; the JS backend must bundle all modules.
    String page = JsCompiler.htmlPageProject(null, moduleSources());
    assertTrue(page.contains("$start"), "editor compiles to a runnable JS bundle");
  }

  /** The full editor app (including Editor.elm and its Assist-wired autocomplete + error ribbon) must
   * compile to a JS bundle for the browser. Guards the UI wiring (custom event decoders, dropdown). */
  @Test
  void fullEditorAppWithAssistCompiles() {
    String[] paths = {
      "/elm/editor/Lang.elm",
      "/elm/editor/Lexer.elm",
      "/elm/editor/Parser.elm",
      "/elm/editor/Eval.elm",
      "/elm/editor/Highlight.elm",
      "/elm/editor/Assist.elm",
      "/elm/editor/Share.elm",
      "/elm/editor/Editor.elm",
      "/elm/editor/Main.elm",
    };
    String[] sources = new String[paths.length];
    for (int i = 0; i < paths.length; i++) {
      sources[i] = Resources.read(paths[i]);
    }
    String page = JsCompiler.htmlPageProject(null, sources);
    assertTrue(page.contains("$start"), "the full editor (with Assist + Share wiring) bundles to JS");
  }

  /**
   * Guards the editor's message wiring against the failure mode where a {@code Msg} constructor is
   * renamed but the (Chrome-gated) headless drivers still dispatch the old name — a break that only
   * surfaced in full CI. The compiled bundle must contain a handler tag for every message those
   * drivers dispatch; this test runs in the normal, no-browser tier, so the rename fails fast.
   */
  @Test
  void editorBundleHandlesTheMessagesTheHeadlessDriversDispatch() {
    String[] sources = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < sources.length; i++) {
      sources[i] = Resources.read(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    String page = JsCompiler.htmlPageProject(null, sources);
    // The exact constructors HeadlessChromeTest dispatches via window.$app.dispatch($data('X',...)).
    for (String ctor : new String[] {"EditAt", "Interp", "Rewind", "GotHash", "LoadedSession"}) {
      assertTrue(
          page.contains("'" + ctor + "'") || page.contains("\"" + ctor + "\""),
          "compiled editor bundle has no handler for the dispatched message " + ctor);
    }
  }

  /** Calls `Share.encodeFiles`/`Share.decodeFiles` and checks they round-trip the file set. */
  @Test
  void shareEncodesAndDecodesTheFileSet() {
    Project share = Project.load(Resources.read("/elm/editor/Share.elm"));
    // files : List (String, String) with content containing separators/newlines to stress the format.
    ElmList files =
        files(
            "Main.elm", "module Main exposing (main)\nmain = 1, 2\n",
            "Util.elm", "x = \"a,b\"\n");
    Object encoded = Apply.apply(share.value("Share", "encodeFiles"), files);
    Object decoded = Apply.apply(share.value("Share", "decodeFiles"), encoded);
    // The decoded list renders identically to the original (round-trip).
    assertEquals(Show.plain(files), Show.plain(decoded), "files round-trip through encode/decode");
  }
}

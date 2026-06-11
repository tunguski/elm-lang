package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmList;

/**
 * The Elm-in-Elm interpreter (the Lang/Lexer/Parser/Eval/Main modules, written in Elm) must itself
 * evaluate correctly when run by this project's interpreter — i.e. Elm interpreting Elm — and
 * compile via the JS backend (so the editor runs in the browser). Covers a functional subset:
 * numbers, strings, booleans, lists, operators, if/let, lambdas with closures, custom types/case,
 * cross-file top-level definitions and the time-travel debugger.
 */
class EditorInterpreterTest extends EditorInterpreterTestSupport {

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
  void interpretsTripleQuotedMultilineStrings() {
    // A raw newline inside a triple-quoted string is kept (a, newline, b, c, d -> length 5).
    assertEquals("5", eval("String.length \"\"\"ab\ncd\"\"\""));
    // Escape sequences are processed like an ordinary string, not left as literal backslash + letter.
    assertEquals("3", eval("String.length \"\"\"a\\nb\"\"\"")); // \n -> one newline char
    assertEquals("3", eval("String.length \"\"\"a\\tb\"\"\"")); // \t -> one tab char
    assertEquals("3", eval("String.length \"\"\"a\\\"b\"\"\"")); // \" -> one quote char
    assertEquals("3", eval("String.length \"\"\"a\\\\b\"\"\"")); // \\ -> one backslash char
    // A raw, unescaped double-quote is a literal quote inside a triple string.
    assertEquals("3", eval("String.length \"\"\"a\"b\"\"\""));
  }

  @Test
  void rendersDefinitionListsAndAdvancedElements() {
    // The editor renderer supports the full Html element set (matching the compiler/interpreter), so
    // dl/dt/dd, details/summary and the reserved-word alias main_ (-> <main>) render in the preview.
    String src =
        """
        module Main exposing (main)
        import Html exposing (main_, dl, dt, dd, details, summary, text)
        main =
            main_ []
                [ dl [] [ dt [] [ text "Term" ], dd [] [ details [] [ summary [] [ text "more" ] ] ] ] ]
        """;
    String html = Show.plain(Apply.apply(EDITOR.value("Eval", "renderProgram"), src));
    assertTrue(html.contains("<main>"), "main_ renders as <main>: " + html);
    assertTrue(html.contains("<dl>") && html.contains("<dt>Term</dt>"), "dl/dt render: " + html);
    assertTrue(
        html.contains("<details>") && html.contains("<summary>more</summary>"),
        "details/summary render: " + html);
  }

  @Test
  void rendersMultilineStringInATopLevelDefinition() {
    // Regression: a """…""" whose continuation line starts at column 0 must not be split into a new
    // top-level declaration by the line-chunker before tokenizing (it rendered `text "" "new file
    // this is "`, dropping the second line). The whole string must survive into the rendered text.
    String src = "main = text \"\"\"new file this is \nmy text\"\"\"";
    String rendered = Show.plain(Apply.apply(EDITOR.value("Eval", "renderProgram"), src));
    assertTrue(rendered.contains("new file this is") && rendered.contains("my text"), rendered);
  }

  @Test
  void interpretsAdditionalStdlibBuiltins() {
    // elm/core parity additions to the editor interpreter.
    assertEquals("[5]", eval("List.singleton 5"));
    assertEquals("[0, 2]", eval("String.indices \"a\" \"aba\"")); // alias of String.indexes
    assertEquals("True", eval("Char.isControl '\\t'"));
    assertEquals("False", eval("Char.isControl 'a'"));
    assertEquals("True", eval("Char.isPunctuation '!'"));
    assertEquals("False", eval("Char.isPunctuation 'a'"));
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


  @Test
  void lifeExampleHasAConfigFormAndEvolvesAGlider() throws Exception {
    // Conway's Game of Life (Life.elm) is a Browser.element with a config form: a pattern <select>
    // and number inputs (cols/rows/cell/step), no number-key handling. Its default is a glider that
    // conserves its five cells across a generation, and a `Tick` advances exactly one generation.
    String src =
        java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/elm/examples/Life.elm"));

    // The initial view is the form (pattern select + number inputs) over the default glider's 5 cells.
    String view0 = Show.plain(Apply.apply(EDITOR.value("Eval", "renderProgram"), src));
    assertTrue(view0.contains("<select"), "the form has a pattern <select>: " + view0);
    assertEquals(4, countMatches(view0, "type=number"), "the form has four number inputs: " + view0);
    assertEquals(5, countMatches(view0, "#50dc8c"), "the default glider has 5 live cells: " + view0);

    // The app is drivable through the editor's TEA loop. One timer Tick (built by applying the
    // subscription's toMsg) advances one Conway generation; the glider keeps its five cells.
    ElmList fs = files("Main.elm", src);
    Object model0 = okValue(Apply.apply(EDITOR.value("Eval", "appInit"), fs));
    Object subPair = unwrapJust(Apply.applyAll(EDITOR.value("Eval", "appSubscription"), fs, model0));
    Object toMsg = ((pl.matsuo.elm.runtime.ElmTuple) subPair).get(1);
    Object tick = okValue(Apply.applyAll(EDITOR.value("Eval", "applyMsgIn"), fs, toMsg, model0));
    Object model1 = okValue(Apply.applyAll(EDITOR.value("Eval", "appUpdate"), fs, tick, model0));
    String view1 = Show.plain(okValue(Apply.applyAll(EDITOR.value("Eval", "appView"), fs, model1)));
    assertEquals(
        5, countMatches(view1, "#50dc8c"), "a glider still has 5 live cells after a generation: " + view1);
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
  void mouseExampleResolvesTheLightPurplePlaygroundColor() throws Exception {
    // Mouse (elm-playground): `circle lightPurple 30` follows the cursor. Regression — the editor
    // interpreter must resolve the `lightPurple` Playground colour (and the rest of the palette)
    // instead of failing with "undefined variable: lightPurple".
    String src =
        java.nio.file.Files.readString(java.nio.file.Path.of("src/main/elm/examples/Mouse.elm"));
    ElmList fs = files("Main.elm", src);
    Object mem = unwrapJust(Apply.apply(EDITOR.value("Eval", "gameInitMem"), fs));
    String frame = renderGame(fs, List.of(), 0.0, mem);
    // A Playground `circle` renders as an <ellipse> with rx = ry; the key point is the fill resolves.
    assertTrue(frame.contains("VStr \"ellipse\""), "draws the follower circle: " + frame);
    assertTrue(frame.contains("#ad7fa8"), "circle is filled with the resolved lightPurple: " + frame);
  }

  @Test
  void firstPersonResolvesVectorMathInThePhysicsLoop() throws Exception {
    // FirstPerson (WebGL): the movement physics compares `Vec3.getY position > eyeLevel`. Regression
    // — the editor interpreter must compute Vec3 accessors/arithmetic on concrete vectors instead of
    // keeping them symbolic, which made the comparison fail with "> needs two numbers".
    String src =
        java.nio.file.Files.readString(java.nio.file.Path.of("src/main/elm/examples/FirstPerson.elm"));
    ElmList fs = files("Main.elm", src);
    assertEquals("2", evalProject(fs, "Vec3.getY (vec3 0 2 -10)"), "Vec3.getY computes a number");
    assertEquals("vec3 1 4 9", evalProject(fs, "Vec3.add (vec3 1 2 3) (vec3 0 2 6)"), "Vec3.add computes");
    String step = evalProject(fs, "stepVelocity 16 noKeys (Person (vec3 0 2 -10) (vec3 0 0 0))");
    assertTrue(step.startsWith("vec3"), "stepVelocity yields a vector, not an error: " + step);
    String upd = evalProject(fs, "updatePerson 16 noKeys (Person (vec3 0 2 -10) (vec3 0 0 0))");
    assertTrue(!upd.contains("needs two numbers") && upd.contains("position ="), "physics loop computes: " + upd);
  }

  @Test
  void positionsResolvesRandomMap2ToASampleableGenerator() throws Exception {
    // Positions: clicking samples a new (Int, Int) from `Random.map2 Tuple.pair (Random.int 50 350)
    // (Random.int 50 350)` and dispatches `NewPosition (x, y)`. Regression — the editor mis-resolved
    // `Random.map2` to Json.Decode's map (Dec.map), which the random sampler didn't recognise, so it
    // produced 0 and `NewPosition (x, y)` failed with "no matching case branch". The generator must
    // now be a real Random generator (a `Random.Gen` the editor samples), not a decoder.
    String src =
        java.nio.file.Files.readString(java.nio.file.Path.of("src/main/elm/examples/Positions.elm"));
    ElmList fs = files("Main.elm", src);
    String gen = evalProject(fs, "positionGenerator");
    assertTrue(gen.contains("Random.Gen") && gen.contains("map2"), "real Random generator: " + gen);
    assertTrue(!gen.contains("Dec.map"), "not mis-resolved to a Json decoder: " + gen);
    // The combinators compose: a map of two int generators is also a Random.Gen, sampleable to a list.
    String listGen = evalProject(fs, "Random.list 3 (Random.int 1 6)");
    assertTrue(listGen.contains("Random.Gen") && listGen.contains("list"), "Random.list is a generator: " + listGen);
  }

  @Test
  void thwompRendersTwoTexturedWebglEntitiesOnceTexturesLoad() throws Exception {
    // Thwomp (WebGL): once both textures resolve the view draws two textured WebGL entities (face +
    // sides). The editor resolves the texture tasks immediately, so the scene must render rather than
    // staying on "Loading textures...". (The async image upload/redraw is a JS-runtime concern the
    // headless JVM can't pixel-verify; this guards the Elm render path.)
    String src =
        java.nio.file.Files.readString(java.nio.file.Path.of("src/main/elm/examples/Thwomp.elm"));
    ElmList fs = files("Main.elm", src);
    String view =
        evalProject(
            fs,
            "view { width = 500, height = 500, x = 0, y = 0,"
                + " side = Just (Texture.load \"s\"), face = Just (Texture.load \"f\") }");
    assertTrue(view.contains("WebGL.scene"), "renders a WebGL scene once textures load: " + view);
    assertEquals(2, countMatches(view, "WebGL.entity"), "two textured entities (face + sides): " + view);
  }

  @Test
  void thwompTextureLoadWithTaskResolves() throws Exception {
    // Regression: Thwomp loads textures with `Texture.loadWith options url`. A fully-applied loadWith
    // evaluates to a VCtor (not a VBuiltin), so the editor's task resolver returned Nothing — GotFace
    // never fired and the view stuck on "Loading textures...". The Task.attempt must now resolve to
    // the url-carrying texture value the GL bridge loads (here surfaced via `identity` as toMsg).
    String probe =
        "main = Task.attempt identity (Texture.loadWith options \"wood.jpg\")\n"
            + "options =\n"
            + "    { magnify = Texture.nearest, minify = Texture.nearest\n"
            + "    , horizontalWrap = Texture.repeat, verticalWrap = Texture.repeat, flipY = True\n"
            + "    }\n";
    ElmList fs = files("Main.elm", probe);
    Object cmd = okValue(Apply.apply(EDITOR.value("Eval", "mainValue"), fs));
    String resolved = Show.plain(Apply.applyAll(EDITOR.value("Eval", "taskResult"), fs, cmd));
    assertTrue(
        resolved.contains("Texture.load") && resolved.contains("wood.jpg"),
        "texture task resolves to a loadable texture, not Nothing: " + resolved);
  }

  @Test
  void uploadExampleRendersTheMultipleFileInput() throws Exception {
    // Upload (Browser.element): the view is `<input type="file" multiple ...>`. Regression — the
    // editor must resolve the `multiple` boolean attribute, not fail "undefined variable: multiple".
    String src =
        java.nio.file.Files.readString(java.nio.file.Path.of("src/main/elm/examples/Upload.elm"));
    String rendered = Show.plain(Apply.apply(EDITOR.value("Eval", "renderProgram"), src));
    assertTrue(rendered.contains("<input"), "renders the file input: " + rendered);
    assertTrue(rendered.contains("multiple"), "the multiple boolean attribute is present: " + rendered);
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
    for (String slug : new String[] {"Triangle", "Crate", "Thwomp", "Cube"}) {
      String src =
          java.nio.file.Files.readString(
              java.nio.file.Path.of("src/main/elm/examples/" + slug + ".elm"));
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
    assertEquals(2, countMatches(scene, "WebGL.entity"), scene);
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

}

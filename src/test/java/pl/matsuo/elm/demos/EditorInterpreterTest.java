package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    assertTrue(eval("1 / 0").contains("division by zero"), eval("1 / 0"));
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
  void compilesToJavaScriptForTheBrowser() {
    // The editor is a multi-module Browser.sandbox program; the JS backend must bundle all modules.
    String page = JsCompiler.htmlPageProject(null, moduleSources());
    assertTrue(page.contains("$start"), "editor compiles to a runnable JS bundle");
  }
}

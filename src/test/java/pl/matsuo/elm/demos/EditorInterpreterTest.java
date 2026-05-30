package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.util.Resources;

/**
 * The Elm-in-Elm interpreter (editor.elm, written in Elm) must itself evaluate correctly when run
 * by this project's interpreter — i.e. Elm interpreting Elm — and compile via the JS backend (so
 * the editor runs in the browser). It now covers a functional subset: numbers, strings, booleans,
 * lists, comparison/logic/append operators, if/let and lambdas with closures.
 */
class EditorInterpreterTest {

  private static final Interpreter EDITOR = Interpreter.load(Resources.read("/elm/demos/editor.elm"));
  private static final String SRC = Resources.read("/elm/demos/editor.elm");
  private static final Object EVAL = EDITOR.value("eval");

  /** Calls the Elm-written `eval : String -> String` on a source expression. */
  private String eval(String expression) {
    return Show.plain(Apply.apply(EVAL, expression));
  }

  /** Builds the Elm `List (String, String)` of (filename, content) from alternating args. */
  private static ElmList files(String... nameThenContent) {
    List<Object> pairs = new ArrayList<>();
    for (int i = 0; i + 1 < nameThenContent.length; i += 2) {
      pairs.add(new ElmTuple(new Object[] {nameThenContent[i], nameThenContent[i + 1]}));
    }
    return ElmList.fromJava(pairs);
  }

  /** Calls `evalProject : List (String,String) -> String -> String`. */
  private String evalProject(ElmList files, String entry) {
    return Show.plain(Apply.applyAll(EDITOR.value("evalProject"), files, entry));
  }

  /** Calls `debugSteps : List (String,String) -> List String -> List String`. */
  @SuppressWarnings("unchecked")
  private List<Object> debugSteps(ElmList files, String... messages) {
    Object r = Apply.applyAll(EDITOR.value("debugSteps"), files, ElmList.fromJava(List.of(messages)));
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
  void compilesToJavaScriptForTheBrowser() {
    // The editor is a Browser.sandbox program; it must compile via the JS backend without throwing.
    String page = JsCompiler.htmlPage(SRC, null);
    assertTrue(page.contains("$start"), "editor compiles to a runnable JS bundle");
  }
}

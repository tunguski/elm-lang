package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.util.Resources;

/**
 * The Elm-in-Elm interpreter (editor.elm, written in Elm) must itself evaluate correctly when run
 * by this project's interpreter — i.e. Elm interpreting Elm — and compile via the JS backend (so
 * the editor runs in the browser). It now covers a functional subset: numbers, strings, booleans,
 * lists, comparison/logic/append operators, if/let and lambdas with closures.
 */
class EditorInterpreterTest {

  private static final String SRC = Resources.read("/elm/demos/editor.elm");
  private static final Object EVAL = Interpreter.load(SRC).value("eval");

  /** Calls the Elm-written `eval : String -> String` on a source expression. */
  private String eval(String expression) {
    return Show.plain(Apply.apply(EVAL, expression));
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
  void compilesToJavaScriptForTheBrowser() {
    // The editor is a Browser.sandbox program; it must compile via the JS backend without throwing.
    String page = JsCompiler.htmlPage(SRC, null);
    assertTrue(page.contains("$start"), "editor compiles to a runnable JS bundle");
  }
}

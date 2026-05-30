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
 * The Elm-in-Elm expression interpreter (editor.elm, written in Elm) must itself evaluate correctly
 * when run by this project's interpreter — i.e. Elm interpreting Elm — and compile via the JS
 * backend (so the editor runs in the browser).
 */
class EditorInterpreterTest {

  private static final String SRC = Resources.read("/elm/demos/editor.elm");

  /** Calls the Elm-written `run : String -> Result String Float` on an expression. */
  private String run(String expression) {
    Object runFn = Interpreter.load(SRC).value("run");
    return Show.plain(Apply.apply(runFn, expression));
  }

  @Test
  void interpretsArithmetic() {
    assertEquals("Ok 14", run("2 + 3 * 4"));
    assertEquals("Ok 6", run("2 + 3 * (4 - 1) // 2")); // 3*3//2 = 4, then 2+4 (// same prec as *)
    assertEquals("Ok 20", run("(100 - 60) / 2"));
    assertTrue(run("2 - 3").replace("(", "").replace(")", "").equals("Ok -1"), run("2 - 3"));
  }

  @Test
  void reportsErrors() {
    assertTrue(run("1 +").startsWith("Err"), run("1 +")); // truncated input
    assertTrue(run("1 / 0").contains("division by zero"), run("1 / 0"));
    assertTrue(run("(1 + 2").startsWith("Err"), run("(1 + 2")); // missing )
  }

  @Test
  void compilesToJavaScriptForTheBrowser() {
    // The editor is a Browser.sandbox program; it must compile via the JS backend without throwing.
    String page = JsCompiler.htmlPage(SRC, null);
    assertTrue(page.contains("$start"), "editor compiles to a runnable JS bundle");
  }
}

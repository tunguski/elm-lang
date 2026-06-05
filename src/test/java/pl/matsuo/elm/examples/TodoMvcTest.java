package pl.matsuo.elm.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.util.Resources;

/**
 * The flagship TodoMVC app, driven through The Elm Architecture on the interpreter: add, toggle,
 * clear-completed and delete, checking the rendered HTML and the live "items left" count. A
 * non-trivial integration test across records, lists, custom types, record update and nested views.
 */
class TodoMvcTest {

  private static String source() {
    return Resources.read("/elm/examples/TodoMvc.elm");
  }

  @Test
  void addToggleClearAndDelete() {
    Interpreter interp = Interpreter.load(source());
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("todos"), app.html());
    assertTrue(app.html().contains("0 items left"), app.html());

    // Add two todos.
    app.send(interp.evalExpr("UpdateField \"milk\""));
    app.send(interp.evalExpr("Add"));
    app.send(interp.evalExpr("UpdateField \"eggs\""));
    app.send(interp.evalExpr("Add"));
    String html = app.html();
    assertTrue(html.contains("milk") && html.contains("eggs"), html);
    assertTrue(html.contains("2 items left"), html);

    // Toggle the first todo (id 1) complete -> one left, shown as [x].
    app.send(interp.evalExpr("Toggle 1"));
    assertTrue(app.html().contains("1 items left"), app.html());
    assertTrue(app.html().contains("[x]"), app.html());

    // Clear completed removes milk, keeps eggs.
    app.send(interp.evalExpr("ClearCompleted"));
    String afterClear = app.html();
    assertFalse(afterClear.contains("milk"), afterClear);
    assertTrue(afterClear.contains("eggs"), afterClear);

    // Delete the remaining todo (id 2) -> empty again.
    app.send(interp.evalExpr("Delete 2"));
    assertTrue(app.html().contains("0 items left"), app.html());
  }

  @Test
  void emptyFieldDoesNotAdd() {
    Interpreter interp = Interpreter.load(source());
    Tea app = Tea.start(interp.value("main"));
    app.send(interp.evalExpr("Add")); // field is empty
    assertEquals(true, app.html().contains("0 items left"), app.html());
  }
}

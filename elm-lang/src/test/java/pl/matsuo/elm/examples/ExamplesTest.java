package pl.matsuo.elm.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;

/**
 * Drives the elm-lang.org examples that are achievable headlessly (HTML rendering and The Elm
 * Architecture). Static views are rendered to HTML; interactive examples are driven by dispatching
 * messages through a {@link Tea} runtime and re-rendering.
 */
class ExamplesTest {

  private static String source(String slug) {
    try (InputStream in = ExamplesTest.class.getResourceAsStream("/examples/" + slug + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // --- HTML group (static) ----------------------------------------------

  @Test
  void hello() {
    String html = HtmlRender.render(Interpreter.load(source("hello")).value("main"));
    assertEquals("Hello!", html);
    // Same result through the bytecode backend.
    assertEquals("Hello!", HtmlRender.render(BytecodeInterpreter.load(source("hello")).value("main")));
  }

  @Test
  void groceries() {
    String html = HtmlRender.render(Interpreter.load(source("groceries")).value("main"));
    assertTrue(html.startsWith("<ul>"), html);
    assertTrue(html.contains("<li>Pamplemousse</li>"), html);
    assertTrue(html.contains("<li>Baguette</li>"), html);
    assertEquals(8, html.split("<li>").length - 1);
  }

  @Test
  void shapes() {
    String html = HtmlRender.render(Interpreter.load(source("shapes")).value("main"));
    assertTrue(html.startsWith("<svg "), html);
    assertTrue(html.contains("viewBox=\"0 0 400 400\""), html);
    assertTrue(html.contains("<circle "), html);
    assertTrue(html.contains("fill=\"#0B79CE\""), html);
    assertTrue(html.contains("<rect "), html);
  }

  // --- User Input group (The Elm Architecture) --------------------------

  @Test
  void buttons() {
    Interpreter interp = Interpreter.load(source("buttons"));
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("<div>0</div>"), app.html());

    app.send(interp.evalExpr("Increment"));
    app.send(interp.evalExpr("Increment"));
    app.send(interp.evalExpr("Increment"));
    assertTrue(app.html().contains("<div>3</div>"), app.html());

    app.send(interp.evalExpr("Decrement"));
    assertTrue(app.html().contains("<div>2</div>"), app.html());
  }

  @Test
  void textFields() {
    Interpreter interp = Interpreter.load(source("text-fields"));
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("placeholder=\"Text to reverse\""), app.html());

    app.send(interp.evalExpr("Change \"hello\""));
    String html = app.html();
    assertTrue(html.contains("value=\"hello\""), html);
    assertTrue(html.contains("<div>olleh</div>"), html);
  }

  @Test
  void forms() {
    Interpreter interp = Interpreter.load(source("forms"));
    Tea app = Tea.start(interp.value("main"));
    // Initially both password fields are empty -> equal -> "OK".
    assertTrue(app.html().contains(">OK</div>"), app.html());

    app.send(interp.evalExpr("Password \"secret\""));
    app.send(interp.evalExpr("PasswordAgain \"different\""));
    String html = app.html();
    assertTrue(html.contains("Passwords do not match!"), html);
    assertTrue(html.contains("color:red;"), html);

    app.send(interp.evalExpr("PasswordAgain \"secret\""));
    assertTrue(app.html().contains(">OK</div>"), app.html());
  }
}

package pl.matsuo.elm.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;

/**
 * Runs the WebGL examples headlessly. Actual pixels require a GPU, so these verify that the program
 * executes — building meshes, computing matrices, assembling entities — and that {@code
 * WebGL.toHtml} produces a {@code <canvas>} describing the scene (entity count).
 */
class WebGLExamplesTest {

  private static String source(String slug) {
    try (InputStream in = WebGLExamplesTest.class.getResourceAsStream("/elm/examples/" + slug + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void triangle() {
    Interpreter interp = Interpreter.load(source("triangle"));
    String html = Tea.start(interp.value("main")).html();
    assertTrue(html.contains("<canvas"), html);
    assertTrue(html.contains("width=\"400\""), html);
    assertTrue(html.contains("data-entities=\"1\""), html);

    // The mesh is a single triangle (3 vertices).
    ElmData mesh = (ElmData) interp.value("mesh");
    assertEquals("$Mesh", mesh.ctor());
    assertEquals(1, ((ElmList) mesh.arg(1)).toJava().size());
  }

  @Test
  void cube() {
    Interpreter interp = Interpreter.load(source("cube"));
    String html = Tea.start(interp.value("main")).html();
    assertTrue(html.contains("<canvas"), html);
    assertTrue(html.contains("data-entities=\"1\""), html);
  }

  @Test
  void crate() {
    // The texture loads via Task.attempt in init (the driver resolves it), so the view leaves the
    // "Loading texture..." state and renders the textured cube.
    Interpreter interp = Interpreter.load(source("crate"));
    String html = Tea.start(interp.value("main")).html();
    assertTrue(html.contains("<canvas"), html);
    assertTrue(html.contains("data-entities=\"1\""), html);
  }

  @Test
  void thwomp() {
    Interpreter interp = Interpreter.load(source("thwomp"));
    String html = Tea.start(interp.value("main")).html();
    assertTrue(html.contains("<canvas"), html);
  }

  @Test
  void firstPerson() {
    Interpreter interp = Interpreter.load(source("first-person"));
    String html = Tea.start(interp.value("main")).html();
    assertTrue(html.contains("<canvas"), html);
  }
}

package pl.matsuo.elm.examples;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Project;

/**
 * Runs elm-lang.org Playground examples by loading the real evancz/elm-playground source alongside
 * the example via the multi-module loader, then rendering the program headlessly to SVG.
 */
class PlaygroundExamplesTest {

  private static String resource(String path) {
    try (InputStream in = PlaygroundExamplesTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String render(String slug) {
    Project project =
        Project.load(resource("/Playground.elm"), resource("/examples/" + slug + ".elm"));
    return Tea.start(project.main()).html();
  }

  @Test
  void picture() {
    String html = render("picture");
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<rect "), html);
    assertTrue(html.contains("<circle "), html);
    assertTrue(html.contains("width=\"40\""), html); // the rectangle
    // rectangle moved down 80 -> svg y is negated -> translate(0,80); circle moved up 100.
    assertTrue(html.contains("translate(0,80)"), html);
    assertTrue(html.contains("translate(0,-100)"), html);
  }

  @Test
  void animation() {
    String html = render("animation");
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<polygon "), html); // the octagons
    assertTrue(html.contains("<rect "), html);
  }

  @Test
  void mouse() {
    String html = render("mouse");
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<circle "), html); // follows the (initially 0,0) mouse
  }

  @Test
  void keyboard() {
    String html = render("keyboard");
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<rect "), html); // the square
  }

  @Test
  void turtle() {
    String html = render("turtle");
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<image "), html);
    assertTrue(html.contains("turtle.gif"), html);
  }

  @Test
  void mario() {
    String html = render("mario");
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<image "), html);
    assertTrue(html.contains("mario"), html);
  }
}

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

  @Test
  void picture() {
    Project project = Project.load(resource("/Playground.elm"), resource("/examples/picture.elm"));
    Tea app = Tea.start(project.main());
    String html = app.html();
    assertTrue(html.contains("<svg "), html);
    assertTrue(html.contains("<rect "), html);
    assertTrue(html.contains("<circle "), html);
    assertTrue(html.contains("width=\"40\""), html); // the rectangle
    // rectangle moved down 80 -> svg y is negated -> translate(0,80); circle moved up 100.
    assertTrue(html.contains("translate(0,80)"), html);
    assertTrue(html.contains("translate(0,-100)"), html);
  }
}

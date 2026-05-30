package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies the static gallery generator: it must emit a landing page plus a demo and a wrapper page
 * for every elm-lang.org example, with the live JS bundle for the examples the JavaScript backend
 * can compile and an interpreter-rendered snapshot for the rest.
 */
class SiteGeneratorTest {

  private static final Path EXAMPLES = Path.of("src/test/resources/examples");
  private static final Path PLAYGROUND = Path.of("src/test/resources/Playground.elm");

  private Path generate() throws IOException {
    Path out = Files.createTempDirectory("elm-site-");
    SiteGenerator.generate(EXAMPLES, PLAYGROUND, out);
    return out;
  }

  @Test
  void emitsAPageForEveryExample() throws IOException {
    Path out = generate();
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("elm-lang"), index.substring(0, 200));
    assertTrue(Files.exists(out.resolve(".nojekyll")));

    for (SiteGenerator.Example ex : SiteGenerator.EXAMPLES) {
      assertTrue(Files.exists(out.resolve(ex.slug() + ".html")), "wrapper for " + ex.slug());
      assertTrue(Files.exists(out.resolve("demos/" + ex.slug() + ".html")), "demo for " + ex.slug());
      assertTrue(index.contains(ex.slug() + ".html"), "index links " + ex.slug());
    }

    try (Stream<Path> demos = Files.list(out.resolve("demos"))) {
      assertEquals(SiteGenerator.EXAMPLES.size(), demos.count());
    }
  }

  @Test
  void buttonsIsALiveCompiledDemo() throws IOException {
    Path out = generate();
    String demo = Files.readString(out.resolve("demos/buttons.html"), StandardCharsets.UTF_8);
    // The JS backend produced a runnable bundle that mounts into the page.
    assertTrue(demo.contains("$start"), "buttons demo should be the compiled JS bundle");
    String wrapper = Files.readString(out.resolve("buttons.html"), StandardCharsets.UTF_8);
    assertTrue(wrapper.contains("badge live"), "buttons should be labelled live");
    assertTrue(wrapper.contains("Browser.sandbox"), "wrapper should show the source");
  }

  @Test
  void playgroundFallsBackToRenderedSnapshot() throws IOException {
    Path out = generate();
    String demo = Files.readString(out.resolve("demos/picture.html"), StandardCharsets.UTF_8);
    // Multi-module Playground program: server-side-rendered initial frame (SVG), not a JS bundle.
    assertTrue(demo.contains("<svg"), "picture snapshot should contain rendered SVG");
    String wrapper = Files.readString(out.resolve("picture.html"), StandardCharsets.UTF_8);
    assertTrue(wrapper.contains("badge snapshot"), "picture should be labelled snapshot");
  }
}

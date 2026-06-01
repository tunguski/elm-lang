package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
      long htmlDemos = demos.filter(p -> p.toString().endsWith(".html")).count();
      assertEquals(SiteGenerator.EXAMPLES.size(), htmlDemos); // (a demos/assets dir may also exist)
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
  void playgroundIsBundledAsLiveJs() throws IOException {
    Path out = generate();
    String demo = Files.readString(out.resolve("demos/picture.html"), StandardCharsets.UTF_8);
    // Multi-module Playground program: bundled live (Playground + example), not a server snapshot.
    assertTrue(demo.contains("$start"), "picture should be a compiled JS bundle");
    assertTrue(demo.contains("$Playground$") || demo.contains("Playground"), "Playground is bundled");
    String wrapper = Files.readString(out.resolve("picture.html"), StandardCharsets.UTF_8);
    assertTrue(wrapper.contains("badge live"), "picture should be labelled live");
  }

  @Test
  void backendsPageRunsJsAndWasm() throws IOException {
    Path out = generate();
    String page = Files.readString(out.resolve("backends.html"), StandardCharsets.UTF_8);
    assertTrue(page.contains("$evalAll"), "embeds the JS evaluator");
    assertTrue(page.contains("WebAssembly.instantiate"), "instantiates the wasm module");
    assertTrue(page.contains("class=\"js\"") && page.contains("class=\"wasm\""), "has both columns");
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("backends.html"), "index links the JS-vs-WASM page");
  }

  @Test
  void rtsGamePageIsCompiledAndLinked() throws IOException {
    Path out = generate();
    // The multi-module RTS game compiles to one live, standalone page.
    String page = Files.readString(out.resolve("rts.html"), StandardCharsets.UTF_8);
    assertTrue(page.contains("$start"), "rts.html is the compiled JS bundle");
    assertTrue(page.contains("RTS Mini"), "rts.html contains the game");
    assertTrue(
        Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8).contains("rts.html"),
        "index links the RTS game");
  }

  @Test
  void playgroundPageEmbedsBothBackends() throws IOException {
    Path out = generate();
    String page = Files.readString(out.resolve("playground.html"), StandardCharsets.UTF_8);
    assertTrue(page.contains("_$fib"), "embeds the compiled JS function");
    assertTrue(page.contains("WebAssembly.instantiate"), "embeds the wasm module");
    assertTrue(page.contains("performance.now"), "times both backends");
    assertTrue(
        Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8).contains("playground.html"));
  }

  @Test
  void servesExampleSourcesTodoMvcAndLinksThem() throws IOException {
    Path out = generate();
    // Raw .elm sources are served under examples/ (downloadable, and fetchable by the editor):
    // every gallery example, the editor's own demos, and the flagship TodoMVC.
    assertTrue(Files.exists(out.resolve("examples/buttons.elm")), "gallery example source served");
    assertTrue(Files.exists(out.resolve("examples/Buttons.elm")), "editor demo source served");
    assertTrue(Files.exists(out.resolve("examples/todomvc.elm")), "todomvc source served");
    // TodoMVC compiles to a live page and is linked from the landing page.
    assertTrue(Files.exists(out.resolve("todomvc.html")), "todomvc.html generated");
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("todomvc.html"), "index links TodoMVC");
  }

  @Test
  void everyExampleIsLiveCompiledJs() throws IOException {
    Path out = generate();
    // After multi-module bundling + the WebGL/effect kernels, all examples run as live JS.
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertFalse(index.contains("badge snapshot"), "no example should fall back to a snapshot");
    assertFalse(index.contains("badge failed"), "no example should be source-only");
  }

  @Test
  void rendersRepoDocsToHtmlAndLinksThemFromTheIndex() throws IOException {
    Path out = Files.createTempDirectory("elm-site-");
    SiteGenerator.generate(EXAMPLES, PLAYGROUND, out, Path.of("docs"));

    // Each guide becomes a styled HTML page derived from its Markdown.
    for (String slug : new String[] {"examples", "scripting", "server"}) {
      Path page = out.resolve(slug + ".html");
      assertTrue(Files.exists(page), "doc page for " + slug);
      String html = Files.readString(page, StandardCharsets.UTF_8);
      assertTrue(html.contains("<h1>"), slug + " should render a heading");
      assertTrue(html.contains("<table>"), slug + " should render its table");
    }
    // Relative Markdown links are rewritten for the flat gallery and out to GitHub.
    String server = Files.readString(out.resolve("server.html"), StandardCharsets.UTF_8);
    assertTrue(server.contains("github.com/tunguski/elm-lang/blob/main/"), "repo links absolute-ised");

    // The landing page links to the rendered docs.
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("scripting.html"), "index should link the scripting guide");
    assertTrue(index.contains("server.html"), "index should link the server guide");
  }
}

package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * End-to-end browser-fidelity tests: compiles an example to a JS app bundle (kernel + DOM/TEA
 * runtime), loads it in real headless Chrome, and asserts on the actually-rendered DOM. Skipped if
 * Chrome is not installed.
 */
class HeadlessChromeTest {

  private static final String CHROME = findChrome();

  private static String findChrome() {
    String[] paths = {
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
      "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
      System.getProperty("user.home") + "/AppData/Local/Google/Chrome/Application/chrome.exe"
    };
    for (String p : paths) {
      if (Files.exists(Path.of(p))) {
        return p;
      }
    }
    return null;
  }

  private static String example(String slug) {
    try (InputStream in = HeadlessChromeTest.class.getResourceAsStream("/examples/" + slug + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Compiles the source to a page, renders it in headless Chrome and returns the serialized DOM. */
  private String renderInBrowser(String source, String driver) throws Exception {
    Path page = Files.createTempFile("elm-page-", ".html");
    Files.writeString(page, JsCompiler.htmlPage(source, driver), StandardCharsets.UTF_8);
    Path userData = Files.createTempDirectory("elm-chrome-");
    Process p =
        new ProcessBuilder(
                CHROME,
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--no-first-run",
                "--no-default-browser-check",
                "--user-data-dir=" + userData,
                "--virtual-time-budget=2000",
                "--dump-dom",
                page.toUri().toString())
            .redirectErrorStream(false)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("Chrome timed out");
    }
    Files.deleteIfExists(page);
    return out;
  }

  @Test
  void helloRendersInChrome() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assertTrue(renderInBrowser(example("hello"), null).contains("Hello!"));
  }

  @Test
  void groceriesRendersList() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String dom = renderInBrowser(example("groceries"), null);
    assertTrue(dom.contains("<li>Pamplemousse</li>"), dom);
    assertTrue(dom.contains("<li>Baguette</li>"), dom);
  }

  @Test
  void shapesRendersSvg() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String dom = renderInBrowser(example("shapes"), null);
    assertTrue(dom.contains("<svg"), dom);
    assertTrue(dom.contains("<circle"), dom);
    assertTrue(dom.contains("<rect"), dom);
  }

  @Test
  void buttonsInitialAndAfterClicks() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assertTrue(renderInBrowser(example("buttons"), null).contains("<div>0</div>"));
    // Drive three Increment clicks through the live TEA runtime, then inspect the re-rendered DOM.
    String driver =
        "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));";
    String dom = renderInBrowser(example("buttons"), driver);
    assertTrue(dom.contains("<div>3</div>"), dom);
  }

  @Test
  void textFieldsReactsToInput() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String driver = "window.$app.dispatch($data('Change',['hello']));";
    String dom = renderInBrowser(example("text-fields"), driver);
    assertTrue(dom.contains("olleh"), dom); // String.reverse "hello"
  }
}

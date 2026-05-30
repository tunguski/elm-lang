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
    // An explicit override wins (used by CI), then the usual install locations on each OS.
    String env = System.getenv("CHROME_BIN");
    if (env != null && !env.isBlank() && Files.exists(Path.of(env))) {
      return env;
    }
    String[] paths = {
      // Windows
      "C:/Program Files/Google/Chrome/Application/chrome.exe",
      "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
      System.getProperty("user.home") + "/AppData/Local/Google/Chrome/Application/chrome.exe",
      // Linux (GitHub-hosted ubuntu runners ship google-chrome-stable)
      "/usr/bin/google-chrome",
      "/usr/bin/google-chrome-stable",
      "/usr/bin/chromium-browser",
      "/usr/bin/chromium",
      "/snap/bin/chromium",
      // macOS
      "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
      "/Applications/Chromium.app/Contents/MacOS/Chromium"
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
    return renderPage(JsCompiler.htmlPage(source, driver));
  }

  /** Renders the given full HTML page in headless Chrome and returns the serialized DOM. */
  private String renderPage(String html) throws Exception {
    Path page = Files.createTempFile("elm-page-", ".html");
    Files.writeString(page, html, StandardCharsets.UTF_8);
    Path userData = Files.createTempDirectory("elm-chrome-");
    Process p =
        new ProcessBuilder(
                CHROME,
                "--headless=new",
                "--disable-gpu",
                "--enable-unsafe-swiftshader",
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

  @Test
  void textFieldKeepsFocusAcrossKeystroke() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: re-rendering used to rebuild the whole subtree, replacing the <input> on every
    // keystroke and dropping focus (so only one character could be typed). Drive a real 'input'
    // event through the live listener and assert the SAME element survives and stays focused.
    String driver =
        "var inp=document.querySelector('input'); inp.$marker='KEEP'; inp.focus();"
            + "inp.value='h'; inp.dispatchEvent(new Event('input',{bubbles:true}));"
            + "var after=document.querySelector('input');"
            + "document.body.setAttribute('data-same', String(after.$marker==='KEEP'));"
            + "document.body.setAttribute('data-focused', String(document.activeElement===after));";
    String dom = renderInBrowser(example("text-fields"), driver);
    assertTrue(dom.contains("data-same=\"true\""), dom); // input element reused, not replaced
    assertTrue(dom.contains("data-focused=\"true\""), dom); // focus preserved
    assertTrue(dom.contains("<div>h</div>"), dom); // model updated and re-rendered (reverse "h")
  }

  // The examples below were a blank page in the browser because the JS runtime had no effect
  // kernel: building the initial Cmd/Sub threw "Unbound: ..." and nothing rendered.

  @Test
  void formsRendersRecordAliasProgram() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Regression: the `Model` record-alias constructor compiled to $data(...) and was applied like a
    // function ("$data is not a function"), so forms rendered nothing.
    String dom = renderInBrowser(example("forms"), null);
    assertTrue(dom.contains("placeholder=\"Name\""), dom);
    assertTrue(dom.contains("placeholder=\"Password\""), dom);
  }

  @Test
  void numbersRollsWithRandomEffect() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Random.generate must build a Cmd (initial render) and, on clicking Roll, produce a 1..6 face.
    assertTrue(renderInBrowser(example("numbers"), null).contains("<h1>"), "die should render");
    String driver =
        "document.querySelector('button').click();"
            + "document.body.setAttribute('data-face', document.querySelector('h1').textContent);";
    String dom = renderInBrowser(example("numbers"), driver);
    assertTrue(dom.matches("(?s).*data-face=\"[1-6]\".*"), dom);
  }

  @Test
  void clockRendersSvgFaceWithTimeSub() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Time.now/here Tasks and the Time.every subscription must run; the clock draws an SVG face.
    String dom = renderInBrowser(example("clock"), null);
    assertTrue(dom.contains("<circle"), dom);
    assertTrue(dom.contains("<line"), dom); // the hand
  }

  @Test
  void uploadRendersFileInput() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The change-event decoder (Json.Decode.at/list/File.decoder) must build without throwing.
    String dom = renderInBrowser(example("upload"), null);
    assertTrue(dom.contains("type=\"file\""), dom);
  }

  @Test
  void playgroundPictureRendersAsMultiModuleBundle() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The real evancz/elm-playground source is bundled with the example into one JS program that
    // runs live in the browser (previously this could only be a server-rendered snapshot).
    String playground = example2("/Playground.elm");
    String dom = renderPage(JsCompiler.htmlPageProject(null, playground, example("picture")));
    assertTrue(dom.contains("<svg"), dom);
    assertTrue(dom.contains("<rect") && dom.contains("<circle"), dom);
  }

  @Test
  void webglTriangleRendersACanvas() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The WebGL kernel builds meshes/matrices and renders into a real <canvas> (software GL here).
    String dom = renderInBrowser(example("triangle"), null);
    assertTrue(dom.contains("<canvas"), dom);
    assertTrue(dom.contains("width=\"400\""), dom);
  }

  private static String example2(String path) {
    try (InputStream in = HeadlessChromeTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

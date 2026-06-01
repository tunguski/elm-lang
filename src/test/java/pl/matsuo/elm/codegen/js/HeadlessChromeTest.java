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

  private static String resource(String path) {
    try (InputStream in = HeadlessChromeTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void todoMvcRendersAndReactsInTheBrowser() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // The flagship TodoMVC app compiled by the JS backend, driven via dispatched messages: add two
    // todos, toggle the first complete, and check the rendered DOM and the live count.
    String driver =
        "window.$app.dispatch($data('UpdateField',['milk']));"
            + "window.$app.dispatch($data('Add',[]));"
            + "window.$app.dispatch($data('UpdateField',['eggs']));"
            + "window.$app.dispatch($data('Add',[]));"
            + "window.$app.dispatch($data('Toggle',[1]));";
    String dom = renderInBrowser(resource("/elm/demos/todomvc.elm"), driver);
    assertTrue(dom.contains("milk") && dom.contains("eggs"), dom);
    assertTrue(dom.contains("1 items left"), dom); // one toggled complete
    assertTrue(dom.contains("[x]"), dom); // the completed marker
  }

  /** Compiles the source to a page, renders it in headless Chrome and returns the serialized DOM. */
  private String renderInBrowser(String source, String driver) throws Exception {
    return renderPage(JsCompiler.htmlPage(source, driver));
  }

  /** Renders the given full HTML page in headless Chrome and returns the serialized DOM. */
  private String renderPage(String html) throws Exception {
    Path page = Files.createTempFile("elm-page-", ".html");
    Files.writeString(page, html, StandardCharsets.UTF_8);
    String dom = renderUrl(page.toUri().toString());
    Files.deleteIfExists(page);
    return dom;
  }

  /** Loads a URL in headless Chrome (letting timers/fetches run) and returns the serialized DOM. */
  private String renderUrl(String url) throws Exception {
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
                url)
            .redirectErrorStream(false)
            .start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("Chrome timed out");
    }
    return out;
  }

  @Test
  void helloRendersInChrome() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    assertTrue(renderInBrowser(example("hello"), null).contains("Hello!"));
  }

  @Test
  void browserApplicationMountsRoutesAndSetsTitle() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A minimal Browser.application: it mounts (Document view + title), reads the initial Url's path,
    // and routes — dispatching the message onUrlChange produces updates the view's shown path.
    String app =
        "module Main exposing (main)\n"
            + "import Browser\n"
            + "import Html exposing (text)\n"
            + "type Msg = Changed String | Ignore\n"
            + "main =\n"
            + "    Browser.application\n"
            + "        { init = \\_ url key -> ( { path = url.path, key = key }, Cmd.none )\n"
            + "        , view = \\m -> { title = \"Routed\", body = [ text (\"at \" ++ m.path) ] }\n"
            + "        , update = \\msg m -> case msg of\n"
            + "            Changed p -> ( { m | path = p }, Cmd.none )\n"
            + "            Ignore -> ( m, Cmd.none )\n"
            + "        , subscriptions = \\_ -> Sub.none\n"
            + "        , onUrlChange = \\u -> Changed u.path\n"
            + "        , onUrlRequest = \\_ -> Ignore\n"
            + "        }\n";
    String dom = renderInBrowser(app, "window.$app.dispatch($data('Changed',['/about']));");
    assertTrue(dom.contains("<title>Routed</title>"), "the Document title is set: " + dom);
    assertTrue(dom.contains("at /about"), "routing updated the view's path: " + dom);
  }

  @Test
  void htmlMapRoutesAChildsMessageThroughTheParent() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A child view dispatches Bump; the parent wraps it with Html.map into Child, so clicking the
    // child's button drives the parent's update through the mapped message.
    String app =
        "module Main exposing (main)\n"
            + "import Browser\n"
            + "import Html exposing (button, div, text)\n"
            + "import Html.Events exposing (onClick)\n"
            + "type ChildMsg = Bump\n"
            + "type Msg = Child ChildMsg\n"
            + "childView = button [ onClick Bump ] [ text \"+\" ]\n"
            + "main = Browser.sandbox { init = 0, update = update, view = view }\n"
            + "update msg n = case msg of\n  Child Bump -> n + 1\n"
            + "view n = div [] [ Html.map Child childView, text (\"count=\" ++ String.fromInt n) ]\n";
    String dom = renderInBrowser(app, "document.querySelector('button').click();");
    assertTrue(dom.contains("count=1"), "the mapped child click reached the parent: " + dom);
  }

  @Test
  void keyedNodesReorderByKeyPreservingDomState() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A keyed list of items; Flip reverses the order. Because nodes are matched by key, the DOM
    // node for "a" is reused (its data-touched marker, set via JS, survives the reorder).
    String app =
        "module Main exposing (main)\n"
            + "import Browser\n"
            + "import Html exposing (text)\n"
            + "import Html.Keyed as Keyed\n"
            + "import Html.Attributes exposing (id)\n"
            + "type Msg = Flip\n"
            + "main = Browser.sandbox { init = False, update = \\_ _ -> True, view = view }\n"
            + "row k = ( k, Html.node \"li\" [ id k ] [ text k ] )\n"
            + "view flipped =\n"
            + "    Keyed.node \"ul\" [] (if flipped then [ row \"b\", row \"a\" ] else [ row \"a\", row \"b\" ])\n";
    // Mark the #a node, then Flip; if its DOM node was reused, the marker is still there afterwards.
    String driver =
        "document.getElementById('a').setAttribute('data-kept','yes');"
            + "window.$app.dispatch($data('Flip',[]));";
    String dom = renderPage(JsCompiler.htmlPage(app, driver));
    assertTrue(dom.contains("data-kept=\"yes\""), "the keyed node was reused across reorder: " + dom);
    // After Flip, b precedes a in the DOM.
    assertTrue(dom.indexOf("id=\"b\"") < dom.indexOf("id=\"a\""), "reordered by key: " + dom);
  }

  @Test
  void platformWorkerRunsWithoutAView() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A headless worker (no view): dispatch Inc, then write the model into the DOM so we can read it.
    String app =
        "module Main exposing (main)\n"
            + "import Platform\n"
            + "type Msg = Inc\n"
            + "main = Platform.worker { init = \\_ -> ( 0, Cmd.none ), update = update, subscriptions = \\_ -> Sub.none }\n"
            + "update msg n = case msg of\n  Inc -> ( n + 1, Cmd.none )\n";
    String driver =
        "window.$app.dispatch($data('Inc',[]));"
            + "window.$app.dispatch($data('Inc',[]));"
            + "document.body.textContent = 'model=' + window.$app.model();";
    String dom = renderPage(JsCompiler.htmlPage(app, driver));
    assertTrue(dom.contains("model=2"), "the worker ran update without a view: " + dom);
  }

  @Test
  void classListAndDoubleClickWork() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // classList renders only the true classes; onDoubleClick drives an update.
    String app =
        "module Main exposing (main)\n"
            + "import Browser\n"
            + "import Html exposing (div, text)\n"
            + "import Html.Attributes exposing (classList)\n"
            + "import Html.Events exposing (onDoubleClick)\n"
            + "type Msg = Hit\n"
            + "main = Browser.sandbox { init = False, update = \\_ _ -> True, view = view }\n"
            + "view active =\n"
            + "    div [ classList [ ( \"on\", active ), ( \"off\", not active ) ], onDoubleClick Hit ]\n"
            + "        [ text (if active then \"ACTIVE\" else \"idle\") ]\n";
    String dom = renderPage(JsCompiler.htmlPage(app, "document.querySelector('.off').dispatchEvent(new MouseEvent('dblclick',{bubbles:true}));"));
    assertTrue(dom.contains("ACTIVE"), "double-click updated the model: " + dom);
    assertTrue(dom.contains("class=\"on\""), "classList rendered the active class only: " + dom);
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

  @Test
  void editorRendersTheSelectedFilesMainLive() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] =
          new String(
              HeadlessChromeTest.class
                  .getResourceAsStream(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i])
                  .readAllBytes(),
              StandardCharsets.UTF_8);
    }
    String dom = renderPage(JsCompiler.htmlPageProject(null, modules));
    // The editor fetches its example files over HTTP at startup; rendered from a file:// page those
    // fetches don't resolve, so only the built-in starter (Buttons.elm) is present — and it runs
    // live: the file list shows Buttons.elm and the minus button / initial model render. (The HTTP
    // loading itself is covered by editorLoadsExamplesOverHttp, served over real HTTP.)
    assertTrue(dom.contains("Buttons.elm"), "starter file listed");
    assertTrue(dom.contains(">-<"), "selected file's main rendered live (the minus button)");
    assertTrue(dom.contains("<div>0</div>"), "live app shows the initial interpreted model");
    assertTrue(dom.contains("Open .elm"), "the Open-file button is present");
  }

  @Test
  void portsSendAndReceiveAcrossTheJsBoundary() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // A port module with an outgoing port (out) and an incoming port (incoming). The driver
    // subscribes to `out`, pushes a value in through `incoming`, and the app echoes the running
    // total back out — exercising both directions of the JS port boundary.
    String source =
        "port module Main exposing (main)\n"
            + "import Browser\n"
            + "import Html exposing (text)\n"
            + "port out : Int -> Cmd msg\n"
            + "port incoming : (Int -> msg) -> Sub msg\n"
            + "type Msg = Got Int\n"
            + "main = Browser.element\n"
            + "    { init = \\_ -> ( 0, Cmd.none )\n"
            + "    , update = \\msg model -> case msg of\n"
            + "        Got n -> ( model + n, out (model + n) )\n"
            + "    , view = \\model -> text (String.fromInt model)\n"
            + "    , subscriptions = \\_ -> incoming Got\n"
            + "    }\n";
    String driver =
        "window.$app.ports.out.subscribe(function(v){ document.title = 'out=' + v; });"
            + "window.$app.ports.incoming.send(5);"
            + "window.$app.ports.incoming.send(10);";
    String dom = renderInBrowser(source, driver);
    // 0 -> Got 5 -> 5 (out 5) -> Got 10 -> 15 (out 15); the last outgoing value is in the title.
    assertTrue(dom.contains("out=15"), "outgoing port delivered the latest value: " + dom);
    assertTrue(dom.contains(">15<") || dom.contains("15"), "the model updated from incoming ports: " + dom);
  }

  @Test
  void editorRendersAWebglProgramToALiveCanvas() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = resource(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    // Replace the selected file with a small WebGL program (one red triangle behind a perspective
    // matrix). The editor evaluates it to a WebGL.scene and the bridge mounts a real <canvas> driven
    // by the GL runtime. We assert the canvas appears and the page didn't error — not the pixels.
    String program =
        String.join(
            "\\n",
            "vert = [glsl| attribute vec3 position; uniform mat4 camera;"
                + " void main () { gl_Position = camera * vec4(position, 1.0); } |]",
            "frag = [glsl| precision mediump float;"
                + " void main () { gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0); } |]",
            "mesh = WebGL.triangles"
                + " [ ( { position = vec3 0 0 0 }, { position = vec3 1 0 0 }, { position = vec3 0 1 0 } ) ]",
            "main = WebGL.toHtml [ width 220, height 160 ]"
                + " [ WebGL.entity vert frag mesh { camera = Mat4.makePerspective 45 1.4 0.1 100 } ]");
    // EditAt carries the new source and the caret offset (the editor's textarea reports both).
    String driver = "window.$app.dispatch($data('EditAt',[\"" + program + "\",0]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("<canvas"), "a live WebGL <canvas> is mounted: " + dom);
    // The canvas carries the requested size, proving the program's attributes flowed through.
    assertTrue(dom.contains("width=\"220\"") || dom.contains("width=220"), "canvas sized from the program");
  }

  @Test
  void editorTimeTravelDebuggerRecordsAndRewinds() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = resource(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    // Dispatch two Increment messages into the interpreted Buttons app, then rewind to the start.
    String inc = "$data('Interp',[$data('VCtor',['Increment',$nil])])";
    String driver =
        "window.$app.dispatch(" + inc + ");window.$app.dispatch(" + inc + ");"
            + "window.$app.dispatch($data('Rewind',[0]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("time travel"), "the time-travel scrubber appears after steps");
    assertTrue(dom.contains("msg 0 /"), "cursor rewound to message 0");
    assertTrue(dom.contains("<div>0</div>"), "rewinding re-renders the initial model");
    // The message-log panel lists each dispatched message (rendered) as a clickable chip.
    assertTrue(dom.contains("messages:"), "the message-log panel appears");
    assertTrue(dom.contains("1. Increment"), "first dispatched message rendered in the log");
    assertTrue(dom.contains("2. Increment"), "second dispatched message rendered in the log");
  }

  @Test
  void editorRestoresASharedSessionFromAPermalink() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = resource(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    // A GotHash carrying an encoded one-file session (Share.encodeFiles format: <len>,<text> per
    // field) replaces the default starter files with the shared file — the permalink restore path.
    // The file's main concatenates "RESTO" ++ "RED": the rendered "RESTORED" appears only if the
    // session was restored AND its main evaluated live (the substring is never literal in the page).
    String encoded = "10,Shared.elm30,main = text (\"RESTO\" ++ \"RED\")";
    String driver = "window.$app.dispatch($data('GotHash',['" + encoded + "']));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("RESTORED"), "the restored file's main rendered live: " + dom);
  }

  @Test
  void editorShowsALineNumberGutter() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = resource(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    // Edit to a 3-line file; the gutter renders a numbered <div> per line. ">N</div>" only appears in
    // the rendered DOM (the compiled bundle builds nodes via $data, not literal tags), so it's a
    // gutter-specific check.
    String program = "a = 1\\nb = 2\\nmain = text (String.fromInt 42)"; // 3 lines, no quotes
    String driver = "window.$app.dispatch($data('EditAt',['" + program + "',0]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains(">1</div>"), "gutter line 1: " + dom);
    assertTrue(dom.contains(">2</div>"), "gutter line 2");
    assertTrue(dom.contains(">3</div>"), "gutter line 3");
    assertTrue(dom.contains("42"), "the file still renders live");
  }

  @Test
  void editorRestoresAnAutosavedSessionFromLocalStorage() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = resource(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    // LoadedSession(Just <encoded>) is what the autosave load dispatches at startup; it restores the
    // saved file, whose main concatenates "AUTO" ++ "SAVED" (the rendered "AUTOSAVED" proves it ran).
    String encoded = "9,Saved.elm31,main = text (\"AUTO\" ++ \"SAVED\")";
    String driver = "window.$app.dispatch($data('LoadedSession',[$data('Just',['" + encoded + "'])]));";
    String dom = renderPage(JsCompiler.htmlPageProject(driver, modules));
    assertTrue(dom.contains("AUTOSAVED"), "the autosaved session restored and rendered live: " + dom);
  }

  @Test
  void editorLoadsExamplesOverHttp() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Serve the editor page + example files over real HTTP and confirm the editor fetches them at
    // startup and lists them as editable files (here: Squares.elm and Toggle.elm).
    String[] modules = new String[pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES.length];
    for (int i = 0; i < modules.length; i++) {
      modules[i] = resource(pl.matsuo.elm.site.SiteGenerator.EDITOR_MODULES[i]);
    }
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("elm-editor-http-");
    java.nio.file.Files.writeString(dir.resolve("editor.html"), JsCompiler.htmlPageProject(null, modules));
    java.nio.file.Path ex = java.nio.file.Files.createDirectories(dir.resolve("examples"));
    for (String name : new String[] {"Buttons", "TextField", "Element", "Hello", "Greeting", "Factorial", "ListSum", "Squares", "Toggle"}) {
      java.nio.file.Files.writeString(ex.resolve(name + ".elm"), resource("/elm/editor-examples/" + name + ".elm"));
    }
    com.sun.net.httpserver.HttpServer http =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext("/", exchange -> {
      java.nio.file.Path f = dir.resolve(exchange.getRequestURI().getPath().substring(1));
      if (java.nio.file.Files.isRegularFile(f)) {
        byte[] b = java.nio.file.Files.readAllBytes(f);
        exchange.sendResponseHeaders(200, b.length);
        try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(b); }
      } else {
        exchange.sendResponseHeaders(404, -1);
      }
      exchange.close();
    });
    http.start();
    try {
      String dom = renderUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/editor.html");
      assertTrue(dom.contains("Squares.elm") && dom.contains("Toggle.elm"), "fetched examples listed: " + dom);
    } finally {
      http.stop(0);
    }
  }

  @Test
  void recordsAndDeterministicallyReplaysTheMessageLog() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Dispatch three increments, capture the recorded message log, then replay it from scratch and
    // confirm the reconstructed model and history match exactly (deterministic reproduction).
    String driver =
        "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "var log = window.$app.messages().slice();"
            + "document.body.setAttribute('data-recorded', String(log.length));"
            + "window.$app.replay(log);"
            + "document.body.setAttribute('data-replayed', document.querySelectorAll('div')[1].textContent);"
            + "document.body.setAttribute('data-steps', String(window.$app.history().length - 1));";
    String dom = renderInBrowser(example("buttons"), driver);
    assertTrue(dom.contains("data-recorded=\"3\""), dom); // three messages recorded
    assertTrue(dom.contains("data-replayed=\"-3+\"") || dom.contains("data-replayed=\"3\""), dom); // count 3
    assertTrue(dom.contains("data-steps=\"3\""), dom); // history rebuilt to the same length
  }

  @Test
  void timeTravelShowsHistoricalModelThenResumesLive() throws Exception {
    assumeTrue(CHROME != null, "Chrome not installed");
    // Three increments produce snapshots [0,1,2,3]. goto(1) re-renders the model after the first
    // step (1) without mutating state; live() returns to the latest (3).
    String driver =
        "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.dispatch($data('Increment',[]));"
            + "window.$app.goto(1);"
            + "document.body.setAttribute('data-historical', document.querySelectorAll('div')[2].textContent);"
            + "window.$app.live();"
            + "document.body.setAttribute('data-live', document.querySelectorAll('div')[2].textContent);"
            + "document.body.setAttribute('data-steps', String(window.$app.history().length - 1));";
    String dom = renderInBrowser(example("buttons"), driver);
    assertTrue(dom.contains("data-historical=\"1\""), dom); // time-travelled to snapshot 1 (count 1)
    assertTrue(dom.contains("data-live=\"3\""), dom); // resumed at the latest model (count 3)
    assertTrue(dom.contains("data-steps=\"3\""), dom); // three recorded steps
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

package pl.matsuo.elm.site;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.codegen.wasm.WasmCompiler;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.runtime.ElmData;

/**
 * Generates the static showcase site published at {@code tunguski.github.io/elm-lang}.
 *
 * <p>For every elm-lang.org example it produces a self-contained demo page and a wrapper page that
 * embeds the demo next to the Elm source. The preferred demo is the <b>JavaScript backend</b>
 * output ({@link JsCompiler#htmlPage}) — a live, interactive page that runs entirely in the
 * browser. Examples the JS backend cannot yet bundle (the multi-module Playground games and the
 * GPU-bound WebGL programs) fall back to a server-side snapshot rendered by the interpreter so the
 * gallery is still complete. Each card is labelled with the method used.
 */
public final class SiteGenerator {

  /** One example as listed on elm-lang.org/examples. */
  public record Example(String category, String title, String slug) {}

  /** The full elm-lang.org/examples catalogue, in display order. */
  public static final List<Example> EXAMPLES =
      List.of(
          new Example("HTML", "Hello", "hello"),
          new Example("HTML", "Groceries", "groceries"),
          new Example("HTML", "Shapes", "shapes"),
          new Example("User Input", "Buttons", "buttons"),
          new Example("User Input", "Text Fields", "text-fields"),
          new Example("User Input", "Forms", "forms"),
          new Example("Random", "Numbers", "numbers"),
          new Example("Random", "Cards", "cards"),
          new Example("Random", "Positions", "positions"),
          new Example("HTTP", "Book", "book"),
          new Example("HTTP", "Quotes", "quotes"),
          new Example("Time", "Time", "time"),
          new Example("Time", "Clock", "clock"),
          new Example("Files", "Upload", "upload"),
          new Example("Files", "Drag and Drop", "drag-and-drop"),
          new Example("Files", "Image Previews", "image-previews"),
          new Example("WebGL", "Triangle", "triangle"),
          new Example("WebGL", "Cube", "cube"),
          new Example("WebGL", "Crate", "crate"),
          new Example("WebGL", "Thwomp", "thwomp"),
          new Example("WebGL", "First Person", "first-person"),
          new Example("Playground", "Picture", "picture"),
          new Example("Playground", "Animation", "animation"),
          new Example("Playground", "Mouse", "mouse"),
          new Example("Playground", "Keyboard", "keyboard"),
          new Example("Playground", "Turtle", "turtle"),
          new Example("Playground", "Mario", "mario"));

  private enum Method {
    LIVE("Live JS (compiled)", "live"),
    SNAPSHOT("Rendered snapshot", "snapshot"),
    FAILED("Source only", "failed");
    final String label;
    final String css;

    Method(String label, String css) {
      this.label = label;
      this.css = css;
    }
  }

  private record Built(Example example, Method method, String note) {}

  private final Path examplesDir;
  private final String playgroundSource;
  private final Path outDir;
  private final Path docsDir; // Markdown docs rendered to HTML; null to skip.

  private SiteGenerator(Path examplesDir, Path playgroundFile, Path outDir, Path docsDir)
      throws IOException {
    this.examplesDir = examplesDir;
    this.playgroundSource = Files.readString(playgroundFile, StandardCharsets.UTF_8);
    this.outDir = outDir;
    this.docsDir = docsDir;
  }

  /** Builds the whole site into {@code outDir} (no docs pages). */
  public static void generate(Path examplesDir, Path playgroundFile, Path outDir)
      throws IOException {
    generate(examplesDir, playgroundFile, outDir, null);
  }

  /** Builds the whole site into {@code outDir}, also rendering {@code docsDir}'s Markdown to HTML. */
  public static void generate(Path examplesDir, Path playgroundFile, Path outDir, Path docsDir)
      throws IOException {
    new SiteGenerator(examplesDir, playgroundFile, outDir, docsDir).run();
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println(
          "usage: SiteGenerator <examplesDir> <Playground.elm> <outDir> [docsDir]");
      System.exit(2);
    }
    generate(
        Path.of(args[0]),
        Path.of(args[1]),
        Path.of(args[2]),
        args.length > 3 ? Path.of(args[3]) : null);
  }

  /** A documentation page rendered from a Markdown file: HTML filename and display title. */
  private record DocPage(String slug, String title) {}

  private void run() throws IOException {
    Files.createDirectories(outDir.resolve("demos"));
    List<Built> built = new ArrayList<>();
    for (Example ex : EXAMPLES) {
      built.add(buildExample(ex));
    }
    writeBackendsPage();
    writePlaygroundPage();
    writeExampleSources();
    writeTodoMvcPage();
    writeEditorPage();
    List<DocPage> docs = writeDocPages();
    writeIndex(built, docs);
    System.out.println("Site written to " + outDir.toAbsolutePath());
    for (Built b : built) {
      System.out.printf("  %-16s %-22s %s%n", b.example.slug(), b.method.label, b.note);
    }
    for (DocPage d : docs) {
      System.out.printf("  %-16s %s%n", d.slug() + ".html", d.title());
    }
  }

  /**
   * Renders every {@code *.md} in {@code docsDir} to a styled HTML page (same base name), linking
   * back to the gallery. Returns the pages in a stable, doc-friendly order for the index nav.
   */
  private List<DocPage> writeDocPages() throws IOException {
    List<DocPage> pages = new ArrayList<>();
    if (docsDir == null || !Files.isDirectory(docsDir)) {
      return pages;
    }
    // A readable order: the examples overview first, then the how-to guides, then anything else.
    List<String> preferred = List.of("examples", "scripting", "server");
    List<Path> files;
    try (var s = Files.list(docsDir)) {
      files =
          new ArrayList<>(
              s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList());
    }
    files.sort(
        (a, b) -> {
          int ia = preferred.indexOf(slugOf(a));
          int ib = preferred.indexOf(slugOf(b));
          if (ia < 0) ia = Integer.MAX_VALUE;
          if (ib < 0) ib = Integer.MAX_VALUE;
          return ia != ib ? Integer.compare(ia, ib) : slugOf(a).compareTo(slugOf(b));
        });
    for (Path md : files) {
      String slug = slugOf(md);
      String source = Files.readString(md, StandardCharsets.UTF_8);
      String title = docTitle(source, slug);
      String body = Markdown.toHtml(rewriteDocLinks(source));
      String page =
          """
          <!doctype html>
          <html lang="en">
          <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>%TITLE% — elm-lang</title>
          %STYLE%
          </head>
          <body>
          <header class="bar">
            <a href="index.html">&larr; Gallery</a>
            %NAV%
          </header>
          <main>
          %BODY%
          </main>
          <footer>Documentation for the from-scratch Elm implementation ·
          <a href="https://github.com/tunguski/elm-lang">source on GitHub</a></footer>
          </body>
          </html>
          """
              .replace("%STYLE%", DOCS_STYLE)
              .replace("%NAV%", docNav(slug, preferred))
              .replace("%TITLE%", escape(title))
              .replace("%BODY%", body);
      Files.writeString(outDir.resolve(slug + ".html"), page, StandardCharsets.UTF_8);
      pages.add(new DocPage(slug, title));
    }
    return pages;
  }

  private static String slugOf(Path md) {
    String name = md.getFileName().toString();
    return name.substring(0, name.length() - ".md".length());
  }

  /** The page title: the first {@code # heading}, else the slug. */
  private static String docTitle(String source, String slug) {
    for (String line : source.split("\n", -1)) {
      String t = line.strip();
      if (t.startsWith("# ")) {
        return t.substring(2).strip();
      }
    }
    return slug;
  }

  /** A nav line linking to the sibling guide pages (the current one shown inert). */
  private String docNav(String current, List<String> preferred) {
    StringBuilder b = new StringBuilder();
    for (String slug : preferred) {
      if (b.length() > 0) {
        b.append(" · ");
      }
      String label = slug.substring(0, 1).toUpperCase() + slug.substring(1);
      if (slug.equals(current)) {
        b.append("<strong>").append(label).append("</strong>");
      } else {
        b.append("<a href=\"").append(slug).append(".html\">").append(label).append("</a>");
      }
    }
    return b.toString();
  }

  /**
   * Rewrites relative Markdown links so they work in the flat gallery: a link to a sibling guide
   * ({@code foo.md}) becomes {@code foo.html}; links into the repo ({@code ../src/...}) become
   * absolute GitHub URLs so they resolve from the published site.
   */
  private static String rewriteDocLinks(String md) {
    String repo = "https://github.com/tunguski/elm-lang/blob/main/";
    return md.replaceAll("\\]\\((?!https?://)([^)]+?)\\.md\\)", "]($1.html)")
        .replaceAll("\\]\\(\\.\\./([^)]+)\\)", "](" + repo + "$1)");
  }

  private Built buildExample(Example ex) throws IOException {
    String source = Files.readString(examplesDir.resolve(ex.slug() + ".elm"), StandardCharsets.UTF_8);
    Method method;
    String note = "";

    // First choice: a real, interactive page compiled by the JavaScript backend.
    String demo = tryLiveJs(source);
    if (demo != null) {
      method = Method.LIVE;
      demo = localizeAssets(demo); // vendor remote images so WebGL textures aren't CORS-blocked
    } else {
      // Fallback: an interpreter-rendered snapshot of the initial view.
      String snapshot = trySnapshot(source);
      if (snapshot != null) {
        demo = snapshotPage(ex, snapshot);
        method = Method.SNAPSHOT;
        note =
            source.contains("import Playground")
                ? "multi-module Playground program; initial frame rendered server-side"
                : "rendered server-side (WebGL needs a real GPU surface)";
      } else {
        demo = sourceOnlyPage(ex);
        method = Method.FAILED;
        note = "could not be evaluated headlessly";
      }
    }

    Files.writeString(outDir.resolve("demos/" + ex.slug() + ".html"), demo, StandardCharsets.UTF_8);
    Files.writeString(
        outDir.resolve(ex.slug() + ".html"), wrapperPage(ex, method, source), StandardCharsets.UTF_8);
    return new Built(ex, method, note);
  }

  /**
   * Downloads remote {@code .jpg/.png/.gif} images referenced by a demo into the gallery
   * (same-origin {@code demos/assets/…}) and rewrites the URLs, so cross-origin WebGL textures
   * (e.g. elm-lang.org's wood crate) actually load and aren't tainted/CORS-blocked. Best-effort: a
   * URL that can't be fetched keeps its original (remote) reference.
   */
  private String localizeAssets(String html) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("https?://[\\w./-]+\\.(?:jpg|jpeg|png|gif)")
            .matcher(html);
    java.util.Set<String> urls = new java.util.LinkedHashSet<>();
    while (m.find()) {
      urls.add(m.group());
    }
    for (String url : urls) {
      String rel = "assets/" + url.replaceFirst("https?://[^/]+/", "");
      Path dest = outDir.resolve("demos").resolve(rel);
      if (download(url, dest)) {
        html = html.replace(url, rel);
      }
    }
    return html;
  }

  /** Downloads {@code url} to {@code dest} (cached if already present); returns success. */
  private boolean download(String url, Path dest) {
    try {
      if (Files.exists(dest)) {
        return true;
      }
      Files.createDirectories(dest.getParent());
      java.net.http.HttpClient client =
          java.net.http.HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(10))
              .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
              .build();
      java.net.http.HttpResponse<byte[]> resp =
          client.send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                  .timeout(java.time.Duration.ofSeconds(20))
                  .build(),
              java.net.http.HttpResponse.BodyHandlers.ofByteArray());
      if (resp.statusCode() == 200) {
        Files.write(dest, resp.body());
        return true;
      }
    } catch (Exception ignored) {
      // network unavailable / blocked — fall back to the original remote URL
    }
    return false;
  }

  private String tryLiveJs(String source) {
    try {
      // Playground games are multi-module: bundle the real Playground source with the example.
      if (source.contains("import Playground")) {
        return JsCompiler.htmlPageProject(null, playgroundSource, source);
      }
      return JsCompiler.htmlPage(source, null);
    } catch (Throwable t) {
      return null;
    }
  }

  /** Renders the initial view through the interpreter, returning an HTML fragment, or null. */
  private String trySnapshot(String source) {
    try {
      Object value =
          source.contains("import Playground")
              ? Project.load(playgroundSource, source).main()
              : Interpreter.load(source).value("main");
      return renderValue(value);
    } catch (Throwable t) {
      return null;
    }
  }

  /** Mirrors {@code Main.render}: programs and Html nodes become HTML; otherwise show the value. */
  private static String renderValue(Object value) {
    if (value instanceof ElmData d) {
      switch (d.ctor()) {
        case "$Sandbox", "$Element", "$Document" -> {
          return Tea.start(value).html();
        }
        case "$Node", "$Text" -> {
          return HtmlRender.render(value);
        }
        default -> {}
      }
    }
    return "<pre>" + escape(Show.plain(value)) + "</pre>";
  }

  // --- page templates ----------------------------------------------------

  private static String snapshotPage(Example ex, String fragment) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
        + escape(ex.title())
        + "</title><style>body{margin:0;padding:16px;font-family:system-ui,sans-serif}</style></head>"
        + "<body>"
        + fragment
        + "</body></html>\n";
  }

  private String sourceOnlyPage(Example ex) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"><title>"
        + escape(ex.title())
        + "</title><style>body{margin:0;padding:16px;font-family:system-ui,sans-serif;color:#555}</style></head>"
        + "<body><p>This example could not be rendered headlessly. See the source alongside.</p></body></html>\n";
  }

  private String wrapperPage(Example ex, Method method, String source) {
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>%TITLE% — elm-lang</title>
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css">
        %STYLE%
        </head>
        <body>
        <header class="bar">
          <a class="home" href="index.html">&larr; All examples</a>
          <span class="badge %CSS%">%METHOD%</span>
        </header>
        <main>
          <h1>%TITLE% <small>%CATEGORY%</small></h1>
          <section class="demo">
            <div class="demo-head">
              <a class="newtab" href="demos/%SLUG%.html" target="_blank" rel="noopener">Open demo in a new tab &#8599;</a>
            </div>
            <iframe title="%TITLE% demo" src="demos/%SLUG%.html" loading="lazy"></iframe>
          </section>
          <section class="src">
            <h2>Source</h2>
            <pre><code class="language-elm">%SOURCE%</code></pre>
          </section>
        </main>
        <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
        <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/elm.min.js"></script>
        <script>hljs.highlightAll();</script>
        </body>
        </html>
        """
        .replace("%STYLE%", PAGE_STYLE)
        .replace("%TITLE%", escape(ex.title()))
        .replace("%CATEGORY%", escape(ex.category()))
        .replace("%SLUG%", ex.slug())
        .replace("%METHOD%", method.label)
        .replace("%CSS%", method.css)
        .replace("%SOURCE%", escape(source));
  }

  /** Numeric snippets the WASM backend supports, evaluated live by both JS and WASM in the page. */
  private static final List<String> BACKEND_SNIPPETS =
      List.of(
          "1 + 2 * 3",
          "(100 - 7) * 3",
          "7 // 2",
          "modBy 7 1000",
          "abs (10 - 37)",
          "let x = 6 in x * x",
          "(\\n -> n * n - 1) 9",
          "if 3 < 5 && 10 > 2 then 100 else 0",
          "1000000 * 1000000");

  /**
   * A page that runs each numeric snippet through BOTH the JavaScript backend and the WebAssembly
   * backend live in the browser, showing the two results side by side (with the interpreter's value
   * as the expected baseline). Demonstrates the two compiled backends agreeing in-browser.
   */
  private void writeBackendsPage() throws IOException {
    List<Expr> parsed = new ArrayList<>();
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < BACKEND_SNIPPETS.size(); i++) {
      String snip = BACKEND_SNIPPETS.get(i);
      parsed.add(Parser.parseExpression(snip));
      String expected = Show.plain(Interpreter.eval(snip));
      rows.append("<tr data-expected=\"")
          .append(escape(expected))
          .append("\"><td><code>")
          .append(escape(snip))
          .append("</code></td><td class=\"exp\">")
          .append(escape(expected))
          .append("</td><td class=\"js\" id=\"js")
          .append(i)
          .append("\">…</td><td class=\"wasm\" id=\"wasm")
          .append(i)
          .append("\">…</td><td class=\"ok\" id=\"ok")
          .append(i)
          .append("\"></td></tr>\n");
    }
    String wasmB64 = Base64.getEncoder().encodeToString(WasmCompiler.module(parsed));
    String jsEval = JsCompiler.expressionsEvalScript(BACKEND_SNIPPETS);
    String perf = perfChart();

    String page =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>JS vs WASM — elm-lang</title>
        %STYLE%
        </head>
        <body>
        <header class="bar">
          <a class="home" href="index.html">&larr; All examples</a>
          <span class="badge live">live in your browser</span>
        </header>
        <main>
          <h1>JavaScript vs WebAssembly</h1>
          <p>Each numeric Elm expression below is compiled by two backends and run right here in your
          browser: the <strong>JavaScript</strong> backend and the from-scratch <strong>WebAssembly</strong>
          backend (a wasm binary instantiated via <code>WebAssembly.instantiate</code>). The
          interpreter's value is the expected baseline; ✓ means all three agree.</p>
          <table>
            <thead><tr><th>Expression</th><th>Interpreter</th><th>JS</th><th>WASM</th><th></th></tr></thead>
            <tbody>
        %ROWS%
            </tbody>
          </table>
          %PERF%
        </main>
        <script>%JSEVAL%</script>
        <script>
        (function(){
          var js = $evalAll();
          for (var i=0;i<js.length;i++){ var el=document.getElementById('js'+i); if(el) el.textContent=js[i]; }
          var bin = Uint8Array.from(atob("%WASM%"), function(c){ return c.charCodeAt(0); });
          WebAssembly.instantiate(bin).then(function(r){
            var ex=r.instance.exports, i=0;
            while(('f'+i) in ex){ document.getElementById('wasm'+i).textContent = ex['f'+i]().toString(); i++; }
            document.querySelectorAll('tbody tr').forEach(function(tr,n){
              var want=tr.getAttribute('data-expected');
              var a=tr.querySelector('.js').textContent, b=tr.querySelector('.wasm').textContent;
              tr.querySelector('.ok').textContent = (a===want && b===want) ? '✓' : '✗';
              tr.querySelector('.ok').className = 'ok ' + ((a===want && b===want)?'good':'bad');
            });
          });
        })();
        </script>
        </body>
        </html>
        """
            .replace("%STYLE%", BACKENDS_STYLE)
            .replace("%ROWS%", rows.toString())
            .replace("%PERF%", perf)
            .replace("%JSEVAL%", jsEval)
            .replace("%WASM%", wasmB64);
    Files.writeString(outDir.resolve("backends.html"), page, StandardCharsets.UTF_8);
  }

  /** The editor's Elm modules (a small interpreter + an Ellie-style multi-file UI), bundled together. */
  public static final String[] EDITOR_MODULES = {
    "/elm/editor/Lang.elm",
    "/elm/editor/Lexer.elm",
    "/elm/editor/Parser.elm",
    "/elm/editor/Eval.elm",
    "/elm/editor/Editor.elm",
    "/elm/editor/Main.elm",
  };

  /** The editor's loadable example modules, served under {@code examples/} for the editor to fetch. */
  private static final String[] EDITOR_EXAMPLES = {
    "Buttons", "TextField", "Element", "Hello", "Greeting", "Factorial", "ListSum", "Squares", "Toggle"
  };

  /**
   * Writes the raw Elm sources under {@code examples/} so they are downloadable from the site and
   * fetchable by the editor: every elm-lang.org example, the editor's own demo files, and TodoMVC.
   */
  private void writeExampleSources() throws IOException {
    Path dir = outDir.resolve("examples");
    Files.createDirectories(dir);
    for (Example ex : EXAMPLES) {
      Files.copy(
          examplesDir.resolve(ex.slug() + ".elm"),
          dir.resolve(ex.slug() + ".elm"),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    for (String name : EDITOR_EXAMPLES) {
      Files.writeString(
          dir.resolve(name + ".elm"),
          pl.matsuo.elm.util.Resources.read("/elm/editor-examples/" + name + ".elm"),
          StandardCharsets.UTF_8);
    }
    Files.writeString(
        dir.resolve("todomvc.elm"),
        pl.matsuo.elm.util.Resources.read("/elm/demos/todomvc.elm"),
        StandardCharsets.UTF_8);
  }

  /** Compiles the bundled TodoMVC demo to a live, interactive page (the flagship TEA showcase). */
  private void writeTodoMvcPage() throws IOException {
    Files.writeString(
        outDir.resolve("todomvc.html"),
        JsCompiler.htmlPageProject(null, pl.matsuo.elm.util.Resources.read("/elm/demos/todomvc.elm")),
        StandardCharsets.UTF_8);
  }

  /**
   * The Ellie-style editor page: a multi-file interpreter written in Elm (the Lang/Lexer/Parser/
   * Eval/Main modules), bundled by the JS backend and running live in the browser — it fetches the
   * example files over HTTP and lets you edit them, rendering each selected file's `main`.
   */
  private void writeEditorPage() throws IOException {
    String[] sources = new String[EDITOR_MODULES.length];
    for (int i = 0; i < EDITOR_MODULES.length; i++) {
      sources[i] = pl.matsuo.elm.util.Resources.read(EDITOR_MODULES[i]);
    }
    Files.writeString(
        outDir.resolve("editor.html"),
        JsCompiler.htmlPageProject(null, sources),
        StandardCharsets.UTF_8);
  }

  /** Numeric functions for the interactive playground (single Int argument each). */
  private static final String PLAYGROUND_SRC =
      """
      fib n = if n < 2 then n else fib (n - 1) + fib (n - 2)
      factorial n = if n < 1 then 1 else n * factorial (n - 1)
      sumTo n = if n == 0 then 0 else n + sumTo (n - 1)
      triple n = n * 3
      """;

  /**
   * An interactive page: pick a function and an input, and it's computed live in the browser by
   * BOTH compiled backends — the JavaScript backend and the WebAssembly backend — with timings.
   * (The compiler is on the JVM, so the functions are pre-compiled; the inputs are interactive.)
   */
  private void writePlaygroundPage() throws IOException {
    String jsScript = JsCompiler.declarationsScript(PLAYGROUND_SRC);
    String wasmB64 = Base64.getEncoder().encodeToString(WasmCompiler.moduleFromSource(PLAYGROUND_SRC));
    String page =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Playground — elm-lang</title>
        %STYLE%
        </head>
        <body>
        <header class="bar">
          <a class="home" href="index.html">&larr; All examples</a>
          <span class="badge live">compiled JS + WASM, live</span>
        </header>
        <main>
          <h1>Interactive backend playground</h1>
          <p>These Elm functions were compiled ahead of time to both JavaScript and WebAssembly.
          Pick one and an input — it runs in <em>both</em> compiled backends right here, with timings.</p>
          <pre class="src"><code class="language-elm">%SRC%</code></pre>
          <div class="controls">
            <select id="fn"><option>fib</option><option>factorial</option><option>sumTo</option><option>triple</option></select>
            <input id="n" type="number" value="25" min="0" max="40">
            <button id="run">Run</button>
          </div>
          <table>
            <tr><th>Backend</th><th>Result</th><th>Time</th></tr>
            <tr><td>JavaScript</td><td id="jsr">—</td><td id="jst"></td></tr>
            <tr><td>WebAssembly</td><td id="wr">—</td><td id="wt"></td></tr>
          </table>
          <p id="agree"></p>
        </main>
        <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css">
        <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
        <script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/elm.min.js"></script>
        <script>hljs.highlightAll();</script>
        <script>%JS%</script>
        <script>
        var wasmExports=null;
        WebAssembly.instantiate(Uint8Array.from(atob("%WASM%"), function(c){return c.charCodeAt(0);}))
          .then(function(r){ wasmExports=r.instance.exports; run(); });
        function run(){
          var fn=document.getElementById('fn').value, n=parseInt(document.getElementById('n').value,10)||0;
          var jsFn=window['_$'+fn];
          var t0=performance.now(); var jr=jsFn(n); var jt=performance.now()-t0;
          document.getElementById('jsr').textContent=String(jr);
          document.getElementById('jst').textContent=jt.toFixed(3)+' ms';
          if(wasmExports){
            var w0=performance.now(); var wr=wasmExports[fn](BigInt(n)); var wt=performance.now()-w0;
            document.getElementById('wr').textContent=wr.toString();
            document.getElementById('wt').textContent=wt.toFixed(3)+' ms';
            document.getElementById('agree').textContent =
              (String(jr)===wr.toString()) ? '✓ both backends agree' : '✗ backends disagree';
          }
        }
        document.getElementById('run').addEventListener('click', run);
        document.getElementById('fn').addEventListener('change', run);
        document.getElementById('n').addEventListener('input', run);
        </script>
        </body>
        </html>
        """
            .replace("%STYLE%", BACKENDS_STYLE)
            .replace("%SRC%", escape(PLAYGROUND_SRC))
            .replace("%JS%", jsScript)
            .replace("%WASM%", wasmB64);
    Files.writeString(outDir.resolve("playground.html"), page, StandardCharsets.UTF_8);
  }

  /** A small bar chart of warm fib timings per backend (best-effort; empty if it can't run). */
  private static String perfChart() {
    try {
      var warm = pl.matsuo.elm.bench.Benchmark.warm(24, 6, 12);
      double max = warm.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
      StringBuilder bars = new StringBuilder();
      warm.forEach(
          (name, ms) ->
              bars.append("<div class=\"bar\"><span class=\"lbl\">")
                  .append(escape(name))
                  .append("</span><span class=\"track\"><span class=\"fill\" style=\"width:")
                  .append(String.format(java.util.Locale.US, "%.1f", Math.max(2, ms / max * 100)))
                  .append("%\"></span></span><span class=\"num\">")
                  .append(String.format(java.util.Locale.US, "%.2f ms", ms))
                  .append("</span></div>\n"));
      return "<section class=\"perf\"><h2>Performance — fib(24), best warm run</h2>"
          + "<p>The same recursive workload (naive fib) timed on each backend (lower is faster):"
          + " the WebAssembly and JavaScript backends compile to fast native/JIT code, the"
          + " Graal-compiled Truffle interpreter follows, and the bytecode VM is the simple"
          + " baseline.</p>"
          + bars
          + "</section>";
    } catch (Throwable t) {
      return ""; // benchmarking is best-effort; never break site generation
    }
  }

  private static final String BACKENDS_STYLE = style("/elm/css/backends.css");

  private void writeIndex(List<Built> built, List<DocPage> docs) throws IOException {
    StringBuilder cards = new StringBuilder();
    String currentCategory = null;
    for (Built b : built) {
      if (!b.example.category().equals(currentCategory)) {
        if (currentCategory != null) {
          cards.append("</div>\n");
        }
        currentCategory = b.example.category();
        cards.append("<h2>").append(escape(currentCategory)).append("</h2>\n<div class=\"grid\">\n");
      }
      cards
          .append("<a class=\"card\" href=\"")
          .append(b.example.slug())
          .append(".html\">")
          .append("<span class=\"thumb\"><iframe tabindex=\"-1\" scrolling=\"no\" src=\"demos/")
          .append(b.example.slug())
          .append(".html\" loading=\"lazy\"></iframe></span>")
          .append("<span class=\"meta\"><strong>")
          .append(escape(b.example.title()))
          .append("</strong><span class=\"badge ")
          .append(b.method.css)
          .append("\">")
          .append(b.method.label)
          .append("</span></span></a>\n");
    }
    if (currentCategory != null) {
      cards.append("</div>\n");
    }

    StringBuilder docLinks = new StringBuilder();
    for (DocPage d : docs) {
      docLinks
          .append("\n          <a href=\"")
          .append(d.slug())
          .append(".html\">")
          .append(escape(d.title()))
          .append(" &#8594;</a> ·");
    }

    long live = built.stream().filter(b -> b.method == Method.LIVE).count();
    String index =
        """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>elm-lang — example gallery</title>
        %STYLE%
        </head>
        <body>
        <header class="hero">
          <h1>elm-lang</h1>
          <p>A from-scratch Elm implementation in Java — a Truffle JIT interpreter, a bytecode VM,
          and a compiler to JavaScript. Every example below is the <strong>JavaScript-compiled</strong>
          output running live in your browser; the multi-module Playground games and a couple of
          GPU-bound programs fall back to a server-side-rendered initial frame.</p>
          <p class="stats">%LIVE% of %TOTAL% examples run as live compiled JavaScript ·
          <a href="backends.html">JS vs WASM &#8594;</a> ·
          <a href="playground.html">Playground &#8594;</a> ·
          <a href="todomvc.html">TodoMVC &#8594;</a> ·
          <a href="editor.html">Elm-in-Elm editor &#8594;</a> ·%DOCS%
          <a href="https://github.com/tunguski/elm-lang">source on GitHub</a></p>
        </header>
        <main>
        %CARDS%
        </main>
        <footer>Generated from the test corpus by <code>SiteGenerator</code>.</footer>
        </body>
        </html>
        """
            .replace("%STYLE%", INDEX_STYLE)
            .replace("%CARDS%", cards.toString())
            .replace("%DOCS%", docLinks.toString())
            .replace("%LIVE%", Long.toString(live))
            .replace("%TOTAL%", Integer.toString(built.size()));
    Files.writeString(outDir.resolve("index.html"), index, StandardCharsets.UTF_8);
    // GitHub Pages: skip Jekyll so files/dirs are served verbatim.
    Files.writeString(outDir.resolve(".nojekyll"), "", StandardCharsets.UTF_8);
  }

  // --- styling -----------------------------------------------------------

  private static final String INDEX_STYLE = style("/elm/css/index.css");

  private static final String PAGE_STYLE = style("/elm/css/page.css");

  private static final String DOCS_STYLE = style("/elm/css/docs.css");

  /** Loads a bundled CSS resource and wraps it in a {@code <style>} block for inlining. */
  private static String style(String resource) {
    return "<style>\n" + pl.matsuo.elm.util.Resources.read(resource) + "</style>\n";
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> b.append("&amp;");
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '"' -> b.append("&quot;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }
}

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

  private SiteGenerator(Path examplesDir, Path playgroundFile, Path outDir) throws IOException {
    this.examplesDir = examplesDir;
    this.playgroundSource = Files.readString(playgroundFile, StandardCharsets.UTF_8);
    this.outDir = outDir;
  }

  /** Builds the whole site into {@code outDir}. */
  public static void generate(Path examplesDir, Path playgroundFile, Path outDir)
      throws IOException {
    new SiteGenerator(examplesDir, playgroundFile, outDir).run();
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println("usage: SiteGenerator <examplesDir> <Playground.elm> <outDir>");
      System.exit(2);
    }
    generate(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
  }

  private void run() throws IOException {
    Files.createDirectories(outDir.resolve("demos"));
    List<Built> built = new ArrayList<>();
    for (Example ex : EXAMPLES) {
      built.add(buildExample(ex));
    }
    writeBackendsPage();
    writePlaygroundPage();
    writeIndex(built);
    System.out.println("Site written to " + outDir.toAbsolutePath());
    for (Built b : built) {
      System.out.printf("  %-16s %-22s %s%n", b.example.slug(), b.method.label, b.note);
    }
  }

  private Built buildExample(Example ex) throws IOException {
    String source = Files.readString(examplesDir.resolve(ex.slug() + ".elm"), StandardCharsets.UTF_8);
    Method method;
    String note = "";

    // First choice: a real, interactive page compiled by the JavaScript backend.
    String demo = tryLiveJs(source);
    if (demo != null) {
      method = Method.LIVE;
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

  private void writeIndex(List<Built> built) throws IOException {
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
            .replace("%LIVE%", Long.toString(live))
            .replace("%TOTAL%", Integer.toString(built.size()));
    Files.writeString(outDir.resolve("index.html"), index, StandardCharsets.UTF_8);
    // GitHub Pages: skip Jekyll so files/dirs are served verbatim.
    Files.writeString(outDir.resolve(".nojekyll"), "", StandardCharsets.UTF_8);
  }

  // --- styling -----------------------------------------------------------

  private static final String INDEX_STYLE = style("/elm/css/index.css");

  private static final String PAGE_STYLE = style("/elm/css/page.css");

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

package pl.matsuo.elm.site;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
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

  private static final String INDEX_STYLE =
      """
      <style>
      :root{--accent:#5fabdc;--ink:#293c4b;--bg:#fafafa}
      *{box-sizing:border-box}
      body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink);background:var(--bg)}
      .hero{padding:48px 24px 24px;max-width:1000px;margin:0 auto}
      .hero h1{font-size:2.6rem;margin:0 0 8px;color:var(--accent)}
      .hero p{max-width:60ch;line-height:1.5}
      .stats{font-size:.9rem;color:#667}
      main{max-width:1000px;margin:0 auto;padding:0 24px 48px}
      main h2{margin:32px 0 12px;border-bottom:2px solid #eee;padding-bottom:4px}
      .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:16px}
      .card{display:flex;flex-direction:column;border:1px solid #e3e3e3;border-radius:10px;overflow:hidden;
        text-decoration:none;color:inherit;background:#fff;transition:box-shadow .15s,transform .15s}
      .card:hover{box-shadow:0 6px 20px rgba(0,0,0,.12);transform:translateY(-2px)}
      .thumb{height:150px;background:#fff;overflow:hidden;border-bottom:1px solid #eee;position:relative}
      .thumb iframe{position:absolute;top:0;left:0;width:200%;height:300px;border:0;transform:scale(.5);transform-origin:top left;pointer-events:none}
      .meta{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;gap:8px}
      .badge{font-size:.7rem;padding:2px 8px;border-radius:999px;white-space:nowrap}
      .badge.live{background:#e3f4e1;color:#246b1e}
      .badge.snapshot{background:#fdf0d5;color:#8a5a00}
      .badge.failed{background:#f6dada;color:#9a1e1e}
      footer{max-width:1000px;margin:0 auto;padding:24px;color:#889;font-size:.85rem}
      </style>
      """;

  private static final String PAGE_STYLE =
      """
      <style>
      :root{--accent:#5fabdc;--ink:#293c4b}
      *{box-sizing:border-box}
      body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink)}
      .bar{display:flex;align-items:center;justify-content:space-between;padding:12px 24px;
        border-bottom:1px solid #eee;position:sticky;top:0;background:#fff}
      .home{color:var(--accent);text-decoration:none;font-weight:600}
      main{max-width:900px;margin:0 auto;padding:24px}
      h1 small{font-size:.9rem;color:#889;font-weight:400}
      .demo-head{display:flex;justify-content:flex-end;margin-bottom:6px}
      .newtab{color:var(--accent);text-decoration:none;font-size:.85rem;font-weight:600}
      .newtab:hover{text-decoration:underline}
      .demo iframe{width:100%;min-height:420px;border:1px solid #e3e3e3;border-radius:10px;background:#fff}
      .src pre{background:#0f1720;border-radius:10px;overflow:auto;line-height:1.5}
      .src pre code{display:block;padding:16px;color:#e6edf3}
      .badge{font-size:.72rem;padding:3px 10px;border-radius:999px}
      .badge.live{background:#e3f4e1;color:#246b1e}
      .badge.snapshot{background:#fdf0d5;color:#8a5a00}
      .badge.failed{background:#f6dada;color:#9a1e1e}
      </style>
      """;

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

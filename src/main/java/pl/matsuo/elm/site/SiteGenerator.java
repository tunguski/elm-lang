package pl.matsuo.elm.site;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import pl.matsuo.elm.script.ScriptRunner;
import pl.matsuo.elm.util.Resources;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.codegen.wasm.WasmCompiler;
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
    LIVE("Live JS (compiled)"),
    SNAPSHOT("Rendered snapshot"),
    FAILED("Source only");
    final String label; // the Elm gallery generator derives the badge CSS class from this label

    Method(String label) {
      this.label = label;
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

  /**
   * Writes the tab-separated manifest that is the data contract between the Java side (which compiles
   * the artifacts) and the Elm gallery generator (which owns all of the index's HTML/CSS). Typed
   * lines, one per row:
   *
   * <pre>
   * example  slug  title  category  demoPath  method
   * aux      href  label                              (backends / playground / editor / … pages)
   * doc      href  label                              (a rendered documentation page)
   * stat     live  total                              (how many demos run as live compiled JS)
   * </pre>
   */
  private void writeManifest(List<Built> built, List<DocPage> docs, boolean rts) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (Built b : built) {
      sb.append("example\t").append(b.example.slug()).append('\t').append(b.example.title())
          .append('\t').append(b.example.category()).append('\t')
          .append("demos/").append(b.example.slug()).append(".html").append('\t')
          .append(b.method.label).append('\n');
    }
    sb.append("aux\tbackends.html\tJS vs WASM\n");
    sb.append("aux\tplayground.html\tPlayground\n");
    sb.append("aux\ttodomvc.html\tTodoMVC\n");
    sb.append("aux\teditor.html\tElm-in-Elm editor\n");
    if (rts) {
      sb.append("aux\trts.html\tRTS Mini game\n");
    }
    for (DocPage d : docs) {
      sb.append("doc\t").append(d.slug()).append(".html\t").append(d.title()).append('\n');
    }
    long live = built.stream().filter(b -> b.method == Method.LIVE).count();
    sb.append("stat\t").append(live).append('\t').append(built.size()).append('\n');
    Files.writeString(outDir.resolve("manifest.tsv"), sb.toString(), StandardCharsets.UTF_8);
  }

  /**
   * Runs the Elm gallery generator ({@code Gallery.elm}) against the manifest in {@code outDir},
   * which writes {@code index.html} and {@code styles.css} — i.e. all of the index's HTML/CSS is
   * produced by Elm via the {@code Site} library, not by Java.
   */
  static void renderGalleryIndex(Path outDir) {
    Object main =
        Project.load(
                Resources.read("/elm/site/Gallery.elm"),
                Resources.read("/elm/lib/Posix.elm"),
                Resources.read("/elm/lib/Bash.elm"),
                Resources.read("/elm/lib/Site.elm"))
            .main();
    int code =
        ScriptRunner.run(
            main, List.of(outDir.toString()), new BufferedReader(new StringReader("")), System.out);
    if (code != 0) {
      throw new IllegalStateException("Elm gallery generator failed with exit code " + code);
    }
  }

  /** Copies the gallery's static stylesheets and client script from resources into {@code outDir}
   * (the Elm generator only links them). Kept as plain files so they aren't generated from Elm. */
  private void copyStaticAssets() throws IOException {
    copyResource("/elm/css/site.css", "site.css"); // the Site library's base stylesheet
    copyResource("/elm/css/gallery.css", "styles.css");
    copyResource("/elm/css/page.css", "page.css");
    copyResource("/elm/css/docs.css", "docs.css");
    copyResource("/elm/js/theme.js", "theme.js"); // shared light/dark theme + top-right toggle
  }

  private void copyResource(String resource, String name) throws IOException {
    Files.writeString(outDir.resolve(name), Resources.read(resource), StandardCharsets.UTF_8);
  }

  /**
   * The unified left-hand navigation, written once as the {@code nav.html} fragment and embedded
   * verbatim by every sub-page (the Elm-generated example wrappers and doc pages, and the static
   * backends/playground templates) so the menu is identical site-wide. The active link is marked
   * client-side by {@code nav.js} from the URL, keeping this a single static fragment.
   */
  private String navHtml(List<DocPage> docs, boolean rts) {
    StringBuilder b = new StringBuilder();
    b.append("<nav class=\"sidebar\">");
    b.append("<a class=\"brand\" href=\"index.html\">elm-lang</a>");
    b.append("<span class=\"tagline\">Elm, from scratch in Java</span>");
    b.append("<div class=\"group\"><span class=\"label\">Demos</span>");
    b.append(navLink("index.html", "Gallery"));
    b.append(navLink("backends.html", "JS vs WASM"));
    b.append(navLink("playground.html", "Playground"));
    b.append(navLink("editor.html", "Editor"));
    b.append(navLink("todomvc.html", "TodoMVC"));
    if (rts) {
      b.append(navLink("rts.html", "RTS Mini game"));
    }
    b.append("</div>");
    if (!docs.isEmpty()) {
      b.append("<div class=\"group\"><span class=\"label\">Guides</span>");
      for (DocPage d : docs) {
        b.append(navLink(d.slug() + ".html", d.title()));
      }
      b.append("</div>");
    }
    b.append("<div class=\"group\">");
    b.append(navLink("https://github.com/tunguski/elm-lang", "Source on GitHub"));
    b.append("</div>");
    b.append("</nav>");
    return b.toString();
  }

  private static String navLink(String href, String label) {
    return "<a href=\"" + escape(href) + "\">" + escape(label) + "</a>";
  }

  /** Writes the shared sidebar fragment and copies its stylesheet/behaviour script in. */
  private void writeNavAssets(String nav) throws IOException {
    Files.writeString(outDir.resolve("nav.html"), nav, StandardCharsets.UTF_8);
    copyResource("/elm/css/nav.css", "nav.css");
    copyResource("/elm/js/nav.js", "nav.js");
  }

  /**
   * Wraps a compiled, full-page app demo ({@code htmlPageProject} output: {@code <body><div
   * id="app"></div>…}) in the shared sidebar so the menu is present here too. The app keeps its
   * mount point and simply renders in the content column to the right of the nav.
   */
  private static String withSidebar(String pageHtml, String nav) {
    return pageHtml
        .replace("</head>", "<link rel=\"stylesheet\" href=\"nav.css\"></head>")
        .replace(
            "<body><div id=\"app\"></div>\n",
            "<body><div class=\"layout\">"
                + nav
                + "<div id=\"app\" class=\"content\"></div></div>\n"
                + "<script src=\"nav.js\"></script>\n<script src=\"theme.js\"></script>\n");
  }

  private void run() throws IOException {
    Files.createDirectories(outDir.resolve("demos"));
    List<Built> built = new ArrayList<>();
    for (Example ex : EXAMPLES) {
      built.add(buildExample(ex));
    }
    // Render the Markdown docs (body artifacts) and learn whether the RTS demo is present first, so
    // the shared sidebar — written once as nav.html and embedded by every sub-page — can list them.
    List<DocPage> docs = writeDocPages();
    boolean rts = rtsAvailable();
    String nav = navHtml(docs, rts);
    writeNavAssets(nav);
    writeBackendsPage(nav);
    writePlaygroundPage(nav);
    writeExampleSources();
    writeTodoMvcPage(nav);
    writeEditorPage(nav);
    if (rts) {
      writeRtsPage(nav);
    }
    // The gallery's HTML is rendered by the Elm generator from the manifest; its stylesheets and
    // client script are static resource files we copy in (the Elm side only links them).
    copyStaticAssets();
    writeManifest(built, docs, rts);
    renderGalleryIndex(outDir);
    // GitHub Pages: skip Jekyll so files/dirs are served verbatim.
    Files.writeString(outDir.resolve(".nojekyll"), "", StandardCharsets.UTF_8);
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
      // Render the Markdown body to an HTML fragment artifact; the Elm gallery generator wraps it in
      // the page chrome (header nav + footer + docs.css), like it does for the index and wrappers.
      Files.writeString(
          outDir.resolve(slug + ".bodyhtml"),
          Markdown.toHtml(rewriteDocLinks(source)),
          StandardCharsets.UTF_8);
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

  /**
   * Rewrites relative Markdown links so they work in the flat gallery: links into the repo
   * ({@code ../src/...}) become absolute GitHub URLs on the default branch (keeping their original
   * extension — they're source files, not gallery pages); a link to a sibling guide ({@code foo.md})
   * becomes {@code foo.html}. Repo links are rewritten first so the {@code .md → .html} rule (which
   * skips {@code https://} URLs) never touches a repo source file.
   */
  private static String rewriteDocLinks(String md) {
    String repo = "https://github.com/tunguski/elm-lang/blob/master/";
    return md.replaceAll("\\]\\(\\.\\./([^)]+)\\)", "](" + repo + "$1)")
        .replaceAll("\\]\\((?!https?://)([^)]+?)\\.md\\)", "]($1.html)");
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
    // The wrapper page (<slug>.html, demo + source) is now written by the Elm gallery generator from
    // the manifest and the example source under examples/; the Java side only emits the demo artifact.
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

  /** Elm snippets the WASM backend supports, evaluated live by both JS and WASM in the page. They
   * span Int, Float, String and List results, so the page shows the backends agreeing on values that
   * cross the wasm boundary as heap pointers (strings, cons-lists), not just plain numbers. */
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
          "1000000 * 1000000",
          "1.5 + 2.25",
          "7.0 / 2.0",
          "\"elm\" ++ \"-lang\"",
          "List.range 1 5",
          "List.map (\\x -> x * x) (List.range 1 4)",
          "List.reverse (List.range 1 5)");

  /** The decode strategy the page's JS uses to read a wasm {@code f()} result of this value. */
  private static String kindOf(Object v) {
    if (v instanceof Double) {
      return "float"; // returned as the i64 bit-pattern of the double
    }
    if (v instanceof String) {
      return "string"; // pointer to a heap string {i64 len, bytes…}
    }
    if (v instanceof pl.matsuo.elm.runtime.ElmList) {
      return "list"; // pointer to a cons-list (0 = [], else {i64 head, i64 tail})
    }
    return "int";
  }

  /**
   * A page that runs each numeric snippet through BOTH the JavaScript backend and the WebAssembly
   * backend live in the browser, showing the two results side by side (with the interpreter's value
   * as the expected baseline). Demonstrates the two compiled backends agreeing in-browser.
   */
  private void writeBackendsPage(String nav) throws IOException {
    StringBuilder src = new StringBuilder();
    StringBuilder rows = new StringBuilder();
    for (int i = 0; i < BACKEND_SNIPPETS.size(); i++) {
      String snip = BACKEND_SNIPPETS.get(i);
      src.append("f").append(i).append(" = ").append(snip).append("\n");
      Object value = Interpreter.eval(snip);
      String expected = Show.plain(value);
      rows.append("<tr data-expected=\"")
          .append(escape(expected))
          .append("\" data-kind=\"")
          .append(kindOf(value))
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
    // Compile through the full source pipeline (type-directed codegen + lambda-lifting), so string
    // and list results — not just numbers — are emitted; each f{i} is exported as `() -> i64`.
    String wasmB64 = Base64.getEncoder().encodeToString(WasmCompiler.moduleFromSource(src.toString()));
    String jsEval = JsCompiler.expressionsEvalScript(BACKEND_SNIPPETS);
    String perf = perfChart();

    String page =
        Resources.read("/elm/site/backends.html")
            .replace("%STYLE%", BACKENDS_STYLE)
            .replace("%NAV%", nav)
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
    "/elm/editor/Highlight.elm",
    "/elm/editor/Assist.elm",
    "/elm/editor/Share.elm",
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
  private void writeTodoMvcPage(String nav) throws IOException {
    Files.writeString(
        outDir.resolve("todomvc.html"),
        withSidebar(
            JsCompiler.htmlPageProject(
                null, pl.matsuo.elm.util.Resources.read("/elm/demos/todomvc.elm")),
            nav),
        StandardCharsets.UTF_8);
  }

  /**
   * The Ellie-style editor page: a multi-file interpreter written in Elm (the Lang/Lexer/Parser/
   * Eval/Main modules), bundled by the JS backend and running live in the browser — it fetches the
   * example files over HTTP and lets you edit them, rendering each selected file's `main`.
   */
  private void writeEditorPage(String nav) throws IOException {
    String[] sources = new String[EDITOR_MODULES.length];
    for (int i = 0; i < EDITOR_MODULES.length; i++) {
      sources[i] = pl.matsuo.elm.util.Resources.read(EDITOR_MODULES[i]);
    }
    // The editor is a full-screen, three-column IDE with its own in-app "back to gallery" link, so
    // it deliberately does NOT get the shared sidebar wrapper (it would crowd the panes). Reset the
    // body margin so its 100vh layout fills the viewport exactly (no page scrollbar).
    Files.writeString(
        outDir.resolve("editor.html"),
        JsCompiler.htmlPageProject(null, sources)
            .replace("</head>", "<style>html,body{margin:0;height:100%}</style></head>"),
        StandardCharsets.UTF_8);
  }

  /** The RTS game's frontend modules (relative to the repo root, where the gallery is generated). */
  private static final String[] RTS_MODULES = {
    "examples/rts/Model.elm",
    "examples/rts/Logic.elm",
    "examples/rts/View.elm",
    "examples/rts/Main.elm",
  };

  /**
   * Compiles the multi-module RTS Mini game (examples/rts) to a single live page that runs entirely
   * in the browser — no backend needed. Returns whether the page was written (the example may be
   * absent in some checkouts). The whole game model/logic/view is the JS-compiled output.
   */
  private static boolean rtsAvailable() {
    for (String m : RTS_MODULES) {
      if (!Files.exists(Path.of(m))) {
        return false;
      }
    }
    return true;
  }

  private void writeRtsPage(String nav) throws IOException {
    String[] sources = new String[RTS_MODULES.length];
    for (int i = 0; i < RTS_MODULES.length; i++) {
      sources[i] = Files.readString(Path.of(RTS_MODULES[i]), StandardCharsets.UTF_8);
    }
    Files.writeString(
        outDir.resolve("rts.html"),
        withSidebar(JsCompiler.htmlPageProject(null, sources), nav),
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
  private void writePlaygroundPage(String nav) throws IOException {
    String jsScript = JsCompiler.declarationsScript(PLAYGROUND_SRC);
    String wasmB64 = Base64.getEncoder().encodeToString(WasmCompiler.moduleFromSource(PLAYGROUND_SRC));
    String page =
        Resources.read("/elm/site/playground.html")
            .replace("%STYLE%", BACKENDS_STYLE)
            .replace("%NAV%", nav)
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

  // --- styling -----------------------------------------------------------

  // The wrapper-page (page.css) and docs (docs.css) stylesheets are now owned by the Elm generator.

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

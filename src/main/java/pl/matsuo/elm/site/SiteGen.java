package pl.matsuo.elm.site;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import pl.matsuo.elm.doc.ApiDocs;
import pl.matsuo.elm.doc.DocGenerator;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.util.Resources;

/**
 * Runs an Elm static-site definition written against the bundled {@code Site} library: the program
 * exposes {@code site : List Site.Page}, and each page is rendered (by {@code Site.render}, in Elm)
 * and written to disk. Optionally also generates grouped API documentation for a set of Elm files —
 * one page per module (via {@link DocGenerator}) plus an index that groups modules by purpose, the
 * index itself rendered through the same {@code Site} library.
 */
public final class SiteGen {

  private SiteGen() {}

  /**
   * Builds the example gallery in two phases: the Java {@link SiteGenerator} compiles the demo
   * artifacts (JS bundles per example) and a {@code manifest.tsv}, then the Elm {@code Gallery}
   * generator script reads that manifest and produces the gallery's HTML/CSS via the {@code Site}
   * library — the compiler/Elm-generator split. Returns the generator's exit code.
   */
  public static int generateGallery(Path examplesDir, Path playground, Path outDir, Path docsDir)
      throws IOException {
    SiteGenerator.generate(examplesDir, playground, outDir, docsDir);
    Object main =
        Project.load(
                Resources.read("/elm/site/Gallery.elm"),
                Resources.read("/elm/lib/Posix.elm"),
                Resources.read("/elm/lib/Bash.elm"),
                Resources.read("/elm/lib/Site.elm"))
            .main();
    return pl.matsuo.elm.script.ScriptRunner.run(
        main, List.of(outDir.toString()), new BufferedReader(new StringReader("")), System.out);
  }

  /** Renders a site definition's pages, plus (for any {@code apiDirs}) grouped per-module API docs,
   * and a sitemap.xml of every page (URLs prefixed with {@code baseUrl}). */
  public static int generate(String userSource, Path outDir, List<Path> apiDirs, String baseUrl)
      throws IOException {
    String siteLib = Resources.read("/elm/lib/Site.elm");
    Project project = Project.load(userSource, siteLib);
    Object render = project.value("Site", "render");

    List<String> paths = new ArrayList<>();
    for (Object pageObj : ((ElmList) project.entryValue("site")).toJava()) {
      ElmRecord page = (ElmRecord) pageObj;
      String path = (String) page.get("path");
      writePage(outDir, path, (String) Apply.apply(render, page));
      paths.add(path);
    }

    if (!apiDirs.isEmpty()) {
      generateApiDocs(apiDirs, outDir, render, paths);
    }
    writeSitemap(outDir, paths, baseUrl);
    System.out.println("Wrote " + paths.size() + " page(s) + sitemap.xml to " + outDir.toAbsolutePath());
    return 0;
  }

  /** A sitemap.xml listing every generated page (each URL is {@code baseUrl + path}). */
  private static void writeSitemap(Path outDir, List<String> paths, String baseUrl)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
    for (String path : paths) {
      sb.append("  <url><loc>").append(escapeXml(baseUrl + path)).append("</loc></url>\n");
    }
    sb.append("</urlset>\n");
    writePage(outDir, "sitemap.xml", sb.toString());
  }

  private static String escapeXml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /**
   * For every {@code .elm} file under {@code apiDirs}: writes {@code api/<Module>.html} (via the doc
   * generator) and collects it under a purpose group. Then writes {@code api/index.html} — a grouped
   * table of contents rendered through the {@code Site} library so it matches the rest of the site.
   */
  private static void generateApiDocs(
      List<Path> apiDirs, Path outDir, Object render, List<String> pathsOut) throws IOException {
    // group -> (module name -> [docPath, summary])
    Map<String, Map<String, String[]>> grouped = new TreeMap<>();
    int count = 0;
    for (Path dir : apiDirs) {
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (var walk = Files.walk(dir)) {
        for (Path file : walk.filter(p -> p.toString().endsWith(".elm")).sorted().toList()) {
          String source = Files.readString(file, StandardCharsets.UTF_8);
          try {
            ApiDocs docs = ApiDocs.of(source);
            String name = docs.moduleName();
            writePage(outDir, "api/" + name + ".html", DocGenerator.html(source));
            pathsOut.add("api/" + name + ".html");
            grouped
                .computeIfAbsent(purpose(file), g -> new TreeMap<>())
                .put(name, new String[] {name + ".html", firstLine(docs.moduleComment())});
            count++;
          } catch (RuntimeException | StackOverflowError e) {
            // Skip files that don't parse / type-check as a standalone module.
          }
        }
      }
    }
    if (count > 0) {
      writePage(outDir, "api/index.html", (String) Apply.apply(render, apiIndexPage(grouped)));
      pathsOut.add("api/index.html");
    }
  }

  /** Builds the grouped API index as a {@code Site.Page} value (so {@code Site.render} styles it). */
  private static ElmRecord apiIndexPage(Map<String, Map<String, String[]>> grouped) {
    List<Object> blocks = new ArrayList<>();
    blocks.add(block("Title", 1L, "API documentation"));
    blocks.add(block("Text", "Every Elm module in the project, grouped by purpose."));
    grouped.forEach(
        (groupName, modules) -> {
          blocks.add(block("Title", 2L, groupName));
          List<Object> rows = new ArrayList<>();
          modules.forEach(
              (name, info) -> rows.add(new ElmTuple(new Object[] {info[0], name})));
          blocks.add(block("LinkRow", ElmList.fromJava(rows)));
          modules.forEach(
              (name, info) -> {
                if (!info[1].isBlank()) {
                  blocks.add(block("Text", name + " — " + info[1]));
                }
              });
        });
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("path", "api/index.html");
    page.put("title", "API documentation");
    page.put("blocks", ElmList.fromJava(blocks));
    return new ElmRecord(page);
  }

  private static ElmData block(String ctor, Object... args) {
    return new ElmData(ctor, args);
  }

  /** A purpose label derived from a module's location (backend / frontend / scripting / examples …). */
  static String purpose(Path file) {
    String s = file.toString().replace('\\', '/');
    if (s.contains("/editor/")) {
      return "Frontend — the in-browser editor";
    }
    if (s.contains("/rts/")) {
      return "Examples — the RTS game";
    }
    if (s.contains("/demos/") || s.contains("/examples/") || s.contains("editor-examples")) {
      return "Examples";
    }
    if (s.endsWith("Server.elm")) {
      return "Backend — HTTP server";
    }
    if (s.endsWith("Posix.elm") || s.endsWith("Bash.elm")) {
      return "Scripting";
    }
    if (s.endsWith("Site.elm")) {
      return "Static site generation";
    }
    if (s.endsWith("Test.elm") || s.endsWith("Expect.elm") || s.endsWith("Fuzz.elm")) {
      return "Testing";
    }
    return "Other";
  }

  private static String firstLine(String comment) {
    if (comment == null) {
      return "";
    }
    String trimmed = comment.strip();
    int nl = trimmed.indexOf('\n');
    return (nl < 0 ? trimmed : trimmed.substring(0, nl)).strip();
  }

  private static void writePage(Path outDir, String path, String html) throws IOException {
    Path target = outDir.resolve(path);
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.writeString(target, html, StandardCharsets.UTF_8);
  }
}

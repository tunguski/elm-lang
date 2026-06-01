package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Elm static-site generator: rendering a `site : List Page` to files, and grouped API docs. */
class SiteGenTest {

  private static final String SITE =
      "module Main exposing (site)\n"
          + "import Site exposing (..)\n"
          + "site =\n"
          + "    [ page \"index.html\" \"Home\"\n"
          + "        [ h1 \"Hi\"\n"
          + "        , text \"<b>unsafe</b> & risky\"\n"
          + "        , codeBlock \"x = 1\"\n"
          + "        , links [ ( \"about.html\", \"About\" ) ]\n"
          + "        ]\n"
          + "    , page \"about.html\" \"About\" [ h1 \"About\" ]\n"
          + "    ]\n";

  @Test
  void rendersPagesToFilesWithEscaping(@TempDir Path out) throws IOException {
    SiteGen.generate(SITE, out, List.of(), "");
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("<title>Home</title>"), index.substring(0, 120));
    assertTrue(index.contains("<h1>Hi</h1>"), "heading rendered");
    assertTrue(index.contains("&lt;b&gt;unsafe&lt;/b&gt; &amp; risky"), "user text is HTML-escaped");
    assertTrue(index.contains("<pre><code>x = 1</code></pre>"), "code block");
    assertTrue(index.contains("href=\"about.html\""), "link rendered");
    assertTrue(Files.exists(out.resolve("about.html")), "second page written");
  }

  @Test
  void generatesGroupedApiDocs(@TempDir Path out) throws IOException {
    SiteGen.generate(SITE, out, List.of(Path.of("src/main/resources/elm/lib")), "");
    assertTrue(Files.exists(out.resolve("api/index.html")), "api index written");
    assertTrue(Files.exists(out.resolve("api/Posix.html")), "per-module doc page written");
    String index = Files.readString(out.resolve("api/index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("Scripting"), "modules grouped by purpose");
    assertTrue(index.contains("Posix.html") && index.contains("Bash.html"), "links the modules");
    assertTrue(index.contains("Static site generation"), "Site module grouped");
  }

  @Test
  void elmLangSiteDefinitionGenerates(@TempDir Path out) throws IOException {
    String src = Files.readString(Path.of("examples/site/ElmLang.elm"), StandardCharsets.UTF_8);
    SiteGen.generate(src, out, List.of(), "");
    assertTrue(Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8).contains("elm-lang"));
    assertTrue(Files.exists(out.resolve("features.html")), "features page");
    assertTrue(Files.exists(out.resolve("cli.html")), "cli page");
    assertTrue(Files.exists(out.resolve("examples.html")), "examples page");
  }

  @Test
  void generatedDocsLinkTheRtsGameAndItsApiDocs(@TempDir Path out) throws IOException {
    String src = Files.readString(Path.of("examples/site/ElmLang.elm"), StandardCharsets.UTF_8);
    SiteGen.generate(src, out, List.of(Path.of("examples/rts")), "");
    String examples = Files.readString(out.resolve("examples.html"), StandardCharsets.UTF_8);
    assertTrue(examples.contains("href=\"rts.html\""), "examples page links the playable game");
    assertTrue(examples.contains("href=\"api/RTS.Model.html\""), "examples page links the RTS API docs");
    assertTrue(Files.exists(out.resolve("api/RTS.Model.html")), "RTS.Model API doc generated");
    assertTrue(
        Files.readString(out.resolve("api/index.html"), StandardCharsets.UTF_8).contains("RTS"),
        "API index has the RTS group");
  }

  @Test
  void writesASitemapOfEveryPage(@TempDir Path out) throws IOException {
    SiteGen.generate(SITE, out, List.of(), "https://example.com/");
    String sitemap = Files.readString(out.resolve("sitemap.xml"), StandardCharsets.UTF_8);
    assertTrue(sitemap.contains("<loc>https://example.com/index.html</loc>"), sitemap);
    assertTrue(sitemap.contains("<loc>https://example.com/about.html</loc>"), sitemap);
    assertTrue(sitemap.contains("urlset"), "valid sitemap envelope");
  }

  @Test
  void markdownBlocksRenderToHtml(@TempDir Path out) throws IOException {
    // A page built from a Markdown string via Site.markdown.
    String src =
        "module Main exposing (site)\n"
            + "import Site exposing (..)\n"
            + "doc = \"# Title\\n\\nA paragraph.\\n\\n- one\\n- two\\n\\n```\\ncode line\\n```\"\n"
            + "site = [ page \"doc.html\" \"Doc\" (markdown doc) ]\n";
    SiteGen.generate(src, out, List.of(), "");
    String html = Files.readString(out.resolve("doc.html"), StandardCharsets.UTF_8);
    assertTrue(html.contains("<h1>Title</h1>"), html);
    assertTrue(html.contains("<p>A paragraph.</p>"), html);
    assertTrue(html.contains("<li>one</li>") && html.contains("<li>two</li>"), html);
    assertTrue(html.contains("<pre><code>code line</code></pre>"), html);
  }

  @Test
  void purposeGroupsAreDerivedFromPaths() {
    assertTrue(SiteGen.purpose(Path.of("src/main/resources/elm/lib/Server.elm")).contains("Backend"));
    assertTrue(SiteGen.purpose(Path.of("src/main/resources/elm/lib/Bash.elm")).contains("Scripting"));
    assertTrue(SiteGen.purpose(Path.of("src/main/resources/elm/editor/Eval.elm")).contains("Frontend"));
    assertTrue(SiteGen.purpose(Path.of("examples/rts/Main.elm")).contains("RTS"));
  }
}

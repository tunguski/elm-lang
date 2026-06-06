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
    SiteGen.generate(SITE, out, List.of(Path.of("src/main/elm/lib")), "");
    assertTrue(Files.exists(out.resolve("api/index.html")), "api index written");
    assertTrue(Files.exists(out.resolve("api/Posix.html")), "per-module doc page written");
    String index = Files.readString(out.resolve("api/index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("Scripting"), "modules grouped by purpose");
    assertTrue(index.contains("Posix.html") && index.contains("Bash.html"), "links the modules");
    assertTrue(index.contains("Static site generation"), "Site module grouped");
  }

  @Test
  void elmLangSiteDefinitionGenerates(@TempDir Path out) throws IOException {
    String src = Files.readString(Path.of("src/main/elm/examples/ElmLang.elm"), StandardCharsets.UTF_8);
    SiteGen.generate(src, out, List.of(), "");
    assertTrue(Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8).contains("elm-lang"));
    assertTrue(Files.exists(out.resolve("features.html")), "features page");
    assertTrue(Files.exists(out.resolve("cli.html")), "cli page");
    assertTrue(Files.exists(out.resolve("examples.html")), "examples page");
  }

  @Test
  void generatedDocsLinkTheRtsGameAndItsApiDocs(@TempDir Path out) throws IOException {
    Path rts = Path.of("projects/elm-rts/src/RTS");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.exists(rts), "projects/elm-rts not present (separate repo github.com/tunguski/elm-rts)");
    String src = Files.readString(Path.of("src/main/elm/examples/ElmLang.elm"), StandardCharsets.UTF_8);
    SiteGen.generate(src, out, List.of(rts), "");
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
  void emitsAnRssFeedOfThePages(@TempDir Path out) throws IOException {
    SiteGen.generate(SITE, out, List.of(), "https://example.com/");
    String feed = Files.readString(out.resolve("feed.xml"), StandardCharsets.UTF_8);
    assertTrue(feed.contains("<rss version=\"2.0\">"), feed);
    assertTrue(feed.contains("<link>https://example.com/about.html</link>"), feed);
    assertTrue(feed.contains("<title>About</title>"), "a page becomes a feed item");
  }

  @Test
  void autoGeneratesAnIndexWhenTheSiteHasNone(@TempDir Path out) throws IOException {
    // A site with no index.html of its own gets an auto table-of-contents index.
    String src =
        "module Main exposing (site)\nimport Site exposing (..)\n"
            + "site = [ page \"a.html\" \"Alpha\" [], page \"b.html\" \"Beta\" [] ]\n";
    SiteGen.generate(src, out, List.of(), "");
    assertTrue(Files.exists(out.resolve("index.html")), "auto index generated");
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("href=\"a.html\"") && index.contains("Alpha"), index);
    assertTrue(index.contains("href=\"b.html\"") && index.contains("Beta"), index);
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
    assertTrue(SiteGen.purpose(Path.of("src/main/elm/lib/Server.elm")).contains("Backend"));
    assertTrue(SiteGen.purpose(Path.of("src/main/elm/lib/Bash.elm")).contains("Scripting"));
    assertTrue(SiteGen.purpose(Path.of("projects/elm-editor/src/Eval.elm")).contains("Frontend"));
    assertTrue(SiteGen.purpose(Path.of("projects/elm-rts/src/RTS/Main.elm")).contains("RTS"));
  }
}

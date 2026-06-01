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
    SiteGen.generate(SITE, out, List.of());
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
    SiteGen.generate(SITE, out, List.of(Path.of("src/main/resources/elm/lib")));
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
    SiteGen.generate(src, out, List.of());
    assertTrue(Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8).contains("elm-lang"));
    assertTrue(Files.exists(out.resolve("features.html")), "features page");
    assertTrue(Files.exists(out.resolve("cli.html")), "cli page");
    assertTrue(Files.exists(out.resolve("examples.html")), "examples page");
  }

  @Test
  void purposeGroupsAreDerivedFromPaths() {
    assertTrue(SiteGen.purpose(Path.of("src/main/resources/elm/lib/Server.elm")).contains("Backend"));
    assertTrue(SiteGen.purpose(Path.of("src/main/resources/elm/lib/Bash.elm")).contains("Scripting"));
    assertTrue(SiteGen.purpose(Path.of("src/main/resources/elm/editor/Eval.elm")).contains("Frontend"));
    assertTrue(SiteGen.purpose(Path.of("examples/rts/Main.elm")).contains("RTS"));
  }
}

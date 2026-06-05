package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The compiler/Elm-generator split: SiteGenerator compiles the demo artifacts and a manifest, and
 * the Elm Gallery generator (run as a script) reads that manifest to produce ALL of the gallery's
 * HTML and CSS via the Site library.
 */
class GalleryTest {

  private static final Path EXAMPLES = Path.of("src/main/elm/examples");
  private static final Path PLAYGROUND = Path.of("src/main/elm/examples/Playground.elm");

  @Test
  void siteGeneratorWritesAManifestOfArtifacts() throws IOException {
    Path out = Files.createTempDirectory("gallery-manifest-");
    SiteGenerator.generate(EXAMPLES, PLAYGROUND, out);
    String manifest = Files.readString(out.resolve("manifest.tsv"), StandardCharsets.UTF_8);
    // Typed lines: example rows, the auxiliary-page links, and a live/total stat.
    assertTrue(manifest.contains("example\tHello\tHello\tHTML\tdemos/Hello.html\t"), manifest);
    assertTrue(manifest.contains("aux\tbackends.html\tJS vs WASM"), manifest);
    assertTrue(manifest.lines().anyMatch(l -> l.startsWith("stat\t")), manifest);
    assertTrue(Files.exists(out.resolve("demos/Hello.html")), "the demo artifact exists");
  }

  @Test
  void elmGeneratorProducesTheGalleryHtmlAndCss() throws IOException {
    Path out = Files.createTempDirectory("gallery-");
    int code = SiteGen.generateGallery(EXAMPLES, PLAYGROUND, out, null);
    assertTrue(code == 0, "the Elm gallery generator succeeded");

    // The index.html is produced by the Elm generator (from the manifest) — hero, stats with
    // auxiliary-page links, the searchable per-category card grid — and links the static assets.
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("<h1>elm-lang</h1>"), index.substring(0, Math.min(200, index.length())));
    assertTrue(index.contains("examples run as live compiled JavaScript"), "stats line rendered");
    assertTrue(index.contains("href=\"backends.html\""), "links the JS-vs-WASM page from the index");
    assertTrue(index.contains("<h2>HTML</h2>"), "examples grouped by category");
    assertTrue(index.contains("href=\"Hello.html\"") && index.contains("src=\"demos/Hello.html\""),
        "a card links its wrapper page and embeds the compiled demo as a thumbnail");
    assertTrue(index.contains(">Hello</strong>"), "the card shows the example title");
    assertTrue(index.contains("href=\"styles.css\""), "links the static stylesheet");
    assertTrue(index.contains("<script src=\"theme.js\"></script>"), "links the shared theme script");
    // The examples search bar was removed; the theme toggle is now a shared top-right button.
    assertFalse(index.contains("id=\"search\""), "no examples search box");
    assertFalse(index.contains("class=\"controls\""), "no controls bar");
    // The script/CSS are NOT inlined into the index — they're static files.
    assertFalse(index.contains("localStorage"), "the theme script is a static file, not inlined");

    // The stylesheets and theme script are static resource files copied in by the Java side.
    String css = Files.readString(out.resolve("styles.css"), StandardCharsets.UTF_8);
    assertTrue(css.contains(".card"), "gallery stylesheet copied in");
    assertTrue(css.contains("prefers-color-scheme: dark"), "dark mode honours the OS preference");
    assertTrue(css.contains("[data-theme=dark]"), "dark mode also has an explicit toggle");
    String js = Files.readString(out.resolve("theme.js"), StandardCharsets.UTF_8);
    assertTrue(js.contains("data-theme") && js.contains("theme-toggle"),
        "the shared theme behaviour + top-right toggle is in theme.js");

    // The per-example wrapper pages are Elm-generated (template) and link the static page.css.
    String wrapper = Files.readString(out.resolve("Hello.html"), StandardCharsets.UTF_8);
    assertTrue(wrapper.contains("class=\"badge live\""), wrapper.substring(0, Math.min(300, wrapper.length())));
    assertTrue(wrapper.contains("src=\"demos/Hello.html\""), "wrapper embeds the demo");
    assertTrue(wrapper.contains("language-elm") && wrapper.contains("main ="), "wrapper shows the source");
    assertTrue(wrapper.contains("href=\"page.css\""), "wrapper links the static page stylesheet");
    assertTrue(Files.exists(out.resolve("page.css")), "page.css copied in by the Java side");

    // The unified sidebar is embedded on every sub-page, with Elm + Bash highlighting.
    assertTrue(wrapper.contains("<nav class=\"sidebar\">"), "wrapper has the shared sidebar");
    assertTrue(wrapper.contains(">Gallery</a>") && wrapper.contains("href=\"editor.html\""),
        "sidebar links the gallery and the other demos");
    assertTrue(wrapper.contains("href=\"nav.css\"") && wrapper.contains("src=\"nav.js\""),
        "wrapper links the shared sidebar assets");
    assertTrue(wrapper.contains("languages/bash.min.js"), "wrapper loads the Bash highlighter too");
    assertTrue(Files.exists(out.resolve("nav.css")) && Files.exists(out.resolve("nav.js"))
            && Files.exists(out.resolve("nav.html")),
        "the shared sidebar assets are written");

    // The demo artifacts (compiled by the Java side) are still present and live.
    assertTrue(
        Files.readString(out.resolve("demos/Hello.html"), StandardCharsets.UTF_8).contains("$start"),
        "the demo is the JS-compiled bundle");
  }
}

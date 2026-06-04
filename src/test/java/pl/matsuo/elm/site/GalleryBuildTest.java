package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The example gallery produced through {@code elm build} ({@link GalleryBuild}): the bundled
 * {@code site.elm} build + {@code Gallery.elm} layout, with the Java side only staging inputs and
 * executing the Elm-defined tasks.
 */
class GalleryBuildTest {

  @Test
  void buildsTheGalleryThroughElmBuild(@TempDir Path out) throws Exception {
    ByteArrayOutputStream log = new ByteArrayOutputStream();
    int code =
        GalleryBuild.generate(
            Path.of("src/main/resources/elm/examples"),
            Path.of("docs"),
            out,
            new PrintStream(log, true, StandardCharsets.UTF_8));
    String output = log.toString(StandardCharsets.UTF_8);
    assertEquals(0, code, output);

    // index.html laid out by Gallery.elm from the manifest the build wrote.
    assertTrue(Files.exists(out.resolve("index.html")), "index written: " + output);
    String index = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
    assertTrue(index.contains("Buttons"), "index lists an example title");

    // A live, compiled demo page (compile JS produced a full htmlPage bundle).
    assertTrue(Files.exists(out.resolve("demos/hello.html")), "demo page written");
    String demo = Files.readString(out.resolve("demos/hello.html"), StandardCharsets.UTF_8);
    assertTrue(demo.contains("$start"), "demo is a live JS bundle");

    // A per-example wrapper page and a rendered guide page.
    assertTrue(Files.exists(out.resolve("buttons.html")), "wrapper page written");
    assertTrue(Files.exists(out.resolve("examples.html")), "guide page written");

    // The gallery stylesheet (gallery.css copied as styles.css) is present.
    assertTrue(Files.exists(out.resolve("styles.css")), "gallery stylesheet copied");

    // HTTP/Files examples compile to live pages too, and .nojekyll is emitted for Pages.
    assertTrue(Files.exists(out.resolve("demos/book.html")), "HTTP example demo written");
    assertTrue(Files.exists(out.resolve("demos/image-previews.html")), "Files example demo written");
    assertTrue(Files.exists(out.resolve(".nojekyll")), ".nojekyll written");

    // The shared sidebar lists the Guides group (embedded on every sub-page by Gallery.elm).
    assertTrue(Files.exists(out.resolve("nav.html")), "nav written");
    String nav = Files.readString(out.resolve("nav.html"), StandardCharsets.UTF_8);
    assertTrue(nav.contains("Guides") && nav.contains("href=\"scripting.html\""), "nav lists guides");
  }
}

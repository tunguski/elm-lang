package pl.matsuo.elm.site;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import pl.matsuo.elm.build.BuildRunner;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.util.Resources;

/**
 * The example gallery generated <b>entirely through {@code elm build}</b> — the Elm-defined
 * counterpart of the Java {@link SiteGenerator}. This class is pure glue: it stages the bundled
 * build inputs (the {@code site.elm} {@code Build.Project}, the {@code Gallery.elm} layout script,
 * the example sources, the Markdown guides and the static assets) into a work directory, then runs
 * the {@code package} phase of that build through {@link BuildRunner}. All the real work — compiling
 * each example to a live page, theming it, rendering the guides, writing the manifest and laying out
 * {@code index.html} / wrapper pages / doc pages — is expressed as Elm {@code Build.Task}s and the
 * {@code Gallery.elm} script; the Java side only stages files and executes those tasks.
 *
 * <p>Kept alongside {@link SiteGenerator} during the transition (see {@code docs/site.md}); it
 * currently covers the JS-compilable examples and the guides (the WASM/editor/Playground pages still
 * live in the Java generator).
 */
public final class GalleryBuild {

  private GalleryBuild() {}

  /** The examples {@code site.elm} compiles to live JS pages (must match its {@code examples} list). */
  static final String[] EXAMPLES = {
    "hello", "groceries", "shapes", "buttons", "text-fields",
    "forms", "numbers", "cards", "time", "clock"
  };

  /** The Markdown guides {@code site.elm} renders (must match its {@code guides} list). */
  static final String[] GUIDES = {"examples", "scripting", "server", "build", "site"};

  /** Output asset filename -> bundled resource. {@code styles.css} is the gallery stylesheet. */
  private static final Map<String, String> ASSETS = new LinkedHashMap<>();

  static {
    ASSETS.put("styles.css", "/elm/css/gallery.css");
    ASSETS.put("page.css", "/elm/css/page.css");
    ASSETS.put("docs.css", "/elm/css/docs.css");
    ASSETS.put("nav.css", "/elm/css/nav.css");
    ASSETS.put("site.css", "/elm/css/site.css");
    ASSETS.put("theme.js", "/elm/js/theme.js");
  }

  /**
   * Stages the inputs, runs the {@code site.elm} build's {@code package} phase, and copies the
   * generated gallery into {@code outDir}. {@code examplesDir} supplies the example {@code .elm}
   * sources and {@code docsDir} the guide {@code .md} files.
   */
  public static int generate(Path examplesDir, Path docsDir, Path outDir, PrintStream out)
      throws IOException {
    Path work = Files.createTempDirectory("elm-gallery-");
    write(work.resolve("site.elm"), Resources.read("/elm/site/site.elm"));
    write(work.resolve("Gallery.elm"), Resources.read("/elm/site/Gallery.elm"));

    Path examples = Files.createDirectories(work.resolve("examples"));
    for (String slug : EXAMPLES) {
      Files.copy(
          examplesDir.resolve(slug + ".elm"),
          examples.resolve(slug + ".elm"),
          StandardCopyOption.REPLACE_EXISTING);
    }

    Path docs = Files.createDirectories(work.resolve("docs"));
    for (String guide : GUIDES) {
      Files.copy(
          docsDir.resolve(guide + ".md"),
          docs.resolve(guide + ".md"),
          StandardCopyOption.REPLACE_EXISTING);
    }

    Path assets = Files.createDirectories(work.resolve("assets"));
    for (Map.Entry<String, String> e : ASSETS.entrySet()) {
      write(assets.resolve(e.getKey()), Resources.read(e.getValue()));
    }

    // Load, plan and run the package phase of the Elm-defined build (baseDir = the work dir, so the
    // build's `out/…` paths land under it).
    String buildSource = Resources.read("/elm/site/site.elm");
    String buildLib = Resources.read("/elm/lib/Build.elm");
    Project project = Project.load(buildSource, buildLib);
    Object projectValue = project.entryValue("project");
    Object planList =
        Apply.applyAll(
            project.value("Build", "plan"), new ElmData("Package", new Object[0]), projectValue);
    int code = BuildRunner.run(planList, work, false, false, out);
    if (code != 0) {
      return code;
    }

    Files.createDirectories(outDir);
    copyTree(work.resolve("out"), outDir);
    out.println("Gallery (elm build) written to " + outDir.toAbsolutePath());
    return 0;
  }

  private static void write(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  private static void copyTree(Path src, Path dst) throws IOException {
    try (var walk = Files.walk(src)) {
      for (Path p : (Iterable<Path>) walk::iterator) {
        Path target = dst.resolve(src.relativize(p).toString());
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}

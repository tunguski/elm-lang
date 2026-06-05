package pl.matsuo.elm;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.matsuo.elm.bytecode.BytecodeDisassembler;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.bytecode.BytecodeProgram;
import pl.matsuo.elm.bytecode.BytecodeReader;
import pl.matsuo.elm.bytecode.BytecodeWriter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmData;

// CLI subcommands extracted from Main.java. Package-private top-level classes registered
// as picocli subcommands on Main; they call back to Main's shared helpers (Main.readElmSource,
// Main.typeError, Main.render) and constants. See docs/file-decomposition.md.

  @Command(name = "docs", description = "Generate API docs from a local module (Markdown, or docs.json with --json), or fetch a published package's docs.json from the registry with --pkg.")
final class Docs implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "The .elm file (omit when using --pkg).")
    Path file;

    @Option(names = "--json", description = "Emit the structured docs.json API instead of Markdown.")
    boolean json;

    @Option(names = "--html", description = "Emit a self-contained, searchable HTML documentation page.")
    boolean html;

    @Option(
        names = "--project",
        description = "An elm.json (or its dir): emit the whole-package docs.json for every exposed module (publish preflight).")
    Path project;

    @Option(names = "--pkg", description = "Fetch a published package's docs.json from the registry, e.g. elm/json.")
    String pkg;

    @Option(names = "--pkg-version", description = "The package version to fetch (default: the latest published).")
    String pkgVersion;

    @Option(
        names = "--elm",
        arity = "0..1",
        fallbackValue = "https://package.elm-lang.org",
        description = "Registry base for --pkg (default: the public package.elm-lang.org).")
    String elm;

    @Override
    public Integer call() throws IOException {
      if (project != null) {
        System.out.print(pl.matsuo.elm.doc.ApiDocs.packageJson(exposedModuleSources(project)));
        return 0;
      }
      if (pkg != null) {
        pl.matsuo.elm.pkg.ElmRegistry reg =
            new pl.matsuo.elm.pkg.ElmRegistry(elm != null ? elm : "https://package.elm-lang.org");
        pl.matsuo.elm.pkg.Version version =
            pkgVersion != null ? pl.matsuo.elm.pkg.Version.parse(pkgVersion) : reg.latest(pkg);
        if (version == null) {
          System.err.println("No published versions found for " + pkg);
          return 1;
        }
        String docs = reg.docsJson(pkg, version);
        if (docs == null) {
          System.err.println("No docs.json for " + pkg + " " + version);
          return 1;
        }
        System.out.print(docs.endsWith("\n") ? docs : docs + "\n");
        return 0;
      }
      if (file == null) {
        System.err.println("Provide a .elm file, or --pkg author/name to fetch from the registry.");
        return 2;
      }
      String source = Files.readString(file);
      if (html) {
        System.out.print(pl.matsuo.elm.doc.DocGenerator.html(source));
      } else {
        System.out.print(
            json
                ? pl.matsuo.elm.doc.ApiDocs.of(source).toJson()
                : pl.matsuo.elm.doc.DocGenerator.markdown(source) + "\n");
      }
      return 0;
    }

    /** Reads a package elm.json's {@code exposed-modules} (a flat list or grouped object) and returns
     * each exposed module's source, located under the {@code source-directories} (default src/). */
    @SuppressWarnings("unchecked")
    private static List<String> exposedModuleSources(Path projectArg) throws IOException {
      Path elmJson = Files.isDirectory(projectArg) ? projectArg.resolve("elm.json") : projectArg;
      Path root = elmJson.toAbsolutePath().getParent();
      Object cfg = pl.matsuo.elm.json.JsonParse.parse(Files.readString(elmJson));
      Map<String, Object> obj = cfg instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

      List<String> dirs = new ArrayList<>();
      if (obj.get("source-directories") instanceof List<?> sd) {
        sd.forEach(d -> dirs.add(String.valueOf(d)));
      }
      if (dirs.isEmpty()) {
        dirs.add("src");
      }

      // exposed-modules is either a flat list or an object grouping lists by category.
      List<String> moduleNames = new ArrayList<>();
      Object exposed = obj.get("exposed-modules");
      if (exposed instanceof List<?> flat) {
        flat.forEach(n -> moduleNames.add(String.valueOf(n)));
      } else if (exposed instanceof Map<?, ?> groups) {
        for (Object group : groups.values()) {
          if (group instanceof List<?> names) {
            names.forEach(n -> moduleNames.add(String.valueOf(n)));
          }
        }
      }

      List<String> sources = new ArrayList<>();
      for (String name : moduleNames) {
        String rel = name.replace('.', '/') + ".elm";
        for (String dir : dirs) {
          Path candidate = root.resolve(dir).resolve(rel);
          if (Files.exists(candidate)) {
            sources.add(Files.readString(candidate));
            break;
          }
        }
      }
      return sources;
    }
  }

  @Command(name = "site", description = "Generate the static example gallery.")
final class Site implements Callable<Integer> {
    @Parameters(index = "0", description = "Examples directory.")
    Path examplesDir;

    @Parameters(index = "1", description = "Playground.elm source.")
    Path playground;

    @Parameters(index = "2", description = "Output directory.")
    Path outDir;

    @Parameters(
        index = "3",
        arity = "0..1",
        description = "Optional Markdown docs directory; each .md is rendered to an HTML page.")
    Path docsDir;

    @Override
    public Integer call() throws IOException {
      pl.matsuo.elm.site.SiteGenerator.generate(examplesDir, playground, outDir, docsDir);
      return 0;
    }
  }

  @Command(
      name = "gen-site",
      description =
          "Generate a static website from an Elm definition (`site : List Site.Page`) using the "
              + "bundled Site library. With --api, also emit grouped API docs for the given Elm dirs.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm gen-site src/main/elm/examples/ElmLang.elm out --api src/main/elm/lib --api src/main/elm/rts",
        "",
        "The program exposes `site : List Page`; each page is rendered to HTML and written under the",
        "output dir. `--api DIR` adds api/<Module>.html for every .elm file plus a grouped api index.",
      })
final class GenSite implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm site definition (its `site : List Site.Page`).")
    Path file;

    @Parameters(index = "1", description = "Output directory.")
    Path outDir;

    @Option(
        names = "--api",
        description = "A directory of .elm files to document (repeatable); grouped by purpose.")
    List<Path> apiDirs = new ArrayList<>();

    @Option(
        names = "--base-url",
        description = "URL prefix for sitemap.xml entries (default: relative paths).")
    String baseUrl = "";

    @Override
    public Integer call() throws IOException {
      return pl.matsuo.elm.site.SiteGen.generate(Main.readElmSource(file), outDir, apiDirs, baseUrl);
    }
  }

  @Command(
      name = "build",
      description =
          "Run an Elm-defined build (a `project : Build.Project`) through the Maven-style lifecycle.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm build              # run validate → compile → test → package (the default)",
        "  elm build test         # run up to the test phase",
        "  elm build clean        # remove every module's output",
        "  elm build install -f build.elm",
        "",
        "The build file exposes `project : Build.Project` (see the bundled Build library): a",
        "declarative, multi-module project run through a fixed phase lifecycle; each phase's work is",
        "a list of goals, and a goal is an ordinary `Module -> List Task` function.",
      })
final class BuildCmd implements Callable<Integer> {
    @Parameters(
        index = "0",
        arity = "0..1",
        description =
            "Phase to run up to (validate|compile|test|package|verify|install) or 'clean'. "
                + "Default: package.")
    String phase = "package";

    @Option(names = {"-f", "--file"}, description = "The build definition (default: build.elm).")
    Path file = Path.of("build.elm");

    @Option(names = "--dry-run", description = "Print the plan (phases, goals, tasks) without running it.")
    boolean dryRun;

    @Option(names = "--watch", description = "Re-run the build whenever a .elm file changes (Ctrl-C to stop).")
    boolean watch;

    @Option(names = "--incremental", description = "Skip compile/package whose output is newer than its inputs.")
    boolean incremental;

    @Option(names = "--parallel", description = "Build independent modules within a phase concurrently.")
    boolean parallel;

    @Option(names = "--init", description = "Write a starter build file (does not overwrite an existing one).")
    boolean init;

    private static final String STARTER =
        """
        module Main exposing (project)

        import Build exposing (..)


        project : Project
        project =
            Build.project "my-app" "1.0.0"
                [ module_ "app" "."
                    |> withEntry "src/Main.elm"
                    |> withOutput "build"
                ]
        """;

    @Override
    public Integer call() throws IOException, InterruptedException {
      if (init) {
        if (Files.exists(file)) {
          System.err.println(file + " already exists; not overwriting.");
          return 1;
        }
        Files.writeString(file, STARTER, StandardCharsets.UTF_8);
        System.out.println("Wrote " + file + ". Edit it, then run: elm build");
        return 0;
      }
      if (watch) {
        Path baseDir = file.toAbsolutePath().getParent();
        List<Path> watched = new ArrayList<>();
        if (baseDir != null && Files.isDirectory(baseDir)) {
          try (var w = Files.walk(baseDir)) {
            w.filter(p -> p.toString().endsWith(".elm")).forEach(watched::add);
          }
        }
        if (watched.isEmpty()) {
          watched.add(file);
        }
        pl.matsuo.elm.util.FileWatcher.watch(watched, 300, this::runOnce);
        return 0;
      }
      return runOnce();
    }

    /** Loads, plans and executes (or dry-runs) the build once, returning the exit code. */
    private int runOnce() {
      try {
        String userSource = Main.readElmSource(file);
        String lib = pl.matsuo.elm.util.Resources.read("/elm/lib/Build.elm");
        var project = pl.matsuo.elm.interp.Project.load(userSource, lib);
        Object projectValue = project.entryValue("project");
        Object planList;
        if (phase.equalsIgnoreCase("clean")) {
          planList = pl.matsuo.elm.interp.Apply.apply(project.value("Build", "cleanPlan"), projectValue);
        } else {
          Object phaseCtor = phaseConstructor(phase);
          if (phaseCtor == null) {
            System.err.println(
                "Unknown phase '" + phase + "'. Use one of: validate, compile, test, package, "
                    + "verify, install (or clean).");
            return 1;
          }
          planList =
              pl.matsuo.elm.interp.Apply.applyAll(project.value("Build", "plan"), phaseCtor, projectValue);
        }
        if (dryRun) {
          return pl.matsuo.elm.build.BuildRunner.dryRun(planList, System.out);
        }
        // Relative paths resolve against the build file's directory (like a pom.xml dir).
        Path baseDir = file.toAbsolutePath().getParent();
        return pl.matsuo.elm.build.BuildRunner.run(planList, baseDir, incremental, parallel, System.out);
      } catch (IOException e) {
        System.err.println("build error: " + e.getMessage());
        return 1;
      }
    }

    /** Maps a lower-case phase name to its {@code Build.Phase} constructor value, or null if unknown. */
    private static Object phaseConstructor(String name) {
      String ctor =
          switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "validate" -> "Validate";
            case "compile" -> "Compile";
            case "test" -> "Test";
            case "package" -> "Package";
            case "verify" -> "Verify";
            case "install" -> "Install";
            default -> null;
          };
      return ctor == null ? null : new pl.matsuo.elm.runtime.ElmData(ctor, new Object[0]);
    }
  }

  @Command(
      name = "gallery",
      description =
          "Generate the example gallery as artifacts + Elm: SiteGenerator compiles the demo bundles "
              + "and a manifest, then the bundled Elm Gallery generator produces the HTML/CSS.")
final class Gallery implements Callable<Integer> {
    @Parameters(index = "0", description = "Examples directory.")
    Path examplesDir;

    @Parameters(index = "1", description = "Playground.elm source.")
    Path playground;

    @Parameters(index = "2", description = "Output directory.")
    Path outDir;

    @Parameters(index = "3", arity = "0..1", description = "Optional Markdown docs directory.")
    Path docsDir;

    @Override
    public Integer call() throws IOException {
      return pl.matsuo.elm.site.SiteGen.generateGallery(examplesDir, playground, outDir, docsDir);
    }
  }

  @Command(
      name = "gallery-elm",
      description =
          "Generate the example gallery entirely through `elm build`: the bundled Site.elm "
              + "Build.Project + Gallery.elm layout, run via the build lifecycle (Java only stages "
              + "inputs and executes the Elm-defined tasks). Transitional alongside `gallery`.")
final class GalleryElm implements Callable<Integer> {
    @Parameters(index = "0", description = "Examples directory (source .elm files).")
    Path examplesDir;

    @Parameters(index = "1", description = "Markdown docs directory (the guides).")
    Path docsDir;

    @Parameters(index = "2", description = "Output directory.")
    Path outDir;

    @Override
    public Integer call() throws IOException {
      return pl.matsuo.elm.site.GalleryBuild.generate(examplesDir, docsDir, outDir, System.out);
    }
  }

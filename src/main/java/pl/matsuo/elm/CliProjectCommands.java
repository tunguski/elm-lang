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

  @Command(
      name = "server",
      description = "Serve HTTP using an Elm `handle : Server.Request -> Server.Response` app.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm server SimpleServerShowcase --port 8080   # bundled demo (or pass a path)",
        "  curl localhost:8080/ping          # -> pong",
        "",
        "The app exposes `handle : Request -> Response` (a pure function). The Server module",
        "provides Request/Response and helpers: text, html, json, response, notFound.",
      })
final class Serve implements Callable<Integer> {
    @Parameters(index = "0", description = "The server .elm file (its `handle`).")
    Path file;

    @Option(
        names = {"-p", "--port"},
        description = "Port to listen on (default 8080).")
    int port = 8080;

    @Option(
        names = "--static",
        description = "Serve text files (HTML/CSS/JS/JSON/SVG) from this directory before the handler.")
    Path staticDir;

    @Option(
        names = "--db",
        description =
            "JDBC URL for the `Db` library (e.g. jdbc:h2:./data or jdbc:h2:mem:app;DB_CLOSE_DELAY=-1);"
                + " a `handle : Request -> Db Response` then runs queries against it.")
    String db;

    @Override
    public Integer call() throws IOException, InterruptedException {
      String userSource = Main.readElmSource(file);
      String lib = pl.matsuo.elm.util.Resources.read("/elm/lib/Server.elm");
      String dbLib = pl.matsuo.elm.util.Resources.read("/elm/lib/Db.elm");
      String backendLib = pl.matsuo.elm.util.Resources.read("/elm/lib/Backend.elm");
      var project = pl.matsuo.elm.interp.Project.load(userSource, lib, dbLib, backendLib);
      // A stateful app exposes `main : Server.Program model`; a stateless one exposes `handle`.
      Object main = null;
      try {
        main = project.entryValue("main");
      } catch (RuntimeException ignored) {
        // no `main` -> stateless `handle`
      }
      com.sun.net.httpserver.HttpServer server;
      if (main instanceof pl.matsuo.elm.runtime.ElmRecord r && r.has("onRequest")) {
        server = pl.matsuo.elm.server.ServerRunner.startStateful(r, port, staticDir);
      } else {
        server =
            pl.matsuo.elm.server.ServerRunner.start(
                project.entryValue("handle"), port, staticDir, db);
      }
      String dbNote = db == null ? "" : " (db: " + db + ")";
      System.out.println(
          "Serving " + file + " on http://localhost:" + port + dbNote + " (Ctrl-C to stop)");
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      Thread.currentThread().join(); // block until the process is interrupted
      return 0;
    }
  }

  @Command(
      name = "reactor",
      description = "Dev server: compile a project's .elm files on the fly and hot-reload on change.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm reactor              # serve the current directory on http://localhost:8000",
        "  elm reactor src --port 8080",
        "",
        "Open http://localhost:<port>/ for the module list, or /<Module>.elm to run it. Editing any",
        ".elm file reloads the open page automatically.",
      })
final class Reactor implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "Project directory (default: current).")
    Path dir = Path.of(".");

    @Option(
        names = {"-p", "--port"},
        description = "Port to listen on (default 8000).")
    int port = 8000;

    @Override
    public Integer call() throws IOException, InterruptedException {
      var server = pl.matsuo.elm.server.ReactorServer.start(dir, port);
      System.out.println("Reactor serving " + dir + " on http://localhost:" + port + " (Ctrl-C to stop)");
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      Thread.currentThread().join();
      return 0;
    }
  }

  @Command(
      name = "test",
      description = "Run Elm tests: every top-level Test value (see the bundled Test/Expect modules).",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm test tests/MathTests.elm",
        "",
        "Expose `suite : Test` built with `test`/`describe`/`fuzz` and `Expect.*` (e.g.",
        "  suite = describe \"math\" [ test \"adds\" (\\_ -> Expect.equal 4 (2 + 2)) ]).",
        "Property tests draw random inputs from `Fuzz` and report a counterexample:",
        "  fuzz Fuzz.int \"self-inverts\" (\\n -> Expect.equal n (negate (negate n))).",
        "",
        "  elm test tests/MathTests.elm --filter addition --fuzz 1000 --seed 42",
      })
final class TestCmd implements Callable<Integer> {
    @Parameters(arity = "1..*", description = "Test .elm files.")
    List<Path> files;

    @Option(names = "--fuzz", description = "Random inputs per fuzz test (default 100).")
    int fuzz = 100;

    @Option(
        names = "--seed",
        description = "Seed for fuzz inputs, for reproducible runs (default: fixed).")
    long seed = 0x5eedL;

    @Option(
        names = "--filter",
        description = "Only run tests whose full path contains this text (case-insensitive).")
    String filter;

    @Option(
        names = "--coverage",
        description = "After the run, report which top-level functions of the test files were exercised.")
    boolean coverage;

    @Option(
        names = "--coverage-html",
        description = "Write an HTML coverage report to this file (implies --coverage).")
    Path coverageHtml;

    @Option(
        names = "--report",
        description = "Output format: console (default), tap, junit, json.")
    String report = "console";

    @Option(names = "--watch", description = "Re-run the suite whenever a test file changes (Ctrl-C to stop).")
    boolean watch;

    @Option(
        names = "--project",
        description =
            "elm.json (or its directory) whose source-directories' modules the tests import. "
                + "Auto-detected by searching upward from the test files if omitted; pass --project= "
                + "(empty) to disable and load only the listed files.")
    Path project;

    @Option(
        names = "--timeout",
        description = "Per-test wall-clock limit in milliseconds; a test exceeding it fails (0 = no limit).")
    long timeout;

    @Override
    public Integer call() throws IOException, InterruptedException {
      if (watch) {
        pl.matsuo.elm.util.FileWatcher.watch(files, 300, () -> runOnce(false));
        return 0;
      }
      return runOnce(true);
    }

    private int runOnce(boolean returnCode) {
      try {
        List<String> sources = new ArrayList<>();
        for (Path p : files) {
          sources.add(Main.readElmSource(p));
        }
        boolean trackCoverage = coverage || coverageHtml != null;
        var result =
            pl.matsuo.elm.test.TestRunner.run(
                sources,
                projectSources(sources),
                new pl.matsuo.elm.test.TestRunner.Options(
                    fuzz, seed, filter, trackCoverage, report, timeout));
        System.out.print(result.report());
        if (coverageHtml != null && result.coverageHtml() != null) {
          Files.writeString(coverageHtml, result.coverageHtml(), java.nio.charset.StandardCharsets.UTF_8);
          System.out.println("Wrote HTML coverage to " + coverageHtml);
        }
        return result.exitCode();
      } catch (IOException e) {
        System.out.println("Error: " + e.getMessage());
        return 1;
      }
    }

    /**
     * The application's own modules (its {@code source-directories}), so a test's
     * {@code import Queue as Q} resolves Q's <em>values</em>, not just its types. Without this the
     * runner only links the files passed on the command line and an imported value is "Unbound".
     * The project is given by {@code --project} or auto-detected by searching upward from the test
     * files for an {@code elm.json}; modules a test file itself defines are excluded (no duplicates).
     */
    private List<String> projectSources(List<String> testSources) {
      Path elmJson = (project != null) ? project : findElmJson();
      if (elmJson == null || elmJson.toString().isEmpty()) {
        return List.of();
      }
      try {
        java.util.Set<String> testModules = new java.util.HashSet<>();
        for (String src : testSources) {
          testModules.add(pl.matsuo.elm.parser.Parser.parseModule(src).name());
        }
        List<String> srcs =
            new ArrayList<>(pl.matsuo.elm.project.ProjectLoader.loadSources(elmJson));
        srcs.removeIf(s -> testModules.contains(pl.matsuo.elm.parser.Parser.parseModule(s).name()));
        return srcs;
      } catch (RuntimeException e) {
        System.err.println("Warning: could not load project sources (" + e.getMessage() + ")");
        return List.of();
      }
    }

    /** Searches upward from the first test file's directory for an {@code elm.json}. */
    private Path findElmJson() {
      if (files == null || files.isEmpty()) {
        return null;
      }
      Path dir = files.get(0).toAbsolutePath().getParent();
      while (dir != null) {
        Path candidate = dir.resolve("elm.json");
        if (Files.exists(candidate)) {
          return candidate;
        }
        dir = dir.getParent();
      }
      return null;
    }
  }

  @Command(
      name = "coverage",
      description = "Run a definition and report which top-level definitions were executed.")
final class CoverageCmd implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm file.")
    Path file;

    @Option(names = "--value", description = "Top-level name to run (default: main).")
    String value = "main";

    @Override
    public Integer call() throws IOException {
      Interpreter interp = Interpreter.loadWithCoverage(Files.readString(file));
      Main.render(interp.value(value)); // force and Main.render, exercising the program
      System.out.print(interp.coverageReport());
      return 0;
    }
  }

  @Command(
      name = "lint",
      description = "Lint Elm source: leftover Debug.* calls and unused top-level definitions.")
final class Lint implements Callable<Integer> {
    @Parameters(arity = "1..*", description = "One or more .elm files.")
    List<Path> files;

    @Override
    public Integer call() throws IOException {
      int total = 0;
      for (Path p : files) {
        var findings = pl.matsuo.elm.lint.Linter.lint(Files.readString(p));
        for (var f : findings) {
          System.out.println(p + ":" + f);
        }
        total += findings.size();
      }
      System.out.println(total == 0 ? "No issues." : total + " issue(s)");
      return total == 0 ? 0 : 1;
    }
  }

  @Command(name = "check", description = "Type-check a module, or a multi-module project.")
final class Check implements Callable<Integer> {
    @Parameters(arity = "0..*", description = "One .elm file, or several for a project (omit with --project).")
    List<Path> files;

    @Option(
        names = "--project",
        description =
            "An elm.json (or its dir): load the project's modules + dependencies into scope, so"
                + " cross-module references resolve (like `make`). Without it, only the given file(s)"
                + " are in scope and a sibling's name reads as unknown.")
    Path project;

    @Option(names = "--registry", description = "Package cache for dependency sources (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Override
    public Integer call() throws IOException {
      List<String> sources = new ArrayList<>();
      if (project != null) {
        Path reg = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
        sources.addAll(pl.matsuo.elm.project.ProjectLoader.loadSources(project, reg));
      }
      if (files != null) {
        for (Path p : files) {
          if (p.toString().endsWith(".elm")) {
            sources.add(Files.readString(p));
          }
        }
      }
      if (sources.isEmpty()) {
        System.out.println(
            pl.matsuo.elm.util.Ansi.error("check:", "no .elm sources — pass a file or --project."));
        return 1;
      }
      try {
        var types =
            sources.size() > 1
                ? pl.matsuo.elm.types.TypeChecker.checkProject(sources.toArray(new String[0]))
                : pl.matsuo.elm.types.TypeChecker.checkModule(sources.get(0));
        types.forEach((name, type) -> System.out.println(name + " : " + type));
        return 0;
      } catch (ElmTypeError e) {
        System.out.println(pl.matsuo.elm.util.Ansi.error("Type error:", e.getMessage()));
        return 1;
      }
    }
  }

  @Command(
      name = "vendor",
      description =
          "Resolve source dependencies from elm.vendored.json: clone/update each repo under git-deps/"
              + " at its pinned revision. `make`/`test`/`check --project` do this automatically; run"
              + " it explicitly to pre-fetch (e.g. to cache git-deps/ in CI).",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm vendor                 # resolve for the project in the current directory",
        "  elm vendor --project path  # resolve for a specific elm.json / project dir",
        "  elm vendor --frozen        # fail if any dep isn't already present at its ref (no fetch)",
      })
final class Vendor implements Callable<Integer> {
    @Option(names = "--project", description = "The elm.json (or its dir); defaults to the current directory.")
    java.nio.file.Path project;

    @Option(names = "--frozen", description = "Require every dep to already be checked out at its ref; do not fetch.")
    boolean frozen;

    @Override
    public Integer call() {
      java.nio.file.Path p = project != null ? project : java.nio.file.Path.of(".");
      java.nio.file.Path root =
          java.nio.file.Files.isDirectory(p) ? p : p.toAbsolutePath().getParent();
      var deps = pl.matsuo.elm.project.VendoredDeps.read(root);
      if (deps.isEmpty()) {
        System.out.println("No " + pl.matsuo.elm.project.VendoredDeps.MANIFEST + " — nothing to vendor.");
        return 0;
      }
      pl.matsuo.elm.project.VendoredDeps.resolve(root, frozen);
      for (var d : deps) {
        System.out.println("Vendored " + d.name() + " @ " + d.ref() + " (" + d.repo() + ")");
      }
      System.out.println("Resolved " + deps.size() + " dependency(ies) into " + root.resolve("git-deps"));
      return 0;
    }
  }

  @Command(name = "repl", description = "Read-eval-print loop.")
final class Repl implements Callable<Integer> {
    @Option(
        names = "--project",
        description = "An elm.json (or its dir): preload the project's modules + dependencies into scope.")
    Path project;

    @Option(names = "--registry", description = "Package cache for dependency sources (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Override
    public Integer call() throws IOException {
      List<String> sources = List.of();
      if (project != null) {
        Path reg = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
        sources = pl.matsuo.elm.project.ProjectLoader.loadSources(project, reg);
      }
      pl.matsuo.elm.repl.Repl.loop(
          new InputStreamReader(System.in),
          System.out,
          sources,
          pl.matsuo.elm.repl.Repl.defaultHistoryFile());
      return 0;
    }
  }

  @Command(name = "lsp", description = "Run the language server over stdio.")
final class Lsp implements Callable<Integer> {
    @Override
    public Integer call() throws IOException {
      new pl.matsuo.elm.lsp.LspServer().serve(System.in, System.out);
      return 0;
    }
  }

  @Command(
      name = "format",
      description = "Format Elm source (elm-format style).",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm format Main.elm              # print formatted source to stdout",
        "  elm format Main.elm --write      # rewrite the file in place",
        "  elm format elm.json --project --check   # CI gate: non-zero if anything is unformatted",
      })
final class Format implements Callable<Integer> {
    @Parameters(index = "0", description = "A .elm file, or an elm.json/dir with --project.")
    Path path;

    @Option(names = "--write", description = "Rewrite files in place.")
    boolean write;

    @Option(names = "--project", description = "Format every module in the project.")
    boolean project;

    @Option(
        names = "--check",
        description = "Don't write; exit non-zero if any file isn't already formatted (for CI).")
    boolean check;

    @Override
    public Integer call() throws IOException {
      if (project) {
        int n = 0;
        int unformatted = 0;
        for (Path p : pl.matsuo.elm.fmt.Formatter.projectFiles(path)) {
          String original = Files.readString(p);
          String out = pl.matsuo.elm.fmt.Formatter.format(original);
          if (check && !out.equals(original)) {
            System.out.println(p + " is not formatted");
            unformatted++;
          } else if (write) {
            Files.writeString(p, out);
          }
          n++;
        }
        if (check) {
          System.out.println(
              unformatted == 0
                  ? "All " + n + " file(s) are formatted"
                  : unformatted + " of " + n + " file(s) need formatting");
          return unformatted == 0 ? 0 : 1;
        }
        System.out.println((write ? "Formatted " : "Checked ") + n + " file(s)");
      } else {
        String original = Files.readString(path);
        String out = pl.matsuo.elm.fmt.Formatter.format(original);
        if (check) {
          boolean formatted = out.equals(original);
          System.out.println(path + (formatted ? " is formatted" : " is not formatted"));
          return formatted ? 0 : 1;
        }
        if (write) {
          Files.writeString(path, out);
          System.out.println("Formatted " + path);
        } else {
          System.out.print(out);
        }
      }
      return 0;
    }
  }

  @Command(name = "project", description = "Load an elm.json project and check or run it.")
final class Project implements Callable<Integer> {
    @Parameters(index = "0", description = "elm.json file or project directory.")
    Path path;

    @Parameters(index = "1", arity = "0..1", description = "check (default) or run.")
    String mode = "check";

    @Option(
        names = "--registry",
        description = "Package cache for dependency sources (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Override
    public Integer call() {
      List<String> sources =
          registry != null
              ? pl.matsuo.elm.project.ProjectLoader.loadSources(path, registry)
              : pl.matsuo.elm.project.ProjectLoader.loadSources(path);
      if (mode.equals("run")) {
        System.out.println(Main.render(pl.matsuo.elm.interp.Project.load(sources.toArray(new String[0])).main(), true));
      } else {
        try {
          pl.matsuo.elm.types.TypeChecker.checkProject(sources.toArray(new String[0]))
              .forEach((name, type) -> System.out.println(name + " : " + type));
        } catch (ElmTypeError e) {
          System.out.println(pl.matsuo.elm.util.Ansi.error("Type error:", e.getMessage()));
          return 1;
        }
      }
      return 0;
    }
  }

  @Command(name = "bench", description = "Benchmark the backends on a recursive workload (or --check against a baseline).")
final class Bench implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "fib(n) input (default 30).")
    long fibN = 30;

    @Option(names = "--check", description = "Compare warm timings against the baseline and fail on a regression.")
    boolean check;

    @Option(names = "--update", description = "Write the current warm timings as the new baseline.")
    boolean update;

    @Option(names = "--baseline", description = "Baseline JSON file (default bench-baseline.json).")
    Path baseline = Path.of("bench-baseline.json");

    @Option(names = "--tolerance", description = "Allowed slowdown before a regression fails, as a fraction (default 0.5 = 50%%).")
    double tolerance = 0.5;

    @Override
    public Integer call() throws IOException {
      if (!check && !update) {
        System.out.print(pl.matsuo.elm.bench.Benchmark.run(fibN, 50, 50));
        return 0;
      }
      var current = pl.matsuo.elm.bench.Benchmark.warm(fibN, 50, 50);
      if (update || !Files.exists(baseline)) {
        Files.writeString(baseline, pl.matsuo.elm.bench.Benchmark.baselineJson(current),
            java.nio.charset.StandardCharsets.UTF_8);
        System.out.println((update ? "Updated" : "Created") + " baseline " + baseline);
        return 0;
      }
      var base = pl.matsuo.elm.bench.Benchmark.parseBaseline(
          Files.readString(baseline, java.nio.charset.StandardCharsets.UTF_8));
      var regressions = pl.matsuo.elm.bench.Benchmark.checkRegressions(base, current, tolerance);
      if (regressions.isEmpty()) {
        System.out.println("No performance regressions (within " + (int) (tolerance * 100) + "% of baseline).");
        return 0;
      }
      System.err.println("Performance regressions:");
      regressions.forEach(r -> System.err.println("  - " + r));
      return 1;
    }
  }

  @Command(
      name = "doctest",
      description = "Run the executable examples (expr followed by a `-->` line) in modules' doc comments.")
final class Doctest implements Callable<Integer> {
    @Parameters(arity = "1..*", description = "Elm files whose doc comments' examples to verify.")
    List<Path> files;

    @Override
    public Integer call() throws IOException {
      int passed = 0;
      int failed = 0;
      for (Path f : files) {
        var result = pl.matsuo.elm.doc.DocTest.run(java.nio.file.Files.readString(f));
        passed += result.passed();
        failed += result.failed();
        for (String fail : result.failures()) {
          System.out.println(f.getFileName() + ": " + fail);
        }
      }
      System.out.println(passed + " passed, " + failed + " failed");
      return failed == 0 ? 0 : 1;
    }
  }

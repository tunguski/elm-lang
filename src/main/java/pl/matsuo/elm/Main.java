package pl.matsuo.elm;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.html.HtmlRender;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmData;

/**
 * Command-line entry point, built on <a href="https://picocli.info">picocli</a>: {@code elm} with
 * subcommands {@code run/js/make/eval/script/server/test/check/repl/lsp/format/project/bench/site/
 * init/install}. Use {@code --help} on any command for usage.
 */
@Command(
    name = "elm",
    mixinStandardHelpOptions = true,
    version = "elm-lang 0.1",
    description = "An Elm interpreter/compiler (Truffle JIT, bytecode VM, JS and WASM backends).",
    footerHeading = "%nExamples:%n",
    footer = {
      "  elm eval \"List.map ((*) 2) [1,2,3]\"      Evaluate an expression",
      "  elm run Main.elm                          Run a program (Html renders to HTML)",
      "  elm make Main.elm -o index.html --optimize  Build a deployable HTML page",
      "  elm check src/Main.elm src/Lib.elm        Type-check a module or project",
      "  elm format Main.elm --write               Reformat in place (--check to verify)",
      "  elm script wordcount.elm a.txt b.txt      Run an Elm file as a CLI script",
      "  elm server simple-server-showcase --port 8080  Serve HTTP from a `handle` function",
      "  elm repl                                  Start the interactive REPL",
      "  elm init                                  Scaffold elm.json + src/",
      "",
      "Bundled examples live under src/main/resources/elm/demos/ — e.g.:",
      "  elm script src/main/resources/elm/demos/wordcount.elm README.md",
      "  elm server simple-server-showcase   # then: curl localhost:8080/ping",
      "",
      "Run 'elm <command> --help' for command-specific options and examples.",
    },
    subcommands = {
      Main.Run.class,
      Main.Js.class,
      Main.Make.class,
      Main.Eval.class,
      Main.Script.class,
      Main.Serve.class,
      Main.Bundle.class,
      Main.TestCmd.class,
      Main.Lint.class,
      Main.Docs.class,
      Main.CoverageCmd.class,
      Main.Check.class,
      Main.Repl.class,
      Main.Lsp.class,
      Main.Format.class,
      Main.Project.class,
      Main.Bench.class,
      Main.Wasm.class,
      Main.Site.class,
      Main.GenSite.class,
      Main.Gallery.class,
      Main.Init.class,
      Main.Install.class,
      Main.Verify.class,
      Main.Diff.class,
      Main.Bump.class,
      Main.Publish.class,
      Main.Reactor.class,
      CommandLine.HelpCommand.class,
    })
public final class Main implements Runnable {

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /** Runs the CLI and returns the process exit code. Split out so tests can assert on it. */
  static int run(String... args) {
    return new CommandLine(new Main())
        .setExecutionExceptionHandler(
            (ex, cmd, parseResult) -> {
              // Turn expected failures into clean one-line messages instead of stack traces.
              String msg =
                  switch (ex) {
                    case java.nio.file.NoSuchFileException e ->
                        "File not found: " + e.getFile();
                    case java.io.UncheckedIOException e
                            when e.getCause() instanceof java.nio.file.NoSuchFileException nf ->
                        "File not found: " + nf.getFile();
                    case java.io.UncheckedIOException e -> "I/O error: " + e.getCause().getMessage();
                    case ElmTypeError e -> "Type error: " + e.getMessage();
                    case pl.matsuo.elm.error.ElmRuntimeError e -> "Runtime error: " + e.getMessage();
                    case pl.matsuo.elm.error.ElmSyntaxError e -> "Syntax error: " + e.getMessage();
                    case IOException e -> "I/O error: " + e.getMessage();
                    default -> ex.getClass().getSimpleName() + ": " + ex.getMessage();
                  };
              cmd.getErr().println(cmd.getColorScheme().errorText(msg));
              return 1;
            })
        .execute(args);
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out); // no subcommand -> show help
  }

  @Command(
      name = "run",
      description = "Evaluate a definition and print it (Html/programs as HTML).",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm run Main.elm                  # type-checks, then renders `main`",
        "  elm run Main.elm --value answer   # evaluate a specific top-level value",
        "  elm run Main.elm --no-check       # skip the type check and just run",
      })
  static final class Run implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm file.")
    Path file;

    @Option(names = "--backend", description = "interp (default) or bytecode.")
    String backend = "interp";

    @Option(names = "--value", description = "Top-level name to evaluate (default: main).")
    String value = "main";

    @Option(names = "--no-check", description = "Skip the type check before running.")
    boolean noCheck;

    @Option(names = "--strict", hidden = true, description = "Deprecated: type-checking is the default.")
    boolean strict;

    @Option(names = "--watch", description = "Re-run whenever the file changes (Ctrl-C to stop).")
    boolean watch;

    @Override
    public Integer call() throws IOException, InterruptedException {
      if (watch) {
        pl.matsuo.elm.util.FileWatcher.watch(List.of(file), 300, this::runOnce);
        return 0;
      }
      return runOnce();
    }

    private int runOnce() {
      try {
        String source = Files.readString(file);
        if (!noCheck && typeError(source) instanceof String msg) {
          System.out.println(pl.matsuo.elm.util.Ansi.error("Type error:", msg));
          return 1;
        }
        Object v =
            backend.equals("bytecode")
                ? BytecodeInterpreter.load(source).value(value)
                : Interpreter.load(source).value(value);
        System.out.println(render(v, true)); // live: a Browser program's init effects actually run
        return 0;
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }

  @Command(name = "js", description = "Compile a module to JavaScript.")
  static final class Js implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm file.")
    Path file;

    @Option(names = "--min", description = "Minify the output.")
    boolean min;

    @Option(names = "--map", description = "Emit an inline Source Map v3.")
    boolean map;

    @Override
    public Integer call() throws IOException {
      String source = Files.readString(file);
      if (map) {
        System.out.println(JsCompiler.moduleProgramWithSourceMap(source, file.toString()).code());
      } else {
        String js = JsCompiler.moduleProgram(source);
        System.out.println(min ? JsCompiler.minify(js) : js);
      }
      return 0;
    }
  }

  @Command(
      name = "make",
      description = "Compile a program to a deployable artifact (HTML page, or a .js bundle).",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm make Main.elm                       # type-checks, then -> index.html",
        "  elm make Main.elm -o app.js --optimize  # -> minified JS bundle",
        "  elm make Main.elm --no-check            # skip the type check",
      })
  static final class Make implements Callable<Integer> {
    @Parameters(arity = "0..*", description = "The .elm entry file (plus any sibling modules); omit with --project.")
    List<Path> files;

    @Option(
        names = {"-o", "--output"},
        description = "Output file. A .js name emits the bundle; anything else an HTML page. "
            + "(default: index.html)")
    String output = "index.html";

    @Option(names = "--optimize", description = "Minify the generated JavaScript.")
    boolean optimize;

    @Option(
        names = "--cache",
        description =
            "Incremental build-cache directory: reuse per-module compiled output, recompiling only "
                + "changed modules.")
    Path cache;

    @Option(names = "--no-check", description = "Skip the type check before compiling.")
    boolean noCheck;

    @Option(names = "--watch", description = "Recompile whenever an input file changes (Ctrl-C to stop).")
    boolean watch;

    @Option(
        names = "--project",
        description =
            "An elm.json (or its directory): compile the project's local modules plus its installed "
                + "dependencies' sources from the package cache, instead of explicit files.")
    Path project;

    @Option(
        names = "--registry",
        description = "Package cache for dependency sources (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Override
    public Integer call() throws IOException, InterruptedException {
      if (watch) {
        pl.matsuo.elm.util.FileWatcher.watch(files == null ? List.of() : files, 300, this::makeOnce);
        return 0;
      }
      return makeOnce();
    }

    private int makeOnce() {
      try {
        List<String> sources = new ArrayList<>();
        if (project != null) {
          // Local modules + installed dependency package sources (so `import`ed packages compile in).
          sources.addAll(
              registry != null
                  ? pl.matsuo.elm.project.ProjectLoader.loadSources(project, registry)
                  : pl.matsuo.elm.project.ProjectLoader.loadSources(project));
        }
        if (files != null) {
          for (Path p : files) {
            sources.add(Files.readString(p));
          }
        }
        String[] arr = sources.toArray(new String[0]);
        if (!noCheck && typeError(arr) instanceof String msg) {
          System.out.println(pl.matsuo.elm.util.Ansi.error("Type error:", msg));
          return 1;
        }
        String bundle =
            cache != null
                ? JsCompiler.appBundleProjectCached(cache, arr)
                : JsCompiler.appBundleProject(arr);
        String js = optimize ? JsCompiler.optimize(bundle) : bundle;
        String artifact =
            output.endsWith(".js")
                ? js
                : "<!doctype html>\n<html>\n<head><meta charset=\"utf-8\"><title>Elm</title></head>\n"
                    + "<body>\n<div id=\"app\"></div>\n<script>\n" + js + "\n</script>\n</body>\n</html>\n";
        Files.writeString(Path.of(output), artifact);
        System.out.println("Wrote " + output + " (" + artifact.length() + " bytes)");
        if (optimize) {
          int saved = bundle.length() - js.length();
          int pct = bundle.length() == 0 ? 0 : saved * 100 / bundle.length();
          System.out.println("Optimized JS: " + bundle.length() + " -> " + js.length() + " bytes (-" + pct + "%)");
        }
        return 0;
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }

  @Command(
      name = "wasm",
      description = "Compile a project to a WebAssembly binary (linear-memory backend; multi-module).",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm wasm Main.elm Util.elm -o app.wasm   # compile a multi-module program",
        "  elm wasm --project .                     # compile an elm.json project (deps included)"
      })
  static final class Wasm implements Callable<Integer> {
    @Parameters(arity = "0..*", description = "The .elm entry file plus any other modules it imports; omit with --project.")
    List<Path> files;

    @Option(names = {"-o", "--output"}, description = "Output .wasm path (default out.wasm).")
    String output = "out.wasm";

    @Option(names = "--project", description = "An elm.json project dir (pulls in local + installed-dependency sources).")
    Path project;

    @Option(names = "--registry", description = "Package cache for dependency sources (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Override
    public Integer call() throws IOException {
      List<String> sources = new ArrayList<>();
      if (project != null) {
        sources.addAll(
            registry != null
                ? pl.matsuo.elm.project.ProjectLoader.loadSources(project, registry)
                : pl.matsuo.elm.project.ProjectLoader.loadSources(project));
      }
      if (files != null) {
        for (Path p : files) {
          sources.add(Files.readString(p));
        }
      }
      if (sources.isEmpty()) {
        System.err.println("Provide a .elm file (plus any imported modules), or --project.");
        return 2;
      }
      byte[] wasm = pl.matsuo.elm.codegen.wasm.WasmCompiler.moduleFromSources(sources);
      Files.write(Path.of(output), wasm);
      System.out.println("Wrote " + output + " (" + wasm.length + " bytes)");
      return 0;
    }
  }

  @Command(
      name = "eval",
      description = "Evaluate a single expression.",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm eval \"1 + 2 * 3\"",
        "  elm eval \"List.range 1 4\" --backend bytecode",
      })
  static final class Eval implements Callable<Integer> {
    @Parameters(index = "0", description = "The expression.")
    String expression;

    @Option(names = "--backend", description = "interp (default) or bytecode.")
    String backend = "interp";

    @Override
    public Integer call() {
      Object v = backend.equals("bytecode") ? BytecodeInterpreter.eval(expression) : Interpreter.eval(expression);
      System.out.println(Show.plain(v));
      return 0;
    }
  }

  @Command(
      name = "script",
      description = "Run an Elm file as a POSIX-style command-line script (JIT). See the Posix module.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm script wordcount README.md     # 'wordcount' is the bundled demo (or pass a path)",
        "",
        "The script's `main : Posix.Io` describes effects in continuation-passing style:",
        "print, readLine, readFile, writeFile, getArgs, exit, done (see the bundled Posix module).",
        "The Bash module adds structured shell commands: ls, find, grep, wc, mkdir, rm, cp, mv, env.",
      })
  static final class Script implements Callable<Integer> {
    @Parameters(index = "0", description = "The script .elm file (its `main : Posix.Io`).")
    Path file;

    @Parameters(index = "1..*", arity = "0..*", description = "Arguments passed to the script.")
    List<String> scriptArgs = new ArrayList<>();

    @Override
    public Integer call() throws IOException {
      String userSource = readElmSource(file);
      String posix = pl.matsuo.elm.util.Resources.read("/elm/lib/Posix.elm");
      String bash = pl.matsuo.elm.util.Resources.read("/elm/lib/Bash.elm");
      Object main = pl.matsuo.elm.interp.Project.load(userSource, posix, bash).main();
      return pl.matsuo.elm.script.ScriptRunner.run(
          main,
          scriptArgs == null ? List.of() : scriptArgs,
          new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
          System.out);
    }
  }

  @Command(
      name = "server",
      description = "Serve HTTP using an Elm `handle : Server.Request -> Server.Response` app.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm server simple-server-showcase --port 8080   # bundled demo (or pass a path)",
        "  curl localhost:8080/ping          # -> pong",
        "",
        "The app exposes `handle : Request -> Response` (a pure function). The Server module",
        "provides Request/Response and helpers: text, html, json, response, notFound.",
      })
  static final class Serve implements Callable<Integer> {
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

    @Override
    public Integer call() throws IOException, InterruptedException {
      String userSource = readElmSource(file);
      String lib = pl.matsuo.elm.util.Resources.read("/elm/lib/Server.elm");
      var project = pl.matsuo.elm.interp.Project.load(userSource, lib);
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
        server = pl.matsuo.elm.server.ServerRunner.start(project.entryValue("handle"), port, staticDir);
      }
      System.out.println("Serving " + file + " on http://localhost:" + port + " (Ctrl-C to stop)");
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      Thread.currentThread().join(); // block until the process is interrupted
      return 0;
    }
  }

  @Command(
      name = "bundle",
      description =
          "Compile a script or server into a standalone, self-contained executable JAR (the Elm "
              + "source and the interpreter embedded) that runs anywhere with `java -jar`, no project "
              + "files needed. With --native, also attempts a GraalVM native binary.",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm bundle script wordcount.elm -o wc    # wc.jar  -> java -jar wc.jar README.md",
        "  elm bundle server my-api.elm --port 8080 # my-api.jar -> java -jar my-api.jar",
        "  elm bundle script wordcount.elm --native  # also try a native binary (needs GraalVM)",
        "",
        "The JAR is the reliable standalone artifact. Native compilation is best-effort: it needs a",
        "GraalVM native-image and isn't possible from inside an already-compiled elm binary.",
      })
  static final class Bundle implements Callable<Integer> {
    @Parameters(index = "0", description = "What to bundle: 'script' or 'server'.")
    String kind;

    @Parameters(index = "1", description = "The .elm file (a script's `main`, or a server's `handle`/`main`).")
    Path file;

    @Option(
        names = {"-o", "--output"},
        description = "Output base path (default: the .elm file's base name).")
    Path output;

    @Option(names = "--port", description = "Port baked into a bundled server (default 8080).")
    int port = 8080;

    @Option(
        names = "--native",
        description = "Also compile a native binary with GraalVM native-image (best-effort).")
    boolean nativeBinary;

    @Override
    public Integer call() throws IOException, InterruptedException {
      if (!kind.equals("script") && !kind.equals("server")) {
        System.err.println("bundle expects 'script' or 'server', got: " + kind);
        return 2;
      }
      String source = readElmSource(file);
      String base =
          output != null ? output.toString() : file.getFileName().toString().replaceFirst("\\.elm$", "");
      Path jar = Path.of(base + ".jar");
      pl.matsuo.elm.bundle.Bundler.buildJar(source, kind, port, jar);
      System.out.println("Wrote executable JAR " + jar + " — run it with: java -jar " + jar);
      if (!nativeBinary) {
        return 0;
      }
      if (pl.matsuo.elm.bundle.Bundler.runningAsNativeImage()) {
        System.err.println(
            "--native is unavailable from inside a compiled elm binary; run it on a GraalVM JDK.");
        return 1;
      }
      var nativeImage = pl.matsuo.elm.bundle.Bundler.findNativeImage();
      if (nativeImage.isEmpty()) {
        System.err.println(
            "GraalVM native-image was not found (set GRAALVM_HOME or put it on PATH); the JAR is ready.");
        return 1;
      }
      var classpath = pl.matsuo.elm.bundle.Bundler.currentClasspath();
      if (pl.matsuo.elm.bundle.Bundler.classpathIsSingleJar(classpath)) {
        System.err.println(
            "Native compilation needs the individual (modular) dependency jars, but this `elm` is "
                + "running from a single shaded jar. Run `elm bundle --native` from the build "
                + "classpath (e.g. via Maven), or use the executable JAR above. The reliable native "
                + "path for the elm tool itself is `./mvnw -Pnative package`.");
        return 1;
      }
      boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
      Path binary = Path.of(base + (windows ? ".exe" : ""));
      Path embedJar = Path.of(base + "-embed.jar");
      pl.matsuo.elm.bundle.Bundler.buildEmbedJar(source, kind, port, embedJar);
      System.out.println("Compiling " + binary + " with native-image (this can take a few minutes)…");
      int code =
          pl.matsuo.elm.bundle.Bundler.compileNative(nativeImage.get(), classpath, embedJar, binary);
      Files.deleteIfExists(embedJar);
      if (code == 0) {
        System.out.println("Wrote native binary " + binary);
      } else {
        System.err.println(
            "native-image failed (exit "
                + code
                + "); the executable JAR "
                + jar
                + " is still usable with java -jar.");
      }
      return code;
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
  static final class TestCmd implements Callable<Integer> {
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
        names = "--report",
        description = "Output format: console (default), tap, junit, json.")
    String report = "console";

    @Option(names = "--watch", description = "Re-run the suite whenever a test file changes (Ctrl-C to stop).")
    boolean watch;

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
          sources.add(readElmSource(p));
        }
        var result =
            pl.matsuo.elm.test.TestRunner.run(
                sources, new pl.matsuo.elm.test.TestRunner.Options(fuzz, seed, filter, coverage, report));
        System.out.print(result.report());
        return result.exitCode();
      } catch (IOException e) {
        System.out.println("Error: " + e.getMessage());
        return 1;
      }
    }
  }

  @Command(
      name = "coverage",
      description = "Run a definition and report which top-level definitions were executed.")
  static final class CoverageCmd implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm file.")
    Path file;

    @Option(names = "--value", description = "Top-level name to run (default: main).")
    String value = "main";

    @Override
    public Integer call() throws IOException {
      Interpreter interp = Interpreter.loadWithCoverage(Files.readString(file));
      render(interp.value(value)); // force and render, exercising the program
      System.out.print(interp.coverageReport());
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
  static final class Reactor implements Callable<Integer> {
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

  @Command(name = "docs", description = "Generate API docs from a local module (Markdown, or docs.json with --json), or fetch a published package's docs.json from the registry with --pkg.")
  static final class Docs implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "The .elm file (omit when using --pkg).")
    Path file;

    @Option(names = "--json", description = "Emit the structured docs.json API instead of Markdown.")
    boolean json;

    @Option(names = "--html", description = "Emit a self-contained, searchable HTML documentation page.")
    boolean html;

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
  }

  @Command(
      name = "diff",
      description = "Compare two versions of a module's public API and report the semver magnitude.",
      footerHeading = "%nExample:%n",
      footer = {"  elm diff old/Main.elm new/Main.elm   # -> MAJOR/MINOR/PATCH and the changes"})
  static final class Diff implements Callable<Integer> {
    @Parameters(index = "0", description = "The old (baseline) .elm file.")
    Path oldFile;

    @Parameters(index = "1", description = "The new .elm file.")
    Path newFile;

    @Override
    public Integer call() throws IOException {
      var diff =
          pl.matsuo.elm.pkg.ApiDiff.compare(
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(oldFile)),
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(newFile)));
      System.out.println(diff.magnitude() + " change");
      if (diff.changes().isEmpty()) {
        System.out.println("  (no public API changes)");
      } else {
        diff.changes().forEach(c -> System.out.println("  " + c));
      }
      return 0;
    }
  }

  @Command(
      name = "bump",
      description = "Propose the next version from the API change since a baseline module.",
      footerHeading = "%nExample:%n",
      footer = {"  elm bump old/Main.elm new/Main.elm 1.2.0   # -> magnitude + the next version"})
  static final class Bump implements Callable<Integer> {
    @Parameters(index = "0", description = "The old (baseline) .elm file.")
    Path oldFile;

    @Parameters(index = "1", description = "The new .elm file.")
    Path newFile;

    @Parameters(index = "2", arity = "0..1", description = "Current version (default 1.0.0).")
    String current = "1.0.0";

    @Override
    public Integer call() throws IOException {
      var diff =
          pl.matsuo.elm.pkg.ApiDiff.compare(
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(oldFile)),
              pl.matsuo.elm.doc.ApiDocs.of(Files.readString(newFile)));
      var next =
          pl.matsuo.elm.pkg.ApiDiff.bump(pl.matsuo.elm.pkg.Version.parse(current), diff.magnitude());
      System.out.println(diff.magnitude() + " change: " + current + " -> " + next);
      return 0;
    }
  }

  @Command(
      name = "publish",
      description = "Dry-run publish checks: type-check, generate docs, and (with --bump-from) the next version.",
      footerHeading = "%nExample:%n",
      footer = {"  elm publish src/Main.elm --bump-from prev/Main.elm --from-version 1.2.0"})
  static final class Publish implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm module to publish.")
    Path file;

    @Option(names = "--bump-from", description = "A baseline .elm of the previously published API; reports the semver bump.")
    Path bumpFrom;

    @Option(names = "--from-version", description = "Current published version, used with --bump-from (default 1.0.0).")
    String fromVersion = "1.0.0";

    @Override
    public Integer call() throws IOException {
      String source = Files.readString(file);
      try {
        pl.matsuo.elm.types.TypeChecker.checkModule(source);
      } catch (ElmTypeError e) {
        System.out.println("x type error: " + e.getMessage());
        return 1;
      }
      System.out.println("ok type-checks");
      var api = pl.matsuo.elm.doc.ApiDocs.of(source);
      int entries = api.values().size() + api.unions().size() + api.aliases().size();
      System.out.println("ok docs.json: " + entries + " exposed entr" + (entries == 1 ? "y" : "ies"));
      if (bumpFrom != null) {
        var diff =
            pl.matsuo.elm.pkg.ApiDiff.compare(
                pl.matsuo.elm.doc.ApiDocs.of(Files.readString(bumpFrom)), api);
        var next =
            pl.matsuo.elm.pkg.ApiDiff.bump(pl.matsuo.elm.pkg.Version.parse(fromVersion), diff.magnitude());
        System.out.println("-> " + diff.magnitude() + " change: " + fromVersion + " -> " + next);
      }
      System.out.println("Dry run OK - ready to publish.");
      return 0;
    }
  }

  @Command(
      name = "lint",
      description = "Lint Elm source: leftover Debug.* calls and unused top-level definitions.")
  static final class Lint implements Callable<Integer> {
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
  static final class Check implements Callable<Integer> {
    @Parameters(arity = "1..*", description = "One .elm file, or several for a project.")
    List<Path> files;

    @Override
    public Integer call() throws IOException {
      List<String> sources = new ArrayList<>();
      for (Path p : files) {
        if (p.toString().endsWith(".elm")) {
          sources.add(Files.readString(p));
        }
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

  @Command(name = "repl", description = "Read-eval-print loop.")
  static final class Repl implements Callable<Integer> {
    @Override
    public Integer call() throws IOException {
      pl.matsuo.elm.repl.Repl.loop(new InputStreamReader(System.in), System.out);
      return 0;
    }
  }

  @Command(name = "lsp", description = "Run the language server over stdio.")
  static final class Lsp implements Callable<Integer> {
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
  static final class Format implements Callable<Integer> {
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
  static final class Project implements Callable<Integer> {
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
        System.out.println(render(pl.matsuo.elm.interp.Project.load(sources.toArray(new String[0])).main(), true));
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

  @Command(name = "bench", description = "Benchmark the backends on a recursive workload.")
  static final class Bench implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "fib(n) input (default 30).")
    long fibN = 30;

    @Override
    public Integer call() {
      System.out.print(pl.matsuo.elm.bench.Benchmark.run(fibN, 50, 50));
      return 0;
    }
  }

  @Command(name = "site", description = "Generate the static example gallery.")
  static final class Site implements Callable<Integer> {
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
        "  elm gen-site examples/site/ElmLang.elm out --api src/main/resources/elm/lib --api examples",
        "",
        "The program exposes `site : List Page`; each page is rendered to HTML and written under the",
        "output dir. `--api DIR` adds api/<Module>.html for every .elm file plus a grouped api index.",
      })
  static final class GenSite implements Callable<Integer> {
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
      return pl.matsuo.elm.site.SiteGen.generate(readElmSource(file), outDir, apiDirs, baseUrl);
    }
  }

  @Command(
      name = "gallery",
      description =
          "Generate the example gallery as artifacts + Elm: SiteGenerator compiles the demo bundles "
              + "and a manifest, then the bundled Elm Gallery generator produces the HTML/CSS.")
  static final class Gallery implements Callable<Integer> {
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

  @Command(name = "init", description = "Initialise an Elm project (elm.json + src/).")
  static final class Init implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", description = "Target directory (default: current).")
    Path dir = Path.of(".");

    @Override
    public Integer call() throws IOException {
      Path elmJson = dir.resolve("elm.json");
      if (Files.exists(elmJson)) {
        System.out.println("elm.json already exists — nothing to do.");
        return 0;
      }
      Files.createDirectories(dir.resolve("src"));
      Files.writeString(elmJson, ELM_JSON, StandardCharsets.UTF_8);
      System.out.println("Created " + elmJson + " and " + dir.resolve("src") + "/");
      return 0;
    }
  }

  @Command(
      name = "install",
      description = "Add a package to elm.json and re-solve dependencies (against a local registry).",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm install acme/strings                          # from the local cache",
        "  elm install acme/strings --from http://host/reg   # solve + download into the cache",
        "",
        "The cache is laid out as <root>/<author>/<name>/<version>/{elm.json, src/…} and defaults",
        "to $ELM_REGISTRY, else ~/.elm/registry. The solver pins a compatible version of every",
        "package in the transitive closure (constraints like \"1.0.0 <= v < 2.0.0\"). With --from it",
        "solves against, and downloads sources from, a remote registry; `project`/`check`/`run`",
        "then compile and run the installed package's modules alongside your own.",
      })
  static final class Install implements Callable<Integer> {
    @Parameters(index = "0", description = "Package to install, as author/name (e.g. elm/regex).")
    String pkg;

    @Option(
        names = "--registry",
        description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(
        names = {"-d", "--dir"},
        description = "Project directory containing elm.json (default: current directory).")
    Path dir = Path.of(".");

    @Option(
        names = "--from",
        description =
            "Remote registry base URL to solve against and download sources from into the cache "
                + "(the simple static-file protocol: versions.txt, files.txt, per-version elm.json).")
    String from;

    @Option(
        names = "--elm",
        arity = "0..1",
        fallbackValue = "https://package.elm-lang.org",
        description =
            "Use a package.elm-lang.org-style registry (all-packages + endpoint.json + zipball). "
                + "With no value, the public registry; pass a URL to point elsewhere.")
    String elm;

    @Override
    public Integer call() throws IOException {
      Path registryRoot = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      // Solve against: the public-style Elm registry (--elm), the simple remote (--from), else cache.
      pl.matsuo.elm.pkg.Registry reg;
      if (elm != null) {
        reg = new pl.matsuo.elm.pkg.ElmRegistry(elm);
      } else if (from != null) {
        reg = new pl.matsuo.elm.pkg.HttpRegistry(from);
      } else {
        reg = new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot);
      }
      try {
        var result = pl.matsuo.elm.pkg.Installer.install(dir, pkg, reg);
        if (result.alreadyPresent()) {
          System.out.println(pkg + " is already a direct dependency (" + result.installed() + ").");
          return 0;
        }
        if (elm != null || from != null) {
          // Download every resolved (non-bundled) package's sources into the cache so the project
          // loader and compiler can use them.
          var all = new java.util.TreeMap<>(result.indirect());
          all.putAll(result.direct());
          for (var dep : all.entrySet()) {
            if (!pl.matsuo.elm.project.ProjectLoader.BUNDLED.contains(dep.getKey())) {
              if (elm != null) {
                new pl.matsuo.elm.pkg.ElmPackageFetcher(elm).fetch(dep.getKey(), dep.getValue(), registryRoot);
              } else {
                new pl.matsuo.elm.pkg.PackageFetcher(from).fetch(dep.getKey(), dep.getValue(), registryRoot);
              }
            }
          }
        }
        System.out.println("Installed " + pkg + " " + result.installed() + ".");
        System.out.println(
            "Dependencies: " + result.direct().size() + " direct, "
                + result.indirect().size() + " indirect.");
        return 0;
      } catch (pl.matsuo.elm.pkg.Solver.Unsolvable | IllegalStateException e) {
        System.err.println("Install failed: " + e.getMessage());
        return 1;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        System.err.println("Install interrupted while downloading.");
        return 1;
      }
    }
  }

  @Command(
      name = "verify",
      description = "Check elm.lock against elm.json and the registry (reproducible, tamper-evident).",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm verify                     # verify the lockfile in the current project",
        "  elm verify -d path/to/project  # against an explicit registry with --registry",
        "",
        "Confirms every locked package still resolves to the same integrity hash and that elm.json's",
        "pinned versions match the lockfile. Exits non-zero (listing the problems) on any mismatch.",
      })
  static final class Verify implements Callable<Integer> {
    @Option(
        names = "--registry",
        description = "Package-cache directory (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(
        names = {"-d", "--dir"},
        description = "Project directory containing elm.json/elm.lock (default: current directory).")
    Path dir = Path.of(".");

    @Override
    public Integer call() throws IOException {
      Path registryRoot = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
      pl.matsuo.elm.pkg.Registry reg = new pl.matsuo.elm.pkg.DirectoryRegistry(registryRoot);
      var problems = pl.matsuo.elm.pkg.Lockfile.verifyProject(dir, reg);
      if (problems.isEmpty()) {
        System.out.println("elm.lock verified: all dependencies match and check out.");
        return 0;
      }
      System.err.println("Lockfile verification failed:");
      problems.forEach(p -> System.err.println("  - " + p));
      return 1;
    }
  }

  /** A standard elm.json for an application project, like `elm init` produces. */
  private static final String ELM_JSON =
      """
      {
          "type": "application",
          "source-directories": [
              "src"
          ],
          "elm-version": "0.19.1",
          "dependencies": {
              "direct": {
                  "elm/browser": "1.0.2",
                  "elm/core": "1.0.5",
                  "elm/html": "1.0.0"
              },
              "indirect": {
                  "elm/json": "1.1.3",
                  "elm/time": "1.0.0",
                  "elm/url": "1.0.0",
                  "elm/virtual-dom": "1.0.3"
              }
          },
          "test-dependencies": {
              "direct": {},
              "indirect": {}
          }
      }
      """;

  /**
   * Reads an Elm source file, falling back to a bundled demo when the path isn't on disk: the file's
   * base name (with or without {@code .elm}) is looked up under {@code /elm/demos/}. So {@code elm
   * script wordcount} and {@code elm script wordcount.elm} both run the bundled example, while a real
   * file on disk always takes precedence. A genuinely missing file still yields a clean error.
   */
  static String readElmSource(Path file) throws IOException {
    if (Files.exists(file)) {
      return Files.readString(file);
    }
    String name = file.getFileName().toString();
    if (!name.endsWith(".elm")) {
      name = name + ".elm";
    }
    try (var in = Main.class.getResourceAsStream("/elm/demos/" + name)) {
      if (in != null) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    throw new java.nio.file.NoSuchFileException(file.toString());
  }

  /**
   * Type-checks the given source(s) and returns the Elm-style error message, or {@code null} if it
   * type-checks. A checker <em>limitation</em> (any non-{@link ElmTypeError}, e.g. an unsupported
   * builtin or an inference edge case) is treated as non-fatal — it returns {@code null} so a valid
   * program the checker can't fully analyze still runs/compiles. Only a real type error blocks.
   */
  static String typeError(String... sources) {
    try {
      if (sources.length > 1) {
        pl.matsuo.elm.types.TypeChecker.checkProject(sources);
      } else {
        pl.matsuo.elm.types.TypeChecker.checkModule(sources[0]);
      }
      return null;
    } catch (ElmTypeError e) {
      return e.getMessage();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  /** Renders a value: Browser programs and Html nodes become HTML, everything else uses Show. */
  static String render(Object value) {
    return render(value, false);
  }

  /**
   * Renders a value. When {@code live}, a Browser program's initial commands run with real effects
   * (an {@code Http.get} actually fetches; {@code Random} is non-deterministic) — for {@code run} and
   * {@code project run}, so effectful programs work outside the browser.
   */
  static String render(Object value, boolean live) {
    if (value instanceof ElmData d) {
      switch (d.ctor()) {
        case "$Sandbox", "$Element", "$Document" -> {
          return (live ? Tea.startLive(value) : Tea.start(value)).html();
        }
        case "$Node", "$Text" -> {
          return HtmlRender.render(value);
        }
        default -> {}
      }
    }
    return Show.plain(value);
  }
}

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
      name = "run",
      description = "Evaluate a definition and print it (Html/programs as HTML).",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm run Main.elm                  # type-checks, then renders `main`",
        "  elm run Main.elm --value answer   # evaluate a specific top-level value",
        "  elm run Main.elm --no-check       # skip the type check and just run",
      })
final class Run implements Callable<Integer> {
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
        if (!noCheck && Main.typeError(source) instanceof String msg) {
          System.out.println(pl.matsuo.elm.util.Ansi.error("Type error:", msg));
          return 1;
        }
        Object v;
        if (backend.equals("bytecode")) {
          v = BytecodeInterpreter.load(source).value(value);
        } else {
          // If the program imports pure bundled libraries (List.Extra, …), run it as a project so
          // those imports resolve; otherwise keep the fast single-module path.
          java.util.List<String> resolved =
              pl.matsuo.elm.interp.BundledLibs.resolve(java.util.List.of(source));
          v =
              resolved.size() > 1
                  ? pl.matsuo.elm.interp.Project.load(resolved.toArray(new String[0]))
                      .value(pl.matsuo.elm.interp.BundledLibs.moduleNameOf(source), value)
                  : Interpreter.load(source).value(value);
        }
        System.out.println(Main.render(v, true)); // live: a Browser program's init effects actually run
        return 0;
      } catch (IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
  }

  @Command(name = "js", description = "Compile a module to JavaScript.")
final class Js implements Callable<Integer> {
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
        System.out.println(min ? pl.matsuo.elm.codegen.js.JsOptimizer.minify(js) : js);
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
final class Make implements Callable<Integer> {
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

    @Option(
        names = "--frozen",
        description = "Fail (instead of warning) if elm.lock is missing or disagrees with elm.json.")
    boolean frozen;

    @Option(
        names = "--split",
        description =
            "Code-split a chunk: --split NAME=Module1,Module2 (repeatable). Those modules and their "
                + "private deps move to a `chunk.NAME.js` written beside the output; the app loads it "
                + "on demand with `Chunk.load \"NAME\"`. The base only references the chunk through the "
                + "dynamic loader, so its code is deferred until first use.")
    Map<String, String> split;

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
          Path reg = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
          sources.addAll(pl.matsuo.elm.project.ProjectLoader.loadSources(project, reg, frozen));
        }
        if (files != null) {
          for (Path p : files) {
            sources.add(Files.readString(p));
          }
        }
        String[] arr = sources.toArray(new String[0]);
        if (!noCheck && Main.typeError(arr) instanceof String msg) {
          System.out.println(pl.matsuo.elm.util.Ansi.error("Type error:", msg));
          return 1;
        }
        if (split != null && !split.isEmpty()) {
          return makeSplit(arr);
        }
        String bundle =
            cache != null
                ? JsCompiler.appBundleProjectCached(cache, arr)
                : JsCompiler.appBundleProject(arr);
        String js = optimize ? pl.matsuo.elm.codegen.js.JsOptimizer.optimize(bundle) : bundle;
        String artifact =
            output.endsWith(".js")
                ? js
                : """
                  <!doctype html>
                  <html>
                  <head><meta charset="utf-8"><title>Elm</title></head>
                  <body>
                  <div id="app"></div>
                  <script>
                  __JS__
                  </script>
                  </body>
                  </html>
                  """.replace("__JS__", js);
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

    /** A code-split build: write the base artifact plus one {@code chunk.NAME.js} beside it. */
    private int makeSplit(String[] arr) throws IOException {
      java.util.Map<String, java.util.Set<String>> chunkModules = new java.util.LinkedHashMap<>();
      split.forEach(
          (name, mods) ->
              chunkModules.put(
                  name, new java.util.LinkedHashSet<>(java.util.Arrays.asList(mods.split(",")))));
      var files = JsCompiler.appBundleSplitFiles(chunkModules, arr);
      // treeShake can't see the dynamic $a("tag","name") references, so for split builds the trimming
      // is pruneKernel (base only) + minify, never treeShake.
      java.util.function.UnaryOperator<String> trim =
          optimize
              ? pl.matsuo.elm.codegen.js.JsOptimizer::minify
              : java.util.function.UnaryOperator.identity();
      String baseJs =
          optimize ? pl.matsuo.elm.codegen.js.JsOptimizer.pruneKernel(files.base()) : files.base();
      baseJs = trim.apply(baseJs);
      Path outPath = Path.of(output).toAbsolutePath();
      Path dir = outPath.getParent();
      for (var e : files.chunks().entrySet()) {
        String chunkJs = trim.apply(e.getValue());
        Path cp = dir.resolve("chunk." + e.getKey() + ".js");
        Files.writeString(cp, chunkJs);
        boolean empty = !e.getValue().contains("var _$");
        System.out.println(
            "Wrote " + cp + " (" + chunkJs.length() + " bytes)"
                + (empty
                    ? "  [empty — the base references this chunk directly; route the call through"
                        + " Chunk.load \"" + e.getKey() + "\" so it can be deferred]"
                    : ""));
      }
      String artifact =
          output.endsWith(".js")
              ? baseJs
              : """
                <!doctype html>
                <html>
                <head><meta charset="utf-8"><title>Elm</title></head>
                <body>
                <div id="app"></div>
                <script>
                __JS__
                </script>
                </body>
                </html>
                """.replace("__JS__", baseJs);
      Files.writeString(Path.of(output), artifact);
      System.out.println("Wrote " + output + " (base, " + artifact.length() + " bytes)");
      return 0;
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
final class Wasm implements Callable<Integer> {
    @Parameters(arity = "0..*", description = "The .elm entry file plus any other modules it imports; omit with --project.")
    List<Path> files;

    @Option(names = {"-o", "--output"}, description = "Output .wasm path (default out.wasm).")
    String output = "out.wasm";

    @Option(names = "--project", description = "An elm.json project dir (pulls in local + installed-dependency sources).")
    Path project;

    @Option(names = "--registry", description = "Package cache for dependency sources (default: $ELM_REGISTRY or ~/.elm/registry).")
    Path registry;

    @Option(names = "--frozen", description = "Fail (instead of warning) if elm.lock is missing or disagrees with elm.json.")
    boolean frozen;

    @Override
    public Integer call() throws IOException {
      List<String> sources = new ArrayList<>();
      if (project != null) {
        Path reg = registry != null ? registry : pl.matsuo.elm.pkg.Installer.defaultRegistryRoot();
        sources.addAll(pl.matsuo.elm.project.ProjectLoader.loadSources(project, reg, frozen));
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
final class Eval implements Callable<Integer> {
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
      name = "bytecode",
      description =
          "Compile a module to a portable .elmbc bytecode artifact, or run a value from one. The "
              + "bytecode is executed by the pure-Java VM, so it runs anywhere — including Android's ART.",
      footerHeading = "%nExamples:%n",
      footer = {
        "  elm bytecode Main.elm -o main.elmbc      Compile to a portable artifact",
        "  elm bytecode main.elmbc --value main     Run a value straight from the artifact",
      })
final class Bytecode implements Callable<Integer> {
    @Parameters(
        arity = "1..*",
        description =
            "A .elm module to compile (plus any sibling modules it needs; first is primary), or a "
                + "single .elmbc artifact to run.")
    List<Path> files;

    @Option(names = {"-o", "--output"}, description = "Write the compiled .elmbc artifact to this path.")
    Path output;

    @Option(names = "--value", description = "Top-level name to evaluate and print (default: main).")
    String value = "main";

    @Option(
        names = "--disassemble",
        description = "Print a human-readable instruction listing instead of running.")
    boolean disassemble;

    @Override
    public Integer call() throws IOException {
      if (files.size() == 1 && files.get(0).toString().endsWith(".elmbc")) {
        // A portable artifact — no source, no recompilation.
        BytecodeProgram program = BytecodeReader.fromBytes(Files.readAllBytes(files.get(0)));
        if (disassemble) {
          System.out.println(BytecodeDisassembler.disassemble(program));
        } else {
          System.out.println(Show.plain(BytecodeInterpreter.fromProgram(program).value(value)));
        }
        return 0;
      }
      List<String> sources = new ArrayList<>();
      for (Path f : files) {
        sources.add(Files.readString(f));
      }
      BytecodeInterpreter compiled = BytecodeInterpreter.loadAll(sources);
      if (disassemble) {
        System.out.println(BytecodeDisassembler.disassemble(compiled.toProgram()));
      } else if (output != null) {
        Files.write(output, BytecodeWriter.toBytes(compiled.toProgram()));
        System.out.println("Wrote " + output);
      } else {
        // No output requested: compile and evaluate, so the command is useful on its own.
        System.out.println(Show.plain(compiled.value(value)));
      }
      return 0;
    }
  }

  @Command(
      name = "script",
      description = "Run an Elm file as a POSIX-style command-line script (JIT). See the Posix module.",
      footerHeading = "%nExample:%n",
      footer = {
        "  elm script WordCount README.md     # 'wordcount' is the bundled demo (or pass a path)",
        "",
        "The script's `main : Posix.Io` describes effects in continuation-passing style:",
        "print, readLine, readFile, writeFile, getArgs, exit, done (see the bundled Posix module).",
        "The Bash module adds structured shell commands: ls, find, grep, wc, mkdir, rm, cp, mv, env.",
      })
final class Script implements Callable<Integer> {
    @Parameters(index = "0", description = "The script .elm file (its `main : Posix.Io`).")
    Path file;

    @Parameters(index = "1..*", arity = "0..*", description = "Arguments passed to the script.")
    List<String> scriptArgs = new ArrayList<>();

    @Override
    public Integer call() throws IOException {
      String userSource = Main.readElmSource(file);
      String posix = pl.matsuo.elm.util.Resources.read("/elm/lib/Posix.elm");
      String bash = pl.matsuo.elm.util.Resources.read("/elm/lib/Bash.elm");
      // Site is bundled too, so a script can read files (Posix) and Main.render them to HTML pages
      // (Site.render) — e.g. generating a page per file in a folder, or from a JSON manifest.
      String site = pl.matsuo.elm.util.Resources.read("/elm/lib/Site.elm");
      // Awk/M4 give scripts text-processing and macro-expansion helpers over the text they read.
      String awk = pl.matsuo.elm.util.Resources.read("/elm/lib/Awk.elm");
      String m4 = pl.matsuo.elm.util.Resources.read("/elm/lib/M4.elm");
      Object main = pl.matsuo.elm.interp.Project.load(userSource, posix, bash, site, awk, m4).main();
      return pl.matsuo.elm.script.ScriptRunner.run(
          main,
          scriptArgs == null ? List.of() : scriptArgs,
          new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
          System.out);
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
        "  elm bundle script WordCount.elm -o wc    # wc.jar  -> java -jar wc.jar README.md",
        "  elm bundle server my-api.elm --port 8080 # my-api.jar -> java -jar my-api.jar",
        "  elm bundle script WordCount.elm --native  # also try a native binary (needs GraalVM)",
        "",
        "The JAR is the reliable standalone artifact. Native compilation is best-effort: it needs a",
        "GraalVM native-image and isn't possible from inside an already-compiled elm binary.",
      })
final class Bundle implements Callable<Integer> {
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
      String source = Main.readElmSource(file);
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

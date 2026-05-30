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
 * subcommands {@code run/js/make/eval/check/repl/lsp/format/project/bench/site/init}. Use
 * {@code --help} on any command for usage.
 */
@Command(
    name = "elm",
    mixinStandardHelpOptions = true,
    version = "elm-lang 0.1",
    description = "An Elm interpreter/compiler (Truffle JIT, bytecode VM, JS and WASM backends).",
    subcommands = {
      Main.Run.class,
      Main.Js.class,
      Main.Make.class,
      Main.Eval.class,
      Main.Check.class,
      Main.Repl.class,
      Main.Lsp.class,
      Main.Format.class,
      Main.Project.class,
      Main.Bench.class,
      Main.Site.class,
      Main.Init.class,
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

  @Command(name = "run", description = "Evaluate a definition and print it (Html/programs as HTML).")
  static final class Run implements Callable<Integer> {
    @Parameters(index = "0", description = "The .elm file.")
    Path file;

    @Option(names = "--backend", description = "interp (default) or bytecode.")
    String backend = "interp";

    @Option(names = "--value", description = "Top-level name to evaluate (default: main).")
    String value = "main";

    @Option(names = "--strict", description = "Type-check first; refuse to run on a type error.")
    boolean strict;

    @Override
    public Integer call() throws IOException {
      String source = Files.readString(file);
      if (strict) {
        try {
          pl.matsuo.elm.types.TypeChecker.checkModule(source);
        } catch (ElmTypeError e) {
          System.out.println("Type error: " + e.getMessage());
          return 1;
        }
      }
      Object v =
          backend.equals("bytecode")
              ? BytecodeInterpreter.load(source).value(value)
              : Interpreter.load(source).value(value);
      System.out.println(render(v));
      return 0;
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
      description = "Compile a program to a deployable artifact (HTML page, or a .js bundle).")
  static final class Make implements Callable<Integer> {
    @Parameters(arity = "1..*", description = "The .elm entry file (plus any sibling modules).")
    List<Path> files;

    @Option(
        names = {"-o", "--output"},
        description = "Output file. A .js name emits the bundle; anything else an HTML page. "
            + "(default: index.html)")
    String output = "index.html";

    @Option(names = "--optimize", description = "Minify the generated JavaScript.")
    boolean optimize;

    @Override
    public Integer call() throws IOException {
      List<String> sources = new ArrayList<>();
      for (Path p : files) {
        sources.add(Files.readString(p));
      }
      String[] arr = sources.toArray(new String[0]);
      String artifact;
      if (output.endsWith(".js")) {
        String bundle = JsCompiler.appBundleProject(arr);
        artifact = optimize ? JsCompiler.minify(bundle) : bundle;
      } else {
        String bundle = JsCompiler.appBundleProject(arr);
        String js = optimize ? JsCompiler.minify(bundle) : bundle;
        artifact =
            "<!doctype html>\n<html>\n<head><meta charset=\"utf-8\"><title>Elm</title></head>\n"
                + "<body>\n<div id=\"app\"></div>\n<script>\n"
                + js
                + "\n</script>\n</body>\n</html>\n";
      }
      Files.writeString(Path.of(output), artifact);
      System.out.println("Wrote " + output + " (" + artifact.length() + " bytes)");
      return 0;
    }
  }

  @Command(name = "eval", description = "Evaluate a single expression.")
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
        System.out.println("Type error: " + e.getMessage());
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

  @Command(name = "format", description = "Format Elm source (elm-format style).")
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

    @Override
    public Integer call() {
      List<String> sources = pl.matsuo.elm.project.ProjectLoader.loadSources(path);
      if (mode.equals("run")) {
        System.out.println(render(pl.matsuo.elm.interp.Project.load(sources.toArray(new String[0])).main()));
      } else {
        try {
          pl.matsuo.elm.types.TypeChecker.checkProject(sources.toArray(new String[0]))
              .forEach((name, type) -> System.out.println(name + " : " + type));
        } catch (ElmTypeError e) {
          System.out.println("Type error: " + e.getMessage());
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

    @Override
    public Integer call() throws IOException {
      pl.matsuo.elm.site.SiteGenerator.generate(examplesDir, playground, outDir);
      return 0;
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

  /** Renders a value: Browser programs and Html nodes become HTML, everything else uses Show. */
  static String render(Object value) {
    if (value instanceof ElmData d) {
      switch (d.ctor()) {
        case "$Sandbox", "$Element", "$Document" -> {
          return Tea.start(value).html();
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

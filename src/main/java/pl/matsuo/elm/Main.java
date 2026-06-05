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
      "  elm script WordCount.elm a.txt b.txt      Run an Elm file as a CLI script",
      "  elm server SimpleServerShowcase --port 8080  Serve HTTP from a `handle` function",
      "  elm repl                                  Start the interactive REPL",
      "  elm init                                  Scaffold elm.json + src/",
      "",
      "Bundled examples live under src/main/elm/ (scripts/, servers/, examples/) — e.g.:",
      "  elm script src/main/elm/scripts/WordCount.elm README.md",
      "  elm server SimpleServerShowcase   # then: curl localhost:8080/ping",
      "",
      "Run 'elm <command> --help' for command-specific options and examples.",
    },
    subcommands = {
      Run.class,
      Js.class,
      Make.class,
      Eval.class,
      Bytecode.class,
      Script.class,
      Serve.class,
      Bundle.class,
      TestCmd.class,
      Lint.class,
      Docs.class,
      CoverageCmd.class,
      Check.class,
      Repl.class,
      Lsp.class,
      Format.class,
      Project.class,
      Bench.class,
      Wasm.class,
      Site.class,
      GenSite.class,
      BuildCmd.class,
      Gallery.class,
      GalleryElm.class,
      Init.class,
      Install.class,
      Upgrade.class,
      Uninstall.class,
      Outdated.class,
      Doctest.class,
      Verify.class,
      Diff.class,
      Bump.class,
      Publish.class,
      Reactor.class,
      CommandLine.HelpCommand.class,
    })
public final class Main implements Runnable {

  public static void main(String[] args) {
    System.exit(run(args));
  }

  /** Runs the CLI and returns the process exit code. Split out so tests can assert on it. */
  public static int run(String... args) {
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


  /** A standard elm.json for an application project, like `elm init` produces. */
  static final String ELM_JSON =
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
   * base name (with or without {@code .elm}) is looked up under the bundled demo directories ({@code
   * /elm/scripts/}, {@code /elm/servers/}, {@code /elm/examples/}). So {@code elm script WordCount}
   * and {@code elm script WordCount.elm} both run the bundled example, while a real file on disk
   * always takes precedence. A genuinely missing file still yields a clean error.
   */
  static final String[] BUNDLED_DEMO_DIRS = {"/elm/scripts/", "/elm/servers/", "/elm/examples/"};

  static String readElmSource(Path file) throws IOException {
    if (Files.exists(file)) {
      return Files.readString(file);
    }
    String name = file.getFileName().toString();
    if (!name.endsWith(".elm")) {
      name = name + ".elm";
    }
    for (String dir : BUNDLED_DEMO_DIRS) {
      try (var in = Main.class.getResourceAsStream(dir + name)) {
        if (in != null) {
          return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

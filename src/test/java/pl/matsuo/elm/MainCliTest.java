package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** End-to-end tests for the {@link Main} command-line entry point. */
class MainCliTest {

  private String run(String... args) throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      Main.run(args);
    } finally {
      System.setOut(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  /** Result of a CLI invocation: exit code plus captured stdout/stderr. */
  private record Result(int code, String out, String err) {}

  private Result invoke(String... args) {
    PrintStream origOut = System.out;
    PrintStream origErr = System.err;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      int code = Main.run(args);
      return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(origOut);
      System.setErr(origErr);
    }
  }

  private Path tempElm(String source) throws Exception {
    Path f = Files.createTempFile("cli-", ".elm");
    Files.writeString(f, source, StandardCharsets.UTF_8);
    return f;
  }

  @Test
  void evalExpression() throws Exception {
    assertTrue(run("eval", "1 + 2 * 3").trim().equals("7"));
    assertTrue(run("eval", "List.range 1 4", "--backend", "bytecode").trim().equals("[1,2,3,4]"));
  }

  @Test
  void runRendersBrowserProgram() throws Exception {
    Path f =
        tempElm(
            """
            import Browser
            import Html exposing (div, button, text)
            import Html.Events exposing (onClick)
            main = Browser.sandbox { init = 0, update = update, view = view }
            type Msg = Inc
            update msg model = model + 1
            view model = div [] [ text (String.fromInt model) ]
            """);
    String out = run("run", f.toString());
    assertTrue(out.contains("<div>0</div>"), out);
  }

  @Test
  void compilesToJavaScript() throws Exception {
    Path f = tempElm("main = 6 * 7\n");
    String js = run("js", f.toString());
    assertTrue(js.contains("var _$main"), js);
    assertTrue(js.contains("$show"), js);
  }

  @Test
  void strictRunsWhenWellTyped() throws Exception {
    Path f = tempElm("main = 6 * 7\n");
    assertTrue(run("run", f.toString(), "--strict").trim().equals("42"));
  }

  @Test
  void strictRefusesToRunOnTypeError() throws Exception {
    Path f = tempElm("main = 1 + \"oops\"\n");
    String out = run("run", f.toString(), "--strict");
    assertTrue(out.contains("Type error"), out);
    assertTrue(out.contains("Hint:"), out); // the Elm-style hint is shown
  }

  @Test
  void initCreatesElmJsonAndSrc() throws Exception {
    Path dir = Files.createTempDirectory("elm-init-");
    String out = run("init", dir.toString());
    assertTrue(out.contains("Created"), out);
    assertTrue(Files.exists(dir.resolve("elm.json")), "elm.json created");
    assertTrue(Files.isDirectory(dir.resolve("src")), "src/ created");
    String json = Files.readString(dir.resolve("elm.json"));
    assertTrue(json.contains("\"type\": \"application\""), json);
    assertTrue(json.contains("source-directories"), json);
  }

  @Test
  void helpIsShownWithNoArguments() throws Exception {
    assertTrue(run().contains("Commands:") || run().contains("Usage:"), run());
  }

  @Test
  void helpListsExamplesIncludingScriptAndServer() {
    String help = invoke("--help").out();
    assertTrue(help.contains("Examples:"), help);
    assertTrue(help.contains("elm script"), help);
    assertTrue(help.contains("elm server"), help);
    // Each command also carries its own examples (shown via `help <command>`).
    String scriptHelp = invoke("help", "script").out();
    assertTrue(scriptHelp.contains("wordcount"), scriptHelp);
  }

  @Test
  void scriptRunsABundledDemoByName() throws Exception {
    // No such file on disk -> resolves the bundled demo /elm/demos/wordcount.elm.
    Path f = tempElm("hello world\n");
    assertTrue(invoke("script", "wordcount", f.toString()).out().contains("total:"));
    // A stale/relocated path whose base name still matches a bundled demo also resolves.
    assertTrue(
        invoke("script", "any/old/path/wordcount.elm", f.toString()).out().contains("total:"));
  }

  @Test
  void formatCheckSucceedsOnFormattedFileAndFailsOnUnformatted() throws Exception {
    // Already-formatted output is a fixed point of `format`, so re-checking it passes.
    Path tidy = tempElm(pl.matsuo.elm.fmt.Formatter.format("module Main exposing (..)\nmain = 1\n"));
    assertTrue(invoke("format", tidy.toString(), "--check").code() == 0);

    Path messy = tempElm("module Main exposing (..)\nmain   =    1\n");
    Result r = invoke("format", messy.toString(), "--check");
    assertTrue(r.code() == 1, "exit code");
    assertTrue(r.out().contains("is not formatted"), r.out());
  }

  @Test
  void makeProducesADeployableHtmlPage() throws Exception {
    Path f =
        tempElm(
            """
            import Browser
            import Html exposing (div, text)
            main = Browser.sandbox { init = 0, update = \\_ m -> m, view = \\m -> div [] [ text "hi" ] }
            type Msg = Noop
            """);
    Path out = Files.createTempDirectory("make-").resolve("index.html");
    Result r = invoke("make", f.toString(), "-o", out.toString());
    assertTrue(r.code() == 0, r.err());
    String html = Files.readString(out);
    assertTrue(html.contains("<!doctype html>"), "is an HTML page");
    assertTrue(html.contains("id=\"app\""), "has a mount point");
    assertTrue(html.contains("$start"), "boots the Elm program");
  }

  @Test
  void makeCanEmitAMinifiedJsBundle() throws Exception {
    Path f = tempElm("main = 6 * 7\n");
    Path out = Files.createTempDirectory("make-js-").resolve("app.js");
    Result r = invoke("make", f.toString(), "-o", out.toString(), "--optimize");
    assertTrue(r.code() == 0, r.err());
    String js = Files.readString(out);
    assertTrue(!js.contains("<script>"), "raw JS, not HTML");
    assertTrue(js.contains("$start") || js.contains("_$main"), js.substring(0, Math.min(200, js.length())));
  }

  @Test
  void missingFileProducesCleanErrorNotStackTrace() {
    Result r = invoke("run", "does-not-exist.elm");
    assertTrue(r.code() == 1, "exit code");
    assertTrue(r.err().contains("File not found"), r.err());
    assertTrue(!r.err().contains("\tat "), "no Java stack trace: " + r.err());
  }

  @Test
  void checkAcceptsAMultiModuleProject() throws Exception {
    Path lib = tempElm("module Lib exposing (..)\ndouble n = n * 2\n");
    Path main = tempElm("module Main exposing (..)\nimport Lib exposing (..)\nmain = double 21\n");
    String out = run("check", lib.toString(), main.toString());
    assertTrue(out.contains("main :"), out);
  }
}

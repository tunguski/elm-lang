package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void runAndCheckResolveBundledLibraryImports() throws Exception {
    Path f = tempElm("module Main exposing (main)\n\nimport List.Extra as LE\n\nmain = LE.last [ 1, 2, 3 ]\n");
    Result checked = invoke("check", f.toString());
    assertEquals(0, checked.code(), "check should resolve List.Extra: " + checked.err());
    Result ran = invoke("run", f.toString());
    assertEquals(0, ran.code(), "run should resolve List.Extra: " + ran.err());
    assertTrue(ran.out().contains("Just 3"), ran.out());
  }

  @Test
  void doctestRunsDocCommentExamples() throws Exception {
    Path good = tempElm(
        "module M exposing (double)\n\n{-| Doubles.\n\n    double 21\n    --> 42\n-}\ndouble n =\n    n * 2\n");
    Result okRun = invoke("doctest", good.toString());
    assertEquals(0, okRun.code(), okRun.out());
    assertTrue(okRun.out().contains("1 passed, 0 failed"), okRun.out());

    Path bad = tempElm(
        "module M exposing (triple)\n\n{-| Triples.\n\n    triple 2\n    --> 7\n-}\ntriple n =\n    n * 3\n");
    Result badRun = invoke("doctest", bad.toString());
    assertEquals(1, badRun.code(), badRun.out());
    assertTrue(badRun.out().contains("0 passed, 1 failed"), badRun.out());
  }

  @Test
  void evalExpression() throws Exception {
    assertTrue(run("eval", "1 + 2 * 3").trim().equals("7"));
    assertTrue(run("eval", "List.range 1 4", "--backend", "bytecode").trim().equals("[1,2,3,4]"));
  }

  @Test
  void bytecodeCompilesAPortableArtifactAndRunsIt() throws Exception {
    Path src = tempElm("module Main exposing (main)\nmain = List.sum (List.range 1 10)\n");
    Path artifact = Files.createTempFile("prog-", ".elmbc");

    // Compile the module to a portable .elmbc artifact.
    Result emit = invoke("bytecode", src.toString(), "-o", artifact.toString());
    assertEquals(0, emit.code(), emit.out() + emit.err());
    assertTrue(Files.size(artifact) > 0, "artifact written");
    byte[] bytes = Files.readAllBytes(artifact);
    assertTrue(bytes.length > 5 && bytes[0] == 'E' && bytes[4] == 'C', "ELMBC magic header");

    // Run the value straight from the artifact (no source, no recompilation).
    assertEquals("55", run("bytecode", artifact.toString(), "--value", "main").trim());
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
  void runTypeChecksByDefaultAndRunsWhenWellTyped() throws Exception {
    Path f = tempElm("main = 6 * 7\n");
    assertTrue(run("run", f.toString()).trim().equals("42"));
  }

  @Test
  void runRefusesByDefaultOnTypeError() throws Exception {
    Path f = tempElm("main = 1 + \"oops\"\n");
    Result r = invoke("run", f.toString());
    assertTrue(r.code() == 1, "exit code");
    assertTrue(r.out().contains("Type error"), r.out());
    assertTrue(r.out().contains("Hint:"), r.out()); // the Elm-style hint is shown
  }

  @Test
  void noCheckRunsDespiteTypeError() throws Exception {
    // --no-check skips the type check; the (ill-typed but evaluable) program still runs.
    Path f = tempElm("main = 6 * 7\n");
    assertTrue(run("run", f.toString(), "--no-check").trim().equals("42"));
  }

  @Test
  void makeRefusesByDefaultOnTypeError() throws Exception {
    Path f = tempElm("main = 1 + \"oops\"\n");
    Path out = Files.createTempDirectory("make-bad-").resolve("index.html");
    Result r = invoke("make", f.toString(), "-o", out.toString());
    assertTrue(r.code() == 1, r.out());
    assertTrue(r.out().contains("Type error"), r.out());
    assertTrue(!Files.exists(out), "no artifact written on a type error");
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
    assertTrue(scriptHelp.contains("WordCount"), scriptHelp);
  }

  @Test
  void scriptRunsABundledDemoByName() throws Exception {
    // No such file on disk -> resolves the bundled demo /elm/scripts/WordCount.elm.
    Path f = tempElm("hello world\n");
    assertTrue(invoke("script", "WordCount", f.toString()).out().contains("total:"));
    // A stale/relocated path whose base name still matches a bundled demo also resolves.
    assertTrue(
        invoke("script", "any/old/path/WordCount.elm", f.toString()).out().contains("total:"));
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
  void testCommandRunsTheBundledSuite() {
    Result r = invoke("test", "ExampleTest"); // resolves the bundled demo by name
    assertTrue(r.code() == 0, r.out());
    assertTrue(r.out().contains("9 passed"), r.out());
  }

  @Test
  void missingFileProducesCleanErrorNotStackTrace() {
    Result r = invoke("run", "does-not-exist.elm");
    assertTrue(r.code() == 1, "exit code");
    assertTrue(r.err().contains("File not found"), r.err());
    assertTrue(!r.err().contains("\tat "), "no Java stack trace: " + r.err());
  }

  @Test
  void publishDryRunChecksDocsAndBump() throws Exception {
    Path oldApi = tempElm("module M exposing (inc)\ninc x = x + 1\n");
    Path newApi = tempElm("module M exposing (inc, dec)\ninc x = x + 1\ndec x = x - 1\n");
    Result r = invoke("publish", newApi.toString(), "--bump-from", oldApi.toString(), "--from-version", "1.0.0");
    assertTrue(r.code() == 0, r.out() + r.err());
    assertTrue(r.out().contains("type-checks"), r.out());
    assertTrue(r.out().contains("exposed entr"), r.out());
    assertTrue(r.out().contains("MINOR change: 1.0.0 -> 1.1.0"), r.out()); // adding `dec` is MINOR
    assertTrue(r.out().contains("ready to publish"), r.out());
  }

  @Test
  void taskOnErrorRecoversAFailedTask() throws Exception {
    // A failing task recovered with Task.onError, run on init and reflected in the view.
    Path f =
        tempElm(
            """
            import Browser
            import Html exposing (text)
            import Task

            type Msg = Got (Result String String)

            main = Browser.element { init = init, update = update, view = view, subscriptions = \\_ -> Sub.none }

            init _ = ( "waiting", Task.attempt Got (Task.onError recover (Task.fail "boom")) )

            recover e = Task.succeed ("recovered " ++ e)

            update msg _ =
                case msg of
                    Got (Ok v) -> ( v, Cmd.none )
                    Got (Err e) -> ( "err " ++ e, Cmd.none )

            view m = text m
            """);
    String out = run("run", f.toString());
    assertTrue(out.contains("recovered boom"), out);
  }

  @Test
  void publishWritesDocsJson() throws Exception {
    Path api = tempElm("module M exposing (inc)\n{-| Adds one. -}\ninc x = x + 1\n");
    Path docs = Files.createTempFile("docs-", ".json");
    Result r = invoke("publish", api.toString(), "--out", docs.toString());
    assertTrue(r.code() == 0, r.out() + r.err());
    assertTrue(r.out().contains("wrote " + docs), r.out());
    String json = Files.readString(docs);
    assertTrue(json.contains("\"inc\""), json); // the exposed value is in docs.json
  }

  @Test
  void publishRecordsThePackageInADirectoryRegistry() throws Exception {
    Path api = tempElm("module Widget exposing (inc)\n{-| Adds one. -}\ninc x = x + 1\n");
    Path reg = Files.createTempDirectory("reg-");
    Result r =
        invoke("publish", api.toString(), "--registry", reg.toString(), "--name", "me/widget",
            "--version", "1.0.0");
    assertTrue(r.code() == 0, r.out() + r.err());
    assertTrue(r.out().contains("published me/widget 1.0.0"), r.out());
    // The registry now records it (elm.json + docs.json), so the solver sees the version.
    Path versionDir = reg.resolve("me/widget/1.0.0");
    assertTrue(Files.exists(versionDir.resolve("elm.json")), "manifest recorded");
    assertTrue(Files.exists(versionDir.resolve("docs.json")), "docs recorded");
    assertTrue(
        new pl.matsuo.elm.pkg.DirectoryRegistry(reg)
            .versions("me/widget")
            .contains(pl.matsuo.elm.pkg.Version.parse("1.0.0")),
        "registry lists the published version");
    // Re-publishing the same version is rejected by the handshake.
    Result again =
        invoke("publish", api.toString(), "--registry", reg.toString(), "--name", "me/widget",
            "--version", "1.0.0");
    assertTrue(again.code() == 1, again.out());
    assertTrue(again.out().contains("already published"), again.out());
  }

  @Test
  void publishRejectsAVersionThatDoesntMatchTheBump() throws Exception {
    Path oldApi = tempElm("module M exposing (inc)\ninc x = x + 1\n");
    Path newApi = tempElm("module M exposing (dec)\ndec x = x - 1\n"); // removed inc -> MAJOR
    // Intending 1.1.0 (a MINOR) for a MAJOR change must fail.
    Result bad =
        invoke("publish", newApi.toString(), "--bump-from", oldApi.toString(),
            "--from-version", "1.0.0", "--version", "1.1.0");
    assertTrue(bad.code() == 1, bad.out());
    assertTrue(bad.out().contains("not the required next version 2.0.0"), bad.out());
    // The correct next version passes.
    Result ok =
        invoke("publish", newApi.toString(), "--bump-from", oldApi.toString(),
            "--from-version", "1.0.0", "--version", "2.0.0");
    assertTrue(ok.code() == 0, ok.out());
    assertTrue(ok.out().contains("matches the required bump"), ok.out());
  }

  @Test
  void bumpReadsCurrentVersionFromASiblingElmJson() throws Exception {
    Path dir = Files.createTempDirectory("cli-bump-");
    Files.writeString(dir.resolve("old.elm"), "module M exposing (inc)\ninc x = x + 1\n");
    Path newApi = dir.resolve("new.elm");
    Files.writeString(newApi, "module M exposing (inc, dec)\ninc x = x + 1\ndec x = x - 1\n");
    Files.writeString(
        dir.resolve("elm.json"), "{ \"type\": \"package\", \"version\": \"2.3.4\" }");
    // No explicit version arg: it comes from the package elm.json next to the new module.
    Result r = invoke("bump", dir.resolve("old.elm").toString(), newApi.toString());
    assertTrue(r.code() == 0, r.out() + r.err());
    assertTrue(r.out().contains("MINOR change: 2.3.4 -> 2.4.0"), r.out()); // adding `dec` is MINOR
  }

  @Test
  void bumpUsesAnExplicitVersionArgumentWhenGiven() throws Exception {
    Path oldApi = tempElm("module M exposing (inc)\ninc x = x + 1\n");
    Path newApi = tempElm("module M exposing (dec)\ndec x = x - 1\n"); // removed inc -> MAJOR
    Result r = invoke("bump", oldApi.toString(), newApi.toString(), "1.2.0");
    assertTrue(r.code() == 0, r.out() + r.err());
    assertTrue(r.out().contains("MAJOR change: 1.2.0 -> 2.0.0"), r.out());
  }

  @Test
  void publishDryRunFailsOnTypeError() throws Exception {
    Path bad = tempElm("module M exposing (x)\nx = 1 + \"oops\"\n");
    Result r = invoke("publish", bad.toString());
    assertTrue(r.code() == 1, r.out());
    assertTrue(r.out().contains("type error"), r.out());
  }

  @Test
  void checkReportsNonExhaustiveCaseListingAllMissingConstructors() throws Exception {
    Path f =
        tempElm(
            """
            module M exposing (..)
            type Color = Red | Green | Blue
            name c =
                case c of
                    Red -> "r"
            """); // missing Green and Blue
    Result r = invoke("check", f.toString());
    assertTrue(r.code() == 1, r.out());
    assertTrue(r.out().contains("does not handle all possible inputs"), r.out());
    assertTrue(r.out().contains("Missing branches for: Green, Blue"), r.out()); // all missing listed
  }

  @Test
  void wasmCommandCompilesAMultiModuleProjectToABinary() throws Exception {
    Path util = tempElm("module Util exposing (square)\nsquare n = n * n\n");
    Path main = tempElm("module Main exposing (main)\nimport Util exposing (square)\nmain = square 7\n");
    Path out = Files.createTempFile("cli-", ".wasm");
    Result r = invoke("wasm", main.toString(), util.toString(), "-o", out.toString());
    assertTrue(r.code() == 0, r.out() + r.err());
    byte[] bytes = Files.readAllBytes(out);
    assertTrue(bytes.length > 8, "wrote a non-trivial wasm binary");
    assertTrue(bytes[0] == 0x00 && bytes[1] == 0x61 && bytes[2] == 0x73 && bytes[3] == 0x6D, "wasm magic \\0asm");
  }

  @Test
  void checkAcceptsAMultiModuleProject() throws Exception {
    Path lib = tempElm("module Lib exposing (..)\ndouble n = n * 2\n");
    Path main = tempElm("module Main exposing (..)\nimport Lib exposing (..)\nmain = double 21\n");
    String out = run("check", lib.toString(), main.toString());
    assertTrue(out.contains("main :"), out);
  }
}

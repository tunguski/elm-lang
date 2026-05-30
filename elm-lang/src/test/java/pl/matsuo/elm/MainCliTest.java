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
      Main.main(args);
    } finally {
      System.setOut(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
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
  void checkAcceptsAMultiModuleProject() throws Exception {
    Path lib = tempElm("module Lib exposing (..)\ndouble n = n * 2\n");
    Path main = tempElm("module Main exposing (..)\nimport Lib exposing (..)\nmain = double 21\n");
    String out = run("check", lib.toString(), main.toString());
    assertTrue(out.contains("main :"), out);
  }
}

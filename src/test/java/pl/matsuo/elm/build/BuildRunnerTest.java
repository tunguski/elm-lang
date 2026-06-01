package pl.matsuo.elm.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.Main;

/** End-to-end tests for the {@code elm build} command: a build definition is loaded, planned and
 *  executed through the lifecycle, performing real filesystem work. */
class BuildRunnerTest {

  /** Result of an {@code elm build} invocation: exit code plus captured stdout. */
  private record Result(int code, String out) {}

  private Result build(Path dir, String... args) {
    PrintStream orig = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      java.util.List<String> argv = new java.util.ArrayList<>();
      argv.add("build");
      for (String a : args) {
        argv.add(a);
      }
      argv.add("-f");
      argv.add(dir.resolve("build.elm").toString());
      int code = Main.run(argv.toArray(new String[0]));
      return new Result(code, captured.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(orig);
    }
  }

  @Test
  void runsCustomGoalTasksAndWritesArtifacts() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "."
                    |> withOutput "out"
                    |> withGoals
                        [ goal Compile "generate"
                            (\\m ->
                                [ makeDir m.output
                                , writeFile (m.output ++ "/hello.txt") ("hi from " ++ m.name)
                                , log "generated hello.txt"
                                ]
                            )
                        ]
                ]
        """);
    Result r = build(dir, "compile");
    assertEquals(0, r.code(), r.out());
    assertTrue(r.out().contains("[compile] app :: generate"), r.out());
    assertTrue(r.out().contains("generated hello.txt"), r.out());
    assertTrue(r.out().contains("BUILD SUCCESS"), r.out());
    Path artifact = dir.resolve("out/hello.txt");
    assertTrue(Files.exists(artifact), "artifact written");
    assertEquals("hi from app", Files.readString(artifact, StandardCharsets.UTF_8));
  }

  @Test
  void defaultCompileGoalCompilesAModuleToAJsPage() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-js-");
    Files.createDirectories(dir.resolve("src"));
    Files.writeString(
        dir.resolve("src/Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nmain = text \"hi\"\n");
    // No goals -> the default lifecycle bindings apply; compile emits a JS page to the output dir.
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "." |> withEntry "src/Main.elm" |> withOutput "out" ]
        """);
    Result r = build(dir, "compile");
    assertEquals(0, r.code(), r.out());
    assertTrue(r.out().contains("compiled src/Main.elm (JS)"), r.out());
    Path js = dir.resolve("out/app.js");
    assertTrue(Files.exists(js), "JS artifact written: " + r.out());
    assertTrue(Files.readString(js, StandardCharsets.UTF_8).contains("$start"), "is a runnable bundle");
  }

  @Test
  void defaultValidateTypeChecksAndFailsTheBuildOnAnError() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-validate-");
    Files.createDirectories(dir.resolve("src"));
    // A type error in the entry: validate must catch it and stop before compile.
    Files.writeString(
        dir.resolve("src/Main.elm"),
        "module Main exposing (x)\nx = 1 + \"oops\"\n");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "." |> withEntry "src/Main.elm" |> withOutput "out" ]
        """);
    Result r = build(dir, "compile");
    assertEquals(1, r.code(), r.out());
    assertTrue(r.out().contains("type error"), r.out());
    assertTrue(r.out().contains("BUILD FAILED"), r.out());
    assertFalse(Files.exists(dir.resolve("out")), "compile did not run after validate failed");
  }

  @Test
  void defaultPackageAndInstallProduceRealArtifacts() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-pkg-");
    Files.createDirectories(dir.resolve("src"));
    Files.writeString(
        dir.resolve("src/Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nmain = text \"hi\"\n");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "." |> withEntry "src/Main.elm" |> withOutput "out" ]
        """);
    Result r = build(dir, "install"); // the whole lifecycle, including package + install
    assertEquals(0, r.code(), r.out());
    assertTrue(r.out().contains("archived"), r.out());
    Path zip = dir.resolve("dist/app.zip");
    assertTrue(Files.exists(zip) && Files.size(zip) > 0, "package produced a non-empty archive");
    assertTrue(Files.exists(dir.resolve("build-repo/app.zip")), "install copied the archive to the repo");
  }

  @Test
  void compilesAMultiFileModuleAsAProject() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-multi-");
    Files.createDirectories(dir.resolve("src"));
    Files.writeString(
        dir.resolve("src/Util.elm"), "module Util exposing (greeting)\ngreeting = \"hi-there\"\n");
    Files.writeString(
        dir.resolve("src/Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nimport Util\nmain = text Util.greeting\n");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "."
                    |> withEntry "src/Main.elm"
                    |> withSources [ "src/Util.elm" ]
                    |> withOutput "out"
                ]
        """);
    Result r = build(dir, "compile");
    assertEquals(0, r.code(), r.out());
    String js = Files.readString(dir.resolve("out/app.js"), StandardCharsets.UTF_8);
    assertTrue(js.contains("$start"), "is a runnable bundle");
    assertTrue(js.contains("hi-there"), "the imported module was bundled in: " + r.out());
  }

  @Test
  void perModuleBackendCompilesToWasm() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-wasm-");
    Files.createDirectories(dir.resolve("src"));
    Files.writeString(dir.resolve("src/Main.elm"), "module Main exposing (main)\nmain = 6 * 7\n");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "." |> withEntry "src/Main.elm" |> withOutput "out" |> withBackend Wasm ]
        """);
    Result r = build(dir, "compile");
    assertEquals(0, r.code(), r.out());
    Path wasm = dir.resolve("out/app.wasm"); // .wasm extension chosen by the backend
    assertTrue(Files.exists(wasm), "wasm artifact written: " + r.out());
    byte[] bytes = Files.readAllBytes(wasm);
    assertTrue(bytes.length > 4 && bytes[0] == 0x00 && bytes[1] == 0x61, "starts with the wasm magic");
  }

  @Test
  void incrementalSkipsCompileWhenTheOutputIsUpToDate() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-incr-");
    Files.createDirectories(dir.resolve("src"));
    Files.writeString(
        dir.resolve("src/Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nmain = text \"hi\"\n");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "." |> withEntry "src/Main.elm" |> withOutput "out" ]
        """);
    Result first = build(dir, "compile", "--incremental");
    assertEquals(0, first.code(), first.out());
    assertTrue(first.out().contains("compiled"), first.out());
    // Second run: the artifact is newer than the source, so compile is skipped.
    Result second = build(dir, "compile", "--incremental");
    assertEquals(0, second.code(), second.out());
    assertTrue(second.out().contains("up to date"), second.out());
    assertFalse(second.out().contains("compiled src/Main.elm"), "did not recompile: " + second.out());
  }

  @Test
  void cleanRemovesEachModulesOutput() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-clean-");
    Files.createDirectories(dir.resolve("out"));
    Files.writeString(dir.resolve("out/stale.txt"), "old");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0" [ module_ "app" "." |> withOutput "out" ]
        """);
    Result r = build(dir, "clean");
    assertEquals(0, r.code(), r.out());
    assertTrue(r.out().contains("[clean] app :: clean"), r.out());
    assertFalse(Files.exists(dir.resolve("out")), "output directory removed");
  }

  @Test
  void dryRunPrintsThePlanWithoutExecuting() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-dry-");
    Files.writeString(
        dir.resolve("build.elm"),
        """
        module Main exposing (project)

        import Build exposing (..)

        project : Project
        project =
            Build.project "demo" "1.0.0"
                [ module_ "app" "."
                    |> withOutput "out"
                    |> withGoals [ goal Compile "gen" (\\m -> [ makeDir m.output, log "hi" ]) ]
                ]
        """);
    Result r = build(dir, "compile", "--dry-run");
    assertEquals(0, r.code(), r.out());
    assertTrue(r.out().contains("[compile] app :: gen"), r.out());
    assertTrue(r.out().contains("makeDir out"), r.out());
    assertTrue(r.out().contains("(dry run — nothing executed)"), r.out());
    // Nothing was actually created.
    assertFalse(Files.exists(dir.resolve("out")), "dry run created no output");
  }

  @Test
  void initScaffoldsAStarterBuildFileAndRefusesToOverwrite() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-init-");
    Result r = build(dir, "--init");
    assertEquals(0, r.code(), r.out());
    Path buildFile = dir.resolve("build.elm");
    assertTrue(Files.exists(buildFile), "starter build.elm written");
    String content = Files.readString(buildFile, StandardCharsets.UTF_8);
    assertTrue(content.contains("project : Project") && content.contains("Build.project"), content);
    // It type-checks and plans as a valid build definition.
    Result dry = build(dir, "package", "--dry-run");
    assertEquals(0, dry.code(), dry.out());
    // A second --init must not clobber the existing file.
    Result again = build(dir, "--init");
    assertEquals(1, again.code(), again.out());
  }

  @Test
  void unknownPhaseFails() throws Exception {
    Path dir = Files.createTempDirectory("elm-build-bad-");
    Files.writeString(
        dir.resolve("build.elm"),
        "module Main exposing (project)\nimport Build exposing (..)\n"
            + "project = Build.project \"d\" \"1.0.0\" [ module_ \"a\" \".\" ]\n");
    Result r = build(dir, "bogus");
    assertEquals(1, r.code(), r.out());
  }
}

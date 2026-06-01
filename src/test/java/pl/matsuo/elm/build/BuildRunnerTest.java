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

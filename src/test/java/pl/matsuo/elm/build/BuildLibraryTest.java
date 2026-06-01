package pl.matsuo.elm.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.util.Resources;

/**
 * The bundled {@code Build} library's planner is a pure function: {@code plan phase project} expands
 * the declarative project into an ordered list of steps. These tests load a build definition through
 * the interpreter and check the lifecycle ordering, the default-vs-custom goal selection, and that a
 * goal's task function is applied to its module.
 */
class BuildLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Build.elm");

  /** A build definition exposing several values derived from {@code Build.plan}, for the assertions. */
  private static final String SRC =
      """
      module Main exposing (defaultsOrder, upToCompile, multiOrder, customOnly, compileTasks)

      import Build exposing (..)

      app : Module
      app = module_ "a" "."

      lib : Module
      lib = module_ "b" "lib"

      proj : Project
      proj = Build.project "demo" "1.0.0" [ app ]

      multi : Project
      multi = Build.project "demo" "1.0.0" [ app, lib ]

      custom : Project
      custom =
          Build.project "demo" "1.0.0"
              [ module_ "c" "."
                  |> withGoals [ goal Package "bundle" (\\m -> [ log ("bundle " ++ m.name) ]) ]
              ]

      label : Step -> String
      label s = s.phase ++ ":" ++ s.moduleName ++ ":" ++ s.goal

      defaultsOrder : List String
      defaultsOrder = List.map label (Build.plan Package proj)

      upToCompile : List String
      upToCompile = List.map label (Build.plan Compile proj)

      multiOrder : List String
      multiOrder = List.map label (Build.plan Compile multi)

      customOnly : List String
      customOnly = List.map label (Build.plan Install custom)

      compileTasks : List Task
      compileTasks = List.concatMap .tasks (Build.plan Compile proj)

      -- web depends on core; declared web-first, but core must build first (reactor order).
      reactor : Project
      reactor =
          Build.project "suite" "1.0.0"
              [ module_ "web" "web" |> withDependencies [ dependency "core" "1.0.0" ]
              , module_ "core" "core"
              ]

      buildOrderNames : List String
      buildOrderNames = List.map .name (Build.buildOrder reactor)

      reactorCompileOrder : List String
      reactorCompileOrder = List.map .moduleName (Build.plan Compile reactor)
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void defaultLifecycleRunsValidateCompileTestPackageInOrder() {
    assertEquals(
        "[\"validate:a:validate\",\"compile:a:compile\",\"test:a:test\",\"package:a:package\"]",
        value("defaultsOrder"));
  }

  @Test
  void askingForAnEarlierPhaseRunsOnlyUpToIt() {
    assertEquals("[\"validate:a:validate\",\"compile:a:compile\"]", value("upToCompile"));
  }

  @Test
  void multiModulePlanIsPhaseMajorAcrossModules() {
    // validate for every module, then compile for every module (Maven reactor order).
    assertEquals(
        "[\"validate:a:validate\",\"validate:b:validate\",\"compile:a:compile\",\"compile:b:compile\"]",
        value("multiOrder"));
  }

  @Test
  void customGoalsReplaceTheDefaults() {
    // The module declares one Package goal, so only it appears — no default validate/compile/test —
    // and its task function ran against the module (its name "c" appears in the produced task).
    assertEquals("[\"package:c:bundle\"]", value("customOnly"));
  }

  @Test
  void modulesBuildInDependencyOrder() {
    // web depends on core, so even though web is declared first, core is ordered first.
    assertEquals("[\"core\",\"web\"]", value("buildOrderNames"));
    // plan Compile spans validate then compile; core precedes web within each phase.
    assertEquals("[\"core\",\"web\",\"core\",\"web\"]", value("reactorCompileOrder"));
  }

  @Test
  void defaultCompileEmitsAMakeDirAndACompileModuleTask() {
    String tasks = value("compileTasks");
    assertTrue(tasks.contains("MakeDir"), tasks);
    assertTrue(tasks.contains("CompileModule JS"), tasks);
    assertTrue(tasks.contains("./src/Main.elm"), tasks); // the module's default entry
  }
}

package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsOptimizer.Partition;

/** Code-splitting codegen: the {@code $a} cross-seam reference and its interaction with partition. */
class JsCompilerSplitTest {

  private static final String HEAVY =
      """
      module Heavy exposing (run)
      run : Int -> Int
      run x = x + 1
      """;
  private static final String MAIN =
      """
      module Main exposing (main)
      import Heavy
      main = List.map Heavy.run [ 1, 2, 3 ]
      """;

  @Test
  void withNoLazyModulesTheOutputIsByteIdenticalToANormalBuild() {
    // Zero-risk default: an empty lazy set must produce exactly the normal bundle.
    assertEquals(
        JsCompiler.appBundleProject(MAIN, HEAVY),
        JsCompiler.appBundleSplit(Set.of(), MAIN, HEAVY));
  }

  @Test
  void aReferenceIntoALazyModuleBecomesADynamicLookup() {
    String split = JsCompiler.appBundleSplit(Set.of("Heavy"), MAIN, HEAVY);
    assertTrue(split.contains("$a(\"Heavy\", \"run\")"), "the base→chunk call is a dynamic $a lookup");
    // Heavy's own declaration is still present (it's emitted; partition then carves it out).
    assertTrue(split.contains("var _$Heavy$run = "), "the lazy module's decl is still emitted");
    // The base's `main` must NOT reference the bare id (that would pull the chunk into base).
    String mainDecl =
        split.lines().filter(l -> l.startsWith("var _$Main$main = ")).findFirst().orElseThrow();
    assertFalse(mainDecl.contains("_$Heavy$run"), "main must not reference the chunk by bare id");
  }

  @Test
  void partitionCarvesTheLazyModuleOutOfTheBase() {
    // The whole point: with the dynamic seam in place, partition moves Heavy to its own chunk and
    // nothing is stuck in base.
    String split = JsCompiler.appBundleSplit(Set.of("Heavy"), MAIN, HEAVY);
    Partition p = JsOptimizer.partition(split, Map.of("heavy", Set.of("Heavy")));
    assertTrue(p.chunkDecls().get("heavy").contains("_$Heavy$run"), "Heavy.run moves to the chunk");
    assertFalse(p.baseDecls().contains("_$Heavy$run"), "Heavy.run is no longer in base");
    assertTrue(p.stuckInBase().isEmpty(), "nothing stuck: base reaches Heavy only via $a strings");
  }
}

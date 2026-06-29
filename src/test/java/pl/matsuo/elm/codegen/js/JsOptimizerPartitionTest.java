package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsOptimizer.Partition;

/** {@link JsOptimizer#partition} — the multi-root reachability split underlying code-splitting. */
class JsOptimizerPartitionTest {

  @Test
  void tagOfDecodesTheDefiningModule() {
    assertEquals("Eval", JsOptimizer.tagOf("_$Eval$run"));
    assertEquals("Eval_Core", JsOptimizer.tagOf("_$Eval_Core$evalExpr")); // dotted module Eval.Core
    assertEquals("", JsOptimizer.tagOf("$data")); // a kernel helper, not an app decl
    assertEquals("", JsOptimizer.tagOf("_$nofield")); // malformed, no tag/name separator
  }

  @Test
  void chunkPrivateSubtreeMovesOutWhenBaseRefersOnlyByString() {
    // The base calls the chunk only through a string-keyed loader lookup ($g("Eval.run")), never by
    // bare id — so the whole Eval subtree (run + its private helper) is free to move to the chunk.
    String bundle =
        String.join(
            "\n",
            "var $D = {};",
            "var _$App$main = $g(\"Eval.run\") + _$App$view;",
            "var _$App$view = 1;",
            "var _$Eval$run = _$Eval$helper;",
            "var _$Eval$helper = 2;",
            "window.$start(_$App$main, document.getElementById('app'));");
    Partition p = JsOptimizer.partition(bundle, Map.of("eval", Set.of("Eval")));
    assertEquals(Set.of("_$App$main", "_$App$view"), p.baseDecls());
    assertEquals(Set.of("_$Eval$run", "_$Eval$helper"), p.chunkDecls().get("eval"));
    assertTrue(p.stuckInBase().isEmpty(), "nothing is stuck: base never names an Eval id directly");
  }

  @Test
  void aDirectBareReferenceKeepsTheWholeSubtreeInBaseAndIsReportedStuck() {
    // The base calls _$Eval$run by BARE id (a synchronous call, not via the loader). It cannot be
    // deferred, so the whole reachable subtree must stay in base (a runnable partition), and the root
    // is reported in stuckInBase so the developer knows to route it through the loader.
    String bundle =
        String.join(
            "\n",
            "var _$App$main = _$Eval$run;",
            "var _$Eval$run = _$Eval$helper;",
            "var _$Eval$helper = 2;",
            "window.$start(_$App$main, document.getElementById('app'));");
    Partition p = JsOptimizer.partition(bundle, Map.of("eval", Set.of("Eval")));
    assertEquals(Set.of("_$Eval$run"), p.stuckInBase());
    assertTrue(p.chunkDecls().get("eval").isEmpty(), "the chunk is empty — everything folded to base");
    assertEquals(
        Set.of("_$App$main", "_$Eval$run", "_$Eval$helper"),
        p.baseDecls(),
        "the stuck root's whole subtree stays in base so the split still runs");
  }

  @Test
  void aDepReachedFromTwoChunksIsHoistedToBase() {
    // _$Shared$u is reached from both chunks. The common-chunk rule hoists it to base so it is defined
    // exactly once; each chunk keeps only its own private decl.
    String bundle =
        String.join(
            "\n",
            "var _$App$main = $g(\"A.run\") + $g(\"B.run\");",
            "var _$A$run = _$A$priv + _$Shared$u;",
            "var _$A$priv = 1;",
            "var _$B$run = _$B$priv + _$Shared$u;",
            "var _$B$priv = 2;",
            "var _$Shared$u = 3;",
            "window.$start(_$App$main, document.getElementById('app'));");
    Partition p =
        JsOptimizer.partition(bundle, Map.of("a", Set.of("A"), "b", Set.of("B")));
    assertEquals(Set.of("_$A$run", "_$A$priv"), p.chunkDecls().get("a"));
    assertEquals(Set.of("_$B$run", "_$B$priv"), p.chunkDecls().get("b"));
    assertTrue(p.baseDecls().contains("_$Shared$u"), "a dep of two chunks is hoisted to base");
    assertTrue(p.baseDecls().contains("_$App$main"));
  }

  @Test
  void everyDeclLandsInExactlyOnePlace() {
    // Partition is a true partition: each top-level decl is owned by base or exactly one chunk.
    String bundle =
        String.join(
            "\n",
            "var _$App$main = $g(\"Eval.run\") + _$App$view;",
            "var _$App$view = 1;",
            "var _$Eval$run = _$Eval$helper;",
            "var _$Eval$helper = 2;",
            "window.$start(_$App$main, document.getElementById('app'));");
    Partition p = JsOptimizer.partition(bundle, Map.of("eval", Set.of("Eval")));
    int chunkTotal = p.chunkDecls().values().stream().mapToInt(Set::size).sum();
    assertEquals(4, p.baseDecls().size() + chunkTotal, "all 4 decls placed");
    for (String d : p.chunkDecls().get("eval")) {
      assertTrue(!p.baseDecls().contains(d), d + " must not be in both base and a chunk");
    }
  }
}

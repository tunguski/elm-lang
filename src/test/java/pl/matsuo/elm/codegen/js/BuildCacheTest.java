package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The incremental, content-hashed build cache: identical output to the plain bundler, and a
 *  body-only edit to one module recompiles only that module (one new cache fragment). */
class BuildCacheTest {

  private static final String UTIL = "module Util exposing (square)\nsquare n = n * n\n";
  private static final String MAIN =
      "module Main exposing (main)\nimport Util\nimport Html exposing (text)\n"
          + "main = text (String.fromInt (Util.square 7))\n";

  private static long fragmentCount(Path dir) throws IOException {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.toString().endsWith(".js")).count();
    }
  }

  @Test
  void cachedBundleEqualsPlainBundle(@TempDir Path cache) {
    assertEquals(
        JsCompiler.appBundleProject(UTIL, MAIN),
        JsCompiler.appBundleProjectCached(cache, UTIL, MAIN),
        "cached output must be byte-identical to the plain bundle");
  }

  @Test
  void bodyEditRecompilesOnlyTheChangedModule(@TempDir Path cache) throws IOException {
    JsCompiler.appBundleProjectCached(cache, UTIL, MAIN);
    long afterFirst = fragmentCount(cache); // one fragment per module
    assertEquals(2, afterFirst, "two module fragments cached");

    // Edit only Util's body (same exposed name `square`); Main's interface salt is unchanged.
    String utilV2 = "module Util exposing (square)\nsquare n = n * n + 0\n";
    JsCompiler.appBundleProjectCached(cache, utilV2, MAIN);
    long afterEdit = fragmentCount(cache);
    // Main reused (no new fragment); Util got exactly one new fragment (its old one orphaned).
    assertEquals(afterFirst + 1, afterEdit, "only the edited module recompiled");
  }

  @Test
  void secondBuildIsCached(@TempDir Path cache) throws IOException {
    JsCompiler.appBundleProjectCached(cache, UTIL, MAIN);
    long after = fragmentCount(cache);
    JsCompiler.appBundleProjectCached(cache, UTIL, MAIN); // identical inputs -> no new fragments
    assertEquals(after, fragmentCount(cache), "an unchanged rebuild writes nothing new");
    assertTrue(JsCompiler.appBundleProjectCached(cache, UTIL, MAIN).contains("square"));
  }
}

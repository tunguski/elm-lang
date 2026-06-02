package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests the semver model and the backtracking dependency solver against an in-memory registry. */
class SolverTest {

  // --- versions & constraints --------------------------------------------

  @Test
  void versionsParseAndOrder() {
    assertTrue(Version.parse("1.2.3").compareTo(Version.parse("1.10.0")) < 0);
    assertEquals(new Version(2, 0, 0), Version.parse("1.9.9").nextMajor());
  }

  @Test
  void constraintsAllowAndIntersect() {
    Constraint c = Constraint.parse("1.0.0 <= v < 2.0.0");
    assertTrue(c.allows(Version.parse("1.5.0")));
    assertTrue(!c.allows(Version.parse("2.0.0")));
    // An exact pin "1.0.5" admits only 1.0.5.
    Constraint exact = Constraint.parse("1.0.5");
    assertTrue(exact.allows(Version.parse("1.0.5")) && !exact.allows(Version.parse("1.0.6")));
    // Intersection narrows; disjoint ranges yield null.
    Constraint narrowed = c.intersect(Constraint.parse("1.4.0 <= v < 3.0.0"));
    assertEquals(Version.parse("1.4.0"), narrowed.lower());
    assertEquals(Version.parse("2.0.0"), narrowed.upper());
    assertEquals(null, Constraint.parse("1.0.0 <= v < 2.0.0").intersect(Constraint.parse("2.0.0 <= v < 3.0.0")));
  }

  // --- a tiny in-memory registry -----------------------------------------

  /** A registry built from {@code "pkg@version" -> {dep: constraint}} entries. */
  private static Registry registry(Map<String, Map<String, String>> data) {
    Map<String, List<Version>> versions = new LinkedHashMap<>();
    Map<String, Map<String, Constraint>> deps = new LinkedHashMap<>();
    data.forEach(
        (key, depMap) -> {
          String pkg = key.substring(0, key.indexOf('@'));
          Version v = Version.parse(key.substring(key.indexOf('@') + 1));
          versions.computeIfAbsent(pkg, p -> new java.util.ArrayList<>()).add(v);
          Map<String, Constraint> cs = new LinkedHashMap<>();
          depMap.forEach((d, c) -> cs.put(d, Constraint.parse(c)));
          deps.put(key, cs);
        });
    versions.values().forEach(l -> l.sort(null));
    return new Registry() {
      @Override
      public List<Version> versions(String pkg) {
        return versions.getOrDefault(pkg, List.of());
      }

      @Override
      public Map<String, Constraint> dependencies(String pkg, Version version) {
        return deps.getOrDefault(pkg + "@" + version, Map.of());
      }
    };
  }

  @Test
  void solvesTransitiveClosurePreferringHighestVersions() {
    Registry reg =
        registry(
            Map.of(
                "app/a@1.0.0", Map.of("lib/b", "1.0.0 <= v < 2.0.0"),
                "lib/b@1.0.0", Map.of("lib/c", "1.0.0 <= v < 2.0.0"),
                "lib/b@1.2.0", Map.of("lib/c", "1.0.0 <= v < 2.0.0"),
                "lib/c@1.0.0", Map.of(),
                "lib/c@1.4.0", Map.of()));
    Map<String, Version> solution =
        new Solver(reg).solve(Map.of("app/a", Constraint.parse("1.0.0 <= v < 2.0.0")));
    assertEquals(Version.parse("1.0.0"), solution.get("app/a"));
    assertEquals(Version.parse("1.2.0"), solution.get("lib/b"), "highest allowed b");
    assertEquals(Version.parse("1.4.0"), solution.get("lib/c"), "highest allowed c");
  }

  @Test
  void prefersNonYankedVersionsButFallsBackToAPinnedYankedOne() {
    Registry base =
        registry(
            Map.of(
                "lib/c@1.0.0", Map.of(),
                "lib/c@1.4.0", Map.of(),
                "lib/c@1.5.0", Map.of()));
    // 1.5.0 is yanked: the solver should pick the highest *non*-yanked allowed version, 1.4.0.
    Registry yanking =
        new Registry() {
          @Override
          public List<Version> versions(String pkg) {
            return base.versions(pkg);
          }

          @Override
          public Map<String, Constraint> dependencies(String pkg, Version version) {
            return base.dependencies(pkg, version);
          }

          @Override
          public boolean isYanked(String pkg, Version version) {
            return pkg.equals("lib/c") && version.equals(Version.parse("1.5.0"));
          }
        };
    Map<String, Version> solution =
        new Solver(yanking).solve(Map.of("lib/c", Constraint.parse("1.0.0 <= v < 2.0.0")));
    assertEquals(Version.parse("1.4.0"), solution.get("lib/c"), "skips the yanked 1.5.0");

    // But an exact pin to the yanked version still resolves (it's the only candidate).
    Map<String, Version> pinned =
        new Solver(yanking).solve(Map.of("lib/c", Constraint.parse("1.5.0")));
    assertEquals(Version.parse("1.5.0"), pinned.get("lib/c"), "a pinned yanked version still resolves");
  }

  @Test
  void backtracksAwayFromAVersionWhoseDepsConflict() {
    // x@2.0.0 needs shared 2.x, but the app pins shared 1.x — the solver must fall back to x@1.0.0.
    Registry reg =
        registry(
            Map.of(
                "p/x@1.0.0", Map.of("p/shared", "1.0.0 <= v < 2.0.0"),
                "p/x@2.0.0", Map.of("p/shared", "2.0.0 <= v < 3.0.0"),
                "p/shared@1.5.0", Map.of(),
                "p/shared@2.1.0", Map.of()));
    Map<String, Version> solution =
        new Solver(reg)
            .solve(
                Map.of(
                    "p/x", Constraint.parse("1.0.0 <= v < 3.0.0"),
                    "p/shared", Constraint.parse("1.0.0 <= v < 2.0.0")));
    assertEquals(Version.parse("1.0.0"), solution.get("p/x"), "must back off to x 1.x");
    assertEquals(Version.parse("1.5.0"), solution.get("p/shared"));
  }

  @Test
  void throwsWhenUnsatisfiable() {
    Registry reg = registry(Map.of("p/x@1.0.0", Map.of("p/y", "5.0.0 <= v < 6.0.0"), "p/y@1.0.0", Map.of()));
    assertThrows(
        Solver.Unsolvable.class,
        () -> new Solver(reg).solve(Map.of("p/x", Constraint.parse("1.0.0 <= v < 2.0.0"))));
  }
}

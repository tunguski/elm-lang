package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Tests the dependency lockfile: deterministic rendering, round-tripping, and integrity checks. */
class LockfileTest {

  /** A registry built from {@code "pkg@version" -> {dep: constraint}} entries. */
  private static Registry registry(Map<String, Map<String, String>> data) {
    Map<String, java.util.List<Version>> versions = new LinkedHashMap<>();
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

  private static Map<String, Version> solution(String... pkgAtVersion) {
    Map<String, Version> out = new TreeMap<>();
    for (String s : pkgAtVersion) {
      out.put(s.substring(0, s.indexOf('@')), Version.parse(s.substring(s.indexOf('@') + 1)));
    }
    return out;
  }

  @Test
  void rendersDeterministicallyAndRoundTrips() {
    Registry reg =
        registry(
            Map.of(
                "elm/regex@1.0.0", Map.of("elm/core", "1.0.0 <= v < 2.0.0"),
                "elm/core@1.0.5", Map.of()));
    Lockfile lock = Lockfile.of(solution("elm/regex@1.0.0", "elm/core@1.0.5"), reg);

    String text = lock.render();
    assertTrue(text.contains("lockfileVersion 1"), text);
    // Packages are listed in sorted order, each with a sha256 integrity hash.
    assertTrue(text.indexOf("elm/core 1.0.5 sha256-") < text.indexOf("elm/regex 1.0.0 sha256-"), text);

    Lockfile reparsed = Lockfile.parse(text);
    assertEquals(Version.parse("1.0.5"), reparsed.packages().get("elm/core").version());
    assertEquals(
        lock.packages().get("elm/regex").integrity(),
        reparsed.packages().get("elm/regex").integrity(),
        "integrity hash survives the round-trip");
  }

  @Test
  void integrityIsStableAndIndependentOfDependencyOrder() {
    // Two registries that declare the same dependencies in different insertion orders must hash equal.
    Registry a =
        registry(Map.of("p/q@2.1.0", new LinkedHashMap<>(Map.of("a/b", "1.0.0 <= v < 2.0.0", "c/d", "1.0.0 <= v < 2.0.0"))));
    Map<String, String> reversed = new LinkedHashMap<>();
    reversed.put("c/d", "1.0.0 <= v < 2.0.0");
    reversed.put("a/b", "1.0.0 <= v < 2.0.0");
    Registry b = registry(Map.of("p/q@2.1.0", reversed));
    assertEquals(
        Lockfile.integrity("p/q", Version.parse("2.1.0"), a),
        Lockfile.integrity("p/q", Version.parse("2.1.0"), b));
  }

  @Test
  void verifyDetectsAChangedDependencySet() {
    Registry original = registry(Map.of("p/q@1.0.0", Map.of("a/b", "1.0.0 <= v < 2.0.0")));
    Lockfile lock = Lockfile.of(solution("p/q@1.0.0"), original);
    assertTrue(lock.verify(original).isEmpty(), "verifies against the registry it was built from");

    // The registry now serves a *different* dependency set for the same pinned version: tampering.
    Registry tampered = registry(Map.of("p/q@1.0.0", Map.of("a/b", "1.0.0 <= v < 3.0.0")));
    List<String> problems = lock.verify(tampered);
    assertFalse(problems.isEmpty(), "the integrity mismatch is detected");
    assertTrue(problems.get(0).contains("integrity mismatch for p/q"), problems.toString());
  }
}

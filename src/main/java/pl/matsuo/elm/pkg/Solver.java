package pl.matsuo.elm.pkg;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A dependency solver: given a set of top-level version constraints and a {@link Registry}, it finds
 * an assignment of one exact {@link Version} to every package in the transitive closure such that
 * every package's declared dependency constraints are satisfied. It prefers the highest allowed
 * version of each package and backtracks when a choice leaves a later package unsatisfiable.
 */
public final class Solver {

  /** Thrown when no version assignment satisfies all constraints. */
  public static final class Unsolvable extends RuntimeException {
    public Unsolvable(String message) {
      super(message);
    }
  }

  private final Registry registry;

  public Solver(Registry registry) {
    this.registry = registry;
  }

  /**
   * Solves the given root constraints into a full pinned solution (direct + transitive), or throws
   * {@link Unsolvable}. The result is sorted by package name.
   */
  public Map<String, Version> solve(Map<String, Constraint> roots) {
    Map<String, Version> result = step(new TreeMap<>(roots), new TreeMap<>());
    if (result == null) {
      throw new Unsolvable("no version assignment satisfies the constraints for " + roots.keySet());
    }
    return result;
  }

  /**
   * One backtracking step: pick a constrained-but-unassigned package, try its allowed versions from
   * highest to lowest, fold in each candidate's dependency constraints, and recurse.
   */
  private Map<String, Version> step(
      TreeMap<String, Constraint> constraints, TreeMap<String, Version> assigned) {
    String pkg = null;
    for (String name : constraints.keySet()) {
      if (!assigned.containsKey(name)) {
        pkg = name;
        break;
      }
    }
    if (pkg == null) {
      return assigned; // every constrained package has a version — done
    }
    Constraint want = constraints.get(pkg);
    List<Version> versions = registry.versions(pkg);
    for (int i = versions.size() - 1; i >= 0; i--) { // highest version first
      Version v = versions.get(i);
      if (!want.allows(v)) {
        continue;
      }
      TreeMap<String, Constraint> next = new TreeMap<>(constraints);
      if (!merge(next, registry.dependencies(pkg, v), assigned)) {
        continue; // this version's dependencies conflict with what's already decided
      }
      TreeMap<String, Version> withPkg = new TreeMap<>(assigned);
      withPkg.put(pkg, v);
      Map<String, Version> solved = step(next, withPkg);
      if (solved != null) {
        return solved;
      }
    }
    return null; // no version of pkg works under the current constraints — backtrack
  }

  /** Folds {@code deps} into {@code constraints} (intersecting), failing on an empty intersection
   *  or a constraint that excludes an already-assigned version. */
  private static boolean merge(
      Map<String, Constraint> constraints,
      Map<String, Constraint> deps,
      Map<String, Version> assigned) {
    for (Map.Entry<String, Constraint> e : deps.entrySet()) {
      Constraint existing = constraints.get(e.getKey());
      Constraint merged = existing == null ? e.getValue() : existing.intersect(e.getValue());
      if (merged == null) {
        return false;
      }
      Version already = assigned.get(e.getKey());
      if (already != null && !merged.allows(already)) {
        return false;
      }
      constraints.put(e.getKey(), merged);
    }
    return true;
  }
}

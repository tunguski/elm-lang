package pl.matsuo.elm.pkg;

import java.util.List;
import java.util.Map;

/**
 * A source of package metadata for the dependency solver: which versions of a package exist, and
 * what each version depends on. Decoupled from transport so it can be backed by a local directory
 * (offline, used in tests and for vendored packages) or, eventually, a remote registry.
 */
public interface Registry {

  /** All published versions of {@code pkg} (e.g. {@code "elm/core"}); empty if it is unknown. */
  List<Version> versions(String pkg);

  /** The dependency constraints declared by {@code pkg} at {@code version} (its {@code elm.json}). */
  Map<String, Constraint> dependencies(String pkg, Version version);

  /**
   * Whether {@code version} of {@code pkg} has been yanked (withdrawn — e.g. broken or insecure).
   * The solver deprioritises yanked versions, choosing one only when no acceptable non-yanked
   * version exists (so an exact pin to a yanked version still resolves). Defaults to {@code false}
   * for registries that don't track this.
   */
  default boolean isYanked(String pkg, Version version) {
    return false;
  }
}

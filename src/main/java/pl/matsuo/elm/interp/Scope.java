package pl.matsuo.elm.interp;

import java.util.Arrays;

/**
 * A lexical scope: a frame of local bindings (function parameters, {@code let} definitions, pattern
 * bindings) chained to an enclosing scope. Globals (top-level definitions and the prelude) are
 * resolved separately through {@link RuntimeEnv}, so the root scope is empty.
 *
 * <p>A frame holds very few bindings (a function's parameters, a {@code let}'s definitions, a
 * pattern's variables — almost always under ~4), so it is backed by small parallel arrays grown on
 * demand rather than a {@link java.util.HashMap}: allocating one is cheaper, and a linear scan of a
 * handful of slots beats hashing on the hot variable-lookup path. The arrays start empty (shared
 * sentinels) so binding-free frames — e.g. a {@code TailLoop}'s captured base — allocate nothing.
 */
public final class Scope {

  private static final String[] NO_NAMES = new String[0];
  private static final Object[] NO_VALUES = new Object[0];

  private final Scope parent;
  private String[] names = NO_NAMES;
  private Object[] values = NO_VALUES;
  private int size = 0;

  public Scope(Scope parent) {
    this.parent = parent;
  }

  public static Scope root() {
    return new Scope(null);
  }

  public Scope child() {
    return new Scope(this);
  }

  public Scope parent() {
    return parent;
  }

  public void bind(String name, Object value) {
    // Elm patterns are linear and let/parameter names are distinct within a frame, so a name is
    // normally new; scan anyway to preserve HashMap.put's overwrite semantics for any rebind.
    for (int i = 0; i < size; i++) {
      if (names[i].equals(name)) {
        values[i] = value;
        return;
      }
    }
    if (size == names.length) {
      int cap = size == 0 ? 4 : size * 2;
      names = Arrays.copyOf(names, cap);
      values = Arrays.copyOf(values, cap);
    }
    names[size] = name;
    values[size] = value;
    size++;
  }

  /**
   * Clears this frame's bindings (keeping the backing arrays) so a single child scope can be reused
   * across the failing branches of a {@code case} instead of allocating one per branch. Only safe
   * when no binding from a prior use can still be referenced — a failed pattern match runs no body,
   * so nothing captures the stale bindings.
   */
  public void reset() {
    for (int i = 0; i < size; i++) {
      values[i] = null; // drop references so a reused frame doesn't pin a previous attempt's values
    }
    size = 0;
  }

  /** Looks up a local binding, returning {@code null} if not found (Elm values are never null). */
  public Object lookup(String name) {
    for (Scope s = this; s != null; s = s.parent) {
      String[] ns = s.names;
      int n = s.size;
      for (int i = 0; i < n; i++) {
        if (ns[i].equals(name)) {
          return s.values[i];
        }
      }
    }
    return null;
  }
}

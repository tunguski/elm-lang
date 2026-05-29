package pl.matsuo.elm.interp;

import java.util.HashMap;
import java.util.Map;

/**
 * A lexical scope: a frame of local bindings (function parameters, {@code let} definitions, pattern
 * bindings) chained to an enclosing scope. Globals (top-level definitions and the prelude) are
 * resolved separately through {@link RuntimeEnv}, so the root scope is empty.
 */
public final class Scope {

  private final Scope parent;
  private final Map<String, Object> vars;

  public Scope(Scope parent) {
    this.parent = parent;
    this.vars = new HashMap<>();
  }

  public static Scope root() {
    return new Scope(null);
  }

  public Scope child() {
    return new Scope(this);
  }

  public void bind(String name, Object value) {
    vars.put(name, value);
  }

  /** Looks up a local binding, returning {@code null} if not found (Elm values are never null). */
  public Object lookup(String name) {
    for (Scope s = this; s != null; s = s.parent) {
      Object v = s.vars.get(name);
      if (v != null) {
        return v;
      }
    }
    return null;
  }
}

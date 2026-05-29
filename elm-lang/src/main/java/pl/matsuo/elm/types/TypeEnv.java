package pl.matsuo.elm.types;

import java.util.Map;

/** A lexical environment of type schemes: a chain of local bindings over a shared globals map. */
public final class TypeEnv {

  private final TypeEnv parent;
  private final String name;
  private final Scheme scheme;
  private final Map<String, Scheme> globals;

  private TypeEnv(TypeEnv parent, String name, Scheme scheme, Map<String, Scheme> globals) {
    this.parent = parent;
    this.name = name;
    this.scheme = scheme;
    this.globals = globals;
  }

  public static TypeEnv root(Map<String, Scheme> globals) {
    return new TypeEnv(null, null, null, globals);
  }

  public TypeEnv extend(String name, Scheme scheme) {
    return new TypeEnv(this, name, scheme, globals);
  }

  /** Looks up a local binding, then a global, returning {@code null} if unknown. */
  public Scheme lookup(String name) {
    for (TypeEnv e = this; e != null; e = e.parent) {
      if (name.equals(e.name)) {
        return e.scheme;
      }
    }
    return globals.get(name);
  }

  public Map<String, Scheme> globals() {
    return globals;
  }
}

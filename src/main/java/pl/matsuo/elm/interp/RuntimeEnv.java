package pl.matsuo.elm.interp;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.Builtin;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmRecord;

/**
 * Resolves names to values for one module: the prelude {@code builtins} (keyed by canonical
 * {@code Module.name}), this module's {@code topLevel} definitions, and the import bindings
 * ({@code unqualified} exposed names and module {@code aliases}). Also constructs custom-type values.
 */
public final class RuntimeEnv {

  private final Map<String, Object> builtins;
  private final Map<String, Object> topLevel = new HashMap<>();
  private final Map<String, String> unqualified;
  private final Map<String, String> aliases;
  private final Map<String, Integer> ctorArity;
  private final Map<String, List<String>> recordConstructors;
  private final String currentModule;

  public RuntimeEnv(
      Map<String, Object> builtins,
      Map<String, String> unqualified,
      Map<String, String> aliases,
      Map<String, Integer> ctorArity,
      Map<String, List<String>> recordConstructors,
      String currentModule) {
    this.builtins = builtins;
    this.unqualified = unqualified;
    this.aliases = aliases;
    this.ctorArity = ctorArity;
    this.recordConstructors = recordConstructors;
    this.currentModule = currentModule;
  }

  public void defineTopLevel(String name, Object value) {
    topLevel.put(name, value);
  }

  /** Registers the constructors/record-aliases of any {@code type} declared inside a {@code let} of
   *  {@code expr}, so a standalone expression that introduces a local type resolves at runtime. */
  public void registerLetTypes(pl.matsuo.elm.ast.Expr expr) {
    TypeDecls.scanExpr(expr, ctorArity, recordConstructors);
  }

  public Map<String, Object> topLevel() {
    return topLevel;
  }

  public Object resolveGlobal(String name) {
    Object top = topLevel.get(name);
    if (top != null) {
      return Thunk.resolve(top);
    }
    String canonical = unqualified.get(name);
    if (canonical != null) {
      Object v = builtins.get(canonical);
      if (v != null) {
        return Thunk.resolve(v);
      }
    }
    Object direct = builtins.get(name);
    if (direct != null) {
      return Thunk.resolve(direct);
    }
    throw new ElmRuntimeError("Unbound variable: " + name);
  }

  public Object resolveQualified(String module, String name) {
    String realModule = aliases.getOrDefault(module, module);
    Object v = builtins.get(realModule + "." + name);
    if (v != null) {
      return Thunk.resolve(v);
    }
    if (realModule.equals(currentModule)) {
      Object top = topLevel.get(name);
      if (top != null) {
        return Thunk.resolve(top);
      }
    }
    throw new ElmRuntimeError("Unbound qualified name: " + module + "." + name);
  }

  /** A module's OWN constructors, used to resolve a reference against its defining module so a name
   * reused across modules (a record alias here, a union constructor there) doesn't collide. */
  public record ModuleCtors(Map<String, List<String>> records, Map<String, Integer> unions) {}

  private Map<String, ModuleCtors> moduleCtors;

  /** Supplies per-module constructor tables (project-wide, keyed by module name) so qualified
   * constructor references resolve against their defining module; absent (single-module compile),
   * resolution falls back to the flat tables. */
  public void setModuleCtors(Map<String, ModuleCtors> moduleCtors) {
    this.moduleCtors = moduleCtors;
  }

  /** The defining module's own constructors for a reference qualified by {@code module} (an import
   * alias or module name), or null when unqualified / not a bundled module. */
  private ModuleCtors ctorsOf(String module) {
    return module == null || moduleCtors == null
        ? null
        : moduleCtors.get(aliases.getOrDefault(module, module));
  }

  /** The field names of a record-type-alias constructor, or null if {@code name} isn't one. */
  public List<String> recordConstructorFields(String name) {
    return recordConstructorFields(null, name);
  }

  /** As {@link #recordConstructorFields(String)} but resolved against the reference's module. */
  public List<String> recordConstructorFields(String module, String name) {
    ModuleCtors mc = ctorsOf(module);
    if (mc != null) {
      if (mc.records().containsKey(name)) {
        return mc.records().get(name);
      }
      if (mc.unions().containsKey(name)) {
        return null; // a union constructor in its module, not a record alias
      }
    }
    return recordConstructors.get(name); // unqualified / unknown module: flat tables
  }

  /** A union constructor's arity, or -1 if {@code name} isn't a known (non-record) constructor. */
  public int unionConstructorArity(String name) {
    return unionConstructorArity(null, name);
  }

  /** As {@link #unionConstructorArity(String)} but resolved against the reference's module. */
  public int unionConstructorArity(String module, String name) {
    ModuleCtors mc = ctorsOf(module);
    if (mc != null) {
      if (mc.unions().containsKey(name)) {
        return mc.unions().get(name);
      }
      if (mc.records().containsKey(name)) {
        return -1; // a record alias in its module, not a union constructor
      }
    }
    return ctorArity.containsKey(name) ? ctorArity.get(name) : -1;
  }

  public Object constructorValue(String name) {
    return constructorValue(null, name);
  }

  /** As {@link #constructorValue(String)} but resolved against the reference's module, so a qualified
   * {@code Game.Move} is Game's union constructor, not another module's record-alias {@code Move}. */
  public Object constructorValue(String module, String name) {
    if (name.equals("True")) {
      return Boolean.TRUE;
    }
    if (name.equals("False")) {
      return Boolean.FALSE;
    }
    List<String> fields = recordConstructorFields(module, name);
    if (fields != null) {
      return recordCtor(name, fields);
    }
    int arity = unionConstructorArity(module, name);
    if (arity <= 0) {
      arity = Math.max(arity, ctorArity.getOrDefault(name, 0)); // unknown -> flat default (0)
    }
    if (arity <= 0) {
      return new ElmData(name, new Object[0]);
    }
    return new Builtin(name, arity, args -> new ElmData(name, args.clone()));
  }

  private static Builtin recordCtor(String name, List<String> fields) {
    return new Builtin(
        name,
        fields.size(),
        args -> {
          Map<String, Object> rec = new LinkedHashMap<>();
          for (int i = 0; i < fields.size(); i++) {
            rec.put(fields.get(i), args[i]);
          }
          return new ElmRecord(rec);
        });
  }
}

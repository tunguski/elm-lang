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
        return v;
      }
    }
    Object direct = builtins.get(name);
    if (direct != null) {
      return direct;
    }
    throw new ElmRuntimeError("Unbound variable: " + name);
  }

  public Object resolveQualified(String module, String name) {
    String realModule = aliases.getOrDefault(module, module);
    Object v = builtins.get(realModule + "." + name);
    if (v != null) {
      return v;
    }
    if (realModule.equals(currentModule)) {
      Object top = topLevel.get(name);
      if (top != null) {
        return Thunk.resolve(top);
      }
    }
    throw new ElmRuntimeError("Unbound qualified name: " + module + "." + name);
  }

  public Object constructorValue(String name) {
    if (name.equals("True")) {
      return Boolean.TRUE;
    }
    if (name.equals("False")) {
      return Boolean.FALSE;
    }
    List<String> fields = recordConstructors.get(name);
    if (fields != null) {
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
    int arity = ctorArity.getOrDefault(name, 0);
    if (arity == 0) {
      return new ElmData(name, new Object[0]);
    }
    return new Builtin(name, arity, args -> new ElmData(name, args.clone()));
  }
}

package pl.matsuo.elm.interp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.parser.Parser;

/**
 * Loads several Elm modules together and resolves references across them. Each module gets its own
 * {@link RuntimeEnv}, but all share one {@code globals} map (a copy of the prelude plus every user
 * definition keyed by {@code Module.name}) and one constructor registry, so qualified ({@code
 * Mod.fn}) and exposed-unqualified references resolve across module boundaries. Definitions are
 * lazy (functions are closures, values are {@link Thunk}s), so module/definition order is irrelevant.
 */
public final class Project {

  private final Map<String, Module> modules = new LinkedHashMap<>();
  private final Map<String, RuntimeEnv> envs = new HashMap<>();
  private final Map<String, Object> globals;
  /** Module names whose top-level functions are coverage-tracked, or null for no tracking. */
  private final java.util.Set<String> tracked;
  private final java.util.Set<String> coverable = new java.util.LinkedHashSet<>();
  private final java.util.Set<String> covered = new java.util.HashSet<>();

  private Project(List<String> sources) {
    this(sources, null);
  }

  private Project(List<String> sources, java.util.Set<String> tracked) {
    this.tracked = tracked;
    // Gather every module's `infix` operator declarations first, so a custom operator defined in one
    // module parses with its declared precedence wherever it's used across the project.
    Map<String, int[]> projectFixities = new HashMap<>();
    for (String source : sources) {
      projectFixities.putAll(Parser.scanFixities(source));
    }
    for (String source : sources) {
      Module module = Parser.parseModule(source, projectFixities);
      modules.put(module.name(), module);
    }

    Map<String, Integer> ctorArity = Prelude.defaultCtorArity();
    Map<String, List<String>> recordCtors = new HashMap<>();
    for (Module m : modules.values()) {
      for (Decl d : m.decls()) {
        if (d instanceof Decl.Union u) {
          for (Decl.Union.Variant v : u.variants()) {
            ctorArity.put(v.name(), v.args().size());
          }
        }
        if (d instanceof Decl.TypeAlias ta
            && ta.type() instanceof Type.Record rec
            && rec.base().isEmpty()) {
          recordCtors.put(ta.name(), rec.fields().stream().map(Type.Record.Field::name).toList());
        }
      }
    }

    globals = new HashMap<>(Prelude.builtins());

    for (Module m : modules.values()) {
      envs.put(m.name(), buildEnv(m, ctorArity, recordCtors));
    }
    for (Module m : modules.values()) {
      loadModule(m);
    }
  }

  public static Project load(String... sources) {
    return new Project(List.of(sources));
  }

  /** Loads with coverage tracking of the top-level functions in {@code trackedModules}. */
  public static Project loadWithCoverage(List<String> sources, java.util.Set<String> trackedModules) {
    return new Project(sources, trackedModules);
  }

  /** A one-line-per-function coverage report ("✓"/"✗") over the tracked modules, with a summary. */
  public String coverageReport() {
    StringBuilder sb = new StringBuilder();
    for (String name : coverable) {
      sb.append(covered.contains(name) ? "✓ " : "✗ ").append(name).append("\n");
    }
    int total = coverable.size();
    int hit = covered.size();
    int pct = total == 0 ? 100 : 100 * hit / total;
    sb.append(hit).append("/").append(total).append(" functions exercised (").append(pct).append("%)\n");
    return sb.toString();
  }

  /** Wraps a top-level function so its invocation records the (unqualified) name as covered. */
  private Object recordingValue(String name, Object value) {
    if (!(value instanceof pl.matsuo.elm.runtime.ElmCallable c)) {
      return value;
    }
    return new pl.matsuo.elm.runtime.ElmCallable() {
      @Override
      public int arity() {
        return c.arity();
      }

      @Override
      public String name() {
        return c.name();
      }

      @Override
      public Object invoke(Object[] args) {
        covered.add(name);
        return c.invoke(args);
      }
    };
  }

  private RuntimeEnv buildEnv(
      Module m, Map<String, Integer> ctorArity, Map<String, List<String>> recordCtors) {
    Map<String, String> unqualified = Prelude.defaultUnqualified();
    Map<String, String> aliases = new HashMap<>();
    for (Module.Import imp : m.imports()) {
      imp.alias().ifPresent(a -> aliases.put(a, imp.module()));
      if (imp.exposing().open()) {
        String prefix = imp.module() + ".";
        for (String key : globals.keySet()) {
          if (key.startsWith(prefix)) {
            unqualified.put(key.substring(prefix.length()), key);
          }
        }
        Module target = modules.get(imp.module());
        if (target != null) {
          for (String name : exposedValueNames(target)) {
            unqualified.put(name, imp.module() + "." + name);
          }
        }
      } else {
        for (String name : imp.exposing().names()) {
          unqualified.put(name, imp.module() + "." + name);
        }
      }
    }
    return new RuntimeEnv(globals, unqualified, aliases, ctorArity, recordCtors, m.name());
  }

  private void loadModule(Module m) {
    RuntimeEnv env = envs.get(m.name());
    Compiler compiler = new Compiler(env);
    Scope root = Scope.root();
    for (Decl d : m.decls()) {
      if (d instanceof Decl.Value v) {
        Object value;
        if (v.params().isEmpty()) {
          ElmNode node = compiler.compile(v.body());
          value = new Thunk(() -> node.execute(root));
        } else {
          value = compiler.compileLambda(v.params(), v.body(), v.name()).execute(root);
        }
        if (tracked != null && tracked.contains(m.name()) && !v.params().isEmpty()) {
          coverable.add(v.name());
          value = recordingValue(v.name(), value);
        }
        env.defineTopLevel(v.name(), value);
        globals.put(m.name() + "." + v.name(), value);
      }
    }
  }

  private static List<String> exposedValueNames(Module m) {
    if (!m.exposing().open()) {
      return m.exposing().names();
    }
    List<String> names = new ArrayList<>();
    for (Decl d : m.decls()) {
      if (d instanceof Decl.Value v) {
        names.add(v.name());
      }
    }
    return names;
  }

  /** Looks up a top-level definition in a specific module. */
  public Object value(String moduleName, String defName) {
    Object v = globals.get(moduleName + "." + defName);
    if (v == null) {
      throw new ElmRuntimeError("No definition " + moduleName + "." + defName);
    }
    return Thunk.resolve(v);
  }

  /**
   * Looks up a top-level definition by simple name, preferring the {@code Main} module, else the
   * first module that defines it. Used to find an entry like {@code handle} in a server app.
   */
  public Object entryValue(String defName) {
    if (globals.containsKey("Main." + defName)) {
      return value("Main", defName);
    }
    for (String name : modules.keySet()) {
      if (globals.containsKey(name + "." + defName)) {
        return value(name, defName);
      }
    }
    throw new ElmRuntimeError("No '" + defName + "' definition found in project");
  }

  /** The {@code main} of the {@code Main} module (or the first module that defines {@code main}). */
  public Object main() {
    if (globals.containsKey("Main.main")) {
      return value("Main", "main");
    }
    for (String name : modules.keySet()) {
      if (globals.containsKey(name + ".main")) {
        return value(name, "main");
      }
    }
    throw new ElmRuntimeError("No 'main' definition found in project");
  }
}

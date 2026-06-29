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
import pl.matsuo.elm.error.ElmTypeError;
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

  private Project(List<String> rawSources, java.util.Set<String> tracked) {
    this.tracked = tracked;
    // Pull in any imported pure bundled libraries (List.Extra, Maybe.Extra, Hex, …) the caller
    // didn't supply, so they can be imported without special wiring.
    List<String> sources = BundledLibs.resolve(rawSources);
    // Gather every module's `infix` operator declarations first, so a custom operator defined in one
    // module parses with its declared precedence wherever it's used across the project.
    Map<String, int[]> projectFixities = new HashMap<>();
    for (String source : sources) {
      projectFixities.putAll(Parser.scanFixities(source));
    }
    for (String source : sources) {
      Module module = Parser.parseModule(source, projectFixities);
      checkUniqueTopLevel(module);
      modules.put(module.name(), module);
    }

    Map<String, Integer> preludeCtorArity = Prelude.defaultCtorArity();
    // Per-module constructor tables (each module's OWN types), so a qualified `Game.Move` resolves to
    // Game's union constructor rather than another module's record-alias `Move`, and so each module's
    // flat fallback table (built below) can be scoped to what's actually in that module's scope.
    Map<String, RuntimeEnv.ModuleCtors> moduleCtors = new HashMap<>();
    Map<String, Map<String, Integer>> ownUnions = new HashMap<>();
    Map<String, Map<String, List<String>>> ownRecords = new HashMap<>();
    for (Module m : modules.values()) {
      Map<String, Integer> unions = new HashMap<>();
      Map<String, List<String>> records = new HashMap<>();
      TypeDecls.scanModule(m, unions, records);
      moduleCtors.put(m.name(), new RuntimeEnv.ModuleCtors(records, unions));
      ownUnions.put(m.name(), unions);
      ownRecords.put(m.name(), records);
    }

    globals = new HashMap<>(Prelude.builtins());

    for (Module m : modules.values()) {
      // The flat fallback constructor tables are MODULE-LOCAL: this module's own constructors win,
      // then those brought into scope by its imports, then the Prelude defaults. Scoping the fallback
      // per module (rather than one shared project-wide table) stops a constructor name reused across
      // modules — e.g. a nullary `Duplicate` in one and a `Duplicate Int` in another — from resolving
      // to the wrong arity when referenced UNQUALIFIED through an import (the name isn't in the
      // importing module's OWN table, so resolution previously fell through to a colliding global
      // table and built a nullary value as an unsaturated function → "Non-exhaustive pattern match on
      // <builtin …>"). Mirrors the type checker's per-import constructor scoping (see Infer).
      Map<String, Integer> ctorArity = new HashMap<>(ownUnions.get(m.name()));
      Map<String, List<String>> recordCtors = new HashMap<>(ownRecords.get(m.name()));
      for (Module.Import imp : m.imports()) {
        Map<String, Integer> importedUnions = ownUnions.get(imp.module());
        if (importedUnions != null) {
          importedUnions.forEach(ctorArity::putIfAbsent);
        }
        Map<String, List<String>> importedRecords = ownRecords.get(imp.module());
        if (importedRecords != null) {
          importedRecords.forEach(recordCtors::putIfAbsent);
        }
      }
      preludeCtorArity.forEach(ctorArity::putIfAbsent);
      RuntimeEnv env = buildEnv(m, ctorArity, recordCtors);
      env.setModuleCtors(moduleCtors);
      envs.put(m.name(), env);
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

  /** A self-contained HTML coverage report: every tracked function, marked hit or miss, with a
   * percentage summary — suitable as a CI artifact. */
  public String coverageHtml() {
    int total = coverable.size();
    int hit = covered.size();
    int pct = total == 0 ? 100 : 100 * hit / total;
    StringBuilder rows = new StringBuilder();
    for (String name : coverable) {
      boolean ok = covered.contains(name);
      rows.append("<li class=\"")
          .append(ok ? "hit" : "miss")
          .append("\"><span class=\"mark\">")
          .append(ok ? "✓" : "✗")
          .append("</span> ")
          .append(escapeHtml(name))
          .append("</li>\n");
    }
    return "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        + "<title>elm test — coverage</title><style>"
        + "body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:24px;color:#293c4b}"
        + "h1{font-size:1.4rem}.bar{height:14px;background:#f0d5d5;border-radius:7px;overflow:hidden;max-width:480px}"
        + ".bar>span{display:block;height:100%;background:#3fae5a}"
        + "ul{list-style:none;padding:0;max-width:480px}"
        + "li{padding:4px 8px;border-bottom:1px solid #eee;font-family:monospace}"
        + ".hit .mark{color:#246b1e}.miss{color:#9a1e1e}.miss .mark{color:#9a1e1e}"
        + "</style></head><body>"
        + "<h1>Coverage — " + hit + "/" + total + " functions exercised (" + pct + "%)</h1>"
        + "<div class=\"bar\"><span style=\"width:" + pct + "%\"></span></div><ul>\n"
        + rows
        + "</ul></body></html>\n";
  }

  private static String escapeHtml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

  /**
   * Reject a module that defines the same top-level value name twice. The interpreter does no type
   * checking, so without this a duplicate definition would silently last-win — every reference to
   * the name picks up whichever definition was parsed last (a duplicate test fixture, say, can
   * quietly hijack an existing one). The type checker ({@code make}/{@code check}) catches this in
   * {@code Infer}; this is the same guard for the interpreter-backed commands ({@code test}/{@code
   * run}/{@code script}). A type annotation {@code f : T} is merged into its {@code f = …} value by
   * the parser, so it is not counted as a separate definition.
   */
  private static void checkUniqueTopLevel(Module m) {
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (Decl d : m.decls()) {
      if (d instanceof Decl.Value v && !seen.add(v.name())) {
        throw new ElmTypeError(
            "The name `"
                + v.name()
                + "` is defined more than once at the top level of module `"
                + m.name()
                + "`. Remove or rename one of the definitions.",
            v.pos());
      }
    }
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

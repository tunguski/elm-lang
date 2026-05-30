package pl.matsuo.elm.interp;

import java.util.HashMap;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.parser.Parser;

/**
 * Loads an Elm {@link Module} into a {@link RuntimeEnv} and evaluates definitions/expressions using
 * the Truffle interpreter. Top-level functions are bound first (as closures) so that recursion and
 * forward references work, then top-level values are evaluated in source order.
 */
public final class Interpreter {

  private final RuntimeEnv env;
  private final Compiler compiler;
  private final Scope rootScope = Scope.root();
  private final boolean coverage;
  private final java.util.Set<String> coverable = new java.util.LinkedHashSet<>();
  private final java.util.Set<String> covered = new java.util.LinkedHashSet<>();

  private Interpreter(Module module) {
    this(module, false);
  }

  private Interpreter(Module module, boolean coverage) {
    this.coverage = coverage;
    Map<String, Integer> ctorArity = Prelude.defaultCtorArity();
    Map<String, java.util.List<String>> recordCtors = new HashMap<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union union) {
        for (Decl.Union.Variant v : union.variants()) {
          ctorArity.put(v.name(), v.args().size());
        }
      }
      if (d instanceof Decl.TypeAlias ta
          && ta.type() instanceof pl.matsuo.elm.ast.Type.Record rec
          && rec.base().isEmpty()) {
        recordCtors.put(ta.name(), rec.fields().stream().map(f -> f.name()).toList());
      }
    }

    Map<String, String> unqualified = Prelude.defaultUnqualified();
    Map<String, String> aliases = new HashMap<>();
    for (Module.Import imp : module.imports()) {
      imp.alias().ifPresent(a -> aliases.put(a, imp.module()));
      if (imp.exposing().open()) {
        String prefix = imp.module() + ".";
        for (String key : Prelude.builtins().keySet()) {
          if (key.startsWith(prefix)) {
            unqualified.put(key.substring(prefix.length()), key);
          }
        }
      } else {
        for (String name : imp.exposing().names()) {
          unqualified.put(name, imp.module() + "." + name);
        }
      }
    }

    this.env =
        new RuntimeEnv(
            Prelude.builtins(), unqualified, aliases, ctorArity, recordCtors, module.name());

    // Best-effort type inference: when the module type-checks, use the inferred types to coerce
    // Int literals that occur in Float contexts. If inference fails (an unsigned builtin, or a real
    // type error), fall back to untyped evaluation so every program still runs.
    java.util.Set<Expr> floatLiterals = java.util.Set.of();
    try {
      pl.matsuo.elm.types.Infer infer = new pl.matsuo.elm.types.Infer();
      infer.inferModule(module, pl.matsuo.elm.types.Signatures.globals());
      floatLiterals = infer.floatLiterals();
    } catch (Throwable ignored) {
      // Any inference failure (unsigned builtin, type error, or pathological recursion): keep untyped.
    }
    this.compiler = new Compiler(env, floatLiterals);
    load(module);
  }

  public static Interpreter load(String source) {
    return new Interpreter(Parser.parseModule(source));
  }

  /** Loads with execution coverage tracking of top-level definitions (see {@link #coverageReport}). */
  public static Interpreter loadWithCoverage(String source) {
    return new Interpreter(Parser.parseModule(source), true);
  }

  /** Top-level definitions defined in the module (the coverable set). */
  public java.util.Set<String> coverableNames() {
    return coverable;
  }

  /** Top-level definitions actually executed so far (a subset of {@link #coverableNames}). */
  public java.util.Set<String> coveredNames() {
    return covered;
  }

  /** A one-line-per-definition coverage report ("✓"/"✗"), with a percentage summary. */
  public String coverageReport() {
    StringBuilder sb = new StringBuilder();
    for (String name : coverable) {
      sb.append(covered.contains(name) ? "✓ " : "✗ ").append(name).append("\n");
    }
    int total = coverable.size();
    int hit = covered.size();
    int pct = total == 0 ? 100 : 100 * hit / total;
    sb.append("\n").append(hit).append("/").append(total).append(" definitions (").append(pct).append("%)\n");
    return sb.toString();
  }

  /** Builds an interpreter with only the prelude, for evaluating standalone expressions. */
  public static Interpreter empty() {
    return new Interpreter(new Module("Main", Module.Exposing.ALL, java.util.List.of(),
        java.util.List.of(), new pl.matsuo.elm.error.Position(1, 1, 0)));
  }

  /** Convenience: evaluate a single expression with only the prelude in scope. */
  public static Object eval(String expression) {
    return empty().evalExpr(expression);
  }

  private void load(Module module) {
    // Pass 1: bind all functions (definitions with parameters) as closures.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && !v.params().isEmpty()) {
        coverable.add(v.name());
        Object closure = compiler.compileLambda(v.params(), v.body(), v.name()).execute(rootScope);
        env.defineTopLevel(v.name(), recordingValue(v.name(), closure));
      }
    }
    // Pass 2: bind values (definitions without parameters) as lazy thunks, so they may
    // reference one another regardless of source order.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.params().isEmpty()) {
        coverable.add(v.name());
        ElmNode node = compiler.compile(v.body());
        String name = v.name();
        env.defineTopLevel(
            name,
            new Thunk(
                () -> {
                  if (coverage) {
                    covered.add(name);
                  }
                  return node.execute(rootScope);
                }));
      }
    }
    // Ports: an outgoing port (`a -> Cmd msg`) is a function producing a Cmd that carries the sent
    // value; an incoming port (`(a -> msg) -> Sub msg`) yields a Sub. Headlessly the runtime has no
    // JS peer, so the Cmd is inert and the Sub never fires — but a port module still loads and runs.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Port p) {
        env.defineTopLevel(p.name(), portValue(p));
      }
    }
  }

  /** A 1-argument function value implementing a port: outgoing -> Cmd, incoming -> Sub. */
  private static Object portValue(Decl.Port p) {
    String name = p.name();
    boolean incoming = returnsSub(p.type());
    return new pl.matsuo.elm.runtime.Builtin(
        name,
        1,
        args ->
            incoming
                ? new pl.matsuo.elm.runtime.ElmData("$SubNone", new Object[0])
                : new pl.matsuo.elm.runtime.ElmData(
                    "$Cmd_Port", new Object[] {name, args[0]}));
  }

  /** True if the port's annotated type ultimately returns a {@code Sub} (an incoming port). */
  private static boolean returnsSub(pl.matsuo.elm.ast.Type type) {
    pl.matsuo.elm.ast.Type t = type;
    while (t instanceof pl.matsuo.elm.ast.Type.Arrow a) {
      t = a.to();
    }
    return t instanceof pl.matsuo.elm.ast.Type.Con c && c.name().equals("Sub");
  }

  /** When coverage is on, wraps a top-level function so its invocation is recorded as covered. */
  private Object recordingValue(String name, Object value) {
    if (!coverage || !(value instanceof pl.matsuo.elm.runtime.ElmCallable c)) {
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

  public RuntimeEnv env() {
    return env;
  }

  /** Looks up a top-level definition by name (e.g. {@code "main"}). */
  public Object value(String name) {
    Object v = env.topLevel().get(name);
    if (v == null) {
      throw new ElmRuntimeError("No top-level definition named '" + name + "'");
    }
    return Thunk.resolve(v);
  }

  /** Compiles and evaluates an expression against this module's environment. */
  public Object evalExpr(String expression) {
    Expr expr = Parser.parseExpression(expression);
    return compiler.compile(expr).execute(rootScope);
  }
}

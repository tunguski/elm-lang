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

  private Interpreter(Module module) {
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
    } catch (RuntimeException ignored) {
      // keep untyped
    }
    this.compiler = new Compiler(env, floatLiterals);
    load(module);
  }

  public static Interpreter load(String source) {
    return new Interpreter(Parser.parseModule(source));
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
        Object closure = compiler.compileLambda(v.params(), v.body(), v.name()).execute(rootScope);
        env.defineTopLevel(v.name(), closure);
      }
    }
    // Pass 2: bind values (definitions without parameters) as lazy thunks, so they may
    // reference one another regardless of source order.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.params().isEmpty()) {
        ElmNode node = compiler.compile(v.body());
        env.defineTopLevel(v.name(), new Thunk(() -> node.execute(rootScope)));
      }
    }
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

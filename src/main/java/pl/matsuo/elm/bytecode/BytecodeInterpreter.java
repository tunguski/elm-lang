package pl.matsuo.elm.bytecode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.error.Position;
import pl.matsuo.elm.interp.Prelude;
import pl.matsuo.elm.interp.RuntimeEnv;
import pl.matsuo.elm.interp.Scope;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.parser.Parser;

/**
 * Loads an Elm module by compiling each definition to {@link Chunk} bytecode and running it on the
 * {@link VM}. Shares the runtime values, prelude and name resolution with the tree interpreter.
 */
public final class BytecodeInterpreter {

  private final RuntimeEnv env;
  private final BytecodeCompiler compiler = new BytecodeCompiler();
  private final Scope rootScope = Scope.root();

  private BytecodeInterpreter(Module module) {
    Map<String, Integer> ctorArity = Prelude.defaultCtorArity();
    Map<String, List<String>> recordCtors = new HashMap<>();
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
    load(module);
  }

  public static BytecodeInterpreter load(String source) {
    return new BytecodeInterpreter(Parser.parseModule(source));
  }

  public static BytecodeInterpreter empty() {
    return new BytecodeInterpreter(
        new Module("Main", Module.Exposing.ALL, List.of(), List.of(), new Position(1, 1, 0)));
  }

  public static Object eval(String expression) {
    return empty().evalExpr(expression);
  }

  private void load(Module module) {
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && !v.params().isEmpty()) {
        Chunk chunk = compiler.compileChunk(v.params(), v.body(), v.name());
        env.defineTopLevel(v.name(), new BytecodeClosure(chunk, rootScope, env));
      }
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.params().isEmpty()) {
        Chunk chunk = compiler.compileChunk(List.of(), v.body(), v.name());
        env.defineTopLevel(v.name(), new Thunk(() -> VM.run(chunk, rootScope.child(), env)));
      }
    }
  }

  public Object value(String name) {
    Object v = env.topLevel().get(name);
    if (v == null) {
      throw new ElmRuntimeError("No top-level definition named '" + name + "'");
    }
    return Thunk.resolve(v);
  }

  public Object evalExpr(String expression) {
    Chunk chunk = compiler.compileChunk(List.of(), Parser.parseExpression(expression), "<expr>");
    return VM.run(chunk, rootScope.child(), env);
  }
}

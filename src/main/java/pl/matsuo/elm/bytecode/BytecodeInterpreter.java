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

  // The compiled program, captured so it can be emitted as a portable .elmbc artifact (see toProgram).
  private final String moduleName;
  private final Map<String, Integer> ctorArity;
  private final Map<String, List<String>> recordCtors;
  private final Map<String, String> unqualified;
  private final Map<String, String> aliases;
  private final List<BytecodeProgram.Def> defs = new java.util.ArrayList<>();

  private BytecodeInterpreter(Module module) {
    this(List.of(module));
  }

  /** Loads one or more modules into a single program (a flat merge of their top-level definitions —
   *  the first module is the primary one carrying `main`). Cross-file references resolve through the
   *  shared top-level scope; this covers multi-file projects whose modules share a flat namespace. */
  private BytecodeInterpreter(List<Module> modules) {
    Module primary = modules.get(0);
    this.ctorArity = Prelude.defaultCtorArity();
    this.recordCtors = new HashMap<>();
    this.unqualified = Prelude.defaultUnqualified();
    this.aliases = new HashMap<>();
    for (Module module : modules) {
      pl.matsuo.elm.interp.TypeDecls.scanModule(module, ctorArity, recordCtors);
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
    }
    this.moduleName = primary.name();
    this.env = new RuntimeEnv(Prelude.builtins(), unqualified, aliases, ctorArity, recordCtors, moduleName);
    // Per-module constructor tables so a qualified `Mod.Ctor` resolves to its defining module, not
    // a same-named constructor from another module merged into the flat tables above.
    java.util.Map<String, RuntimeEnv.ModuleCtors> moduleCtors = new HashMap<>();
    for (Module module : modules) {
      java.util.Map<String, Integer> unions = new HashMap<>();
      java.util.Map<String, List<String>> records = new HashMap<>();
      pl.matsuo.elm.interp.TypeDecls.scanModule(module, unions, records);
      moduleCtors.put(module.name(), new RuntimeEnv.ModuleCtors(records, unions));
    }
    env.setModuleCtors(moduleCtors);
    // All functions (closures) first, then all values (thunks) — across every module, so any
    // definition can reference any other regardless of file or declaration order.
    for (Module module : modules) {
      loadFunctions(module);
    }
    for (Module module : modules) {
      loadValues(module);
    }
  }

  /** Rebuilds an interpreter from a deserialized portable program: the pre-compiled chunks and name
   *  resolution tables, bound against the always-available {@link Prelude} builtins. */
  private BytecodeInterpreter(BytecodeProgram program) {
    this.moduleName = program.moduleName();
    this.ctorArity = new HashMap<>(program.ctorArity());
    this.recordCtors = new HashMap<>(program.recordCtors());
    this.unqualified = new HashMap<>(program.unqualified());
    this.aliases = new HashMap<>(program.aliases());
    this.env = new RuntimeEnv(Prelude.builtins(), unqualified, aliases, ctorArity, recordCtors, moduleName);
    for (BytecodeProgram.Def def : program.defs()) {
      defs.add(def);
      defineFromChunk(def.name(), def.chunk());
    }
  }

  public static BytecodeInterpreter load(String source) {
    return new BytecodeInterpreter(Parser.parseModule(source));
  }

  /** Compiles several module sources into one program (a flat merge; the first source is primary). */
  public static BytecodeInterpreter loadAll(List<String> sources) {
    return new BytecodeInterpreter(sources.stream().map(Parser::parseModule).toList());
  }

  /** Rebuilds a runnable interpreter from a portable bytecode program (e.g. read from {@code .elmbc}). */
  public static BytecodeInterpreter fromProgram(BytecodeProgram program) {
    return new BytecodeInterpreter(program);
  }

  /** The compiled program in a serializable form, for {@link BytecodeWriter} to emit. */
  public BytecodeProgram toProgram() {
    return new BytecodeProgram(
        moduleName, ctorArity, recordCtors, unqualified, aliases, List.copyOf(defs));
  }

  public static BytecodeInterpreter empty() {
    return new BytecodeInterpreter(
        new Module("Main", Module.Exposing.ALL, List.of(), List.of(), new Position(1, 1, 0)));
  }

  public static Object eval(String expression) {
    return empty().evalExpr(expression);
  }

  /** Compiles and binds a module's functions (value decls with parameters) as closures. */
  private void loadFunctions(Module module) {
    compiler.enterModule(module.name());
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && !v.params().isEmpty()) {
        Chunk chunk = compiler.compileChunk(v.params(), v.body(), v.name());
        defs.add(new BytecodeProgram.Def(v.name(), chunk));
        defineFromChunk(v.name(), chunk);
      }
    }
  }

  /** Compiles and binds a module's parameterless values as lazily-evaluated thunks. */
  private void loadValues(Module module) {
    compiler.enterModule(module.name());
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.params().isEmpty()) {
        Chunk chunk = compiler.compileChunk(List.of(), v.body(), v.name());
        defs.add(new BytecodeProgram.Def(v.name(), chunk));
        defineFromChunk(v.name(), chunk);
      }
    }
  }

  /** Binds a top-level name to its chunk: a function (params) becomes a closure, a value (no params)
   *  a lazily-evaluated thunk — the same convention {@link #load} and {@link #fromProgram} share. */
  private void defineFromChunk(String name, Chunk chunk) {
    if (chunk.params().isEmpty()) {
      env.defineTopLevel(name, new Thunk(() -> VM.run(chunk, rootScope.child(), env)));
    } else {
      env.defineTopLevel(name, new BytecodeClosure(chunk, rootScope, env));
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
    pl.matsuo.elm.ast.Expr expr = Parser.parseExpression(expression);
    env.registerLetTypes(expr); // a let-local `type` in the expression must be known at runtime
    Chunk chunk = compiler.compileChunk(List.of(), expr, "<expr>");
    return VM.run(chunk, rootScope.child(), env);
  }
}

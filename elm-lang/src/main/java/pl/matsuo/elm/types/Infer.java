package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.error.ElmTypeError;

/** Algorithm-W type inference over {@link Expr}, with let-generalization and Elm's constrained
 * variables. Variables are mutated in place; call {@link Types#prune} / {@link Types#show} on the
 * result. */
public final class Infer {

  private int level = 1;
  private final Map<String, AliasDef> aliases = new HashMap<>();
  private final Map<String, String> moduleAliases = new HashMap<>();
  private final Map<Expr, Ty> numericLiterals = new IdentityHashMap<>();

  private record AliasDef(List<String> params, Type body) {}

  /**
   * The {@code Int} literal expressions that inference resolved to {@code Float}. The compiler uses
   * this to coerce them to floating-point at runtime, so untyped numeric literals get the right
   * representation in a {@code Float} context (e.g. {@code Rect 3 4} with {@code Float} fields).
   */
  public Set<Expr> floatLiterals() {
    Set<Expr> out = Collections.newSetFromMap(new IdentityHashMap<>());
    numericLiterals.forEach(
        (e, t) -> {
          Ty p = Types.prune(t);
          if (p instanceof Ty.Con c && c.name().equals("Float")) {
            out.add(e);
          }
        });
    return out;
  }

  private Ty fresh() {
    return new Ty.Var(level, Ty.Constraint.NONE);
  }

  // --- whole-module inference --------------------------------------------

  /** Infers the types of all top-level definitions, registering the module's custom types, record
   * aliases and constructors; throws {@link ElmTypeError} on a type error. */
  public Map<String, Scheme> inferModule(Module module, Map<String, Scheme> base) {
    Map<String, Scheme> globals = new HashMap<>(base);
    aliases.clear();
    moduleAliases.clear();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.TypeAlias ta) {
        aliases.put(ta.name(), new AliasDef(ta.params(), ta.type()));
      }
    }
    // Mirror the runtime's import resolution so exposed names resolve during inference.
    for (Module.Import imp : module.imports()) {
      imp.alias().ifPresent(a -> moduleAliases.put(a, imp.module()));
      if (imp.exposing().open()) {
        String prefix = imp.module() + ".";
        for (String key : new java.util.ArrayList<>(globals.keySet())) {
          if (key.startsWith(prefix)) {
            globals.putIfAbsent(key.substring(prefix.length()), globals.get(key));
          }
        }
      } else {
        for (String name : imp.exposing().names()) {
          Scheme s = globals.get(imp.module() + "." + name);
          if (s != null) {
            globals.put(name, s);
          }
        }
      }
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        registerUnion(u, globals);
      } else if (d instanceof Decl.TypeAlias ta && ta.type() instanceof Type.Record) {
        registerRecordAliasConstructor(ta, globals);
      }
    }

    TypeEnv env = TypeEnv.root(globals);
    int outer = level;
    level++;
    Map<String, Ty> placeholders = new LinkedHashMap<>();
    TypeEnv rec = env;
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        Ty ph = fresh();
        placeholders.put(v.name(), ph);
        rec = rec.extend(v.name(), Scheme.mono(ph));
      }
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        Ty ph = placeholders.get(v.name());
        v.annotation().ifPresent(ann -> Unify.unify(ph, astToTy(ann, new HashMap<>())));
        Ty rhs = v.params().isEmpty() ? infer(rec, v.body()) : inferLambda(rec, v.params(), v.body());
        Unify.unify(ph, rhs);
      }
    }
    level = outer;

    Map<String, Scheme> result = new LinkedHashMap<>();
    placeholders.forEach((name, ph) -> result.put(name, Types.generalize(Types.prune(ph), outer)));
    return result;
  }

  private void registerUnion(Decl.Union u, Map<String, Scheme> globals) {
    Map<String, Ty> params = new LinkedHashMap<>();
    for (String p : u.params()) {
      params.put(p, fresh());
    }
    Ty result = new Ty.Con(u.name(), new ArrayList<>(params.values()));
    List<Ty.Var> vars = params.values().stream().map(t -> (Ty.Var) t).toList();
    for (Decl.Union.Variant variant : u.variants()) {
      Ty ctor = result;
      List<Type> args = variant.args();
      for (int i = args.size() - 1; i >= 0; i--) {
        ctor = new Ty.Arrow(astToTy(args.get(i), reuse(params)), ctor);
      }
      globals.put(variant.name(), new Scheme(vars, ctor));
    }
  }

  private void registerRecordAliasConstructor(Decl.TypeAlias ta, Map<String, Scheme> globals) {
    Map<String, Ty> params = new LinkedHashMap<>();
    for (String p : ta.params()) {
      params.put(p, fresh());
    }
    Type.Record rec = (Type.Record) ta.type();
    Ty recordTy = astToTy(ta.type(), reuse(params));
    Ty ctor = recordTy;
    List<Type.Record.Field> fields = rec.fields();
    for (int i = fields.size() - 1; i >= 0; i--) {
      ctor = new Ty.Arrow(astToTy(fields.get(i).type(), reuse(params)), ctor);
    }
    globals.put(ta.name(), new Scheme(params.values().stream().map(t -> (Ty.Var) t).toList(), ctor));
  }

  /** A param map that reuses the same variable objects (so a scheme's vars stay shared). */
  private Map<String, Ty> reuse(Map<String, Ty> params) {
    return new HashMap<>(params);
  }

  /** Converts a surface {@link Type} to an inference {@link Ty}, expanding type aliases. */
  private Ty astToTy(Type type, Map<String, Ty> vars) {
    return switch (type) {
      case Type.Var v -> vars.computeIfAbsent(v.name(), n -> fresh());
      case Type.Unit ignored -> new Ty.Unit();
      case Type.Arrow a -> new Ty.Arrow(astToTy(a.from(), vars), astToTy(a.to(), vars));
      case Type.Tuple t -> new Ty.Tuple(t.items().stream().map(x -> astToTy(x, vars)).toList());
      case Type.Record r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Type.Record.Field f : r.fields()) {
          fields.put(f.name(), astToTy(f.type(), vars));
        }
        Ty tail = r.base().map(b -> vars.computeIfAbsent(b, n -> fresh())).orElse(null);
        yield new Ty.Record(fields, tail);
      }
      case Type.Con c -> {
        AliasDef alias = aliases.get(c.name());
        if (alias != null) {
          Map<String, Ty> sub = new HashMap<>();
          for (int i = 0; i < alias.params().size() && i < c.args().size(); i++) {
            sub.put(alias.params().get(i), astToTy(c.args().get(i), vars));
          }
          yield astToTy(alias.body(), sub);
        }
        yield new Ty.Con(c.name(), c.args().stream().map(x -> astToTy(x, vars)).toList());
      }
    };
  }

  private Ty fresh(Ty.Constraint c) {
    return new Ty.Var(level, c);
  }

  public Ty infer(TypeEnv env, Expr expr) {
    return switch (expr) {
      case Expr.IntLit lit -> {
        Ty t = fresh(Ty.Constraint.NUMBER);
        numericLiterals.put(lit, t);
        yield t;
      }
      case Expr.FloatLit ignored -> Ty.FLOAT;
      case Expr.StrLit ignored -> Ty.STRING;
      case Expr.CharLit ignored -> Ty.CHAR;
      case Expr.Unit ignored -> new Ty.Unit();
      case Expr.Shader ignored -> fresh();
      case Expr.Var v -> instantiate(resolve(env, v.module(), v.name()));
      case Expr.Ctor c -> instantiate(resolve(env, c.module(), c.name()));
      case Expr.OpFunc o -> instantiate(operator(o.op()));
      case Expr.ListLit l -> {
        Ty elem = fresh();
        for (Expr item : l.items()) {
          Unify.unify(elem, infer(env, item));
        }
        yield Ty.list(elem);
      }
      case Expr.Tuple t -> new Ty.Tuple(t.items().stream().map(i -> infer(env, i)).toList());
      case Expr.Record r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Expr.Record.Field f : r.fields()) {
          fields.put(f.name(), infer(env, f.value()));
        }
        yield new Ty.Record(fields, null);
      }
      case Expr.RecordAccess a -> {
        Ty target = infer(env, a.target());
        Ty result = fresh();
        Unify.unify(target, new Ty.Record(Map.of(a.field(), result), fresh()));
        yield result;
      }
      case Expr.Accessor a -> {
        Ty result = fresh();
        yield new Ty.Arrow(new Ty.Record(Map.of(a.field(), result), fresh()), result);
      }
      case Expr.RecordUpdate u -> {
        Ty base = instantiate(resolve(env, null, u.base()));
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Expr.Record.Field f : u.fields()) {
          fields.put(f.name(), infer(env, f.value()));
        }
        Unify.unify(base, new Ty.Record(fields, fresh()));
        yield base;
      }
      case Expr.App app -> {
        Ty fn = infer(env, app.fn());
        Ty arg = infer(env, app.arg());
        Ty result = fresh();
        Unify.unify(fn, new Ty.Arrow(arg, result));
        yield result;
      }
      case Expr.BinOp b -> {
        Ty op = instantiate(operator(b.op()));
        Ty result = fresh();
        Unify.unify(op, new Ty.Arrow(infer(env, b.left()), new Ty.Arrow(infer(env, b.right()), result)));
        yield result;
      }
      case Expr.Negate n -> {
        Ty t = fresh(Ty.Constraint.NUMBER);
        Unify.unify(t, infer(env, n.operand()));
        yield t;
      }
      case Expr.If iff -> {
        Unify.unify(Ty.BOOL, infer(env, iff.cond()));
        Ty t = infer(env, iff.thenBranch());
        Unify.unify(t, infer(env, iff.elseBranch()));
        yield t;
      }
      case Expr.Lambda l -> inferLambda(env, l.params(), l.body());
      case Expr.Let let -> inferLet(env, let);
      case Expr.Case c -> inferCase(env, c);
    };
  }

  private Ty inferLambda(TypeEnv env, List<Pattern> params, Expr body) {
    Map<String, Ty> binds = new LinkedHashMap<>();
    List<Ty> paramTypes = new ArrayList<>();
    for (Pattern p : params) {
      paramTypes.add(inferPattern(env, p, binds));
    }
    TypeEnv body_env = extendMono(env, binds);
    Ty result = infer(body_env, body);
    for (int i = paramTypes.size() - 1; i >= 0; i--) {
      result = new Ty.Arrow(paramTypes.get(i), result);
    }
    return result;
  }

  private Ty inferLet(TypeEnv env, Expr.Let let) {
    // Bind each definition monomorphically (for recursion), infer, then generalize.
    int outer = level;
    level++;
    record Binding(String name, Ty placeholder) {}
    List<Binding> bindings = new ArrayList<>();
    TypeEnv rec = env;
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        Ty ph = fresh();
        bindings.add(new Binding(v.name(), ph));
        rec = rec.extend(v.name(), Scheme.mono(ph));
      }
    }
    for (Decl d : let.defs()) {
      switch (d) {
        case Decl.Value v -> {
          Ty rhs = v.params().isEmpty() ? infer(rec, v.body()) : inferLambda(rec, v.params(), v.body());
          Ty ph = bindings.stream().filter(b -> b.name().equals(v.name())).findFirst().get().placeholder();
          Unify.unify(ph, rhs);
        }
        case Decl.Destructure de -> {
          Map<String, Ty> binds = new LinkedHashMap<>();
          Ty pat = inferPattern(rec, de.pattern(), binds);
          Unify.unify(pat, infer(rec, de.body()));
          for (var e : binds.entrySet()) {
            rec = rec.extend(e.getKey(), Scheme.mono(e.getValue()));
            bindings.add(new Binding(e.getKey(), e.getValue()));
          }
        }
        default -> throw new ElmTypeError("Unsupported declaration in let");
      }
    }
    level = outer;
    TypeEnv body_env = env;
    for (Binding b : bindings) {
      body_env = body_env.extend(b.name(), Types.generalize(Types.prune(b.placeholder()), outer));
    }
    return infer(body_env, let.body());
  }

  private Ty inferCase(TypeEnv env, Expr.Case c) {
    Ty scrut = infer(env, c.scrutinee());
    Ty result = fresh();
    for (Expr.Case.Branch branch : c.branches()) {
      Map<String, Ty> binds = new LinkedHashMap<>();
      Ty pat = inferPattern(env, branch.pattern(), binds);
      Unify.unify(pat, scrut);
      Unify.unify(result, infer(extendMono(env, binds), branch.body()));
    }
    return result;
  }

  // --- patterns ----------------------------------------------------------

  private Ty inferPattern(TypeEnv env, Pattern pattern, Map<String, Ty> binds) {
    return switch (pattern) {
      case Pattern.Wildcard ignored -> fresh();
      case Pattern.Var v -> {
        Ty t = fresh();
        binds.put(v.name(), t);
        yield t;
      }
      case Pattern.Unit ignored -> new Ty.Unit();
      case Pattern.IntLit ignored -> fresh(Ty.Constraint.NUMBER);
      case Pattern.StrLit ignored -> Ty.STRING;
      case Pattern.CharLit ignored -> Ty.CHAR;
      case Pattern.Tuple t -> new Ty.Tuple(t.items().stream().map(p -> inferPattern(env, p, binds)).toList());
      case Pattern.ListPat l -> {
        Ty elem = fresh();
        for (Pattern p : l.items()) {
          Unify.unify(elem, inferPattern(env, p, binds));
        }
        yield Ty.list(elem);
      }
      case Pattern.Cons cons -> {
        Ty elem = inferPattern(env, cons.head(), binds);
        Unify.unify(Ty.list(elem), inferPattern(env, cons.tail(), binds));
        yield Ty.list(elem);
      }
      case Pattern.RecordPat r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (String field : r.fields()) {
          Ty t = fresh();
          fields.put(field, t);
          binds.put(field, t);
        }
        yield new Ty.Record(fields, fresh());
      }
      case Pattern.Alias a -> {
        Ty t = inferPattern(env, a.pattern(), binds);
        binds.put(a.name(), t);
        yield t;
      }
      case Pattern.Ctor c -> {
        Ty ctor = instantiate(resolve(env, c.module(), c.name()));
        Ty cur = ctor;
        for (Pattern arg : c.args()) {
          Ty argTy = inferPattern(env, arg, binds);
          Ty rest = fresh();
          Unify.unify(cur, new Ty.Arrow(argTy, rest));
          cur = rest;
        }
        yield cur;
      }
    };
  }

  // --- helpers -----------------------------------------------------------

  private TypeEnv extendMono(TypeEnv env, Map<String, Ty> binds) {
    TypeEnv e = env;
    for (var entry : binds.entrySet()) {
      e = e.extend(entry.getKey(), Scheme.mono(entry.getValue()));
    }
    return e;
  }

  private Ty instantiate(Scheme scheme) {
    return Types.instantiate(scheme, level);
  }

  private Scheme resolve(TypeEnv env, String module, String name) {
    Scheme s = env.lookup(name);
    if (s == null && module != null) {
      String real = moduleAliases.getOrDefault(module, module);
      s = env.globals().get(real + "." + name);
    }
    if (s == null) {
      s = env.globals().get(name);
    }
    if (s == null) {
      throw new ElmTypeError("Unknown name: " + (module == null ? name : module + "." + name));
    }
    return s;
  }

  private Scheme operator(String op) {
    Scheme s = Signatures.operator(op);
    if (s == null) {
      throw new ElmTypeError("Unknown operator: " + op);
    }
    return s;
  }
}

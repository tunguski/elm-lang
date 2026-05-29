package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmTypeError;

/** Algorithm-W type inference over {@link Expr}, with let-generalization and Elm's constrained
 * variables. Variables are mutated in place; call {@link Types#prune} / {@link Types#show} on the
 * result. */
public final class Infer {

  private int level = 1;

  private Ty fresh() {
    return new Ty.Var(level, Ty.Constraint.NONE);
  }

  private Ty fresh(Ty.Constraint c) {
    return new Ty.Var(level, c);
  }

  public Ty infer(TypeEnv env, Expr expr) {
    return switch (expr) {
      case Expr.IntLit ignored -> fresh(Ty.Constraint.NUMBER);
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
      s = env.globals().get(module + "." + name);
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

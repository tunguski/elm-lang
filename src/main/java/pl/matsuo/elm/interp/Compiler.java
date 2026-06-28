package pl.matsuo.elm.interp;

import com.oracle.truffle.api.RootCallTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmUnit;

/** Compiles the parsed {@link Expr} AST into a tree of executable Truffle {@link ElmNode}s. */
public final class Compiler {

  private final RuntimeEnv env;
  private final Set<Expr> floatLiterals;

  public Compiler(RuntimeEnv env) {
    this(env, Set.of());
  }

  /** {@code floatLiterals} are Int-literal expressions that the type checker resolved to Float;
   * they are compiled as floating-point constants. */
  public Compiler(RuntimeEnv env, Set<Expr> floatLiterals) {
    this.env = env;
    this.floatLiterals = floatLiterals;
  }

  public ElmNode compile(Expr e) {
    return switch (e) {
      case Expr.IntLit i ->
          new Nodes.Const(floatLiterals.contains(i) ? (Object) (double) i.value() : (Object) i.value());
      case Expr.FloatLit f -> new Nodes.Const(f.value());
      case Expr.StrLit s -> new Nodes.Const(s.value());
      case Expr.CharLit c -> new Nodes.Const(new ElmChar(c.codePoint()));
      case Expr.Shader s ->
          new Nodes.Const(new pl.matsuo.elm.runtime.ElmData("$Shader", new Object[] {s.source()}));
      case Expr.Unit ignored -> new Nodes.Const(ElmUnit.INSTANCE);
      case Expr.Var v -> new Nodes.Var(v.module(), v.name(), env, v.pos());
      case Expr.Ctor c -> new Nodes.Ctor(c.module(), c.name(), env);
      case Expr.OpFunc o ->
          Operators.isBuiltin(o.op())
              ? new Nodes.OpFunc(o.op())
              : new Nodes.Var(null, o.op(), env, o.pos()); // (op) as a value -> its defining function
      case Expr.ListLit l -> new Nodes.ListLit(compileAll(l.items()));
      case Expr.Tuple t -> new Nodes.TupleLit(compileAll(t.items()));
      case Expr.Record r -> compileRecord(r);
      case Expr.RecordUpdate u -> compileRecordUpdate(u);
      case Expr.RecordAccess a -> new Nodes.Access(compile(a.target()), a.field());
      case Expr.Accessor a -> new Nodes.Accessor(a.field());
      case Expr.App app -> compileApp(app);
      case Expr.BinOp b ->
          Operators.isBuiltin(b.op())
              ? new Nodes.BinOp(b.op(), compile(b.left()), compile(b.right()))
              // A user/package-defined operator: `a op b` is the function `(op)` applied to a and b.
              : new Nodes.App(
                  new Nodes.App(new Nodes.Var(null, b.op(), env, b.pos()), compile(b.left())),
                  compile(b.right()));
      case Expr.Negate n -> new Nodes.Negate(compile(n.operand()));
      case Expr.If iff ->
          new Nodes.If(compile(iff.cond()), compile(iff.thenBranch()), compile(iff.elseBranch()));
      case Expr.Lambda l -> compileAnonymous(l.params(), l.body());
      case Expr.Let let -> compileLet(let);
      case Expr.Case c -> compileCase(c);
    };
  }

  /**
   * Compiles an application. A constructor applied to exactly its arity builds its value directly
   * (a {@link Nodes.CtorApp} for a union constructor, a {@link Nodes.RecordLit} for a record-alias
   * one), skipping the curried {@link pl.matsuo.elm.runtime.PartialApp} chain. Anything else falls
   * back to one-argument-at-a-time application.
   */
  private ElmNode compileApp(Expr.App app) {
    List<Expr> args = new java.util.ArrayList<>();
    Expr cur = app;
    while (cur instanceof Expr.App a) {
      args.add(0, a.arg());
      cur = a.fn();
    }
    if (cur instanceof Expr.Ctor c) {
      ElmNode direct = compileSaturatedCtor(c.module(), c.name(), args);
      if (direct != null) {
        return direct;
      }
    }
    ElmNode out = compile(cur);
    for (Expr arg : args) {
      out = new Nodes.App(out, compile(arg));
    }
    return out;
  }

  /** The direct-build node for a constructor applied to exactly its arity, or null otherwise. The
   * constructor is resolved against its defining {@code module} so a name reused across modules
   * (a record alias in one, a union constructor in another) isn't confused. */
  private ElmNode compileSaturatedCtor(String module, String name, List<Expr> args) {
    List<String> fields = env.recordConstructorFields(module, name);
    if (fields != null) {
      return args.size() == fields.size()
          ? new Nodes.RecordLit(fields.toArray(new String[0]), compileAll(args))
          : null;
    }
    int arity = env.unionConstructorArity(module, name);
    return arity >= 1 && args.size() == arity ? new Nodes.CtorApp(name, compileAll(args)) : null;
  }

  private ElmNode[] compileAll(List<Expr> exprs) {
    ElmNode[] nodes = new ElmNode[exprs.size()];
    for (int i = 0; i < nodes.length; i++) {
      nodes[i] = compile(exprs.get(i));
    }
    return nodes;
  }

  private ElmNode compileRecord(Expr.Record r) {
    String[] names = new String[r.fields().size()];
    ElmNode[] values = new ElmNode[r.fields().size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = r.fields().get(i).name();
      values[i] = compile(r.fields().get(i).value());
    }
    return new Nodes.RecordLit(names, values);
  }

  private ElmNode compileRecordUpdate(Expr.RecordUpdate u) {
    String[] names = new String[u.fields().size()];
    ElmNode[] values = new ElmNode[u.fields().size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = u.fields().get(i).name();
      values[i] = compile(u.fields().get(i).value());
    }
    return new Nodes.RecordUpdate(new Nodes.Var(null, u.base(), env), names, values);
  }

  /** Compiles a named function body, applying self-tail-call optimization. */
  public Nodes.Lambda compileLambda(List<Pattern> params, Expr body, String name) {
    return compileFunction(params, body, name, name);
  }

  /** Compiles an anonymous lambda (no self-name, so no tail-call optimization). */
  public Nodes.Lambda compileAnonymous(List<Pattern> params, Expr body) {
    return compileFunction(params, body, "lambda", null);
  }

  private Nodes.Lambda compileFunction(
      List<Pattern> params, Expr body, String debugName, String selfName) {
    ElmNode bodyNode;
    if (selfName != null) {
      bodyNode = new Nodes.TailLoop(compileTail(body, selfName, params.size()), params.toArray(new Pattern[0]));
    } else {
      bodyNode = compile(body);
    }
    RootCallTarget target = new ElmRootNode(bodyNode).getCallTarget();
    return new Nodes.Lambda(debugName, target, params);
  }

  /**
   * Compiles {@code e} in tail position: a saturated call to {@code self} becomes a {@link
   * Nodes.SelfTailCall}; if/case/let recurse into their tail sub-expressions; anything else is a
   * normal (non-tail) expression.
   */
  private ElmNode compileTail(Expr e, String self, int arity) {
    Expr[] selfArgs = saturatedSelfCall(e, self, arity);
    if (selfArgs != null) {
      return new Nodes.SelfTailCall(compileAll(java.util.Arrays.asList(selfArgs)));
    }
    return switch (e) {
      case Expr.If iff ->
          new Nodes.If(
              compile(iff.cond()),
              compileTail(iff.thenBranch(), self, arity),
              compileTail(iff.elseBranch(), self, arity));
      case Expr.Let let -> compileLetTail(let, self, arity);
      case Expr.Case c -> compileCaseTail(c, self, arity);
      default -> compile(e);
    };
  }

  /** Returns the argument expressions if {@code e} is exactly {@code self a1 .. aArity}, else null. */
  private Expr[] saturatedSelfCall(Expr e, String self, int arity) {
    java.util.Deque<Expr> args = new java.util.ArrayDeque<>();
    Expr cur = e;
    while (cur instanceof Expr.App app) {
      args.push(app.arg());
      cur = app.fn();
    }
    if (args.size() == arity
        && cur instanceof Expr.Var v
        && v.module() == null
        && self.equals(v.name())) {
      return args.toArray(new Expr[0]);
    }
    return null;
  }

  private ElmNode compileLetTail(Expr.Let let, String self, int arity) {
    return buildLet(let, compileTail(let.body(), self, arity));
  }

  private ElmNode compileCaseTail(Expr.Case c, String self, int arity) {
    Pattern[] patterns = new Pattern[c.branches().size()];
    ElmNode[] bodies = new ElmNode[c.branches().size()];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = c.branches().get(i).pattern();
      bodies[i] = compileTail(c.branches().get(i).body(), self, arity);
    }
    return new Nodes.Case(compile(c.scrutinee()), patterns, bodies, c.pos());
  }

  private ElmNode compileLet(Expr.Let let) {
    return buildLet(let, compile(let.body()));
  }

  private ElmNode buildLet(Expr.Let let, ElmNode bodyNode) {
    List<Pattern> targets = new ArrayList<>();
    List<ElmNode> rhs = new ArrayList<>();
    // Function bindings first. A let-bound function's closure captures the (mutable) local scope and
    // its body runs only when the function is later CALLED — by which point every binding is in
    // scope. Binding functions before the value bindings lets a value binding use a helper function
    // defined lower in the same `let` (Elm's `let` is recursive / order-independent); otherwise the
    // value's eagerly-evaluated rhs referenced a not-yet-bound name. Mirrors the JS backend.
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v && !v.params().isEmpty()) {
        targets.add(new Pattern.Var(v.name()));
        rhs.add(compileLambda(v.params(), v.body(), v.name()));
      }
    }
    // Then the value and destructuring bindings, in source order.
    for (Decl d : let.defs()) {
      switch (d) {
        case Decl.Value v -> {
          if (v.params().isEmpty()) {
            targets.add(new Pattern.Var(v.name()));
            rhs.add(compile(v.body()));
          }
        }
        case Decl.Destructure de -> {
          targets.add(de.pattern());
          rhs.add(compile(de.body()));
        }
        case Decl.Union ignored -> {} // type-level only; constructors are resolved by name
        case Decl.TypeAlias ignored -> {} // type-level only (record-alias ctors resolved by name)
        default -> throw new ElmRuntimeError("Unsupported declaration in let: " + d);
      }
    }
    return new Nodes.Let(targets.toArray(new Pattern[0]), rhs.toArray(new ElmNode[0]), bodyNode);
  }

  private ElmNode compileCase(Expr.Case c) {
    Pattern[] patterns = new Pattern[c.branches().size()];
    ElmNode[] bodies = new ElmNode[c.branches().size()];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = c.branches().get(i).pattern();
      bodies[i] = compile(c.branches().get(i).body());
    }
    return new Nodes.Case(compile(c.scrutinee()), patterns, bodies, c.pos());
  }
}

package pl.matsuo.elm.interp;

import com.oracle.truffle.api.RootCallTarget;
import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmUnit;

/** Compiles the parsed {@link Expr} AST into a tree of executable Truffle {@link ElmNode}s. */
public final class Compiler {

  private final RuntimeEnv env;

  public Compiler(RuntimeEnv env) {
    this.env = env;
  }

  public ElmNode compile(Expr e) {
    return switch (e) {
      case Expr.IntLit i -> new Nodes.Const(i.value());
      case Expr.FloatLit f -> new Nodes.Const(f.value());
      case Expr.StrLit s -> new Nodes.Const(s.value());
      case Expr.CharLit c -> new Nodes.Const(new ElmChar(c.codePoint()));
      case Expr.Unit ignored -> new Nodes.Const(ElmUnit.INSTANCE);
      case Expr.Var v -> new Nodes.Var(v.module(), v.name(), env);
      case Expr.Ctor c -> new Nodes.Ctor(c.name(), env);
      case Expr.OpFunc o -> new Nodes.OpFunc(o.op());
      case Expr.ListLit l -> new Nodes.ListLit(compileAll(l.items()));
      case Expr.Tuple t -> new Nodes.TupleLit(compileAll(t.items()));
      case Expr.Record r -> compileRecord(r);
      case Expr.RecordUpdate u -> compileRecordUpdate(u);
      case Expr.RecordAccess a -> new Nodes.Access(compile(a.target()), a.field());
      case Expr.Accessor a -> new Nodes.Accessor(a.field());
      case Expr.App app -> new Nodes.App(compile(app.fn()), compile(app.arg()));
      case Expr.BinOp b -> new Nodes.BinOp(b.op(), compile(b.left()), compile(b.right()));
      case Expr.Negate n -> new Nodes.Negate(compile(n.operand()));
      case Expr.If iff ->
          new Nodes.If(compile(iff.cond()), compile(iff.thenBranch()), compile(iff.elseBranch()));
      case Expr.Lambda l -> compileLambda(l.params(), l.body(), "lambda");
      case Expr.Let let -> compileLet(let);
      case Expr.Case c -> compileCase(c);
    };
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

  /** Compiles a function/lambda body into its own Truffle {@link RootCallTarget}. */
  public Nodes.Lambda compileLambda(List<Pattern> params, Expr body, String name) {
    RootCallTarget target = new ElmRootNode(compile(body)).getCallTarget();
    return new Nodes.Lambda(name, target, params);
  }

  private ElmNode compileLet(Expr.Let let) {
    List<Pattern> targets = new ArrayList<>();
    List<ElmNode> rhs = new ArrayList<>();
    for (Decl d : let.defs()) {
      switch (d) {
        case Decl.Value v -> {
          targets.add(new Pattern.Var(v.name()));
          rhs.add(
              v.params().isEmpty()
                  ? compile(v.body())
                  : compileLambda(v.params(), v.body(), v.name()));
        }
        case Decl.Destructure de -> {
          targets.add(de.pattern());
          rhs.add(compile(de.body()));
        }
        default -> throw new ElmRuntimeError("Unsupported declaration in let: " + d);
      }
    }
    return new Nodes.Let(
        targets.toArray(new Pattern[0]), rhs.toArray(new ElmNode[0]), compile(let.body()));
  }

  private ElmNode compileCase(Expr.Case c) {
    Pattern[] patterns = new Pattern[c.branches().size()];
    ElmNode[] bodies = new ElmNode[c.branches().size()];
    for (int i = 0; i < patterns.length; i++) {
      patterns[i] = c.branches().get(i).pattern();
      bodies[i] = compile(c.branches().get(i).body());
    }
    return new Nodes.Case(compile(c.scrutinee()), patterns, bodies);
  }
}

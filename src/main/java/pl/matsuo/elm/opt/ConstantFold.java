package pl.matsuo.elm.opt;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;

/**
 * A value-preserving constant-folding pass over the AST, shared by the code generators: literal
 * arithmetic/comparison/boolean/string-concat is evaluated at compile time, unary negation of a
 * literal is collapsed, and an {@code if} on a literal {@code True}/{@code False} is replaced by the
 * taken branch. Everything else is rebuilt with its children folded. Pure Elm has no side effects,
 * so folding (including dropping the untaken {@code if} branch or a short-circuited operand) never
 * changes a program's result.
 */
public final class ConstantFold {

  private ConstantFold() {}

  public static Expr fold(Expr e) {
    return switch (e) {
      case Expr.Negate n -> foldNegate(fold(n.operand()), n.pos());
      case Expr.BinOp b -> foldBinOp(b.op(), fold(b.left()), fold(b.right()), b.pos());
      case Expr.If iff -> foldIf(fold(iff.cond()), fold(iff.thenBranch()), fold(iff.elseBranch()), iff.pos());
      case Expr.App app -> new Expr.App(fold(app.fn()), fold(app.arg()), app.pos());
      case Expr.ListLit l -> new Expr.ListLit(foldAll(l.items()), l.pos());
      case Expr.Tuple t -> new Expr.Tuple(foldAll(t.items()), t.pos());
      case Expr.RecordAccess a -> new Expr.RecordAccess(fold(a.target()), a.field(), a.pos());
      case Expr.Record r -> new Expr.Record(foldFields(r.fields()), r.pos());
      case Expr.RecordUpdate u -> new Expr.RecordUpdate(u.base(), foldFields(u.fields()), u.pos());
      case Expr.Lambda lam -> new Expr.Lambda(lam.params(), fold(lam.body()), lam.pos());
      case Expr.Let let -> new Expr.Let(foldDecls(let.defs()), fold(let.body()), let.pos());
      case Expr.Case c -> foldCase(c);
      default -> e; // literals, vars, ctors, operators, accessors, shaders, unit
    };
  }

  private static List<Expr> foldAll(List<Expr> items) {
    List<Expr> out = new ArrayList<>(items.size());
    for (Expr i : items) {
      out.add(fold(i));
    }
    return out;
  }

  private static List<Expr.Record.Field> foldFields(List<Expr.Record.Field> fields) {
    List<Expr.Record.Field> out = new ArrayList<>(fields.size());
    for (Expr.Record.Field f : fields) {
      out.add(new Expr.Record.Field(f.name(), fold(f.value())));
    }
    return out;
  }

  /** Folds the bodies of a list of declarations (top-level or in a {@code let}). */
  public static List<Decl> foldDecls(List<Decl> defs) {
    List<Decl> out = new ArrayList<>(defs.size());
    for (Decl d : defs) {
      if (d instanceof Decl.Value v) {
        out.add(new Decl.Value(v.name(), v.params(), fold(v.body()), v.annotation(), v.pos()));
      } else if (d instanceof Decl.Destructure de) {
        out.add(new Decl.Destructure(de.pattern(), fold(de.body()), de.pos()));
      } else {
        out.add(d);
      }
    }
    return out;
  }

  private static Expr foldCase(Expr.Case c) {
    List<Expr.Case.Branch> branches = new ArrayList<>(c.branches().size());
    for (Expr.Case.Branch br : c.branches()) {
      branches.add(new Expr.Case.Branch(br.pattern(), fold(br.body())));
    }
    return new Expr.Case(fold(c.scrutinee()), branches, c.pos());
  }

  private static Expr foldNegate(Expr operand, pl.matsuo.elm.error.Position pos) {
    if (operand instanceof Expr.IntLit i) {
      return new Expr.IntLit(-i.value(), pos);
    }
    if (operand instanceof Expr.FloatLit f) {
      return new Expr.FloatLit(-f.value(), pos);
    }
    return new Expr.Negate(operand, pos);
  }

  private static Expr foldIf(Expr cond, Expr thenB, Expr elseB, pl.matsuo.elm.error.Position pos) {
    if (isBool(cond, true)) {
      return thenB;
    }
    if (isBool(cond, false)) {
      return elseB;
    }
    return new Expr.If(cond, thenB, elseB, pos);
  }

  private static boolean isBool(Expr e, boolean which) {
    return e instanceof Expr.Ctor c && c.name().equals(which ? "True" : "False");
  }

  private static Expr boolLit(boolean b, pl.matsuo.elm.error.Position pos) {
    return new Expr.Ctor(null, b ? "True" : "False", pos);
  }

  private static Expr foldBinOp(String op, Expr l, Expr r, pl.matsuo.elm.error.Position pos) {
    // Boolean short-circuit simplification with a literal operand (pure language: always safe).
    switch (op) {
      case "&&" -> {
        if (isBool(l, true)) {
          return r;
        }
        if (isBool(l, false)) {
          return boolLit(false, pos);
        }
        if (isBool(r, true)) {
          return l;
        }
      }
      case "||" -> {
        if (isBool(l, true)) {
          return boolLit(true, pos);
        }
        if (isBool(l, false)) {
          return r;
        }
        if (isBool(r, false)) {
          return l;
        }
      }
      default -> {}
    }
    // String concatenation of two literals.
    if (op.equals("++") && l instanceof Expr.StrLit ls && r instanceof Expr.StrLit rs) {
      return new Expr.StrLit(ls.value() + rs.value(), pos);
    }
    // Integer arithmetic / comparison on two int literals.
    if (l instanceof Expr.IntLit li && r instanceof Expr.IntLit ri) {
      Expr folded = foldIntOp(op, li.value(), ri.value(), pos);
      if (folded != null) {
        return folded;
      }
    }
    // Float arithmetic / comparison on two float literals.
    if (l instanceof Expr.FloatLit lf && r instanceof Expr.FloatLit rf) {
      Expr folded = foldFloatOp(op, lf.value(), rf.value(), pos);
      if (folded != null) {
        return folded;
      }
    }
    return new Expr.BinOp(op, l, r, pos);
  }

  private static Expr foldIntOp(String op, long a, long b, pl.matsuo.elm.error.Position pos) {
    return switch (op) {
      case "+" -> new Expr.IntLit(a + b, pos);
      case "-" -> new Expr.IntLit(a - b, pos);
      case "*" -> new Expr.IntLit(a * b, pos);
      case "//" -> b == 0 ? null : new Expr.IntLit(a / b, pos); // Elm `//` truncates toward zero
      case "==" -> boolLit(a == b, pos);
      case "/=" -> boolLit(a != b, pos);
      case "<" -> boolLit(a < b, pos);
      case ">" -> boolLit(a > b, pos);
      case "<=" -> boolLit(a <= b, pos);
      case ">=" -> boolLit(a >= b, pos);
      default -> null;
    };
  }

  private static Expr foldFloatOp(String op, double a, double b, pl.matsuo.elm.error.Position pos) {
    return switch (op) {
      case "+" -> new Expr.FloatLit(a + b, pos);
      case "-" -> new Expr.FloatLit(a - b, pos);
      case "*" -> new Expr.FloatLit(a * b, pos);
      case "/" -> b == 0.0 ? null : new Expr.FloatLit(a / b, pos);
      case "==" -> boolLit(a == b, pos);
      case "/=" -> boolLit(a != b, pos);
      case "<" -> boolLit(a < b, pos);
      case ">" -> boolLit(a > b, pos);
      case "<=" -> boolLit(a <= b, pos);
      case ">=" -> boolLit(a >= b, pos);
      default -> null;
    };
  }
}

package pl.matsuo.elm.opt;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;

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
      case Expr.Let let -> foldLet(let);
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

  /**
   * Folds a {@code let}, then shrinks it with two value-preserving rewrites:
   *
   * <ol>
   *   <li><b>Literal inlining</b>: a binding {@code x = <literal>} is substituted into the body and
   *       the other bindings (literals are closed, so this can never capture), after which {@code x}
   *       is unused and dropped — exposing further folding (e.g. {@code let x = 2 in x + 3 → 5}).
   *   <li><b>Dead-binding elimination</b>: a parameterless binding that is referenced nowhere and
   *       whose right-hand side is <em>total</em> (cannot error or diverge — see {@link #isTotal})
   *       is removed. The totality guard keeps this sound against the strict interpreter reference,
   *       which evaluates every {@code let} binding eagerly: dropping one that could crash would
   *       change the result.
   * </ol>
   *
   * Both iterate to a fixpoint (removing one binding can make another dead). If nothing is left, the
   * body replaces the {@code let} entirely.
   */
  private static Expr foldLet(Expr.Let let) {
    List<Decl> defs = new ArrayList<>(foldDecls(let.defs()));
    Expr body = fold(let.body());
    boolean changed = true;
    while (changed) {
      changed = false;
      // 1. Inline literal bindings that have at least one free (unshadowed) use — so the substitution
      // makes real progress (this avoids re-inlining a binding whose only occurrence is shadowed).
      for (Decl d : defs) {
        if (d instanceof Decl.Value v && v.params().isEmpty() && isLiteral(v.body())
            && freeReferencedExcept(defs, body, v.name(), d)) {
          String name = v.name();
          List<Decl> next = new ArrayList<>(defs.size());
          for (Decl other : defs) {
            if (other != d && other instanceof Decl.Value ov && !boundByParams(ov, name)) {
              next.add(new Decl.Value(ov.name(), ov.params(), subst(ov.body(), name, v.body()),
                  ov.annotation(), ov.pos()));
            } else {
              next.add(other);
            }
          }
          defs = next;
          body = fold(subst(body, name, v.body()));
          changed = true;
          break;
        }
      }
      // 2. Drop unused, total bindings.
      List<Decl> kept = new ArrayList<>(defs.size());
      for (Decl d : defs) {
        if (d instanceof Decl.Value v && v.params().isEmpty() && isTotal(v.body())
            && !referencedExcept(defs, body, v.name(), d)) {
          changed = true; // dropped
        } else {
          kept.add(d);
        }
      }
      defs = kept;
    }
    if (defs.isEmpty()) {
      return body;
    }
    return new Expr.Let(defs, body, let.pos());
  }

  /** Whether {@code name} is referenced (as an unqualified Var) in {@code body} or in any binding's
   * right-hand side except {@code self}. Conservative: ignores shadowing, so it never under-counts —
   * used by dead-binding elimination, where over-counting only forgoes an optimization (stays sound). */
  private static boolean referencedExcept(List<Decl> defs, Expr body, String name, Decl self) {
    if (uses(body, name)) {
      return true;
    }
    for (Decl d : defs) {
      if (d != self && d instanceof Decl.Value v && uses(v.body(), name)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code name} has a free (unshadowed) use in {@code body} or another binding's right-hand
   * side — i.e. a use that literal inlining would actually replace. */
  private static boolean freeReferencedExcept(List<Decl> defs, Expr body, String name, Decl self) {
    if (freeUses(body, name)) {
      return true;
    }
    for (Decl d : defs) {
      if (d != self && d instanceof Decl.Value v && !boundByParams(v, name) && freeUses(v.body(), name)) {
        return true;
      }
    }
    return false;
  }

  private static boolean boundByParams(Decl.Value v, String name) {
    return v.params().stream().anyMatch(p -> binds(p, name));
  }

  /** Whether {@code name} occurs as a free, unqualified {@code Var} in {@code e}, stopping at any
   * scope that rebinds it — the precise dual of {@link #subst}. */
  private static boolean freeUses(Expr e, String name) {
    return switch (e) {
      case Expr.Var v -> v.module() == null && v.name().equals(name);
      case Expr.Negate n -> freeUses(n.operand(), name);
      case Expr.BinOp b -> freeUses(b.left(), name) || freeUses(b.right(), name);
      case Expr.If i -> freeUses(i.cond(), name) || freeUses(i.thenBranch(), name) || freeUses(i.elseBranch(), name);
      case Expr.App a -> freeUses(a.fn(), name) || freeUses(a.arg(), name);
      case Expr.ListLit l -> l.items().stream().anyMatch(x -> freeUses(x, name));
      case Expr.Tuple t -> t.items().stream().anyMatch(x -> freeUses(x, name));
      case Expr.RecordAccess a -> freeUses(a.target(), name);
      case Expr.Record r -> r.fields().stream().anyMatch(f -> freeUses(f.value(), name));
      case Expr.RecordUpdate u -> u.base().equals(name) || u.fields().stream().anyMatch(f -> freeUses(f.value(), name));
      case Expr.Lambda lam ->
          lam.params().stream().noneMatch(p -> binds(p, name)) && freeUses(lam.body(), name);
      case Expr.Let let -> {
        if (let.defs().stream().anyMatch(d -> d instanceof Decl.Value v && v.name().equals(name))) {
          yield false; // the let's recursive scope rebinds the name
        }
        boolean inDefs =
            let.defs().stream()
                .anyMatch(d -> d instanceof Decl.Value v && !boundByParams(v, name) && freeUses(v.body(), name));
        yield inDefs || freeUses(let.body(), name);
      }
      case Expr.Case c ->
          freeUses(c.scrutinee(), name)
              || c.branches().stream().anyMatch(b -> !binds(b.pattern(), name) && freeUses(b.body(), name));
      default -> false;
    };
  }

  private static boolean isLiteral(Expr e) {
    return e instanceof Expr.IntLit
        || e instanceof Expr.FloatLit
        || e instanceof Expr.StrLit
        || e instanceof Expr.CharLit
        || (e instanceof Expr.Ctor c && (c.name().equals("True") || c.name().equals("False")));
  }

  /** A conservative "cannot error or diverge when evaluated" test: literals, variable lookups, lambda
   * values, bare constructors, and aggregates (tuple/list/record/if/negate) built only from those.
   * Anything that might trap — function application, division, record access, case — is treated as
   * non-total, so a binding with such a right-hand side is never dropped. */
  private static boolean isTotal(Expr e) {
    return switch (e) {
      case Expr.IntLit _ -> true;
      case Expr.FloatLit _ -> true;
      case Expr.StrLit _ -> true;
      case Expr.CharLit _ -> true;
      case Expr.Unit _ -> true;
      case Expr.Var _ -> true;
      case Expr.Ctor _ -> true;
      case Expr.Lambda _ -> true;
      case Expr.Negate n -> isTotal(n.operand());
      case Expr.Tuple t -> t.items().stream().allMatch(ConstantFold::isTotal);
      case Expr.ListLit l -> l.items().stream().allMatch(ConstantFold::isTotal);
      case Expr.Record r -> r.fields().stream().allMatch(f -> isTotal(f.value()));
      case Expr.If i -> isTotal(i.cond()) && isTotal(i.thenBranch()) && isTotal(i.elseBranch());
      default -> false;
    };
  }

  /** Whether {@code name} appears as an unqualified {@code Var} anywhere in {@code e} (ignoring
   * shadowing — used only as a conservative "is it referenced at all" check for DCE). */
  private static boolean uses(Expr e, String name) {
    return switch (e) {
      case Expr.Var v -> v.module() == null && v.name().equals(name);
      case Expr.Negate n -> uses(n.operand(), name);
      case Expr.BinOp b -> uses(b.left(), name) || uses(b.right(), name);
      case Expr.If i -> uses(i.cond(), name) || uses(i.thenBranch(), name) || uses(i.elseBranch(), name);
      case Expr.App a -> uses(a.fn(), name) || uses(a.arg(), name);
      case Expr.ListLit l -> l.items().stream().anyMatch(x -> uses(x, name));
      case Expr.Tuple t -> t.items().stream().anyMatch(x -> uses(x, name));
      case Expr.RecordAccess a -> uses(a.target(), name);
      case Expr.Record r -> r.fields().stream().anyMatch(f -> uses(f.value(), name));
      case Expr.RecordUpdate u -> u.base().equals(name) || u.fields().stream().anyMatch(f -> uses(f.value(), name));
      case Expr.Lambda lam -> uses(lam.body(), name);
      case Expr.Let let ->
          let.defs().stream().anyMatch(d -> d instanceof Decl.Value v && uses(v.body(), name))
              || uses(let.body(), name);
      case Expr.Case c ->
          uses(c.scrutinee(), name) || c.branches().stream().anyMatch(b -> uses(b.body(), name));
      default -> false;
    };
  }

  /** Replaces every free, unqualified {@code Var name} in {@code e} with {@code repl} (a closed
   * literal, so no capture is possible), stopping at any scope that rebinds {@code name}. */
  private static Expr subst(Expr e, String name, Expr repl) {
    return switch (e) {
      case Expr.Var v -> (v.module() == null && v.name().equals(name)) ? repl : e;
      case Expr.Negate n -> new Expr.Negate(subst(n.operand(), name, repl), n.pos());
      case Expr.BinOp b ->
          new Expr.BinOp(b.op(), subst(b.left(), name, repl), subst(b.right(), name, repl), b.pos());
      case Expr.If i ->
          new Expr.If(subst(i.cond(), name, repl), subst(i.thenBranch(), name, repl),
              subst(i.elseBranch(), name, repl), i.pos());
      case Expr.App a -> new Expr.App(subst(a.fn(), name, repl), subst(a.arg(), name, repl), a.pos());
      case Expr.ListLit l -> new Expr.ListLit(substAll(l.items(), name, repl), l.pos());
      case Expr.Tuple t -> new Expr.Tuple(substAll(t.items(), name, repl), t.pos());
      case Expr.RecordAccess a -> new Expr.RecordAccess(subst(a.target(), name, repl), a.field(), a.pos());
      case Expr.Record r -> new Expr.Record(substFields(r.fields(), name, repl), r.pos());
      case Expr.RecordUpdate u -> new Expr.RecordUpdate(u.base(), substFields(u.fields(), name, repl), u.pos());
      case Expr.Lambda lam ->
          lam.params().stream().anyMatch(p -> binds(p, name))
              ? lam
              : new Expr.Lambda(lam.params(), subst(lam.body(), name, repl), lam.pos());
      case Expr.Let let -> substLet(let, name, repl);
      case Expr.Case c -> substCase(c, name, repl);
      default -> e;
    };
  }

  private static Expr substLet(Expr.Let let, String name, Expr repl) {
    // A let is a recursive scope: if it (re)binds the name, leave the whole let untouched.
    if (let.defs().stream().anyMatch(d -> d instanceof Decl.Value v && v.name().equals(name))) {
      return let;
    }
    List<Decl> defs = new ArrayList<>(let.defs().size());
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v && v.params().stream().noneMatch(p -> binds(p, name))) {
        defs.add(new Decl.Value(v.name(), v.params(), subst(v.body(), name, repl), v.annotation(), v.pos()));
      } else {
        defs.add(d);
      }
    }
    return new Expr.Let(defs, subst(let.body(), name, repl), let.pos());
  }

  private static Expr substCase(Expr.Case c, String name, Expr repl) {
    List<Expr.Case.Branch> branches = new ArrayList<>(c.branches().size());
    for (Expr.Case.Branch br : c.branches()) {
      branches.add(
          binds(br.pattern(), name)
              ? br
              : new Expr.Case.Branch(br.pattern(), subst(br.body(), name, repl)));
    }
    return new Expr.Case(subst(c.scrutinee(), name, repl), branches, c.pos());
  }

  private static List<Expr> substAll(List<Expr> items, String name, Expr repl) {
    List<Expr> out = new ArrayList<>(items.size());
    for (Expr i : items) {
      out.add(subst(i, name, repl));
    }
    return out;
  }

  private static List<Expr.Record.Field> substFields(List<Expr.Record.Field> fields, String name, Expr repl) {
    List<Expr.Record.Field> out = new ArrayList<>(fields.size());
    for (Expr.Record.Field f : fields) {
      out.add(new Expr.Record.Field(f.name(), subst(f.value(), name, repl)));
    }
    return out;
  }

  /** Whether a pattern binds the variable {@code name}. */
  private static boolean binds(Pattern p, String name) {
    return switch (p) {
      case Pattern.Var v -> v.name().equals(name);
      case Pattern.Alias a -> a.name().equals(name) || binds(a.pattern(), name);
      case Pattern.Ctor c -> c.args().stream().anyMatch(x -> binds(x, name));
      case Pattern.Tuple t -> t.items().stream().anyMatch(x -> binds(x, name));
      case Pattern.ListPat l -> l.items().stream().anyMatch(x -> binds(x, name));
      case Pattern.Cons cs -> binds(cs.head(), name) || binds(cs.tail(), name);
      case Pattern.RecordPat r -> r.fields().contains(name);
      default -> false;
    };
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

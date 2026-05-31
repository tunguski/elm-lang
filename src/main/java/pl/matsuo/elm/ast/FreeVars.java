package pl.matsuo.elm.ast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The free lowercase, unqualified variables of an expression — names it uses but does not itself
 * bind (via lambda parameters, {@code let} definitions or {@code case} patterns). Used by the LSP's
 * "extract function" refactor to turn an expression's free locals into the new function's parameters
 * so the extracted code still type-checks. (The WASM backend computes the same thing inline for
 * lambda lifting; this is the standalone, order-preserving version.)
 */
public final class FreeVars {

  private FreeVars() {}

  /** Free variables of {@code e}, in order of first appearance. */
  public static List<String> of(Expr e) {
    Set<String> out = new LinkedHashSet<>();
    addFree(e, new HashSet<>(), out);
    return new ArrayList<>(out);
  }

  private static void addFree(Expr e, Set<String> bound, Set<String> out) {
    switch (e) {
      case Expr.Var v -> {
        if (v.module() == null && !v.name().isEmpty() && Character.isLowerCase(v.name().charAt(0))
            && !bound.contains(v.name())) {
          out.add(v.name());
        }
      }
      case Expr.App a -> {
        addFree(a.fn(), bound, out);
        addFree(a.arg(), bound, out);
      }
      case Expr.BinOp b -> {
        addFree(b.left(), bound, out);
        addFree(b.right(), bound, out);
      }
      case Expr.If i -> {
        addFree(i.cond(), bound, out);
        addFree(i.thenBranch(), bound, out);
        addFree(i.elseBranch(), bound, out);
      }
      case Expr.Negate n -> addFree(n.operand(), bound, out);
      case Expr.Lambda lam -> {
        Set<String> inner = new HashSet<>(bound);
        for (Pattern p : lam.params()) {
          patternVars(p, inner);
        }
        addFree(lam.body(), inner, out);
      }
      case Expr.Let let -> {
        Set<String> inner = new HashSet<>(bound);
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            inner.add(v.name());
          }
        }
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            Set<String> defScope = new HashSet<>(inner);
            for (Pattern p : v.params()) {
              patternVars(p, defScope);
            }
            addFree(v.body(), defScope, out);
          }
        }
        addFree(let.body(), inner, out);
      }
      case Expr.Case c -> {
        addFree(c.scrutinee(), bound, out);
        for (Expr.Case.Branch br : c.branches()) {
          Set<String> inner = new HashSet<>(bound);
          patternVars(br.pattern(), inner);
          addFree(br.body(), inner, out);
        }
      }
      case Expr.ListLit l -> l.items().forEach(x -> addFree(x, bound, out));
      case Expr.Tuple t -> t.items().forEach(x -> addFree(x, bound, out));
      case Expr.Record r -> r.fields().forEach(fld -> addFree(fld.value(), bound, out));
      case Expr.RecordAccess a -> addFree(a.target(), bound, out);
      case Expr.RecordUpdate u -> {
        if (!bound.contains(u.base())) {
          out.add(u.base());
        }
        u.fields().forEach(fld -> addFree(fld.value(), bound, out));
      }
      default -> {}
    }
  }

  private static void patternVars(Pattern p, Set<String> bound) {
    switch (p) {
      case Pattern.Var v -> bound.add(v.name());
      case Pattern.Alias a -> {
        bound.add(a.name());
        patternVars(a.pattern(), bound);
      }
      case Pattern.Ctor c -> c.args().forEach(x -> patternVars(x, bound));
      case Pattern.Tuple t -> t.items().forEach(x -> patternVars(x, bound));
      case Pattern.ListPat l -> l.items().forEach(x -> patternVars(x, bound));
      case Pattern.Cons cs -> {
        patternVars(cs.head(), bound);
        patternVars(cs.tail(), bound);
      }
      case Pattern.RecordPat r -> bound.addAll(r.fields());
      default -> {}
    }
  }
}

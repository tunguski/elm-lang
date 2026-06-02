package pl.matsuo.elm.interp;

import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Type;

/**
 * Collects the runtime effect of {@code type} / {@code type alias} declarations — constructor
 * arities and record-alias field lists — including those declared inside a {@code let}. The grammar
 * permits a {@code type}/{@code type alias} as a {@code let} declaration, so a constructor introduced
 * there must be registered before the body runs.
 */
public final class TypeDecls {

  private TypeDecls() {}

  /** Registers every type declaration in {@code module} (top-level and let-local) into the runtime's
   *  constructor-arity and record-constructor tables. */
  public static void scanModule(
      Module module, Map<String, Integer> ctorArity, Map<String, List<String>> recordCtors) {
    for (Decl d : module.decls()) {
      register(d, ctorArity, recordCtors);
      if (d instanceof Decl.Value v) {
        collectFromExpr(v.body(), ctorArity, recordCtors);
      }
    }
  }

  /** Registers a single {@code type} / {@code type alias} declaration. */
  public static void register(
      Decl d, Map<String, Integer> ctorArity, Map<String, List<String>> recordCtors) {
    if (d instanceof Decl.Union union) {
      for (Decl.Union.Variant v : union.variants()) {
        ctorArity.put(v.name(), v.args().size());
      }
    } else if (d instanceof Decl.TypeAlias ta
        && ta.type() instanceof Type.Record rec
        && rec.base().isEmpty()) {
      recordCtors.put(ta.name(), rec.fields().stream().map(Type.Record.Field::name).toList());
    }
  }

  /** Registers any type declaration that appears inside a {@code let} of the given expression (for
   *  evaluating a standalone expression that introduces a local {@code type}). */
  public static void scanExpr(
      Expr e, Map<String, Integer> ctorArity, Map<String, List<String>> recordCtors) {
    collectFromExpr(e, ctorArity, recordCtors);
  }

  /** Walks an expression, registering any type declaration that appears inside a {@code let}. */
  private static void collectFromExpr(
      Expr e, Map<String, Integer> ctorArity, Map<String, List<String>> recordCtors) {
    switch (e) {
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          register(d, ctorArity, recordCtors);
          if (d instanceof Decl.Value v) {
            collectFromExpr(v.body(), ctorArity, recordCtors);
          } else if (d instanceof Decl.Destructure de) {
            collectFromExpr(de.body(), ctorArity, recordCtors);
          }
        }
        collectFromExpr(let.body(), ctorArity, recordCtors);
      }
      case Expr.App a -> {
        collectFromExpr(a.fn(), ctorArity, recordCtors);
        collectFromExpr(a.arg(), ctorArity, recordCtors);
      }
      case Expr.BinOp b -> {
        collectFromExpr(b.left(), ctorArity, recordCtors);
        collectFromExpr(b.right(), ctorArity, recordCtors);
      }
      case Expr.Negate n -> collectFromExpr(n.operand(), ctorArity, recordCtors);
      case Expr.If i -> {
        collectFromExpr(i.cond(), ctorArity, recordCtors);
        collectFromExpr(i.thenBranch(), ctorArity, recordCtors);
        collectFromExpr(i.elseBranch(), ctorArity, recordCtors);
      }
      case Expr.Lambda l -> collectFromExpr(l.body(), ctorArity, recordCtors);
      case Expr.Case c -> {
        collectFromExpr(c.scrutinee(), ctorArity, recordCtors);
        for (Expr.Case.Branch br : c.branches()) {
          collectFromExpr(br.body(), ctorArity, recordCtors);
        }
      }
      case Expr.Tuple t -> t.items().forEach(x -> collectFromExpr(x, ctorArity, recordCtors));
      case Expr.ListLit l -> l.items().forEach(x -> collectFromExpr(x, ctorArity, recordCtors));
      case Expr.Record r ->
          r.fields().forEach(f -> collectFromExpr(f.value(), ctorArity, recordCtors));
      case Expr.RecordUpdate u ->
          u.fields().forEach(f -> collectFromExpr(f.value(), ctorArity, recordCtors));
      case Expr.RecordAccess ra -> collectFromExpr(ra.target(), ctorArity, recordCtors);
      default -> {} // leaves (literals, Var, Ctor, …) hold no nested let
    }
  }
}

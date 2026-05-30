package pl.matsuo.elm.parser;

import java.util.stream.Collectors;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;

/**
 * Renders an {@link Expr} back to Elm source. Fully parenthesized (it favours being unambiguous to
 * re-parse over being pretty), so {@code parse(unparse(parse(s)))} is structurally equal to {@code
 * parse(s)} — which the round-trip property test relies on.
 */
public final class Unparse {

  private Unparse() {}

  public static String expr(Expr e) {
    return switch (e) {
      case Expr.IntLit i -> Long.toString(i.value());
      case Expr.FloatLit f -> Double.toString(f.value());
      case Expr.StrLit s -> "\"" + s.value().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
      case Expr.CharLit c -> "'" + new String(Character.toChars(c.codePoint())) + "'";
      case Expr.Unit ignored -> "()";
      case Expr.Var v -> v.module() == null ? v.name() : v.module() + "." + v.name();
      case Expr.Ctor c -> c.module() == null ? c.name() : c.module() + "." + c.name();
      case Expr.Negate n -> "(-" + expr(n.operand()) + ")";
      case Expr.BinOp b -> "(" + expr(b.left()) + " " + b.op() + " " + expr(b.right()) + ")";
      case Expr.If i ->
          "(if " + expr(i.cond()) + " then " + expr(i.thenBranch()) + " else "
              + expr(i.elseBranch()) + ")";
      case Expr.App a -> "(" + expr(a.fn()) + " " + expr(a.arg()) + ")";
      case Expr.Lambda l ->
          "(\\" + l.params().stream().map(Unparse::pattern).collect(Collectors.joining(" "))
              + " -> " + expr(l.body()) + ")";
      case Expr.Let let -> {
        String defs =
            let.defs().stream().map(Unparse::decl).collect(Collectors.joining("\n "));
        yield "(let " + defs + " in " + expr(let.body()) + ")";
      }
      case Expr.ListLit l ->
          "[" + l.items().stream().map(Unparse::expr).collect(Collectors.joining(", ")) + "]";
      case Expr.Tuple t ->
          "(" + t.items().stream().map(Unparse::expr).collect(Collectors.joining(", ")) + ")";
      case Expr.Record r ->
          "{ " + r.fields().stream()
              .map(f -> f.name() + " = " + expr(f.value()))
              .collect(Collectors.joining(", ")) + " }";
      case Expr.RecordAccess a -> "(" + expr(a.target()) + ")." + a.field();
      case Expr.Accessor a -> "." + a.field();
      default -> throw new IllegalArgumentException("cannot unparse " + e.getClass().getSimpleName());
    };
  }

  private static String decl(Decl d) {
    if (d instanceof Decl.Value v) {
      String params =
          v.params().isEmpty()
              ? ""
              : " " + v.params().stream().map(Unparse::pattern).collect(Collectors.joining(" "));
      return v.name() + params + " = " + expr(v.body());
    }
    throw new IllegalArgumentException("cannot unparse declaration " + d);
  }

  private static String pattern(Pattern p) {
    return switch (p) {
      case Pattern.Var v -> v.name();
      case Pattern.Wildcard ignored -> "_";
      default -> throw new IllegalArgumentException("cannot unparse pattern " + p);
    };
  }
}

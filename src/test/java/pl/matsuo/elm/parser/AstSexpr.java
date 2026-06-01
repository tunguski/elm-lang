package pl.matsuo.elm.parser;

import java.util.stream.Collectors;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;

/**
 * Renders an {@link Expr}/{@link Pattern}/{@link Module} to a compact S-expression string. Test-only
 * helper that makes precedence/associativity and shape assertions easy to read — and, being
 * position-insensitive, lets tests compare two ASTs ignoring layout (e.g. before/after formatting).
 */
public final class AstSexpr {

  private AstSexpr() {}

  /** A position-insensitive signature of a whole module: every declaration's shape, in order. Two
   * modules with the same signature are structurally equal regardless of layout/positions. */
  public static String module(Module m) {
    StringBuilder sb = new StringBuilder();
    for (Decl d : m.decls()) {
      switch (d) {
        case Decl.Value v -> {
          sb.append("val ").append(v.name());
          for (Pattern p : v.params()) {
            sb.append(' ').append(showPat(p));
          }
          sb.append(" = ").append(show(v.body())).append('\n');
        }
        case Decl.Union u -> sb.append("type ").append(u.name()).append('/').append(u.variants().size()).append('\n');
        case Decl.TypeAlias a -> sb.append("alias ").append(a.name()).append('\n');
        case Decl.Port p -> sb.append("port ").append(p.name()).append('\n');
        case Decl.Destructure de -> sb.append("destr ").append(showPat(de.pattern())).append(" = ").append(show(de.body())).append('\n');
      }
    }
    return sb.toString();
  }

  public static String show(Expr e) {
    return switch (e) {
      case Expr.IntLit i -> Long.toString(i.value());
      case Expr.FloatLit f -> Double.toString(f.value());
      case Expr.StrLit s -> '"' + s.value() + '"';
      case Expr.CharLit c -> "'" + new String(Character.toChars(c.codePoint())) + "'";
      case Expr.Shader ignored -> "[glsl]";
      case Expr.Var v -> v.module() == null ? v.name() : v.module() + "." + v.name();
      case Expr.Ctor c -> c.module() == null ? c.name() : c.module() + "." + c.name();
      case Expr.OpFunc o -> "(" + o.op() + ")";
      case Expr.ListLit l ->
          "[" + l.items().stream().map(AstSexpr::show).collect(Collectors.joining(", ")) + "]";
      case Expr.Tuple t ->
          "(" + t.items().stream().map(AstSexpr::show).collect(Collectors.joining(", ")) + ")";
      case Expr.Unit ignored -> "()";
      case Expr.Record r ->
          "{"
              + r.fields().stream()
                  .map(f -> f.name() + "=" + show(f.value()))
                  .collect(Collectors.joining(", "))
              + "}";
      case Expr.RecordUpdate u ->
          "{"
              + u.base()
              + " | "
              + u.fields().stream()
                  .map(f -> f.name() + "=" + show(f.value()))
                  .collect(Collectors.joining(", "))
              + "}";
      case Expr.RecordAccess a -> show(a.target()) + "." + a.field();
      case Expr.Accessor a -> "." + a.field();
      case Expr.App app -> "(" + show(app.fn()) + " " + show(app.arg()) + ")";
      case Expr.BinOp b -> "(" + b.op() + " " + show(b.left()) + " " + show(b.right()) + ")";
      case Expr.Negate n -> "(neg " + show(n.operand()) + ")";
      case Expr.If iff ->
          "(if " + show(iff.cond()) + " " + show(iff.thenBranch()) + " " + show(iff.elseBranch())
              + ")";
      case Expr.Lambda l ->
          "(\\ "
              + l.params().stream().map(AstSexpr::showPat).collect(Collectors.joining(" "))
              + " -> "
              + show(l.body())
              + ")";
      case Expr.Let let ->
          "(let "
              + let.defs().size()
              + " "
              + show(let.body())
              + ")";
      case Expr.Case c ->
          "(case "
              + show(c.scrutinee())
              + " "
              + c.branches().stream()
                  .map(br -> showPat(br.pattern()) + "->" + show(br.body()))
                  .collect(Collectors.joining(" "))
              + ")";
    };
  }

  public static String showPat(Pattern p) {
    return switch (p) {
      case Pattern.Wildcard ignored -> "_";
      case Pattern.Var v -> v.name();
      case Pattern.Unit ignored -> "()";
      case Pattern.IntLit i -> Long.toString(i.value());
      case Pattern.StrLit s -> '"' + s.value() + '"';
      case Pattern.CharLit c -> "'" + new String(Character.toChars(c.codePoint())) + "'";
      case Pattern.Ctor c -> {
        String head = c.module() == null ? c.name() : c.module() + "." + c.name();
        if (c.args().isEmpty()) {
          yield head;
        }
        yield "("
            + head
            + " "
            + c.args().stream().map(AstSexpr::showPat).collect(Collectors.joining(" "))
            + ")";
      }
      case Pattern.Tuple t ->
          "(" + t.items().stream().map(AstSexpr::showPat).collect(Collectors.joining(", ")) + ")";
      case Pattern.ListPat l ->
          "[" + l.items().stream().map(AstSexpr::showPat).collect(Collectors.joining(", ")) + "]";
      case Pattern.Cons c -> "(:: " + showPat(c.head()) + " " + showPat(c.tail()) + ")";
      case Pattern.RecordPat r -> "{" + String.join(", ", r.fields()) + "}";
      case Pattern.Alias a -> "(as " + showPat(a.pattern()) + " " + a.name() + ")";
    };
  }
}

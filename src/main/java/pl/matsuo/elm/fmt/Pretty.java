package pl.matsuo.elm.fmt;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;

/**
 * A precedence- and indentation-aware pretty-printer for expressions, following elm-format's main
 * conventions: minimal parentheses, {@code if}/{@code let}/{@code case} laid out across lines with
 * 4-space indentation, and spaced records/lists/tuples.
 */
final class Pretty {

  /** op -> {precedence, associativity} (0 = left, 1 = right, 2 = non). */
  private static final Map<String, int[]> OPS =
      Map.ofEntries(
          Map.entry("<|", new int[] {0, 1}), Map.entry("|>", new int[] {0, 0}),
          Map.entry("||", new int[] {2, 1}), Map.entry("&&", new int[] {3, 1}),
          Map.entry("==", new int[] {4, 2}), Map.entry("/=", new int[] {4, 2}),
          Map.entry("<", new int[] {4, 2}), Map.entry(">", new int[] {4, 2}),
          Map.entry("<=", new int[] {4, 2}), Map.entry(">=", new int[] {4, 2}),
          Map.entry("++", new int[] {5, 1}), Map.entry("::", new int[] {5, 1}),
          Map.entry("+", new int[] {6, 0}), Map.entry("-", new int[] {6, 0}),
          Map.entry("*", new int[] {7, 0}), Map.entry("/", new int[] {7, 0}),
          Map.entry("//", new int[] {7, 0}), Map.entry("^", new int[] {8, 1}),
          Map.entry("<<", new int[] {9, 1}), Map.entry(">>", new int[] {9, 0}),
          Map.entry("|=", new int[] {5, 0}), Map.entry("|.", new int[] {6, 0}),
          Map.entry("</>", new int[] {7, 1}), Map.entry("<?>", new int[] {8, 0}));

  private static final int APP = 10; // application binds tighter than any operator

  private Pretty() {}

  static String expr(Expr e, int ctx, int ind) {
    return switch (e) {
      case Expr.IntLit i -> Long.toString(i.value());
      case Expr.FloatLit f -> trimFloat(f.value());
      case Expr.StrLit s -> "\"" + escapeString(s.value()) + "\"";
      case Expr.CharLit c -> "'" + escapeChar(c.codePoint()) + "'";
      case Expr.Unit ignored -> "()";
      case Expr.Var v -> v.module() == null ? v.name() : v.module() + "." + v.name();
      case Expr.Ctor c -> c.module() == null ? c.name() : c.module() + "." + c.name();
      case Expr.OpFunc o -> "(" + o.op() + ")";
      case Expr.Shader s -> "[glsl|" + s.source() + "|]";
      case Expr.Accessor a -> "." + a.field();
      case Expr.RecordAccess a -> expr(a.target(), APP + 1, ind) + "." + a.field();
      case Expr.Negate n -> "-" + expr(n.operand(), APP + 1, ind);
      case Expr.ListLit l ->
          l.items().isEmpty()
              ? "[]"
              : bracketed("[", "]", l.items().stream().map(x -> expr(x, 0, ind + 2)).toList(), ind);
      case Expr.Tuple t ->
          "( " + t.items().stream().map(x -> expr(x, 0, ind)).collect(Collectors.joining(", ")) + " )";
      case Expr.Record r ->
          r.fields().isEmpty()
              ? "{}"
              : bracketed(
                  "{", "}",
                  r.fields().stream().map(f -> f.name() + " = " + expr(f.value(), 0, ind + 2)).toList(),
                  ind);
      case Expr.RecordUpdate u ->
          "{ " + u.base() + " | " + u.fields().stream()
              .map(f -> f.name() + " = " + expr(f.value(), 0, ind))
              .collect(Collectors.joining(", ")) + " }";
      case Expr.BinOp b -> binOp(b, ctx, ind);
      case Expr.App a -> application(a, ctx, ind);
      case Expr.Lambda l -> paren(ctx > 0, lambda(l, ind));
      case Expr.If iff -> paren(ctx > 0, ifExpr(iff, ind));
      case Expr.Let let -> paren(ctx > 0, letExpr(let, ind));
      case Expr.Case c -> paren(ctx > 0, caseExpr(c, ind));
    };
  }

  /** elm-format's line width: lists/records longer than this (from their indent) wrap one item per
   *  line, with the brackets and commas aligned at {@code ind}. */
  private static final int MAX_WIDTH = 100;

  /**
   * Renders a comma-separated bracketed construct (a list {@code [ … ]} or record {@code { … }}) on
   * one line when it fits, or one item per line otherwise (elm-format style). The wrap decision is a
   * pure function of {@code ind} and the rendered items, so it is stable under reformatting.
   */
  private static String bracketed(String open, String close, List<String> items, int ind) {
    String flat = open + " " + String.join(", ", items) + " " + close;
    if (!flat.contains("\n") && ind + flat.length() <= MAX_WIDTH) {
      return flat;
    }
    String in = " ".repeat(ind);
    StringBuilder sb = new StringBuilder(open).append(" ").append(items.get(0));
    for (int i = 1; i < items.size(); i++) {
      sb.append("\n").append(in).append(", ").append(items.get(i));
    }
    return sb.append("\n").append(in).append(close).toString();
  }

  private static String binOp(Expr.BinOp b, int ctx, int ind) {
    int[] f = OPS.getOrDefault(b.op(), new int[] {0, 0});
    int prec = f[0], assoc = f[1];
    String left = expr(b.left(), assoc == 0 ? prec : prec + 1, ind);
    String right = expr(b.right(), assoc == 1 ? prec : prec + 1, ind);
    return paren(prec < ctx, left + " " + b.op() + " " + right);
  }

  private static String application(Expr.App a, int ctx, int ind) {
    Expr head = a;
    java.util.Deque<String> parts = new java.util.ArrayDeque<>();
    while (head instanceof Expr.App app) {
      parts.addFirst(expr(app.arg(), APP + 1, ind));
      head = app.fn();
    }
    parts.addFirst(expr(head, APP, ind));
    return paren(ctx > APP, String.join(" ", parts));
  }

  private static String lambda(Expr.Lambda l, int ind) {
    String params = l.params().stream().map(Pretty::patternArg).collect(Collectors.joining(" "));
    return "\\" + params + " -> " + expr(l.body(), 0, ind);
  }

  private static String ifExpr(Expr.If iff, int ind) {
    String in = " ".repeat(ind);
    String in4 = " ".repeat(ind + 4);
    return "if " + expr(iff.cond(), 0, ind) + " then\n"
        + in4 + expr(iff.thenBranch(), 0, ind + 4) + "\n"
        + in + "else\n"
        + in4 + expr(iff.elseBranch(), 0, ind + 4);
  }

  private static String letExpr(Expr.Let let, int ind) {
    String in = " ".repeat(ind);
    String in4 = " ".repeat(ind + 4);
    StringBuilder sb = new StringBuilder("let\n");
    for (Decl d : let.defs()) {
      sb.append(in4).append(decl(d, ind + 4).replace("\n", "\n")).append("\n");
    }
    sb.append(in).append("in\n").append(in).append(expr(let.body(), 0, ind));
    return sb.toString();
  }

  private static String caseExpr(Expr.Case c, int ind) {
    String in4 = " ".repeat(ind + 4);
    String in8 = " ".repeat(ind + 8);
    StringBuilder sb = new StringBuilder("case " + expr(c.scrutinee(), 0, ind) + " of\n");
    List<Expr.Case.Branch> branches = c.branches();
    for (int i = 0; i < branches.size(); i++) {
      Expr.Case.Branch br = branches.get(i);
      sb.append(in4).append(pattern(br.pattern())).append(" ->\n");
      sb.append(in8).append(expr(br.body(), 0, ind + 8));
      if (i < branches.size() - 1) {
        sb.append("\n\n");
      }
    }
    return sb.toString();
  }

  /** A top-level or let declaration body (used by the formatter and let-printing). */
  static String decl(Decl d, int ind) {
    if (d instanceof Decl.Value v) {
      String params =
          v.params().isEmpty()
              ? ""
              : " " + v.params().stream().map(Pretty::patternArg).collect(Collectors.joining(" "));
      String head = declName(v.name()) + params + " =";
      String body = expr(v.body(), 0, ind + 4);
      // A short body stays on the same line; blocks/long bodies move to the next indented line.
      if (!body.contains("\n") && body.length() + head.length() < 80) {
        return head + " " + body;
      }
      return head + "\n" + " ".repeat(ind + 4) + body;
    }
    if (d instanceof Decl.Destructure de) {
      return pattern(de.pattern()) + " =\n" + " ".repeat(ind + 4) + expr(de.body(), 0, ind + 4);
    }
    return d.toString();
  }

  static String pattern(Pattern p) {
    return switch (p) {
      case Pattern.Var v -> v.name();
      case Pattern.Wildcard ignored -> "_";
      case Pattern.Unit ignored -> "()";
      case Pattern.IntLit i -> Long.toString(i.value());
      case Pattern.StrLit s -> "\"" + escapeString(s.value()) + "\"";
      case Pattern.CharLit c -> "'" + escapeChar(c.codePoint()) + "'";
      case Pattern.Tuple t ->
          "( " + t.items().stream().map(Pretty::pattern).collect(Collectors.joining(", ")) + " )";
      case Pattern.ListPat l ->
          l.items().isEmpty()
              ? "[]"
              : "[ " + l.items().stream().map(Pretty::pattern).collect(Collectors.joining(", ")) + " ]";
      case Pattern.Cons cons -> {
        // A cons head that is itself an alias or cons binds looser than `::`, so it needs parens —
        // e.g. `((x as y)) :: rest`, `(a :: b) :: rest`.
        Pattern h = cons.head();
        String head =
            (h instanceof Pattern.Alias || h instanceof Pattern.Cons) ? "(" + pattern(h) + ")" : pattern(h);
        yield head + " :: " + pattern(cons.tail());
      }
      case Pattern.RecordPat r -> "{ " + String.join(", ", r.fields()) + " }";
      case Pattern.Alias a -> pattern(a.pattern()) + " as " + a.name();
      case Pattern.Ctor c -> {
        String name = c.module() == null ? c.name() : c.module() + "." + c.name();
        if (c.args().isEmpty()) {
          yield name;
        }
        yield name + " " + c.args().stream().map(Pretty::patternArg).collect(Collectors.joining(" "));
      }
    };
  }

  /** A pattern in "atom" position — a constructor argument, or a function/lambda parameter —
   * parenthesized when it isn't already atomic: a constructor with arguments, a cons, or an alias. */
  static String patternArg(Pattern p) {
    if (p instanceof Pattern.Ctor c && !c.args().isEmpty()) {
      return "(" + pattern(p) + ")";
    }
    if (p instanceof Pattern.Cons || p instanceof Pattern.Alias) {
      return "(" + pattern(p) + ")";
    }
    return pattern(p);
  }

  private static String paren(boolean yes, String s) {
    return yes ? "(" + s + ")" : s;
  }

  private static String trimFloat(double d) {
    if (d == Math.floor(d) && !Double.isInfinite(d)) {
      return (long) d + ".0";
    }
    return Double.toString(d);
  }

  /** A definition's name, wrapping an operator (a non-identifier name) in parentheses: {@code (|=)}. */
  static String declName(String name) {
    boolean ident = !name.isEmpty() && (Character.isLetter(name.charAt(0)) || name.charAt(0) == '_');
    return ident ? name : "(" + name + ")";
  }

  /** Re-escapes a string literal's value so the printed form re-parses to the same string (the AST
   * holds the decoded value, e.g. a real newline for {@code \n}). Backslash first, then the rest. */
  static String escapeString(String s) {
    return s.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t")
        .replace("\r", "\\r");
  }

  /** Re-escapes a character literal's code point (so {@code '\n'} prints as {@code '\n'}, not a raw
   * newline that would break the literal). */
  static String escapeChar(int codePoint) {
    return switch (codePoint) {
      case '\\' -> "\\\\";
      case '\'' -> "\\'";
      case '\n' -> "\\n";
      case '\t' -> "\\t";
      case '\r' -> "\\r";
      default -> new String(Character.toChars(codePoint));
    };
  }
}

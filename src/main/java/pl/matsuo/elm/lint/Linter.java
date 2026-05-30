package pl.matsuo.elm.lint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.parser.Parser;

/**
 * A small elm-review-style linter: parses a module and reports likely issues — {@code Debug.log}/
 * {@code Debug.todo} left in the code, and top-level definitions that are never used and not
 * exposed. Findings are located and carry a rule name; this is a lint pass, not a type check.
 */
public final class Linter {

  private Linter() {}

  /** A lint finding: where it is (1-based), the rule that fired, and a message. */
  public record Finding(int line, int col, String rule, String message) {
    @Override
    public String toString() {
      return line + ":" + col + "  " + rule + ": " + message;
    }
  }

  /** Lints one module's source, returning findings in source order. */
  public static List<Finding> lint(String source) {
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (RuntimeException e) {
      return List.of(); // a parse error isn't a lint finding (the checker reports it)
    }
    List<Finding> findings = new ArrayList<>();
    debugUsages(module, findings);
    unusedDefinitions(module, findings);
    findings.sort(
        (a, b) -> a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.col(), b.col()));
    return findings;
  }

  // --- rule: NoDebug ------------------------------------------------------

  private static void debugUsages(Module module, List<Finding> out) {
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        walk(
            v.body(),
            e -> {
              if (e instanceof Expr.Var var && "Debug".equals(var.module())) {
                out.add(
                    new Finding(
                        var.pos().line(),
                        var.pos().col(),
                        "NoDebug",
                        "`Debug." + var.name() + "` should be removed before shipping."));
              }
            });
      }
    }
  }

  // --- rule: NoUnused (top-level values) ----------------------------------

  private static void unusedDefinitions(Module module, List<Finding> out) {
    Set<String> referenced = new HashSet<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        walk(
            v.body(),
            e -> {
              if (e instanceof Expr.Var var && var.module() == null) {
                referenced.add(var.name());
              } else if (e instanceof Expr.Ctor c && c.module() == null) {
                referenced.add(c.name());
              }
            });
      }
    }
    boolean exposesAll = module.exposing().open();
    Set<String> exposed = new HashSet<>(module.exposing().names());
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v
          && !v.name().equals("main")
          && !referenced.contains(v.name())
          && !exposesAll
          && !exposed.contains(v.name())) {
        out.add(
            new Finding(
                v.pos().line(),
                v.pos().col(),
                "NoUnused",
                "`" + v.name() + "` is defined but never used."));
      }
    }
  }

  // --- AST walk -----------------------------------------------------------

  private static void walk(Expr e, java.util.function.Consumer<Expr> visit) {
    visit.accept(e);
    switch (e) {
      case Expr.App a -> {
        walk(a.fn(), visit);
        walk(a.arg(), visit);
      }
      case Expr.BinOp b -> {
        walk(b.left(), visit);
        walk(b.right(), visit);
      }
      case Expr.Negate n -> walk(n.operand(), visit);
      case Expr.If i -> {
        walk(i.cond(), visit);
        walk(i.thenBranch(), visit);
        walk(i.elseBranch(), visit);
      }
      case Expr.Lambda l -> walk(l.body(), visit);
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            walk(v.body(), visit);
          } else if (d instanceof Decl.Destructure dd) {
            walk(dd.body(), visit);
          }
        }
        walk(let.body(), visit);
      }
      case Expr.Case c -> {
        walk(c.scrutinee(), visit);
        c.branches().forEach(br -> walk(br.body(), visit));
      }
      case Expr.ListLit l -> l.items().forEach(x -> walk(x, visit));
      case Expr.Tuple t -> t.items().forEach(x -> walk(x, visit));
      case Expr.Record r -> r.fields().forEach(f -> walk(f.value(), visit));
      case Expr.RecordUpdate u -> u.fields().forEach(f -> walk(f.value(), visit));
      case Expr.RecordAccess a -> walk(a.target(), visit);
      default -> {}
    }
  }
}

package pl.matsuo.elm.opt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.parser.Parser;

/** Tests the shared constant-folding pass: it reduces literal expressions and never changes the
 * value (checked against the interpreter, including over randomly generated expressions). */
class ConstantFoldTest {

  private static Expr fold(String expr) {
    return ConstantFold.fold(Parser.parseExpression(expr));
  }

  @Test
  void foldsLiteralArithmeticToASingleLiteral() {
    assertEquals(Expr.IntLit.class, fold("1 + 2 * 3").getClass()); // -> 7
    assertEquals(7L, ((Expr.IntLit) fold("1 + 2 * 3")).value());
    assertEquals(-3L, ((Expr.IntLit) fold("(10 - 7) - 6")).value()); // 3 - 6
    assertEquals(Expr.FloatLit.class, fold("1.5 + 2.25").getClass()); // -> 3.75
    // Comparisons fold to a Bool constructor.
    assertEquals("True", ((Expr.Ctor) fold("3 < 5")).name());
    assertEquals("False", ((Expr.Ctor) fold("3 >= 5")).name());
    // String concatenation of literals.
    assertEquals("ab", ((Expr.StrLit) fold("\"a\" ++ \"b\"")).value());
  }

  @Test
  void foldsConstantIfAndBoolean() {
    // if True/False collapses to the taken branch; here the cond folds from 1 < 2.
    assertEquals(10L, ((Expr.IntLit) fold("if 1 < 2 then 10 else 20")).value());
    assertEquals(20L, ((Expr.IntLit) fold("if 9 < 2 then 10 else 20")).value());
    // && / || with a folded literal operand simplify.
    assertEquals("False", ((Expr.Ctor) fold("(1 > 2) && someVar")).name()); // False && x -> False
  }

  @Test
  void doesNotFoldDivisionByZeroOrVariables() {
    // Division by zero is left for the runtime (so its error semantics are preserved).
    assertEquals(Expr.BinOp.class, fold("1 // 0").getClass());
    // A variable operand is not foldable.
    assertEquals(Expr.BinOp.class, fold("x + 1").getClass());
  }

  @Test
  void dropsDeadTotalLetBindings() {
    // An unused binding with a total right-hand side is removed; with only that binding, the let
    // collapses to its body.
    assertEquals(7L, ((Expr.IntLit) fold("let x = 5 in 7")).value());
    assertEquals(7L, ((Expr.IntLit) fold("let r = { a = 1, b = 2 } in 7")).value());
    assertEquals(7L, ((Expr.IntLit) fold("let f = \\n -> n + 1 in 7")).value());
  }

  @Test
  void keepsDeadButPossiblyErroringLetBindings() {
    // The right-hand side is a function application (could trap/diverge) and the interpreter evaluates
    // every binding eagerly, so even though `x` is unused the binding must be kept for soundness.
    assertEquals(Expr.Let.class, fold("let x = modBy 0 1 in 7").getClass());
  }

  @Test
  void inlinesLiteralLetBindingsAndRefolds() {
    // x = 2 is substituted into the body, after which 2 + 3 folds to 5 and the let disappears.
    assertEquals(5L, ((Expr.IntLit) fold("let x = 2 in x + 3")).value());
    // A literal binding referenced through another binding inlines transitively.
    assertEquals(9L, ((Expr.IntLit) fold("let a = 4 in let b = a + 1 in b + a")).value());
    // Shadowing is respected: the inner x wins, so the outer literal must not leak in.
    assertEquals(Expr.Let.class, fold("let x = 1 in (\\x -> x + x) 9").getClass());
  }

  @Test
  void letOptimizationsNeverChangeTheValueOverRandomExpressions() {
    Random rng = new Random(20260604L);
    for (int i = 0; i < 300; i++) {
      int[] counter = {0};
      String expr = genLet(rng, 4, counter);
      String original = Show.plain(Interpreter.eval(expr));
      Object foldedValue = Interpreter.empty().evalExpr(ConstantFold.fold(Parser.parseExpression(expr)));
      assertEquals(original, Show.plain(foldedValue), "let-folding changed value of: " + expr);
    }
  }

  /** Like {@link #gen} but sprinkles in (used and dead) {@code let} bindings to exercise inlining and
   * dead-binding elimination. Every binding's right-hand side is total and divisor-safe, so the
   * original expression always evaluates to a value to compare against. */
  private static String genLet(Random rng, int depth, int[] counter) {
    if (depth <= 0 || rng.nextInt(100) < 30) {
      return Integer.toString(rng.nextInt(20));
    }
    return switch (rng.nextInt(6)) {
      case 0 -> "(" + genLet(rng, depth - 1, counter) + " + " + genLet(rng, depth - 1, counter) + ")";
      case 1 -> "(" + genLet(rng, depth - 1, counter) + " - " + genLet(rng, depth - 1, counter) + ")";
      case 2 -> "(" + genLet(rng, depth - 1, counter) + " * " + genLet(rng, depth - 1, counter) + ")";
      case 3 -> { // a used binding
        String v = "v" + counter[0]++;
        yield "(let " + v + " = " + genLet(rng, depth - 1, counter) + " in " + v + " + "
            + genLet(rng, depth - 1, counter) + ")";
      }
      case 4 -> { // a dead binding
        String v = "v" + counter[0]++;
        yield "(let " + v + " = " + genLet(rng, depth - 1, counter) + " in "
            + genLet(rng, depth - 1, counter) + ")";
      }
      default -> "(modBy " + (1 + rng.nextInt(7)) + " " + genLet(rng, depth - 1, counter) + ")";
    };
  }

  @Test
  void foldingNeverChangesTheValueOverRandomExpressions() {
    // Differential: fold(e) must evaluate to the same value as e on the interpreter, for many random
    // literal-heavy expressions.
    Random rng = new Random(20260603L);
    for (int i = 0; i < 300; i++) {
      String expr = gen(rng, 4);
      String original = Show.plain(Interpreter.eval(expr));
      // Re-render the folded AST by evaluating it directly through the interpreter's compiler.
      Object foldedValue = Interpreter.empty().evalExpr(ConstantFold.fold(Parser.parseExpression(expr)));
      assertEquals(original, Show.plain(foldedValue), "fold changed value of: " + expr);
    }
  }

  /** A generator of literal-heavy Int expressions (arithmetic, comparisons folded into `if`). */
  private static String gen(Random rng, int depth) {
    if (depth <= 0 || rng.nextInt(100) < 30) {
      return Integer.toString(rng.nextInt(20));
    }
    return switch (rng.nextInt(5)) {
      case 0 -> "(" + gen(rng, depth - 1) + " + " + gen(rng, depth - 1) + ")";
      case 1 -> "(" + gen(rng, depth - 1) + " - " + gen(rng, depth - 1) + ")";
      case 2 -> "(" + gen(rng, depth - 1) + " * " + gen(rng, depth - 1) + ")";
      case 3 -> "(if " + gen(rng, depth - 1) + " < " + gen(rng, depth - 1) + " then "
          + gen(rng, depth - 1) + " else " + gen(rng, depth - 1) + ")";
      default -> "(modBy " + (1 + rng.nextInt(7)) + " " + gen(rng, depth - 1) + ")";
    };
  }
}

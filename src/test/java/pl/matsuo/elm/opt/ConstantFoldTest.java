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

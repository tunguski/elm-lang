package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Property test for the type checker: a large batch of randomly generated, well-typed numeric
 * expressions must all type-check (no false positives), and to a numeric type.
 */
class InferencePropertyTest {

  private static String expr(Random r, int depth) {
    if (depth <= 0 || r.nextInt(100) < 30) {
      return Integer.toString(r.nextInt(50));
    }
    return switch (r.nextInt(7)) {
      case 0 -> "(" + expr(r, depth - 1) + " + " + expr(r, depth - 1) + ")";
      case 1 -> "(" + expr(r, depth - 1) + " * " + expr(r, depth - 1) + ")";
      case 2 -> "(if " + expr(r, depth - 1) + " < " + expr(r, depth - 1) + " then "
          + expr(r, depth - 1) + " else " + expr(r, depth - 1) + ")";
      case 3 -> "(let x = " + expr(r, depth - 1) + " in x + x)";
      case 4 -> "(abs " + expr(r, depth - 1) + ")";
      case 5 -> "(List.sum [" + expr(r, depth - 1) + ", " + expr(r, depth - 1) + "])";
      default -> "((\\n -> n + " + expr(r, depth - 1) + ") " + expr(r, depth - 1) + ")";
    };
  }

  @Test
  void wellTypedExpressionsAlwaysCheck() {
    Random r = new Random(99L);
    for (int i = 0; i < 400; i++) {
      String e = expr(r, 5);
      String type = TypeChecker.infer(e); // must not throw an ElmTypeError
      assertTrue(
          type.equals("Int") || type.contains("number"),
          "expected a numeric type for " + e + " but got " + type);
    }
  }
}

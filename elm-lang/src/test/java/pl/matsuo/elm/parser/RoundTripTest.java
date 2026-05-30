package pl.matsuo.elm.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.ast.Expr;

/**
 * Parser round-trip property: for randomly generated expressions, parsing, unparsing ({@link
 * Unparse}) and re-parsing yields a structurally identical AST. This fuzzes the parser and the
 * pretty-printer against each other.
 */
class RoundTripTest {

  private static String gen(Random r, int depth) {
    if (depth <= 0 || r.nextInt(100) < 30) {
      return Integer.toString(r.nextInt(100));
    }
    return switch (r.nextInt(8)) {
      case 0 -> "(" + gen(r, depth - 1) + " + " + gen(r, depth - 1) + ")";
      case 1 -> "(" + gen(r, depth - 1) + " * " + gen(r, depth - 1) + ")";
      case 2 -> "(" + gen(r, depth - 1) + " < " + gen(r, depth - 1) + ")";
      case 3 -> "(if " + gen(r, depth - 1) + " then " + gen(r, depth - 1) + " else "
          + gen(r, depth - 1) + ")";
      case 4 -> "(let x = " + gen(r, depth - 1) + " in (x + " + gen(r, depth - 1) + "))";
      case 5 -> "((\\n -> (n + " + gen(r, depth - 1) + ")) " + gen(r, depth - 1) + ")";
      case 6 -> "[" + gen(r, depth - 1) + ", " + gen(r, depth - 1) + "]";
      default -> "(" + gen(r, depth - 1) + ", " + gen(r, depth - 1) + ")";
    };
  }

  @Test
  void parseUnparseReparseIsStable() {
    Random r = new Random(7L);
    for (int i = 0; i < 500; i++) {
      String src = gen(r, 5);
      Expr a1 = Parser.parseExpression(src);
      Expr a2 = Parser.parseExpression(Unparse.expr(a1));
      assertEquals(AstSexpr.show(a1), AstSexpr.show(a2), "round-trip changed AST for: " + src);
    }
  }
}

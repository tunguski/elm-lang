package pl.matsuo.elm.doc;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.lexer.Lexer;

/**
 * Runs the executable examples in a module's doc comments. An example is an expression line followed
 * by a {@code -->} line giving its expected value (the elm-verify-examples convention):
 *
 * <pre>
 *     List.map negate [ 1, 2 ]
 *     --> [ -1, -2 ]
 * </pre>
 *
 * Each expression is evaluated with the module's own declarations in scope and compared (by rendered
 * value) against the expected expression, so the docs are checked to actually be correct.
 */
public final class DocTest {

  private DocTest() {}

  /** A doctest run: how many examples passed/failed, with a message per failure. */
  public record Result(int passed, int failed, List<String> failures) {
    public boolean ok() {
      return failed == 0;
    }
  }

  public static Result run(String source) {
    int passed = 0;
    List<String> failures = new ArrayList<>();
    Interpreter interp;
    try {
      interp = Interpreter.load(source);
    } catch (RuntimeException e) {
      return new Result(0, 1, List.of("module did not load: " + e.getMessage()));
    }
    for (String[] example : examples(source)) {
      String expr = example[0];
      String expected = example[1];
      try {
        String got = Show.plain(interp.evalExpr(expr));
        String want = Show.plain(interp.evalExpr(expected));
        if (got.equals(want)) {
          passed++;
        } else {
          failures.add(expr + "  -->  expected " + want + " but got " + got);
        }
      } catch (RuntimeException e) {
        failures.add(expr + "  -->  error: " + e.getMessage());
      }
    }
    return new Result(passed, failures.size(), failures);
  }

  /** Extracts {@code [expression, expected]} pairs from every doc comment. */
  static List<String[]> examples(String source) {
    List<String[]> out = new ArrayList<>();
    for (Lexer.Comment c : Lexer.comments(source)) {
      if (!c.block() || !c.text().startsWith("{-|")) {
        continue;
      }
      String[] lines = c.text().split("\n", -1);
      String prevExpr = null;
      for (String raw : lines) {
        String line = stripDocPrefix(raw);
        if (line.startsWith("-->")) {
          if (prevExpr != null) {
            out.add(new String[] {prevExpr, line.substring(3).strip()});
            prevExpr = null;
          }
        } else if (!line.isBlank()) {
          prevExpr = line;
        }
      }
    }
    return out;
  }

  /** Strips a doc line down to its code: drops leading indentation and a {@code {-|} opener. */
  private static String stripDocPrefix(String raw) {
    String s = raw.strip();
    if (s.startsWith("{-|")) {
      s = s.substring(3).strip();
    }
    if (s.endsWith("-}")) {
      s = s.substring(0, s.length() - 2).strip();
    }
    return s;
  }
}

package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * Property-based differential testing: randomly generated (well-typed, total) Elm expressions must
 * evaluate to the same value on all three backends — the Truffle interpreter, the bytecode VM and
 * the JavaScript compiler (run under Node). This is how cross-backend disagreements are found
 * proactively rather than one example at a time.
 */
class DifferentialPropertyTest {

  /** Generates closed, total, Int-typed Elm expressions over a small grammar. */
  private static final class Gen {
    final Random rng;

    Gen(long seed) {
      this.rng = new Random(seed);
    }

    String expr(int depth) {
      if (depth <= 0 || rng.nextInt(100) < 25) {
        return Integer.toString(rng.nextInt(20)); // small non-negative literal
      }
      return switch (rng.nextInt(9)) {
        case 0 -> "(" + expr(depth - 1) + " + " + expr(depth - 1) + ")";
        case 1 -> "(" + expr(depth - 1) + " * " + expr(depth - 1) + ")";
        case 2 -> "(" + expr(depth - 1) + " - " + expr(depth - 1) + ")";
        case 3 ->
            "(if "
                + expr(depth - 1)
                + " < "
                + expr(depth - 1)
                + " then "
                + expr(depth - 1)
                + " else "
                + expr(depth - 1)
                + ")";
        case 4 -> "(let x = " + expr(depth - 1) + " in (x + x))";
        case 5 -> "((\\n -> n * n - " + expr(depth - 1) + ") " + expr(depth - 1) + ")";
        case 6 -> "(modBy " + (1 + rng.nextInt(7)) + " " + expr(depth - 1) + ")";
        case 7 -> "(abs (" + expr(depth - 1) + " - " + expr(depth - 1) + "))";
        default -> "(List.sum (List.map (\\n -> n + 1) (List.range 0 " + (1 + rng.nextInt(5)) + ")))";
      };
    }
  }

  @Test
  void backendsAgreeOnRandomExpressions() throws Exception {
    Gen gen = new Gen(20240530L); // fixed seed -> reproducible
    List<String> exprs = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      exprs.add(gen.expr(4));
    }

    // Interpreter and bytecode VM are evaluated in-process.
    List<String> interp = new ArrayList<>();
    List<String> bytecode = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      bytecode.add(Show.plain(BytecodeInterpreter.eval(e)));
    }
    for (int i = 0; i < exprs.size(); i++) {
      assertEquals(interp.get(i), bytecode.get(i), "interp vs bytecode: " + exprs.get(i));
    }

    // The JS backend is compared too, in a single Node run (skipped if Node is unavailable).
    String node = runNode(JsCompiler.expressionsProgram(exprs));
    if (node != null) {
      String[] js = node.split("\n", -1);
      assertEquals(exprs.size(), js.length, "JS produced a result per expression");
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), js[i], "interp vs JS: " + exprs.get(i));
      }
    }
  }

  /** Runs a JS program under Node, returning stdout, or null if Node is not installed. */
  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-diff-", ".js");
      Files.writeString(file, program, StandardCharsets.UTF_8);
      Process p = new ProcessBuilder("node", file.toString()).redirectErrorStream(false).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("node timed out");
      }
      Files.deleteIfExists(file);
      assertTrue(p.exitValue() == 0, "node failed: " + err);
      return out;
    } catch (IOException e) {
      return null; // Node not installed — interpreter/bytecode comparison still ran.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}

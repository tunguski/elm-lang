package pl.matsuo.elm.repl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReplTest {

  private String session(String input) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Repl.loop(new StringReader(input), new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void evaluatesExpressionsAndReportsErrors() throws Exception {
    String out = session("1 + 2 * 3\nList.map (\\x -> x * x) [1,2,3]\n:quit\n");
    assertTrue(out.contains("7"), out);
    assertTrue(out.contains("[1,4,9]"), out);
  }

  @Test
  void recoversFromAnErrorAndKeepsGoing() throws Exception {
    String out = session("nonexistentValue\n40 + 2\n");
    assertTrue(out.contains("Error:"), out); // unbound name reported
    assertTrue(out.contains("42"), out); // session continued after the error
  }

  @Test
  void definitionsPersistAcrossEntries() throws Exception {
    String out = session("double n = n * 2\nx = 21\ndouble x\n:quit\n");
    assertTrue(out.contains("42"), out); // both `double` and `x` were remembered
  }

  @Test
  void typeCommandShowsInferredType() throws Exception {
    String out = session(":type List.map\n:type 1 + 2\n:quit\n");
    assertTrue(out.contains("(a -> b) -> List a -> List b"), out);
    assertTrue(out.contains(": number"), out);
  }

  @Test
  void multiLineInputIsAccumulatedUntilComplete() throws Exception {
    String out = session("if 1 < 2 then\n  100\nelse\n  200\n:quit\n");
    assertTrue(out.contains("100"), out);
  }

  @Test
  void resetForgetsDefinitions() throws Exception {
    String out = session("y = 5\n:reset\ny\n:quit\n");
    assertTrue(out.contains("Error:"), out); // `y` is unknown again after :reset
  }

  @Test
  void completeDetectsBalanceAndContinuations() {
    assertTrue(Repl.complete("1 + 2"));
    assertTrue(Repl.complete("begin")); // ends in "in" but isn't the keyword
    org.junit.jupiter.api.Assertions.assertFalse(Repl.complete("(1 +"));
    org.junit.jupiter.api.Assertions.assertFalse(Repl.complete("if x then"));
    org.junit.jupiter.api.Assertions.assertFalse(Repl.complete("x ="));
  }
}

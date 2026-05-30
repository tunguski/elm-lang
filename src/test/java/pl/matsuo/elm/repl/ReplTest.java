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
}

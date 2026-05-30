package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests execution coverage of top-level definitions via the interpreter. */
class CoverageTest {

  @Test
  void recordsWhichDefinitionsRan() {
    String src =
        """
        used n = n + 1
        alsoUsed = 10
        neverCalled n = n * 999
        main = used alsoUsed
        """;
    Interpreter interp = Interpreter.loadWithCoverage(src);
    interp.value("main"); // force/run main

    assertTrue(interp.coverableNames().containsAll(java.util.List.of("used", "alsoUsed", "neverCalled", "main")));
    assertTrue(interp.coveredNames().contains("main"), interp.coverageReport());
    assertTrue(interp.coveredNames().contains("used"), interp.coverageReport());
    assertTrue(interp.coveredNames().contains("alsoUsed"), interp.coverageReport());
    assertTrue(!interp.coveredNames().contains("neverCalled"), "unused function not covered");
    assertTrue(interp.coverageReport().contains("✗ neverCalled"), interp.coverageReport());
    assertEquals(3, interp.coveredNames().size());
  }

  @Test
  void normalLoadHasNoCoverageOverhead() {
    // Without coverage, nothing is recorded (the default fast path is untouched).
    Interpreter interp = Interpreter.load("answer = 42\nmain = answer\n");
    interp.value("main");
    assertTrue(interp.coveredNames().isEmpty());
  }
}

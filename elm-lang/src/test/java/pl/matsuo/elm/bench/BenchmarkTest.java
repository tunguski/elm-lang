package pl.matsuo.elm.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BenchmarkTest {

  @Test
  void benchmarkRunsAndBackendsAgree() throws Exception {
    // A real run on the Maven test JVM (which loads the optimizing Graal Truffle runtime). The
    // harness cross-checks that the interpreter and bytecode VM agree; the report is written out so
    // the measured timings can be inspected.
    String report = Benchmark.run(30, 50, 50);
    Files.writeString(Path.of("target", "bench-report.txt"), report);
    assertTrue(report.contains("fib(30) = 832040"), report);
    assertTrue(report.contains("Truffle interpreter"), report);
    assertTrue(report.contains("Bytecode VM"), report);
    // The JS backend is timed too when Node is available (it is on CI).
    assertTrue(
        report.contains("JavaScript (Node)") || !nodeAvailable(),
        "JS row should appear when Node is installed:\n" + report);
  }

  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").start();
      p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      return p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}

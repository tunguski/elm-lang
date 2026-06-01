package pl.matsuo.elm.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchmarkTest {

  @Test
  void regressionCheckFlagsBackendsThatGotSlower() {
    // Pure comparison logic (no real timing): a backend slower than baseline*(1+tolerance) regresses.
    Map<String, Double> base = Map.of("Interp", 10.0, "Bytecode", 20.0, "JS", 5.0);
    Map<String, Double> current = Map.of("Interp", 30.0, "Bytecode", 22.0, "JS", 5.0);
    List<String> regressions = Benchmark.checkRegressions(base, current, 0.5); // allow 50% slower
    // Interp 30 > 10*1.5=15 -> regressed; Bytecode 22 < 20*1.5=30 -> ok; JS unchanged -> ok.
    assertEquals(1, regressions.size(), regressions.toString());
    assertTrue(regressions.get(0).contains("Interp"), regressions.toString());
    // A clean run yields no regressions.
    assertTrue(Benchmark.checkRegressions(base, base, 0.5).isEmpty());
  }

  @Test
  void baselineJsonRoundTrips() {
    Map<String, Double> warm = new java.util.LinkedHashMap<>();
    warm.put("Truffle interpreter", 12.5);
    warm.put("Bytecode VM", 40.0);
    Map<String, Double> back = Benchmark.parseBaseline(Benchmark.baselineJson(warm));
    assertEquals(12.5, back.get("Truffle interpreter"));
    assertEquals(40.0, back.get("Bytecode VM"));
  }

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
    // The JS, WASM and WasmGC backends are timed too when Node is available (it is on CI).
    assertTrue(
        (report.contains("JavaScript (Node)")
                && report.contains("WebAssembly (Node)")
                && report.contains("WasmGC (Node)"))
            || !nodeAvailable(),
        "JS, WASM and WasmGC rows should appear when Node is installed:\n" + report);
    // The extra workloads (list fold, record update) are reported too.
    assertTrue(report.contains("Workload: list fold"), report);
    assertTrue(report.contains("Workload: record update"), report);
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

package pl.matsuo.elm.bench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BenchmarkTest {

  @Test
  void benchmarkRunsAndBackendsAgree() {
    // A small run; Benchmark.run cross-checks that the interpreter and bytecode VM agree, and
    // reports cold vs warm timings (the Graal JIT warm-up is visible when run on GraalVM).
    String report = Benchmark.run(20, 3, 3);
    assertTrue(report.contains("fib(20) = 6765"), report);
    assertTrue(report.contains("Truffle interpreter"), report);
    assertTrue(report.contains("Bytecode VM"), report);
  }
}

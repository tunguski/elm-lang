package pl.matsuo.elm.bench;

import java.util.Arrays;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Interpreter;

/**
 * A small benchmark comparing the Truffle JIT interpreter against the bytecode VM on a hot,
 * call-heavy workload (naive recursive Fibonacci). On GraalVM the Truffle interpreter's hot
 * CallTargets are partially evaluated and compiled to machine code, so its per-run time should drop
 * sharply after warm-up — which this harness makes visible by printing cold vs warm timings.
 */
public final class Benchmark {

  private Benchmark() {}

  private static final String SOURCE =
      """
      fib n = if n < 2 then n else fib (n - 1) + fib (n - 2)
      sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)
      """;

  /** Runs the benchmark and returns a formatted report. */
  public static String run(long fibN, int warmup, int measured) {
    Object interpFib = Interpreter.load(SOURCE).value("fib");
    Object bcFib = BytecodeInterpreter.load(SOURCE).value("fib");

    long interpResult = (Long) Apply.apply(interpFib, fibN);
    long bcResult = (Long) Apply.apply(bcFib, fibN);
    if (interpResult != bcResult) {
      throw new IllegalStateException("backends disagree: " + interpResult + " vs " + bcResult);
    }

    double[] interp = time(() -> Apply.apply(interpFib, fibN), warmup, measured);
    double[] bytecode = time(() -> Apply.apply(bcFib, fibN), warmup, measured);

    StringBuilder sb = new StringBuilder();
    sb.append("Benchmark: fib(").append(fibN).append(") = ").append(interpResult)
        .append("  (warmup=").append(warmup).append(", measured=").append(measured).append(")\n");
    sb.append(String.format("%-22s %12s %12s %12s%n", "backend", "cold ms", "warm ms", "speedup"));
    report(sb, "Truffle interpreter", interp);
    report(sb, "Bytecode VM", bytecode);
    return sb.toString();
  }

  private static void report(StringBuilder sb, String name, double[] r) {
    sb.append(String.format("%-22s %12.2f %12.2f %11.1fx%n", name, r[0], r[1], r[0] / r[1]));
  }

  /** Returns {cold ms (first run), warm ms (median of measured runs)}. */
  private static double[] time(Runnable task, int warmup, int measured) {
    long first = System.nanoTime();
    task.run();
    double cold = (System.nanoTime() - first) / 1e6;
    for (int i = 1; i < warmup; i++) {
      task.run();
    }
    double[] samples = new double[measured];
    for (int i = 0; i < measured; i++) {
      long t = System.nanoTime();
      task.run();
      samples[i] = (System.nanoTime() - t) / 1e6;
    }
    Arrays.sort(samples);
    return new double[] {cold, samples[measured / 2]};
  }

  public static void main(String[] args) {
    long n = args.length > 0 ? Long.parseLong(args[0]) : 32;
    System.out.print(run(n, 50, 50));
  }
}

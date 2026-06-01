package pl.matsuo.elm.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.codegen.wasm.WasmCompiler;
import pl.matsuo.elm.codegen.wasm.WasmGc;
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

  // Extra workloads stress different costs than fib's pure call overhead. Both use only user-defined
  // recursion + cons-lists / closed records (no stdlib), so all five backends — including WasmGC,
  // which has no prelude — can compile them.
  private static final String LIST_SOURCE =
      """
      range lo hi = if lo > hi then [] else lo :: range (lo + 1) hi
      sum xs = case xs of
          [] -> 0
          h :: t -> h + sum t
      total n = sum (range 1 n)
      """;
  private static final String RECORD_SOURCE =
      """
      bump p = { p | x = p.x + 1, y = p.y + 2 }
      loop n p = if n == 0 then p.x + p.y else loop (n - 1) (bump p)
      run n = loop n { x = 0, y = 0 }
      """;

  /** A named benchmark: a module, the entry function (an {@code Int -> Int}) and its argument. */
  private record Workload(String name, String source, String entry, long arg) {}

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
    double[] js = timeJs(SOURCE, "fib", fibN, warmup, measured); // null if Node is unavailable
    double[] wasm = timeWasm(SOURCE, "fib", fibN, warmup, measured);
    double[] wasmGc = timeWasmGc(SOURCE, "fib", fibN, warmup, measured);

    StringBuilder sb = new StringBuilder();
    sb.append("Benchmark: fib(").append(fibN).append(") = ").append(interpResult)
        .append("  (warmup=").append(warmup).append(", measured=").append(measured).append(")\n");
    sb.append(String.format("%-22s %12s %12s%n", "backend", "cold ms", "warm ms (best)"));
    report(sb, "Truffle interpreter", interp);
    report(sb, "Bytecode VM", bytecode);
    if (js != null) {
      report(sb, "JavaScript (Node)", js);
    }
    if (wasm != null) {
      report(sb, "WebAssembly (Node)", wasm);
    }
    if (wasmGc != null) {
      report(sb, "WasmGC (Node)", wasmGc);
    }
    sb.append(String.format(
        "%nWarm: Truffle interpreter is %.2fx the bytecode VM "
            + "(its hot CallTargets are Graal-compiled on GraalVM).%n",
        bytecode[1] / interp[1]));

    // Additional workloads (warm ms per backend) — list allocation/fold and record update, which
    // stress allocation and data-structure handling rather than fib's pure call overhead.
    // Modest depths: the bytecode VM keeps each call on the Java stack (no TCO), so these stay well
    // under its limit while still exercising allocation and data-structure handling.
    sb.append(runWorkload(new Workload("list fold: sum (range 1 100)", LIST_SOURCE, "total", 100), warmup, measured));
    sb.append(runWorkload(new Workload("record update x150", RECORD_SOURCE, "run", 150), warmup, measured));
    return sb.toString();
  }

  /** Times one workload on every backend that can compile it, as a small "warm ms" table (a backend
   * whose module fails to compile, or Node being absent, shows {@code n/a}). */
  private static String runWorkload(Workload w, int warmup, int measured) {
    Object interpFn = Interpreter.load(w.source()).value(w.entry());
    Object bcFn = BytecodeInterpreter.load(w.source()).value(w.entry());
    double interp = time(() -> Apply.apply(interpFn, w.arg()), warmup, measured)[1];
    double bytecode = time(() -> Apply.apply(bcFn, w.arg()), warmup, measured)[1];
    double[] js = timeJs(w.source(), w.entry(), w.arg(), warmup, measured);
    double[] wasm = timeWasm(w.source(), w.entry(), w.arg(), warmup, measured);
    double[] gc = timeWasmGc(w.source(), w.entry(), w.arg(), warmup, measured);
    StringBuilder sb = new StringBuilder();
    sb.append(String.format("%nWorkload: %s  (warm ms)%n", w.name()));
    sb.append(String.format("  %-22s %10.2f%n", "Truffle interpreter", interp));
    sb.append(String.format("  %-22s %10.2f%n", "Bytecode VM", bytecode));
    workloadRow(sb, "JavaScript (Node)", js);
    workloadRow(sb, "WebAssembly (Node)", wasm);
    workloadRow(sb, "WasmGC (Node)", gc);
    return sb.toString();
  }

  private static void workloadRow(StringBuilder sb, String name, double[] r) {
    if (r != null) {
      sb.append(String.format("  %-22s %10.2f%n", name, r[1]));
    } else {
      sb.append(String.format("  %-22s %10s%n", name, "n/a"));
    }
  }

  /** Best warm time (ms) per backend, for charting. JS is included only if Node is available. */
  public static java.util.LinkedHashMap<String, Double> warm(long fibN, int warmup, int measured) {
    Object interpFib = Interpreter.load(SOURCE).value("fib");
    Object bcFib = BytecodeInterpreter.load(SOURCE).value("fib");
    java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
    out.put("Truffle interpreter", time(() -> Apply.apply(interpFib, fibN), warmup, measured)[1]);
    out.put("Bytecode VM", time(() -> Apply.apply(bcFib, fibN), warmup, measured)[1]);
    double[] js = timeJs(SOURCE, "fib", fibN, warmup, measured);
    if (js != null) {
      out.put("JavaScript (Node)", js[1]);
    }
    double[] wasm = timeWasm(SOURCE, "fib", fibN, warmup, measured);
    if (wasm != null) {
      out.put("WebAssembly (Node)", wasm[1]);
    }
    double[] wasmGc = timeWasmGc(SOURCE, "fib", fibN, warmup, measured);
    if (wasmGc != null) {
      out.put("WasmGC (Node)", wasmGc[1]);
    }
    return out;
  }

  // --- regression guard --------------------------------------------------

  /** Serialises warm timings to a baseline JSON object ({@code {"backend": ms, …}}). */
  public static String baselineJson(java.util.Map<String, Double> warm) {
    java.util.LinkedHashMap<String, Object> obj = new java.util.LinkedHashMap<>(warm);
    return pl.matsuo.elm.json.JsonEncode.serialize(obj, 2) + "\n";
  }

  /** Parses a baseline JSON object back to per-backend warm times. */
  @SuppressWarnings("unchecked")
  public static java.util.Map<String, Double> parseBaseline(String json) {
    java.util.LinkedHashMap<String, Double> out = new java.util.LinkedHashMap<>();
    if (pl.matsuo.elm.json.JsonParse.parse(json) instanceof java.util.Map<?, ?> m) {
      ((java.util.Map<String, Object>) m)
          .forEach((k, v) -> out.put(k, ((Number) v).doubleValue()));
    }
    return out;
  }

  /**
   * Compares current warm timings against a baseline and returns a message for each backend that
   * regressed by more than {@code tolerance} (a fraction: 0.5 = 50% slower). Backends absent from
   * either side are skipped. Timing is noisy, so callers should use a generous tolerance.
   */
  public static java.util.List<String> checkRegressions(
      java.util.Map<String, Double> baseline,
      java.util.Map<String, Double> current,
      double tolerance) {
    java.util.List<String> regressions = new java.util.ArrayList<>();
    baseline.forEach(
        (name, base) -> {
          Double cur = current.get(name);
          if (cur != null && cur > base * (1 + tolerance)) {
            regressions.add(
                String.format(
                    java.util.Locale.US,
                    "%s regressed: %.2f ms vs baseline %.2f ms (>%.0f%% slower)",
                    name, cur, base, tolerance * 100));
          }
        });
    return regressions;
  }

  /** Warm timing of the WasmGC backend, or null if Node is unavailable or the module can't compile. */
  public static double[] timeWasmGc(String src, String fn, long n, int warmup, int measured) {
    try {
      return timeWasmModule(WasmGc.module(src), fn, n, warmup, measured);
    } catch (RuntimeException e) {
      return null; // the workload uses a construct WasmGC doesn't support
    }
  }

  /** Warm timing of the linear-memory WASM backend, or null if unavailable / uncompilable. */
  public static double[] timeWasm(String src, String fn, long n, int warmup, int measured) {
    try {
      return timeWasmModule(WasmCompiler.moduleFromSource(src), fn, n, warmup, measured);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Instantiates a wasm module under Node, times {@code func(fibN)} cold then warm (best of
   * {@code measured}), and returns {cold ms, warm ms} — or null if Node/instantiation is unavailable. */
  private static double[] timeWasmModule(byte[] module, String func, long fibN, int warmup, int measured) {
    try {
      Path wasm = Files.createTempFile("elm-bench-", ".wasm");
      Files.write(wasm, module);
      Path js = Files.createTempFile("elm-bench-", ".js");
      Files.writeString(
          js,
          "const fs=require('fs');"
              + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
              + "const f=r.instance.exports." + func + ",N=" + fibN + "n;"
              + "let t=process.hrtime.bigint();f(N);let cold=Number(process.hrtime.bigint()-t)/1e6;"
              + "for(let i=1;i<" + warmup + ";i++)f(N);"
              + "let best=Infinity;for(let j=0;j<" + measured + ";j++){let s=process.hrtime.bigint();f(N);"
              + "let ms=Number(process.hrtime.bigint()-s)/1e6;if(ms<best)best=ms;}"
              + "process.stdout.write(cold+' '+best);}).catch(e=>{process.exit(1);});",
          StandardCharsets.UTF_8);
      Process p =
          new ProcessBuilder("node", js.toString(), wasm.toString()).redirectErrorStream(false).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      Files.deleteIfExists(wasm);
      Files.deleteIfExists(js);
      if (p.exitValue() != 0 || out.isEmpty()) {
        return null;
      }
      String[] parts = out.split("\\s+");
      return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    } catch (Exception e) {
      return null;
    }
  }

  /** Warm timing of the JS backend (run under Node), or null if Node is not installed. */
  public static double[] timeJs(String src, String fn, long n, int warmup, int measured) {
    try {
      Path file = Files.createTempFile("elm-bench-", ".js");
      Files.writeString(
          file, JsCompiler.benchProgram(src, fn, n, warmup, measured), StandardCharsets.UTF_8);
      Process p = new ProcessBuilder("node", file.toString()).redirectErrorStream(false).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        return null;
      }
      Files.deleteIfExists(file);
      if (p.exitValue() != 0 || out.isEmpty()) {
        return null;
      }
      String[] parts = out.split("\\s+");
      return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
    } catch (Exception e) {
      return null;
    }
  }

  private static void report(StringBuilder sb, String name, double[] r) {
    sb.append(String.format("%-22s %12.2f %12.2f%n", name, r[0], r[1]));
  }

  /** Returns {cold ms (first run), warm ms (best of the measured runs)}. */
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
    return new double[] {cold, samples[0]};
  }

  public static void main(String[] args) {
    long n = args.length > 0 ? Long.parseLong(args[0]) : 32;
    System.out.print(run(n, 50, 50));
  }
}

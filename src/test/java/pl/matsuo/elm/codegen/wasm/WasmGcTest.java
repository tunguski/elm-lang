package pl.matsuo.elm.codegen.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * The WasmGC backend: cons-lists compiled to host-garbage-collected structs (no linear memory).
 * Each program's `main` is compiled, run under Node (whose V8 supports WasmGC), and checked against
 * the tree interpreter. Skipped if Node is unavailable or its WasmGC is disabled.
 */
class WasmGcTest {

  private static final boolean NODE = nodeAvailable();

  private String runMain(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-gc-", ".wasm");
    Files.write(wasm, WasmGc.module(source));
    Path js = Files.createTempFile("elm-gc-run-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "process.stdout.write(r.instance.exports.main().toString());"
            + "}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p = new ProcessBuilder("node", js.toString(), wasm.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm-gc failed: " + err);
    }
    return out;
  }

  private void agrees(String source) throws Exception {
    assumeTrue(NODE, "node not available");
    String expected = Show.plain(Interpreter.load(source).value("main"));
    assertEquals(expected, runMain(source), source);
  }

  @Test
  void recursionWithoutLists() throws Exception {
    agrees("fib n = if n < 2 then n else fib (n - 1) + fib (n - 2)\nmain = fib 15\n");
  }

  @Test
  void tailRecursionRunsAtGreatDepth() throws Exception {
    agrees("sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)\nmain = sumTo 1000000 0\n");
  }

  @Test
  void floatArithmetic() throws Exception {
    agrees("main = 1.5 + 2.25\n"); // 3.75
    agrees("main = 7.0 / 2.0\n"); // 3.5
    agrees("main = 3.0 * 3.0 - 1.0\n"); // 8
    agrees("main = 0.0 - 4.5\n"); // -4.5
  }

  @Test
  void floatFunctionsConditionalsAndLet() throws Exception {
    agrees("area r = 3.14 * r * r\nmain = area 2.0\n"); // 12.56
    agrees("main = if 1.5 < 2.5 then 10.0 else 0.0\n"); // 10
    agrees("main = let x = 1.5 in x + x\n"); // 3
  }

  @Test
  void sumsAConsListBuiltOnTheGcHeap() throws Exception {
    agrees(
        """
        range lo hi = if lo > hi then [] else lo :: range (lo + 1) hi
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        main = sum (range 1 100)
        """);
  }

  @Test
  void listLengthAndLiterals() throws Exception {
    agrees(
        """
        len xs = case xs of
            [] -> 0
            h :: t -> 1 + len t
        main = len [ 10, 20, 30, 40 ]
        """);
  }

  @Test
  void buildsAndFoldsManyCellsThenReclaims() throws Exception {
    // A deep GC list (no linear memory, no manual reclamation) — the engine collects it.
    agrees(
        """
        range lo hi = if lo > hi then [] else lo :: range (lo + 1) hi
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        loop k = if k == 0 then 0 else sum (range 1 500) + loop (k - 1)
        main = loop 100
        """);
  }

  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}

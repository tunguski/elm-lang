package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.codegen.wasm.WasmCompiler;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * A non-trivial pure data core — the kind of logic underlying the TodoMVC app — run on <b>all four
 * backends</b> (Truffle interpreter, bytecode VM, JavaScript and WebAssembly), which must all agree.
 * It exercises records, lists, booleans, closures, `List.map`/`filter`/`sum` and arithmetic together,
 * across the backends that don't do Html/effects (WASM/bytecode can't render TEA, but they run this).
 */
class FourBackendCoreTest {

  // String-free so it compiles to WASM: a list of {weight, done} tasks; sum the weight of the
  // pending ones. (5 + 8 + 4 = 17.)
  private static final String CORE =
      """
      tasks =
          [ { weight = 5, done = False }
          , { weight = 3, done = True }
          , { weight = 8, done = False }
          , { weight = 2, done = True }
          , { weight = 4, done = False }
          ]
      pending ts = List.filter (\\t -> t.done == False) ts
      totalWeight ts = List.sum (List.map (\\t -> t.weight) ts)
      main = totalWeight (pending tasks)
      """;

  private static final boolean NODE = nodeAvailable();

  @Test
  void pendingWeightAgreesAcrossAllBackends() throws Exception {
    String expected = Show.plain(Interpreter.load(CORE).value("main")); // the reference
    assertEquals("17", expected);
    assertEquals(expected, Show.plain(BytecodeInterpreter.load(CORE).value("main")), "bytecode VM");

    assumeTrue(NODE, "node not available");
    assertEquals(expected, runNode(JsCompiler.moduleProgram(CORE)).trim(), "JavaScript backend");
    assertEquals(expected, runWasmMain(CORE), "WebAssembly backend");
  }

  // A partially applied self-tail-recursive function, mapped over a list: every element must see the
  // same captured argument (`sumFrom 0`), so the result is 6 + 6 + 6 = 18. Regression for a JS-backend
  // bug where the self-tail-call loop reassigned the *outer* curried parameters that the partial
  // application's closure captures, so each element resumed from the previous one's mutated `acc`
  // (giving 36). The other three backends were always correct — which is exactly why an
  // interpreter-only differential check missed it, hence this all-four-backends test.
  private static final String PARTIAL_APP =
      """
      sumFrom acc n = if n <= 0 then acc else sumFrom (acc + n) (n - 1)
      main = List.sum (List.map (sumFrom 0) [ 3, 3, 3 ])
      """;

  @Test
  void partialApplicationOfTailRecursiveFunctionAgreesAcrossAllBackends() throws Exception {
    String expected = Show.plain(Interpreter.load(PARTIAL_APP).value("main")); // the reference
    assertEquals("18", expected);
    assertEquals(
        expected, Show.plain(BytecodeInterpreter.load(PARTIAL_APP).value("main")), "bytecode VM");

    assumeTrue(NODE, "node not available");
    assertEquals(expected, runNode(JsCompiler.moduleProgram(PARTIAL_APP)).trim(), "JavaScript backend");
    assertEquals(expected, runWasmMain(PARTIAL_APP), "WebAssembly backend");
  }

  private static String runNode(String program) throws Exception {
    Path file = Files.createTempFile("elm-core-js-", ".js");
    Files.writeString(file, program, StandardCharsets.UTF_8);
    String out = exec(file);
    Files.deleteIfExists(file);
    return out;
  }

  private static String runWasmMain(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-core-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-core-run-", ".js");
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
      throw new IllegalStateException("wasm run failed: " + err);
    }
    return out.trim();
  }

  private static String exec(Path jsFile) throws Exception {
    Process p = new ProcessBuilder("node", jsFile.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node failed: " + err);
    }
    return out;
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

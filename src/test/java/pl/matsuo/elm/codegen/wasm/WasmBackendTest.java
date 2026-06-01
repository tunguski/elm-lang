package pl.matsuo.elm.codegen.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.parser.Parser;

/**
 * Tests the WebAssembly backend: each expression is compiled to a wasm binary, instantiated and run
 * under Node, and the result must equal the Truffle interpreter's. Skipped if Node is unavailable.
 */
class WasmBackendTest {

  private static final boolean NODE = nodeAvailable();

  /** Compiles the expressions to one wasm module, runs every exported f0..fN, returns the results. */
  private List<String> runWasm(List<String> expressions) throws Exception {
    List<Expr> exprs = new ArrayList<>();
    for (String e : expressions) {
      exprs.add(Parser.parseExpression(e));
    }
    Path wasm = Files.createTempFile("elm-", ".wasm");
    Files.write(wasm, WasmCompiler.module(exprs));
    Path js = Files.createTempFile("elm-runwasm-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports;let i=0,out=[];"
            + "while(('f'+i) in ex){out.push(ex['f'+i]().toString());i++;}"
            + "process.stdout.write(out.join('\\n'));}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p =
        new ProcessBuilder("node", js.toString(), wasm.toString()).redirectErrorStream(false).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(30, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return List.of(out.split("\n", -1));
  }

  /** Asserts the wasm result equals the interpreter's for a single expression. */
  private void same(String expr) throws Exception {
    assumeTrue(NODE, "Node not installed");
    assertEquals(Show.plain(Interpreter.eval(expr)), runWasm(List.of(expr)).get(0), expr);
  }

  /** The binary carries a "name" custom section so disassembly/traces show readable function names. */
  @Test
  void emitsANameSectionWithFunctionNames() throws Exception {
    byte[] binary =
        WasmCompiler.moduleFromSource(
            "module M exposing (sum)\nsum : Int -> Int -> Int\nsum a b = a + b");
    String text = new String(binary, StandardCharsets.ISO_8859_1);
    // The custom section name itself, the module name, a user function and a native runtime function.
    org.junit.jupiter.api.Assertions.assertTrue(text.contains("name"), "custom section name present");
    org.junit.jupiter.api.Assertions.assertTrue(text.contains("sum"), "user function named");
    org.junit.jupiter.api.Assertions.assertTrue(text.contains("$apply"), "runtime function named");
  }

  @Test
  void arithmetic() throws Exception {
    same("1 + 2 * 3");
    same("(100 - 7) * 3");
    same("7 // 2");
    same("2 * 2 * 2 * 2 * 2");
    same("-5 + 3");
  }

  @Test
  void modAndAbs() throws Exception {
    same("modBy 3 17");
    same("modBy 7 100");
    same("abs (3 - 8)");
    same("abs 42");
  }

  @Test
  void conditionalsAndBooleans() throws Exception {
    same("if 3 < 5 then 10 else 20");
    same("if 5 < 3 then 10 else 20");
    same("if 2 < 1 && 3 > 0 then 1 else 99");
    same("if 2 < 1 || 3 > 0 then 1 else 99");
    same("if not (4 == 4) then 1 else 0");
  }

  @Test
  void letAndLambda() throws Exception {
    same("let x = 4 in x + x");
    same("let a = 10 in let b = 20 in a * b");
    same("(\\n -> n * n + 1) 6");
  }

  @Test
  void largeValuesUseInt64() throws Exception {
    // Exceeds 32 bits, so this only matches if the backend really uses i64 like the interpreter.
    same("1000000 * 1000000");
  }

  /** Compiles a module and calls an exported function with i64 args, returning its result. */
  private long runFunc(String source, String fn, long... args) throws Exception {
    Path wasm = Files.createTempFile("elm-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    StringBuilder call = new StringBuilder("ex." + fn + "(");
    for (int i = 0; i < args.length; i++) {
      call.append(i > 0 ? "," : "").append(args[i]).append("n");
    }
    call.append(")");
    Path js = Files.createTempFile("elm-runwasm-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports;process.stdout.write(("
            + call
            + ").toString());}).catch(e=>{console.error(e);process.exit(1);});",
        StandardCharsets.UTF_8);
    Process p =
        new ProcessBuilder("node", js.toString(), wasm.toString()).redirectErrorStream(false).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(30, TimeUnit.SECONDS);
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return Long.parseLong(out.trim());
  }

  @Test
  void recursiveAndMutuallyRecursiveFunctions() throws Exception {
    assumeTrue(NODE, "Node not installed");
    String src =
        """
        fib n = if n < 2 then n else fib (n - 1) + fib (n - 2)
        sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)
        isEven n = if n == 0 then 1 else isOdd (n - 1)
        isOdd n = if n == 0 then 0 else isEven (n - 1)
        """;
    assertEquals(832040L, runFunc(src, "fib", 30)); // recursion, matches the interpreter
    assertEquals(5050L, runFunc(src, "sumTo", 100, 0)); // two i64 params
    assertEquals(1L, runFunc(src, "isEven", 10)); // mutual recursion
    assertEquals(0L, runFunc(src, "isOdd", 10));
  }

  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").start();
      p.waitFor(10, TimeUnit.SECONDS);
      return p.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }
}

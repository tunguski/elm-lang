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
  void tuplesConstructAndProject() throws Exception {
    // A pair built as a GC struct, with Tuple.first / Tuple.second projecting its fields.
    agrees("pair = ( 3 + 4, 10 * 2 )\nmain = Tuple.first pair + Tuple.second pair\n"); // 27
    agrees("main = Tuple.second ( 1, 99 )\n"); // 99
  }

  @Test
  void tupleDestructuringInLetAndCase() throws Exception {
    agrees("main = let ( a, b ) = ( 5, 6 ) in a * b\n"); // 30
    agrees("swapSum t = case t of\n    ( a, b ) -> b - a\nmain = swapSum ( 2, 9 )\n"); // 7
  }

  @Test
  void tupleAcrossFunctionsAndConditionals() throws Exception {
    // A tuple returned from a function, chosen by an `if`, then projected.
    agrees(
        """
        choose flag = if flag then ( 1, 2 ) else ( 3, 4 )
        main = Tuple.first (choose False) + Tuple.second (choose True)
        """); // 3 + 2 = 5
  }

  @Test
  void nullaryCustomTypesAsEnumTags() throws Exception {
    // A nullary union is an i64 tag; `case` dispatches on it (last branch is the default).
    agrees(
        """
        type Color = Red | Green | Blue
        rank c = case c of
            Red -> 1
            Green -> 2
            Blue -> 3
        main = rank Green + rank Blue
        """); // 2 + 3 = 5
    agrees(
        """
        type Light = On | Off
        toInt l = case l of
            On -> 1
            Off -> 0
        main = toInt On
        """); // 1
  }

  @Test
  void recordsConstructAccessAndUpdate() throws Exception {
    // Records compile to GC structs (fields in sorted-name order); literal, access and update.
    agrees("main = .x { x = 3, y = 4 }\n"); // 3
    agrees("p = { x = 3, y = 4 }\nmain = p.x + p.y\n"); // 7
    agrees("p = { x = 3, y = 4 }\nq = { p | x = 10 }\nmain = q.x + q.y\n"); // 14
  }

  @Test
  void recordsThroughLetAndConditionals() throws Exception {
    // Concrete (closed) records flow through `let`, `if` and nested access. (Row-polymorphic record
    // parameters have no fixed struct layout, so they stay on the linear-memory backend.)
    agrees("main = let p = { x = 5, y = 6 } in p.x + p.y\n"); // 11
    agrees("main = if True then .x { x = 7, y = 0 } else 0\n"); // 7
    agrees("p = { x = 1, y = 2 }\nq = { p | x = p.x + 9 }\nmain = q.x + q.y\n"); // 12
    agrees("main = let r = { pos = { x = 1, y = 2 }, n = 3 } in r.pos.x + r.n\n"); // nested record: 1 + 3 = 4
  }

  @Test
  void listsOfNonIntElements() throws Exception {
    // The cons cell's head type now follows the element type, so lists of Float, tuples and lists
    // (nested) all compile to their own GC struct shapes.
    agrees(
        """
        sum xs = case xs of
            [] -> 0.0
            h :: t -> h + sum t
        main = sum [ 1.5, 2.5, 3.0 ]
        """); // 7
    agrees("main = case 1.5 :: 2.5 :: [] of\n    [] -> 0.0\n    h :: _ -> h\n"); // 1.5
    agrees(
        """
        main = case [ ( 1, 2 ), ( 3, 4 ) ] of
            [] -> 0
            h :: _ -> Tuple.first h + Tuple.second h
        """); // 3
    agrees(
        """
        first xs = case xs of
            [] -> 0
            h :: _ -> h
        firstRow xss = case xss of
            [] -> 0
            row :: _ -> first row
        main = firstRow [ [ 7, 8 ], [ 9 ] ]
        """); // 7
  }

  @Test
  void strings() throws Exception {
    // Strings are GC `array i8`; literals build the array, `String.length` is array.len (byte count,
    // == code-point count for ASCII), and `++` allocates a fresh array and copies both halves in.
    agrees("main = String.length \"hello\"\n"); // 5
    agrees("main = String.length \"\"\n"); // 0
    agrees("main = String.length (\"ab\" ++ \"cde\")\n"); // 5
    agrees("main = String.length (\"\" ++ \"xyz\")\n"); // 3
    agrees("greet name = \"hi \" ++ name\nmain = String.length (greet \"bob\")\n"); // 6
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

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
 * Exercises the WASM backend's linear-memory heap: cons-lists and tuples built and consumed inside
 * wasm, with the result compared against the Truffle interpreter. Skipped if Node is unavailable.
 */
class WasmHeapTest {

  private static final boolean NODE = nodeAvailable();

  /** Compiles a module to wasm, runs its exported `main`, and returns the i64 result as a string. */
  private String runMain(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-heap-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runmain-", ".js");
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
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out;
  }

  private void agrees(String source) throws Exception {
    assumeTrue(NODE, "node not available");
    String expected = Show.plain(Interpreter.load(source).value("main"));
    assertEquals(expected, runMain(source), source);
  }

  @Test
  void sumsAListLiteralRecursively() throws Exception {
    agrees(
        """
        sum xs =
            case xs of
                [] -> 0
                h :: t -> h + sum t
        main = sum [ 1, 2, 3, 4, 5 ]
        """);
  }

  @Test
  void computesListLengthRecursively() throws Exception {
    agrees(
        """
        len xs =
            case xs of
                [] -> 0
                h :: t -> 1 + len t
        main = len [ 10, 20, 30 ]
        """);
  }

  @Test
  void consPrependsThenFolds() throws Exception {
    agrees(
        """
        sum xs =
            case xs of
                [] -> 0
                h :: t -> h + sum t
        build n = n :: (n + 1) :: (n + 2) :: []
        main = sum (build 10)
        """);
  }

  @Test
  void buildsAndSumsViaHelperThatReturnsAList() throws Exception {
    // The allocator must survive nested allocation: range builds cells while sum walks them.
    agrees(
        """
        range lo hi =
            if lo > hi then
                []
            else
                lo :: range (lo + 1) hi
        sum xs =
            case xs of
                [] -> 0
                h :: t -> h + sum t
        main = sum (range 1 10)
        """);
  }

  @Test
  void matchesOnACustomTypeTag() throws Exception {
    agrees(
        """
        type Shape = Circle Int | Rect Int Int | Unit
        area s =
            case s of
                Circle r -> r * r * 3
                Rect w h -> w * h
                Unit -> 1
        main = area (Rect 3 4)
        """);
  }

  @Test
  void recursesOverACustomTypeTree() throws Exception {
    // A recursive ADT (binary tree) built and summed entirely in wasm.
    agrees(
        """
        type Tree = Leaf Int | Node Tree Tree
        total t =
            case t of
                Leaf n -> n
                Node l r -> total l + total r
        main = total (Node (Node (Leaf 1) (Leaf 2)) (Leaf 3))
        """);
  }

  @Test
  void nullaryConstructorsAndDefaultBranch() throws Exception {
    agrees(
        """
        type Color = Red | Green | Blue
        code c =
            case c of
                Red -> 1
                _ -> 0
        main = code Blue
        """);
  }

  @Test
  void randomCustomTypeMatchesAgreeWithInterpreter() throws Exception {
    assumeTrue(NODE, "node not available");
    java.util.Random rng = new java.util.Random(20260530L);
    for (int trial = 0; trial < 20; trial++) {
      int a = rng.nextInt(50);
      int b = rng.nextInt(50);
      String shape =
          switch (trial % 3) {
            case 0 -> "Circle " + a;
            case 1 -> "Rect " + a + " " + b;
            default -> "Empty";
          };
      String source =
          """
          type Shape = Circle Int | Rect Int Int | Empty
          area s =
              case s of
                  Circle r -> r * r
                  Rect w h -> w * h
                  Empty -> 0
          main = area (%s)
          """
              .formatted(shape);
      String expected = Show.plain(Interpreter.load(source).value("main"));
      assertEquals(expected, runMain(source), source);
    }
  }

  @Test
  void randomListFoldsAgreeWithInterpreter() throws Exception {
    assumeTrue(NODE, "node not available");
    // Property: for random integer lists, a recursive sum/length/max in wasm equals the
    // interpreter's — exercising the bump allocator across many shapes and lengths.
    java.util.Random rng = new java.util.Random(20260530L);
    for (int trial = 0; trial < 25; trial++) {
      int n = rng.nextInt(12); // 0..11 elements
      StringBuilder list = new StringBuilder("[");
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          list.append(", ");
        }
        list.append(rng.nextInt(200) - 100); // -100..99
      }
      list.append("]");
      String fold =
          switch (trial % 3) {
            case 0 -> "sum";
            case 1 -> "len";
            default -> "maxOr0";
          };
      String source =
          """
          sum xs = case xs of
              [] -> 0
              h :: t -> h + sum t
          len xs = case xs of
              [] -> 0
              h :: t -> 1 + len t
          maxOr0 xs = case xs of
              [] -> 0
              h :: t -> let m = maxOr0 t in if h > m then h else m
          main = %s %s
          """
              .formatted(fold, list);
      String expected = Show.plain(Interpreter.load(source).value("main"));
      assertEquals(expected, runMain(source), source);
    }
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

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

  /** Runs `main`, treating its i64 result as a pointer to a heap string, and decodes the bytes. */
  private String runMainString(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-str-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runstr-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const ptr=Number(ex.main());"
            + "const dv=new DataView(ex.memory.buffer);"
            + "const len=Number(dv.getBigInt64(ptr,true));"
            + "const bytes=new Uint8Array(ex.memory.buffer, ptr+8, len);"
            + "process.stdout.write(Buffer.from(bytes).toString('utf8'));"
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

  /** Runs `main` whose result is a Float, reinterpreting the returned i64 bit pattern to a double. */
  private void agreesFloat(String source) throws Exception {
    assumeTrue(NODE, "node not available");
    double expected = ((Number) Interpreter.load(source).value("main")).doubleValue();
    double actual = Double.longBitsToDouble(Long.parseLong(runMain(source).trim()));
    assertEquals(expected, actual, 1e-9, source);
  }

  @Test
  void floatArithmetic() throws Exception {
    agreesFloat("main = 1.5 + 2.25\n");
    agreesFloat("main = 7.0 / 2.0\n");
    agreesFloat("main = 3.0 * 1.5 - 0.5\n");
    agreesFloat("main = -2.5 + 2.5\n"); // negate on a float
  }

  @Test
  void floatComparisonDrivesABranch() throws Exception {
    agrees("main = if 1.5 < 2.0 then 1 else 0\n"); // Int result
    agrees("main = if 2.5 >= 2.5 then 1 else 0\n");
  }

  @Test
  void intFloatConversions() throws Exception {
    agreesFloat("main = toFloat 5 / 2.0\n"); // 2.5
    agrees("main = round (7.0 / 2.0)\n"); // 4 (half up)
    agrees("main = floor 3.9\n"); // 3
    agrees("main = ceiling 3.1\n"); // 4
    agrees("main = truncate -3.9\n"); // -3
  }

  @Test
  void floatRecordFieldAndLiteralCoercion() throws Exception {
    // The `3`/`4` literals are inferred Float (the record's fields), so they must be emitted as
    // float bit patterns, and the arithmetic as f64.
    agreesFloat(
        """
        type alias V = { x : Float, y : Float }
        area : V -> Float
        area v = v.x * v.y
        main = area { x = 3, y = 4 }
        """);
  }

  @Test
  void stringLengthLoadsTheLengthWord() throws Exception {
    agrees("main = String.length \"hello\"\n");
  }

  @Test
  void stringConcatThenLength() throws Exception {
    agrees("main = String.length (\"ab\" ++ \"cde\")\n");
  }

  @Test
  void stringEqualityDrivesABranch() throws Exception {
    assumeTrue(NODE, "node not available");
    // == and /= over strings (statically typed String via annotations), exercised through `if`;
    // the result is an Int the host can read. Equal/different and equal/different lengths.
    agrees(
        """
        cmp : String -> String -> Int
        cmp x y = if x == y then 1 else 0
        ne : String -> String -> Int
        ne x y = if x /= y then 1 else 0
        main = cmp "abc" "abc" + cmp "abc" "abd" + ne "ab" "abc" + cmp "ab" "abc"
        """);
  }

  @Test
  void stringConcatProducesTheRightBytes() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("foobar", runMainString("main = \"foo\" ++ \"bar\"\n"));
    // A chained concat and an empty operand.
    assertEquals("Hello, world!", runMainString("main = \"Hello\" ++ \", \" ++ \"world!\"\n"));
    assertEquals("abc", runMainString("main = \"\" ++ \"abc\"\n"));
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
  void buildsARecordAndReadsAFieldByName() throws Exception {
    // A record literal lays its fields out in name-sorted order; the annotated (closed) record type
    // lets the field accesses resolve to the matching offsets.
    agrees(
        """
        type alias Point = { x : Int, y : Int }
        normSq : Point -> Int
        normSq p = p.x * p.x + p.y * p.y
        main = normSq { x = 3, y = 4 }
        """);
  }

  @Test
  void recordFieldOrderDoesNotDependOnLiteralOrder() throws Exception {
    // The literal lists fields out of alphabetical order; sorted layout must still read correctly.
    agrees(
        """
        type alias R = { b : Int, a : Int }
        diff : R -> Int
        diff r = r.a - r.b
        main = diff { b = 10, a = 30 }
        """);
  }

  @Test
  void updatesARecordImmutably() throws Exception {
    agrees(
        """
        type alias Counter = { count : Int, step : Int }
        bump : Counter -> Counter
        bump c = { c | count = c.count + c.step }
        get : Counter -> Int
        get c = c.count
        main = get (bump (bump { count = 0, step = 5 }))
        """);
  }

  @Test
  void appliesAFunctionPassedAsAValue() throws Exception {
    // A top-level function used as a value (its table index) and invoked via call_indirect.
    agrees(
        """
        inc n = n + 1
        apply f x = f x
        main = apply inc 5
        """);
  }

  @Test
  void higherOrderTwiceComposesAFunctionValue() throws Exception {
    agrees(
        """
        double n = n * 2
        twice f x = f (f x)
        main = twice double 7
        """);
  }

  @Test
  void choosesBetweenFunctionValuesThenCallsIndirectly() throws Exception {
    // The function value is selected at runtime, exercising the indirect dispatch over the table.
    agrees(
        """
        inc n = n + 1
        dec n = n - 1
        pick b = if b == 1 then inc else dec
        run g x = g x
        main = run (pick 1) 41 + run (pick 0) 10
        """);
  }

  @Test
  void growsTheHeapAcrossManyPagesForALargeTree() throws Exception {
    // A depth-13 binary tree is ~16k cells (~6 pages, far past the initial 64 KiB), yet recursion
    // stays shallow (depth 13). It only runs without trapping if the allocator grows memory; the
    // total (2^13 = 8192 leaves) must still match the interpreter.
    agrees(
        """
        type Tree = Leaf Int | Node Tree Tree
        build d =
            if d == 0 then
                Leaf 1
            else
                Node (build (d - 1)) (build (d - 1))
        total t =
            case t of
                Leaf n -> n
                Node l r -> total l + total r
        main = total (build 13)
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

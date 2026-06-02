package pl.matsuo.elm.codegen.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

  /** Compiles a multi-module project to one wasm binary, runs `main`, and returns the i64 result. */
  private String runMainProject(java.util.List<String> sources) throws Exception {
    Path wasm = Files.createTempFile("elm-proj-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSources(sources));
    Path js = Files.createTempFile("elm-proj-run-", ".js");
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
    p.waitFor(30, TimeUnit.SECONDS);
    Files.deleteIfExists(wasm);
    Files.deleteIfExists(js);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node/wasm failed: " + err);
    }
    return out;
  }

  @Test
  void compilesAMultiModuleProject() throws Exception {
    assumeTrue(NODE, "node not available");
    // The entry module calls a function defined in another module (as an installed package would be):
    // the merged unit resolves `Util.square` to the compiled `square`.
    String util = "module Util exposing (square)\nsquare n = n * n\n";
    String main =
        "module Main exposing (main)\nimport Util exposing (square)\nmain = Util.square 7 + square 3\n";
    assertEquals("58", runMainProject(java.util.List.of(main, util))); // 49 + 9
  }

  @Test
  void scalarReducingLoopKeepsMemoryBounded() throws Exception {
    assumeTrue(NODE, "node not available");
    // Each iteration builds a 1000-element list and reduces it to an Int; arena reclamation frees
    // that list after every `List.sum (List.range …)` call, so memory stays tiny across 2000
    // iterations instead of accumulating ~2,000,000 cons cells (~32 MB).
    String source =
        """
        inner n = List.sum (List.range 1 n)
        loop k = if k == 0 then 0 else inner 1000 + loop (k - 1)
        main = loop 2000
        """;
    long[] r = runMainAndPages(source);
    assertEquals(1_001_000_000L, r[0], "2000 * sum(1..1000)");
    assertTrue(r[1] < 32, "memory should stay bounded (was " + r[1] + " pages of 64 KiB)");
  }

  /** Runs `main` (an Int result), returning {result, finalMemoryPages}. */
  private long[] runMainAndPages(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-mem-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runmem-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const v=ex.main();"
            + "process.stdout.write(v.toString()+','+(ex.memory.buffer.byteLength/65536));"
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
    String[] parts = out.trim().split(",");
    return new long[] {Long.parseLong(parts[0]), Long.parseLong(parts[1])};
  }

  @Test
  void standardLibraryListFunctions() throws Exception {
    // The WASM prelude (List.map/filter/foldl/range/sum/...) compiles and runs, agreeing with the
    // interpreter — higher-order, recursive, and over cons-lists built on the heap.
    agrees("main = List.sum (List.map (\\x -> x * x) (List.range 1 5))\n"); // 1+4+9+16+25 = 55
    agrees("main = List.length (List.filter (\\n -> n > 2) (List.range 1 6))\n"); // 4
    agrees("main = List.foldl (\\x acc -> x + acc) 0 (List.range 1 10)\n"); // 55
    agrees("main = List.sum (List.reverse (List.range 1 4))\n"); // 10
  }

  @Test
  void standardLibraryMaybe() throws Exception {
    agrees("main = Maybe.withDefault 0 (Maybe.map (\\x -> x + 1) (Just 41))\n"); // 42
    agrees("main = Maybe.withDefault 7 Nothing\n"); // 7
    agrees("main = Maybe.withDefault 0 (Maybe.andThen (\\x -> Just (x * 2)) (Just 21))\n"); // 42
    agrees("main = Result.withDefault 0 (Result.map (\\x -> x + 1) (Ok 41))\n"); // 42
  }

  @Test
  void expandedListPrelude() throws Exception {
    agrees("main = List.sum (List.take 3 (List.range 1 10))\n"); // 1+2+3 = 6
    agrees("main = List.sum (List.drop 7 (List.range 1 10))\n"); // 8+9+10 = 27
    agrees("main = List.sum (List.repeat 4 5)\n"); // 20
    agrees("main = List.product (List.range 1 5)\n"); // 120
    agrees("main = List.length (List.concat [ [ 1, 2 ], [ 3 ], [ 4, 5 ] ])\n"); // 5
    agrees("main = List.sum (List.concatMap (\\x -> [ x, x ]) (List.range 1 3))\n"); // 12
    agrees("main = List.sum (List.map2 (\\a b -> a * b) [ 1, 2, 3 ] [ 4, 5, 6 ])\n"); // 4+10+18 = 32
    agrees("main = if List.all (\\n -> n > 0) (List.range 1 5) then 1 else 0\n"); // 1
    agrees("main = if List.any (\\n -> n > 4) (List.range 1 5) then 1 else 0\n"); // 1
    agrees("main = if List.isEmpty (List.drop 9 (List.range 1 3)) then 100 else 0\n"); // 100
  }

  @Test
  void listAppendOperator() throws Exception {
    // `++` on lists lowers to the prelude's listAppend (copy the left spine onto the shared right).
    agrees("main = List.sum ([ 1, 2, 3 ] ++ [ 4, 5 ])\n"); // 15
    agrees("main = List.length ([ 1, 2 ] ++ [ 3, 4, 5 ])\n"); // 5
    agrees("main = List.sum ([] ++ List.range 1 4)\n"); // 10
    agrees("main = List.sum (List.range 1 3 ++ [])\n"); // 6
    assertEquals("[1,2,3,4,5]", decodeList("main = [ 1, 2, 3 ] ++ [ 4, 5 ]\n"));
  }

  @Test
  void sortingSearchingAndIndexing() throws Exception {
    // Comparison-based prelude additions: maximum/minimum/member/sort/sortBy/indexedMap.
    agrees("main = Maybe.withDefault 0 (List.maximum [ 3, 9, 2, 7 ])\n"); // 9
    agrees("main = Maybe.withDefault 0 (List.minimum [ 3, 9, 2, 7 ])\n"); // 2
    agrees("main = Maybe.withDefault (-1) (List.maximum [])\n"); // -1 (Nothing)
    agrees("main = if List.member 5 [ 1, 5, 9 ] then 1 else 0\n"); // 1
    agrees("main = if List.member 4 [ 1, 5, 9 ] then 1 else 0\n"); // 0
    agrees("main = Maybe.withDefault 0 (List.maximum (List.sort [ 5, 1, 4, 2, 3 ]))\n"); // 5
    agrees("main = List.sum (List.take 2 (List.sort [ 5, 1, 4, 2, 3 ]))\n"); // 1 + 2 = 3
    agrees("main = List.sum (List.take 2 (List.sortBy (\\n -> 0 - n) [ 5, 1, 4, 2, 3 ]))\n"); // 5 + 4 = 9
    agrees("main = List.sum (List.indexedMap (\\i x -> i * x) [ 10, 20, 30 ])\n"); // 0 + 20 + 60 = 80
  }

  @Test
  void headTailFilterMapAndMap3() throws Exception {
    agrees("main = Maybe.withDefault 0 (List.head [ 7, 8, 9 ])\n"); // 7
    agrees("main = List.sum (Maybe.withDefault [] (List.tail [ 7, 8, 9 ]))\n"); // 8 + 9 = 17
    agrees("main = List.sum (List.filterMap (\\n -> if n > 2 then Just (n * 10) else Nothing) [ 1, 2, 3, 4 ])\n"); // 30 + 40 = 70
    agrees("main = List.sum (List.map3 (\\a b c -> a + b + c) [ 1, 2 ] [ 10, 20 ] [ 100, 200 ])\n"); // 111 + 222 = 333
  }

  @Test
  void stringIsEmpty() throws Exception {
    agrees("main = if String.isEmpty \"\" then 1 else 0\n"); // 1
    agrees("main = if String.isEmpty \"x\" then 1 else 0\n"); // 0
  }

  @Test
  void stringAppendConcatenates() throws Exception {
    agrees("main = String.length (String.append \"ab\" \"cde\")\n"); // 5
    agrees("main = if String.append \"ab\" \"cd\" == \"abcd\" then 1 else 0\n"); // 1
  }

  @Test
  void stringFromInt() throws Exception {
    agrees("main = String.length (String.fromInt 12345)\n"); // 5
    agrees("main = if String.fromInt 0 == \"0\" then 1 else 0\n"); // 1
    agrees("main = if String.fromInt 42 == \"42\" then 1 else 0\n"); // 1
    agrees("main = if String.fromInt (0 - 7) == \"-7\" then 1 else 0\n"); // "-7"
  }

  @Test
  void multiExportModuleDecodesEveryKind() throws Exception {
    assumeTrue(NODE, "node not available");
    // Mirrors the JS-vs-WASM gallery page: one module exporting f0..fN of mixed result types, each
    // decoded from the i64 return per its kind (number, Float bit-pattern, heap string, cons-list).
    String source =
        "f0 = 1 + 2 * 3\n"
            + "f1 = 7.0 / 2.0\n"
            + "f2 = \"elm\" ++ \"-lang\"\n"
            + "f3 = List.map (\\x -> x * x) (List.range 1 4)\n";
    String[] kinds = {"int", "float", "string", "list"};
    String[] expected = {
      Show.plain(Interpreter.eval("1 + 2 * 3")),
      Show.plain(Interpreter.eval("7.0 / 2.0")),
      Show.plain(Interpreter.eval("\"elm\" ++ \"-lang\"")),
      Show.plain(Interpreter.eval("List.map (\\x -> x * x) (List.range 1 4)")),
    };
    String[] got = decodeKinds(source, kinds);
    for (int i = 0; i < kinds.length; i++) {
      assertEquals(expected[i], got[i], kinds[i] + " (f" + i + ")");
    }
  }

  /** Compiles a multi-export module and decodes each f{i}() per the matching kind (as the page does). */
  private String[] decodeKinds(String source, String[] kinds) throws Exception {
    Path wasm = Files.createTempFile("elm-multi-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-multi-run-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs'); const kinds="
            + "[" + String.join(",", java.util.Arrays.stream(kinds).map(k -> "\"" + k + "\"").toList()) + "];"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const fb=new ArrayBuffer(8), fdv=new DataView(fb);"
            + "function decode(kind,raw){"
            + " if(kind==='float'){fdv.setBigInt64(0,raw,true);return String(fdv.getFloat64(0,true));}"
            + " const dv=new DataView(ex.memory.buffer);"
            + " if(kind==='string'){const p=Number(raw),len=Number(dv.getBigInt64(p,true));"
            + "  return new TextDecoder().decode(new Uint8Array(ex.memory.buffer,p+8,len));}"
            + " if(kind==='list'){let p=Number(raw);const out=[];"
            + "  while(p!==0){out.push(Number(dv.getBigInt64(p,true)).toString());p=Number(dv.getBigInt64(p+8,true));}"
            + "  return '['+out.join(',')+']';}"
            + " return raw.toString();}"
            + "const res=kinds.map((k,i)=>decode(k,ex['f'+i]()));"
            + "process.stdout.write(res.join('\\u0001'));"
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
    return out.split("\u0001", -1);
  }

  @Test
  void marshalsAListResultBackToTheHost() throws Exception {
    assumeTrue(NODE, "node not available");
    // Host marshalling: decode a wasm cons-list result (0 = [], else a {head, tail} cell) into a
    // JS array. Proves results beyond plain numbers can cross the boundary.
    assertEquals("[1,4,9]", decodeList("main = List.map (\\x -> x * x) (List.range 1 3)\n"));
  }

  /** Runs `main` (a cons-list), walking the heap from the host to recover the elements as JSON. */
  private String decodeList(String source) throws Exception {
    Path wasm = Files.createTempFile("elm-list-", ".wasm");
    Files.write(wasm, WasmCompiler.moduleFromSource(source));
    Path js = Files.createTempFile("elm-runlist-", ".js");
    Files.writeString(
        js,
        "const fs=require('fs');"
            + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
            + "const ex=r.instance.exports; const dv=new DataView(ex.memory.buffer);"
            + "let p=Number(ex.main()); const out=[];"
            + "while(p!==0){ out.push(Number(dv.getBigInt64(p,true))); p=Number(dv.getBigInt64(p+8,true)); }"
            + "process.stdout.write(JSON.stringify(out));"
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
  void polymorphicRecordAccessAcrossShapes() throws Exception {
    // `get r = r.x` is row-polymorphic: it knows only the `x` field, yet works on records of
    // different shapes because access looks the field up by name at runtime.
    agrees(
        """
        getX r = r.x
        main = getX { x = 7, y = 99 } + getX { x = 3, name = 5, z = 2 }
        """);
  }

  @Test
  void polymorphicRecordUpdate() throws Exception {
    agrees(
        """
        getX r = r.x
        setX r v = { r | x = v }
        main = getX (setX { x = 1, y = 2 } 100)
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
  void tailRecursionRunsAtGreatDepth() throws Exception {
    // A direct tail call compiles to return_call, reusing the frame — so a million-deep loop runs
    // instead of overflowing the wasm call stack.
    agrees(
        """
        down n = if n == 0 then 0 else down (n - 1)
        main = down 1000000
        """);
    agrees(
        """
        sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)
        main = sumTo 1000000 0
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
  void partialApplicationOfANamedFunction() throws Exception {
    // `add 1` is a partial application (a closure); applying it later completes the call.
    agrees(
        """
        add a b = a + b
        inc = add 1
        main = inc 41
        """);
  }

  @Test
  void closureCapturesALocal() throws Exception {
    // The returned lambda captures the parameter x.
    agrees(
        """
        adder x = \\y -> x + y
        main = (adder 10) 5
        """);
  }

  @Test
  void closureCapturedThroughALet() throws Exception {
    agrees(
        """
        makeAdder n = \\x -> n + x
        main =
            let
                add5 = makeAdder 5
            in
            add5 100
        """);
  }

  @Test
  void higherOrderWithALambdaArgument() throws Exception {
    agrees(
        """
        twiceApply f x = f (f x)
        main = twiceApply (\\n -> n + 1) 5
        """);
  }

  @Test
  void curriedHelperCapturesAndAccumulates() throws Exception {
    // applyTo holds an arg and applies a function to it; combined with a capturing lambda.
    agrees(
        """
        applyTo x f = f x
        main =
            let
                base = 100
                bump = \\n -> n + base
            in
            applyTo 7 bump
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

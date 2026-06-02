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
  void nestedTuplePatterns() throws Exception {
    // Nested tuple destructuring in both `case` and `let`, to any depth.
    agrees("f p =\n    case p of\n        ( ( x, y ), z ) -> x + y + z\nmain = f ( ( 1, 2 ), 3 )\n"); // 6
    agrees("main =\n    let\n        ( ( x, y ), z ) = ( ( 4, 5 ), 6 )\n    in\n    x + y + z\n"); // 15
    agrees("g p =\n    case p of\n        ( a, ( b, ( c, d ) ) ) -> a + b + c + d\nmain = g ( 1, ( 2, ( 3, 4 ) ) )\n"); // 10
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
  void argumentCarryingCustomTypes() throws Exception {
    // A boxed custom type: a shared base struct {tag} with a subtype per constructor; `case`
    // dispatches on the tag and ref.casts to read the arguments.
    agrees(
        """
        type Shape = Circle Int | Rect Int Int
        area s = case s of
            Circle r -> 3 * r * r
            Rect w h -> w * h
        main = area (Circle 10) + area (Rect 3 4)
        """); // 300 + 12 = 312
  }

  @Test
  void recursiveCustomType() throws Exception {
    // A recursive boxed type: Node's fields reference the shared base (Tree = ref to the base).
    agrees(
        """
        type Tree = Leaf | Node Int Tree Tree
        sum t = case t of
            Leaf -> 0
            Node n l r -> n + sum l + sum r
        main = sum (Node 1 (Node 2 Leaf Leaf) (Node 3 Leaf Leaf))
        """); // 1 + 2 + 3 = 6
  }

  @Test
  void polymorphicCustomTypeAtInt() throws Exception {
    // A polymorphic union monomorphised to its single instantiation (here `Box Int`): the type
    // variable `a` resolves to Int from the use site, so Wrap's field is laid out as an i64.
    agrees(
        """
        type Box a = Wrap a | Empty
        unwrap b = case b of
            Wrap x -> x
            Empty -> 0
        main = unwrap (Wrap 42) + unwrap Empty
        """); // 42 + 0 = 42
  }

  @Test
  void polymorphicCustomTypeAtStringPayload() throws Exception {
    // The same union instantiated at String: Wrap's field becomes a string-array reference, and the
    // bound variable is read back as that reference (String.length over it).
    agrees(
        """
        type Box a = Wrap a | Empty
        size b = case b of
            Wrap s -> String.length s
            Empty -> 0
        main = size (Wrap "hello")
        """); // 5
  }

  @Test
  void polymorphicCustomTypeAtFloat() throws Exception {
    // Instantiated at Float: Wrap's field is an f64.
    agrees(
        """
        type Opt a = Some a | None
        get o = case o of
            Some x -> x
            None -> 0.0
        main = get (Some 2.5) + get None
        """); // 2.5
  }

  @Test
  void polymorphicCustomTypeCarryingAList() throws Exception {
    // The payload itself is compound (`List Int`): Wrap's field is the cons-list reference.
    agrees(
        """
        type Box a = Wrap a | Empty
        total b = case b of
            Wrap xs -> sum xs
            Empty -> 0
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        main = total (Wrap [ 1, 2, 3, 4 ])
        """); // 10
  }

  @Test
  void polymorphicUnionAtTwoRepresentationsIsRejected() {
    // No single wasm layout exists for `Wrap` used at both Int and String in one module — reported
    // as unsupported rather than miscompiled.
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class,
        () ->
            WasmGc.module(
                """
                type Box a = Wrap a | Empty
                asInt b = case b of
                    Wrap x -> x
                    Empty -> 0
                asLen b = case b of
                    Wrap s -> String.length s
                    Empty -> 0
                main = asInt (Wrap 1) + asLen (Wrap "x")
                """));
  }

  @Test
  void builtinMaybeCompiles() throws Exception {
    // Maybe is predeclared as a boxed union when Just/Nothing are used; monomorphised to Maybe Int.
    agrees(
        """
        toMaybe b = if b then Just 42 else Nothing
        unwrap m = case m of
            Just x -> x
            Nothing -> 0
        main = unwrap (toMaybe True) + unwrap (toMaybe False)
        """); // 42 + 0 = 42
  }

  @Test
  void builtinResultCompiles() throws Exception {
    agrees(
        """
        attempt ok = if ok then Ok 15 else Err 7
        value r = case r of
            Ok x -> x
            Err e -> e
        main = value (attempt True) + value (attempt False)
        """); // 15 + 7 = 22
  }

  @Test
  void builtinMaybeAtStringPayload() throws Exception {
    // Maybe instantiated at String: Just's field is a string-array reference.
    agrees(
        """
        pick b = if b then Just "hello" else Nothing
        size m = case m of
            Just s -> String.length s
            Nothing -> 0
        main = size (pick True)
        """); // 5
  }

  @Test
  void higherOrderTopLevelFunction() throws Exception {
    // A top-level unary function passed as a value (ref.func) and applied via call_ref.
    agrees("inc n = n + 1\napply f x = f x\nmain = apply inc 5\n"); // 6
    agrees("inc n = n + 1\ntwice f x = f (f x)\nmain = twice inc 5\n"); // 7
    agrees("dbl n = n * 2\napplyTo7 f = f 7\nmain = applyTo7 dbl\n"); // 14
  }

  @Test
  void closedLambdasAsValues() throws Exception {
    // A capture-free lambda is lifted to a top-level function and passed/applied like a named one.
    agrees("apply f x = f x\nmain = apply (\\n -> n * 2) 21\n"); // 42
    agrees("apply f x = f x\nmain = apply (\\n -> n + 1) 5\n"); // 6
    agrees("twice f x = f (f x)\nmain = twice (\\n -> n + 3) 1\n"); // 7
    // A lambda referring to a top-level function is still capture-free (the name is global).
    agrees("inc n = n + 1\napply f x = f x\nmain = apply (\\n -> inc (inc n)) 5\n"); // 7
  }

  @Test
  void multiArgClosuresAndCurrying() throws Exception {
    // A multi-argument function value applied to all its arguments (peeled one at a time).
    agrees("apply2 f x y = f x y\nmain = apply2 (\\a b -> a + b) 3 4\n"); // 7
    // A multi-argument lambda applied directly.
    agrees("main = (\\a b -> a + b) 3 4\n"); // 7
    // A higher-order function whose function parameter takes two arguments.
    agrees(
        """
        zipWith f xs ys = case xs of
            [] -> []
            xh :: xt -> case ys of
                [] -> []
                yh :: yt -> f xh yh :: zipWith f xt yt
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        main = sum (zipWith (\\a b -> a * b) [1, 2, 3] [4, 5, 6])
        """); // 4+10+18 = 32
    // A returned (curried) closure applied later.
    agrees("adder a = \\b -> a + b\nmain = (adder 10) 5\n"); // 15
  }

  @Test
  void namedMultiArgFunctionsAsValuesAndPartials() throws Exception {
    // A named two-argument function passed by name as a value (no lambda wrapper).
    agrees("add a b = a + b\napply2 f x y = f x y\nmain = apply2 add 3 4\n"); // 7
    // A named function partially applied to one argument, then completed later.
    agrees("add a b = a + b\napply f x = f x\nmain = apply (add 10) 5\n"); // 15
    // A partial application as a higher-order argument over a list (the classic `map (add 1)`).
    agrees(
        """
        map f xs = case xs of
            [] -> []
            h :: t -> f h :: map f t
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        add a b = a + b
        main = sum (map (add 10) [1, 2, 3])
        """); // 11+12+13 = 36
    // A named three-argument function partially applied to two arguments.
    agrees(
        """
        clamp3 lo hi x = if x < lo then lo else if x > hi then hi else x
        apply f x = f x
        main = apply (clamp3 0 5) 9
        """); // 5
  }

  @Test
  void destructuringParameters() throws Exception {
    // A tuple-destructuring function parameter (the body is wrapped in a tuple case).
    agrees("addPair ( a, b ) = a + b\nmain = addPair ( 3, 4 )\n"); // 7
    // A constructor-destructuring parameter.
    agrees(
        """
        type Box = Box Int
        unbox (Box n) = n
        main = unbox (Box 9)
        """); // 9
  }

  @Test
  void matchesOnIntegerLiterals() throws Exception {
    String classify =
        """
        classify n =
            case n of
                0 -> 100
                1 -> 200
                _ -> n
        main = classify (%d)
        """;
    agrees(classify.formatted(0)); // 100
    agrees(classify.formatted(1)); // 200
    agrees(classify.formatted(9)); // 9
    agrees("f n = case n of\n    0 -> 0\n    k -> k * k\nmain = f 6\n"); // 36
  }

  @Test
  void listAppendOperator() throws Exception {
    // `++` on lists reverses the left spine then conses it onto the (shared) right list.
    String sum =
        """
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        main = sum (%s)
        """;
    agrees(sum.formatted("[1, 2, 3] ++ [4, 5]")); // 15
    agrees(sum.formatted("[] ++ [4, 5, 6]")); // 15
    agrees(sum.formatted("[1, 2, 3] ++ []")); // 6
    agrees(sum.formatted("([1, 2] ++ [3, 4]) ++ [5]")); // 15
  }

  @Test
  void capturingLambdasAsClosures() throws Exception {
    // A lambda that closes over an enclosing parameter is lifted to a function taking its captures
    // as struct fields; the closure value carries them and the body reads them via struct.get.
    agrees("apply f x = f x\nadder a = \\n -> n + a\nmain = apply (adder 10) 5\n"); // 15
    agrees("apply f x = f x\nmake a b = \\n -> n + a + b\nmain = apply (make 3 4) 5\n"); // 12
    // The capture itself is a let-bound local, not a parameter.
    agrees(
        """
        apply f x = f x
        main =
            let k = 100
            in apply (\\n -> n + k) 7
        """); // 107
    // A top-level higher-order function applied to a capturing closure over a list element.
    agrees(
        """
        map f xs = case xs of
            [] -> []
            h :: t -> f h :: map f t
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        scaleAll k xs = map (\\x -> x * k) xs
        main = sum (scaleAll 3 [1, 2, 3])
        """); // 3+6+9 = 18
  }

  @Test
  void userDefinedHigherOrderOverLists() throws Exception {
    // A user-defined map/filter/foldr with a function parameter (call_ref) and a lambda argument —
    // higher-order code over cons-lists on the GC heap, end to end.
    agrees(
        """
        map f xs = case xs of
            [] -> []
            h :: t -> f h :: map f t
        range lo hi = if lo > hi then [] else lo :: range (lo + 1) hi
        sum xs = case xs of
            [] -> 0
            h :: t -> h + sum t
        main = sum (map (\\x -> x * 2) (range 1 3))
        """); // 2+4+6 = 12
    agrees(
        """
        filter pred xs = case xs of
            [] -> []
            h :: t -> if pred h then h :: filter pred t else filter pred t
        range lo hi = if lo > hi then [] else lo :: range (lo + 1) hi
        len xs = case xs of
            [] -> 0
            h :: t -> 1 + len t
        main = len (filter (\\x -> x > 2) (range 1 6))
        """); // [3,4,5,6] -> 4
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
  void nestedLetShadowingIsScoped() throws Exception {
    // Regression (found by the differential property test): a shadowing inner `let x` must not leak
    // its local out to the enclosing `x`.
    agrees("main = let x = 9 * (let x = (let x = 2 in x + x) in x + x) in x + x\n"); // 144
    agrees("main = let x = 1 in (let x = 2 in x) + x\n"); // 3
  }

  @Test
  void nameSectionCarriesTypeAndFieldNames() {
    // The "name" section names the GC struct types and their fields (a custom section engines ignore,
    // so it never affects execution) — disassembly shows $tuple/$record and item0/alpha instead of
    // bare indices. We inspect the emitted bytes directly (no Node needed).
    byte[] bytes =
        WasmGc.module(
            "main = let t = ( 1, 2 ) in let r = { alpha = 3, beta = 4 } in Tuple.first t + r.alpha\n");
    String blob = new String(bytes, StandardCharsets.ISO_8859_1);
    assertTrue(blob.contains("name"), "has a name custom section");
    // Field names of the tuple and record structs.
    for (String field : new String[] {"item0", "item1", "alpha", "beta"}) {
      assertTrue(blob.contains(field), "field name present: " + field);
    }
    // Type names for each struct kind.
    for (String type : new String[] {"tuple$", "record$"}) {
      assertTrue(blob.contains(type), "type name present: " + type);
    }
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
    // Code-point/UTF-16-aware length (matches the interpreter), not raw UTF-8 byte length:
    agrees("main = String.length \"héllo\"\n"); // é is 2 bytes but 1 unit -> 5
    agrees("main = String.length \"éèê\"\n"); // 3 accented letters -> 3
    agrees("main = String.length \"café 世界\"\n"); // "café 世界" -> 7
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

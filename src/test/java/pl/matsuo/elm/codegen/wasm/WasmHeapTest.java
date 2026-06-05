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
class WasmHeapTest extends WasmHeapTestSupport {

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

  @Test
  void marshalsAListResultBackToTheHost() throws Exception {
    assumeTrue(NODE, "node not available");
    // Host marshalling: decode a wasm cons-list result (0 = [], else a {head, tail} cell) into a
    // JS array. Proves results beyond plain numbers can cross the boundary.
    assertEquals("[1,4,9]", decodeList("main = List.map (\\x -> x * x) (List.range 1 3)\n"));
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
  void resultToMaybeMapErrorFromMaybeCompile() throws Exception {
    agrees("main = Maybe.withDefault 0 (Result.toMaybe (Ok 5))\n"); // 5
    agrees("main = Maybe.withDefault 0 (Result.toMaybe (Err 9))\n"); // 0 (Err -> Nothing)
    agrees("main = Result.withDefault 0 (Result.mapError (\\e -> e + 1) (Ok 5))\n"); // 5
    agrees("main = Result.withDefault 0 (Result.fromMaybe 7 (Just 3))\n"); // 3
    agrees("main = Result.withDefault 7 (Result.fromMaybe 7 Nothing)\n"); // 7
  }

  @Test
  void maybeAndResultCombinatorsCompile() throws Exception {
    agrees("main = Maybe.withDefault 0 (Maybe.map2 (\\a b -> a + b) (Just 3) (Just 4))\n"); // 7
    agrees("main = Maybe.withDefault 0 (Maybe.map2 (\\a b -> a + b) (Just 3) Nothing)\n"); // 0
    agrees("main = Maybe.withDefault 0 (Maybe.map3 (\\a b c -> a + b + c) (Just 1) (Just 2) (Just 3))\n"); // 6
    agrees("main = Maybe.withDefault 0 (Maybe.map4 (\\a b c d -> a + b + c + d) (Just 1) (Just 2) (Just 3) (Just 4))\n"); // 10
    agrees("main = Maybe.withDefault 0 (Maybe.map5 (\\a b c d e -> a) (Just 1) (Just 2) (Just 3) (Just 4) Nothing)\n"); // 0
    agrees("main = Result.withDefault 0 (Result.andThen (\\x -> Ok (x + 1)) (Ok 41))\n"); // 42
    agrees("main = Result.withDefault 0 (Result.map2 (\\a b -> a * b) (Ok 6) (Ok 7))\n"); // 42
  }

  @Test
  void listMap4Compiles() throws Exception {
    agrees("main = List.sum (List.map4 (\\a b c d -> a + b + c + d) [ 1, 2 ] [ 10, 20 ] [ 100, 200 ] [ 0, 0 ])\n"); // 111 + 222 = 333
  }

  @Test
  void listSingletonCompiles() throws Exception {
    assertEquals("[5]", decodeList("main = List.singleton 5\n"));
    assertEquals("[1,5]", decodeList("main = 1 :: List.singleton 5\n"));
  }

  @Test
  void tupleFirstSecondCompile() throws Exception {
    agrees("main = Tuple.first ( 7, 9 )\n"); // 7
    agrees("main = Tuple.second ( 7, 9 )\n"); // 9
    agrees("main = Tuple.first ( 7, 9 ) + Tuple.second ( 7, 9 )\n"); // 16
    // Tuple.mapFirst / mapSecond / mapBoth.
    agrees("main = Tuple.first (Tuple.mapFirst (\\x -> x + 1) ( 7, 9 ))\n"); // 8
    agrees("main = Tuple.second (Tuple.mapSecond (\\y -> y * 2) ( 7, 9 ))\n"); // 18
    agrees("main = Tuple.first (Tuple.mapBoth (\\x -> x + 1) (\\y -> y * 2) ( 7, 9 ))\n"); // 8
    agrees("main = Tuple.second (Tuple.mapBoth (\\x -> x + 1) (\\y -> y * 2) ( 7, 9 ))\n"); // 18
    agrees("main = Tuple.first (Tuple.pair 7 9)\n"); // 7
    agrees("main = if Char.isControl (Char.fromCode 9) then 1 else 0\n"); // 1 (tab)
    agrees("main = if Char.isControl (Char.fromCode 65) then 1 else 0\n"); // 0 ('A')
  }

  @Test
  void stringUnconsCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals(
        "h", runMainString("main = String.fromChar (Tuple.first (Maybe.withDefault ( ' ', \"\" ) (String.uncons \"hi\")))\n"));
    assertEquals(
        "i", runMainString("main = Tuple.second (Maybe.withDefault ( ' ', \"\" ) (String.uncons \"hi\"))\n"));
    assertEquals(
        " ", runMainString("main = String.fromChar (Tuple.first (Maybe.withDefault ( ' ', \"\" ) (String.uncons \"\")))\n")); // empty -> default
  }

  @Test
  void listMap5Compiles() throws Exception {
    agrees("main = List.sum (List.map5 (\\a b c d e -> a + b + c + d + e) [ 1 ] [ 2 ] [ 3 ] [ 4 ] [ 5 ])\n"); // 15
  }

  @Test
  void listUnzipCompiles() throws Exception {
    // unzip [(1,4),(2,5),(3,6)] -> ([1,2,3],[4,5,6]); encode as sum(firsts)*100 + sum(seconds).
    agrees("main =\n    case List.unzip [ ( 1, 4 ), ( 2, 5 ), ( 3, 6 ) ] of\n        ( xs, ys ) -> List.sum xs * 100 + List.sum ys\n"); // 615
  }

  @Test
  void listPartitionCompiles() throws Exception {
    // partition splits [1,2,3] by (> 1) into ([2,3], [1]); encode the two lengths as a*10 + b.
    agrees("main =\n    case List.partition (\\x -> x > 1) [ 1, 2, 3 ] of\n        ( a, b ) -> List.length a * 10 + List.length b\n"); // 21
  }

  @Test
  void listIntersperseCompiles() throws Exception {
    assertEquals("[1,0,2,0,3]", decodeList("main = List.intersperse 0 [ 1, 2, 3 ]\n"));
    assertEquals("[]", decodeList("main = List.intersperse 0 []\n"));
    assertEquals("[7]", decodeList("main = List.intersperse 0 [ 7 ]\n"));
  }

  @Test
  void maxMinClampCompile() throws Exception {
    agrees("main = max 3 5\n"); // 5
    agrees("main = min 3 5\n"); // 3
    agrees("main = clamp 0 10 15\n"); // 10
    agrees("main = clamp 0 10 (0 - 4)\n"); // 0
    agrees("main = max (min 7 4) 2\n"); // 4
  }

  @Test
  void singleAllocationLargerThanOnePageGrowsEnoughPages() throws Exception {
    assumeTrue(NODE, "node not available");
    // A string literal whose single allocation (~140 KB) overruns the current 1-page heap by more
    // than a page: growing only one page would still leave $hp past capacity and trap. The allocator
    // must grow as many pages as the deficit needs.
    String big = "a".repeat(140000);
    assertEquals(140000, Integer.parseInt(runMain("main = String.length \"" + big + "\"\n")));
  }

  @Test
  void stringLeftProducesThePrefixBytes() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("hel", runMainString("main = String.left 3 \"hello\"\n"));
    assertEquals("hello", runMainString("main = String.left 10 \"hello\"\n")); // n > length clamps
    assertEquals("", runMainString("main = String.left 0 \"hello\"\n"));
    assertEquals("", runMainString("main = String.left (0 - 2) \"hello\"\n")); // negative clamps to 0
    agrees("main = String.length (String.left 3 \"hello\")\n"); // 3, matches the interpreter
  }

  @Test
  void stringDropLeftRightDropRightProduceTheRightBytes() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("lo", runMainString("main = String.dropLeft 3 \"hello\"\n"));
    assertEquals("", runMainString("main = String.dropLeft 9 \"hello\"\n")); // drop > length
    assertEquals("llo", runMainString("main = String.right 3 \"hello\"\n"));
    assertEquals("hello", runMainString("main = String.right 9 \"hello\"\n")); // right > length
    assertEquals("he", runMainString("main = String.dropRight 3 \"hello\"\n"));
    assertEquals("", runMainString("main = String.dropRight 9 \"hello\"\n"));
  }

  @Test
  void charOperationsCompile() throws Exception {
    agrees("main = Char.toCode 'A'\n"); // 65
    agrees("main = if Char.isDigit '7' then 1 else 0\n"); // 1
    agrees("main = if Char.isDigit 'a' then 1 else 0\n"); // 0
    agrees("main = if Char.isUpper 'A' then 1 else 0\n"); // 1
    agrees("main = if Char.isAlpha 'z' then 1 else 0\n"); // 1
    agrees("main = if Char.isAlphaNum '5' then 1 else 0\n"); // 1
    agrees("main = Char.toCode (Char.fromCode 97)\n"); // 97
    agrees("main = Char.toCode (Char.toUpper 'a')\n"); // 65
    agrees("main = Char.toCode (Char.toLower 'A')\n"); // 97
    agrees("main = Char.toCode (Char.toUpper '5')\n"); // 53 (unchanged)
    agrees("main = if Char.isSpace ' ' then 1 else 0\n"); // 1
    agrees("main = if Char.isOctDigit '7' then 1 else 0\n"); // 1
    agrees("main = if Char.isOctDigit '8' then 1 else 0\n"); // 0
    agrees("main = if Char.isHexDigit 'f' then 1 else 0\n"); // 1
    agrees("main = if Char.isHexDigit 'F' then 1 else 0\n"); // 1
    agrees("main = if Char.isHexDigit 'g' then 1 else 0\n"); // 0
  }

  @Test
  void stringFromCharAndConsCompile() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("a", runMainString("main = String.fromChar 'a'\n"));
    assertEquals("xhi", runMainString("main = String.cons 'x' \"hi\"\n"));
    agrees("main = String.length (String.cons 'a' \"bc\")\n"); // 3
  }

  @Test
  void stringToUpperToLowerCaseFoldAscii() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("HELLO", runMainString("main = String.toUpper \"Hello\"\n"));
    assertEquals("hello", runMainString("main = String.toLower \"Hello\"\n"));
    assertEquals("ABC123", runMainString("main = String.toUpper \"abc123\"\n")); // digits unchanged
    assertEquals("a-b-c", runMainString("main = String.toLower \"A-B-C\"\n")); // punctuation unchanged
  }

  @Test
  void stringReverseProducesReversedBytes() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("olleh", runMainString("main = String.reverse \"hello\"\n"));
    assertEquals("", runMainString("main = String.reverse \"\"\n"));
    assertEquals("a", runMainString("main = String.reverse \"a\"\n"));
    assertEquals("hello", runMainString("main = String.reverse (String.reverse \"hello\")\n"));
  }

  @Test
  void stringPadCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("007", runMainString("main = String.padLeft 3 '0' \"7\"\n"));
    assertEquals("7  ", runMainString("main = String.padRight 3 ' ' \"7\"\n"));
    assertEquals("hello", runMainString("main = String.padLeft 3 '0' \"hello\"\n")); // already long enough
  }

  @Test
  void stringTrimCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("hi", runMainString("main = String.trim \"  hi  \"\n"));
    assertEquals("hi  ", runMainString("main = String.trimLeft \"  hi  \"\n"));
    assertEquals("  hi", runMainString("main = String.trimRight \"  hi  \"\n"));
    assertEquals("", runMainString("main = String.trim \"   \"\n"));
    assertEquals("a b", runMainString("main = String.trim \" a b \"\n")); // inner spaces kept
  }

  @Test
  void stringFromListCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("hi", runMainString("main = String.fromList [ 'h', 'i' ]\n"));
    assertEquals("", runMainString("main = String.fromList []\n"));
    assertEquals("abc", runMainString("main = String.fromList [ 'a', 'b', 'c' ]\n"));
  }

  @Test
  void stringToListCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    // Round-trip through the char-code list exercises $strToList and String.fromList together.
    assertEquals("abc", runMainString("main = String.fromList (String.toList \"abc\")\n"));
    assertEquals("", runMainString("main = String.fromList (String.toList \"\")\n"));
    // toList feeds List functions: reverse the chars, then rebuild.
    assertEquals(
        "olleh", runMainString("main = String.fromList (List.reverse (String.toList \"hello\"))\n"));
    assertEquals("5", runMainString("main = String.fromInt (List.length (String.toList \"hello\"))\n"));
  }

  @Test
  void stringFoldMapFilterCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    // map over chars (uppercase via Char.toUpper)
    assertEquals("HELLO", runMainString("main = String.map Char.toUpper \"hello\"\n"));
    // filter chars (keep letters from a mixed string)
    assertEquals("abc", runMainString("main = String.filter Char.isAlpha \"a1b2c3\"\n"));
    // foldl building a reversed copy by consing
    assertEquals("cba", runMainString("main = String.foldl String.cons \"\" \"abc\"\n"));
    // foldr building a forward copy by consing
    assertEquals("abc", runMainString("main = String.foldr String.cons \"\" \"abc\"\n"));
    // any / all over chars, surfaced as strings
    assertEquals(
        "yn",
        runMainString(
            "yn b = if b then \"y\" else \"n\"\n"
                + "main = yn (String.any Char.isDigit \"a1b\") ++ yn (String.all Char.isDigit \"a1b\")\n"));
  }

  @Test
  void stringToIntCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    String wrap = "main = String.fromInt (Maybe.withDefault -1 (String.toInt ";
    assertEquals("123", runMainString(wrap + "\"123\"))\n"));
    assertEquals("-42", runMainString(wrap + "\"-42\"))\n"));
    assertEquals("0", runMainString(wrap + "\"0\"))\n"));
    assertEquals("-1", runMainString(wrap + "\"abc\"))\n")); // not a number -> Nothing
    assertEquals("-1", runMainString(wrap + "\"\"))\n")); // empty -> Nothing
    assertEquals("-1", runMainString(wrap + "\"12x\"))\n")); // trailing junk -> Nothing
  }

  @Test
  void stringReplaceCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("a_b_c", runMainString("main = String.replace \",\" \"_\" \"a,b,c\"\n"));
    assertEquals("hello", runMainString("main = String.replace \"x\" \"y\" \"hello\"\n")); // no match
    assertEquals("heLLo", runMainString("main = String.replace \"l\" \"L\" \"hello\"\n")); // all occurrences
  }

  @Test
  void stringLinesCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    agrees("main = List.length (String.lines \"a\\nb\\nc\")\n"); // 3
    assertEquals("a|b|c", runMainString("main = String.join \"|\" (String.lines \"a\\nb\\nc\")\n"));
  }

  @Test
  void stringSplitCompiles() throws Exception {
    assumeTrue(NODE, "node not available");
    agrees("main = List.length (String.split \",\" \"a,b,c\")\n"); // 3
    assertEquals("a-b-c", runMainString("main = String.join \"-\" (String.split \",\" \"a,b,c\")\n"));
    assertEquals("hello", runMainString("main = String.join \"-\" (String.split \",\" \"hello\")\n")); // no sep -> single piece
    assertEquals("a|b", runMainString("main = String.join \"|\" (String.split \"::\" \"a::b\")\n")); // multi-char sep
  }

  @Test
  void stringIndexesCompiles() throws Exception {
    assertEquals("[2,3]", decodeList("main = String.indexes \"l\" \"hello\"\n"));
    assertEquals("[2]", decodeList("main = String.indexes \"ll\" \"hello\"\n"));
    assertEquals("[]", decodeList("main = String.indexes \"x\" \"hello\"\n"));
    assertEquals("[0,2,4]", decodeList("main = String.indexes \"ab\" \"ababab\"\n"));
  }

  @Test
  void stringContainsCompiles() throws Exception {
    agrees("main = if String.contains \"ll\" \"hello\" then 1 else 0\n"); // 1
    agrees("main = if String.contains \"x\" \"hello\" then 1 else 0\n"); // 0
    agrees("main = if String.contains \"hello\" \"hello\" then 1 else 0\n"); // 1 (whole)
    agrees("main = if String.contains \"\" \"hello\" then 1 else 0\n"); // 1 (empty)
    agrees("main = if String.contains \"lo\" \"hello\" then 1 else 0\n"); // 1 (suffix)
  }

  @Test
  void stringStartsWithEndsWithCompile() throws Exception {
    agrees("main = if String.startsWith \"he\" \"hello\" then 1 else 0\n"); // 1
    agrees("main = if String.startsWith \"x\" \"hello\" then 1 else 0\n"); // 0
    agrees("main = if String.endsWith \"lo\" \"hello\" then 1 else 0\n"); // 1
    agrees("main = if String.endsWith \"xx\" \"hello\" then 1 else 0\n"); // 0
  }

  @Test
  void stringSliceHandlesNegativeIndices() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("el", runMainString("main = String.slice 1 3 \"hello\"\n"));
    assertEquals("hell", runMainString("main = String.slice 0 (0 - 1) \"hello\"\n")); // end = -1
    assertEquals("lo", runMainString("main = String.slice (0 - 2) 5 \"hello\"\n")); // start = -2
    assertEquals("", runMainString("main = String.slice 3 1 \"hello\"\n")); // end before start
  }

  @Test
  void stringConcatJoinRepeatProduceTheRightBytes() throws Exception {
    assumeTrue(NODE, "node not available");
    assertEquals("ababab", runMainString("main = String.repeat 3 \"ab\"\n"));
    assertEquals("", runMainString("main = String.repeat 0 \"ab\"\n"));
    assertEquals("abc", runMainString("main = String.concat [ \"a\", \"b\", \"c\" ]\n"));
    assertEquals("", runMainString("main = String.concat []\n"));
    assertEquals("a, b, c", runMainString("main = String.join \", \" [ \"a\", \"b\", \"c\" ]\n"));
    assertEquals("solo", runMainString("main = String.join \"-\" [ \"solo\" ]\n"));
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


}

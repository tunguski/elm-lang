package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmRuntimeError;

class InterpreterTest {

  private Object eval(String src) {
    return Interpreter.eval(src);
  }

  private String show(String src) {
    return Show.plain(Interpreter.eval(src));
  }

  // --- arithmetic & operators -------------------------------------------

  @Test
  void arithmetic() {
    assertEquals(7L, eval("1 + 2 * 3"));
    assertEquals(3.5, eval("7 / 2"));
    assertEquals(3L, eval("7 // 2"));
    assertEquals(8L, eval("2 ^ 3"));
    assertEquals(1L, eval("modBy 3 7"));
    assertEquals(-5L, eval("-5"));
    assertEquals(-5L, eval("negate 5"));
  }

  @Test
  void negativeIntegerPatterns() {
    assertEquals(100L, eval("case -1 of\n  -1 -> 100\n  _ -> 0"));
    assertEquals(0L, eval("case 5 of\n  -1 -> 100\n  _ -> 0"));
    assertEquals(22L, eval("case -2 of\n  -2 -> 22\n  _ -> 0"));
    // also valid as a function parameter / let scrutinee
    assertEquals(1L, eval("(\\n -> case n of\n  -1 -> 1\n  _ -> 0) -1"));
    // consecutive negative-pattern branches: the body of one branch must not absorb the next `-`
    assertEquals(7L, eval("case -3 of\n  -1 -> 1\n  -3 -> 7\n  _ -> 0"));
  }

  @Test
  void booleansAndComparison() {
    assertEquals(true, eval("3 < 5 && 2 == 2"));
    assertEquals(false, eval("3 > 5 || False"));
    assertEquals(true, eval("\"abc\" < \"abd\""));
    assertEquals(false, eval("[1,2,3] == [1,2,4]"));
  }

  @Test
  void stringsAndAppend() {
    assertEquals("ab", eval("\"a\" ++ \"b\""));
    assertEquals("42", eval("String.fromInt 42"));
    assertEquals(3L, eval("String.length \"abc\""));
    assertEquals("CBA", eval("String.toUpper (String.reverse \"abc\")"));
  }

  @Test
  void charPredicates() {
    assertEquals(true, eval("Char.isSpace ' '"));
    assertEquals(true, eval("Char.isSpace '\\t'"));
    assertEquals(false, eval("Char.isSpace 'a'"));
    assertEquals(true, eval("Char.isPunctuation '!'"));
    assertEquals(true, eval("Char.isPunctuation ';'"));
    assertEquals(false, eval("Char.isPunctuation 'a'"));
    assertEquals(true, eval("Char.isControl (Char.fromCode 0)"));
    assertEquals(false, eval("Char.isControl 'a'"));
  }

  // --- let-local type declarations --------------------------------------

  @Test
  void letLocalUnionType() {
    assertEquals(
        2L,
        eval(
            """
            let
                type Color = Red | Green | Blue
                toInt c =
                    case c of
                        Red -> 1
                        Green -> 2
                        Blue -> 3
            in
            toInt Green
            """));
  }

  @Test
  void letLocalUnionWithArguments() {
    assertEquals(
        42L,
        eval(
            """
            let
                type Box = Box Int
                unbox b =
                    case b of
                        Box n -> n
            in
            unbox (Box 42)
            """));
  }

  @Test
  void letLocalRecordAlias() {
    // A record type alias declared in a let introduces a constructor function (P x y).
    assertEquals(
        7L,
        eval(
            """
            let
                type alias P = { x : Int, y : Int }
                mk = P 3 4
            in
            mk.x + mk.y
            """));
  }

  // --- collections -------------------------------------------------------

  @Test
  void lists() {
    assertEquals("[1,2,3]", show("[1, 2, 3]"));
    assertEquals("[1,4,9]", show("List.map (\\x -> x * x) [1, 2, 3]"));
    assertEquals(10L, eval("List.foldl (+) 0 [1, 2, 3, 4]"));
    assertEquals("[3,2,1]", show("List.reverse [1, 2, 3]"));
    assertEquals("[2,4]", show("List.filter (\\x -> modBy 2 x == 0) [1, 2, 3, 4]"));
    assertEquals(6L, eval("List.sum (List.range 1 3)"));
    assertEquals("[1,2,3,4]", show("[1, 2] ++ [3, 4]"));
    assertEquals("(1,2,3)", show("(1, 2, 3)"));
  }

  // --- compound expressions ---------------------------------------------

  @Test
  void controlFlow() {
    assertEquals(1L, eval("if True then 1 else 2"));
    assertEquals(6L, eval("let x = 5 in x + 1"));
    assertEquals(42L, eval("(\\x -> x * 2) 21"));
    assertEquals(6L, eval("5 |> (\\x -> x + 1)"));
    assertEquals(7L, eval("((\\x -> x + 1) << (\\x -> x * 2)) 3"));
  }

  @Test
  void maybeAndResult() {
    assertEquals(5L, eval("Maybe.withDefault 0 (Just 5)"));
    assertEquals(0L, eval("Maybe.withDefault 0 Nothing"));
    assertEquals("Just 6", show("Maybe.map (\\x -> x + 1) (Just 5)"));
    assertEquals(9L, eval("Result.withDefault 0 (Ok 9)"));
  }

  @Test
  void records() {
    assertEquals(1L, eval("(\\r -> r.x) { x = 1, y = 2 }"));
    assertEquals(1L, eval("{ x = 1, y = 2 }.x"));
    assertEquals("{ x = 9, y = 2 }", show("(\\r -> { r | x = 9 }) { x = 1, y = 2 }"));
    assertEquals(1L, eval("(.x) { x = 1, y = 2 }"));
  }

  // --- modules -----------------------------------------------------------

  @Test
  void simpleModule() {
    String src =
        """
        module Main exposing (main)

        double : Int -> Int
        double n =
            n * 2

        main =
            double 21
        """;
    assertEquals(42L, Interpreter.load(src).value("main"));
  }

  @Test
  void recursion() {
    String src =
        """
        factorial n =
            if n <= 1 then
                1
            else
                n * factorial (n - 1)

        main = factorial 5
        """;
    assertEquals(120L, Interpreter.load(src).value("main"));
  }

  @Test
  void mutualRecursion() {
    String src =
        """
        isEven n = if n == 0 then True else isOdd (n - 1)
        isOdd n = if n == 0 then False else isEven (n - 1)
        main = isEven 10
        """;
    assertEquals(true, Interpreter.load(src).value("main"));
  }

  @Test
  void patternMatchingInCase() {
    String src =
        """
        describe xs =
            case xs of
                [] ->
                    "empty"

                [ _ ] ->
                    "one"

                _ :: _ ->
                    "many"

        main = describe
        """;
    Interpreter interp = Interpreter.load(src);
    Object describe = interp.value("describe");
    assertEquals("empty", Apply.apply(describe, ElmListOf()));
    assertEquals("one", Apply.apply(describe, ElmListOf(1L)));
    assertEquals("many", Apply.apply(describe, ElmListOf(1L, 2L, 3L)));
  }

  @Test
  void customTypeAndCase() {
    String src =
        """
        type Shape
            = Circle Float
            | Rect Float Float

        area shape =
            case shape of
                Circle r ->
                    pi * r * r

                Rect w h ->
                    w * h

        main = area (Rect 3 4)
        """;
    // Int literals 3 and 4 are inferred as Float (Rect's fields) and coerced -> 12.0, not 12.
    assertEquals(12.0, Interpreter.load(src).value("main"));
  }

  @Test
  void specializedOperators() {
    // Exercises the Truffle DSL arithmetic/comparison nodes incl. derived >, <=, >=, /=.
    assertEquals(7L, eval("3 + 4"));
    assertEquals(2.5, eval("5.0 / 2.0"));
    assertEquals(6.0, eval("2.0 * 3.0"));
    assertEquals(true, eval("5 >= 5"));
    assertEquals(true, eval("4 <= 5"));
    assertEquals(false, eval("5 <= 4"));
    assertEquals(true, eval("5 > 4"));
    assertEquals(true, eval("3 /= 4"));
    assertEquals(false, eval("3 /= 3"));
    assertEquals(true, eval("1.5 < 2.5"));
    assertEquals(true, eval("\"a\" <= \"b\"")); // structural fallback + derived negate/swap
    assertEquals(true, eval("[1,2] < [1,3]"));
  }

  @Test
  void tailCallOptimizationIf() {
    // Without TCO this 1,000,000-deep self-recursion would overflow the JVM stack.
    String src =
        """
        count n acc =
            if n == 0 then acc else count (n - 1) (acc + 1)
        main = count 1000000 0
        """;
    assertEquals(1000000L, Interpreter.load(src).value("main"));
  }

  @Test
  void tailCallOptimizationCase() {
    String src =
        """
        sumTo n acc =
            case n of
                0 -> acc
                _ -> sumTo (n - 1) (acc + n)
        main = sumTo 100000 0
        """;
    assertEquals(5000050000L, Interpreter.load(src).value("main"));
  }

  @Test
  void numericLiteralCoercedToFloatByInference() {
    // Without type-directed coercion, `5` would stay an Int (Long) and the result would be 5, not
    // 5.0. The Box field is Float, so inference resolves the literal to Float.
    String src =
        """
        type alias Box = { w : Float }
        box = Box 5
        main = box.w
        """;
    assertEquals(5.0, Interpreter.load(src).value("main"));
  }

  @Test
  void unboundVariableThrows() {
    assertThrows(ElmRuntimeError.class, () -> eval("nonexistentThing"));
  }

  private static pl.matsuo.elm.runtime.ElmList ElmListOf(Object... items) {
    return pl.matsuo.elm.runtime.ElmList.fromJava(java.util.Arrays.asList(items));
  }
}

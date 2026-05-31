package pl.matsuo.elm.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/** Differential tests: the bytecode VM must produce the same value as the tree interpreter. */
class BytecodeBackendTest {

  private void same(String expression) {
    assertEquals(
        Show.plain(Interpreter.eval(expression)),
        Show.plain(BytecodeInterpreter.eval(expression)),
        "expression: " + expression);
  }

  private void sameModule(String source) {
    assertEquals(
        Show.plain(Interpreter.load(source).value("main")),
        Show.plain(BytecodeInterpreter.load(source).value("main")));
  }

  @Test
  void arithmeticAndOperators() {
    same("1 + 2 * 3");
    same("7 / 2");
    same("2 ^ 10");
    same("7 // 2");
    same("modBy 3 17");
    same("-5 + 3");
  }

  @Test
  void comparisonsBooleansShortCircuit() {
    same("3 < 5 && 2 == 2");
    same("False && (1 // 0 == 0)"); // short-circuit avoids division by zero
    same("True || (1 // 0 == 0)");
    same("compare 3 5");
    same("[1,2,3] == [1,2,3]");
  }

  @Test
  void listsStringsRecords() {
    same("List.map (\\x -> x * x) [1, 2, 3]");
    same("List.foldl (+) 0 [1, 2, 3, 4]");
    same("List.range 1 5 |> List.filter (\\x -> modBy 2 x == 0)");
    same("[1, 2] ++ [3, 4]");
    same("String.toUpper (String.reverse \"abc\")");
    same("{ x = 1, y = 2 }.x");
    same("(\\r -> { r | x = 9 }) { x = 1, y = 2 }");
  }

  @Test
  void controlFlowAndClosures() {
    same("if True then 1 else 2");
    same("let x = 5 in x + 1");
    same("(\\x -> x * 2) 21");
    same("5 |> (\\x -> x + 1)");
    same("((\\x -> x + 1) << (\\x -> x * 2)) 3");
    same("Maybe.map (\\x -> x + 1) (Just 5)");
  }

  @Test
  void modules() {
    sameModule(
        """
        factorial n = if n <= 1 then 1 else n * factorial (n - 1)
        main = factorial 6
        """);
    sameModule(
        """
        isEven n = if n == 0 then True else isOdd (n - 1)
        isOdd n = if n == 0 then False else isEven (n - 1)
        main = isEven 12
        """);
    sameModule(
        """
        type Shape = Circle Float | Rect Float Float
        area shape =
            case shape of
                Circle r -> pi * r * r
                Rect w h -> w * h
        main = area (Rect 3.0 4.0)
        """);
    sameModule(
        """
        describe xs =
            case xs of
                [] -> "empty"
                [ _ ] -> "one"
                first :: _ -> "many starting with " ++ String.fromInt first
        main = describe [7, 8, 9]
        """);
  }

  @Test
  void deepSelfTailRecursionDoesNotOverflow() {
    // Self-tail-calls become a TAIL_CALL loop, so this runs in constant Java stack. Before TCO the
    // bytecode VM kept every call on the stack and overflowed well before a million.
    sameModule("sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)\nmain = sumTo 1000000 0\n");
    // Tail call from inside a `case` and a `let` (the other tail positions) also loops.
    sameModule(
        """
        count n acc =
            case n of
                0 -> acc
                _ -> count (n - 1) (acc + 1)
        main = count 500000 0
        """);
    sameModule(
        """
        go n acc =
            let step = acc + n
            in if n == 0 then acc else go (n - 1) step
        main = go 500000 0
        """);
  }

  @Test
  void tailCallIsNotMisfiredWhenTheNameIsShadowed() {
    // A `let` binding shadowing the function name must NOT be treated as a self-tail-call.
    sameModule(
        """
        f n =
            let f = n + 1
            in f
        main = f 41
        """); // 42, not infinite recursion
  }

  @Test
  void nestedCaseAndLet() {
    sameModule(
        """
        classify n =
            let
                sign =
                    if n < 0 then "neg" else "pos"
            in
            case n of
                0 -> "zero"
                _ -> sign
        main = classify (-4) ++ "," ++ classify 0 ++ "," ++ classify 9
        """);
  }
}

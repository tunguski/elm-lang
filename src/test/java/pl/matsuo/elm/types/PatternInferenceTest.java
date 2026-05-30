package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * Locks in inference (and evaluation) of the less-common pattern forms — as-patterns, cons in
 * argument position, nested constructor patterns and record destructuring — across {@code case},
 * function parameters and lambdas.
 */
class PatternInferenceTest {

  private static String mainType(String source) {
    return TypeChecker.checkModule(source).get("main");
  }

  private static String eval(String source, String value) {
    return Show.plain(Interpreter.load(source).value(value));
  }

  @Test
  void asPatternInArgument() {
    assertEquals("(number, number)", mainType("f ((a, b) as whole) = whole\nmain = f (1, 2)\n"));
    assertEquals("(1,2)", eval("f ((a, b) as whole) = whole\nr = f (1, 2)\n", "r"));
  }

  @Test
  void asPatternBindingBothWholeAndParts() {
    // Both the alias and the destructured part are in scope in the body.
    assertEquals(
        "3",
        eval(
            """
            f point =
                case point of
                    (x, y) as p -> x + y
            r = f (1, 2)
            """,
            "r"));
  }

  @Test
  void consInArgumentPosition() {
    assertEquals("number", mainType("f (x :: xs) = x\nmain = f [ 1, 2, 3 ]\n"));
    assertEquals("10", eval("f (x :: xs) = x\nr = f [ 10, 20 ]\n", "r"));
  }

  @Test
  void nestedConsInCase() {
    assertEquals(
        "7",
        eval(
            """
            f xs =
                case xs of
                    a :: b :: _ -> a + b
                    _ -> 0
            r = f [ 3, 4, 5 ]
            """,
            "r"));
  }

  @Test
  void nestedConstructorInCase() {
    assertEquals(
        "7",
        eval(
            """
            f x =
                case x of
                    Just (Just n) -> n
                    _ -> 0
            r = f (Just (Just 7))
            """,
            "r"));
  }

  @Test
  void recordDestructureInArgument() {
    assertEquals("String", mainType("greet { name } = name\nmain = greet { name = \"a\", age = 1 }\n"));
    assertEquals(
        "3", eval("sum { x, y } = x + y\nr = sum { x = 1, y = 2 }\n", "r"));
  }

  @Test
  void aliasOverConstructorInCase() {
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            f x =
                case x of
                    (Just n) as orig -> n
                    Nothing -> 0
            main = f (Just 1)
            """);
    assertEquals("number", types.get("main"));
  }
}

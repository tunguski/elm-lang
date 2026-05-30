package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmRuntimeError;

/** Runtime errors carry the source location of the offending construct. */
class RuntimePositionTest {

  @Test
  void unboundNameReportsItsLocation() {
    ElmRuntimeError e =
        assertThrows(ElmRuntimeError.class, () -> Interpreter.load("main = boom\n").value("main"));
    assertNotNull(e.position, e.getMessage());
    assertEquals(1, e.position.line());
    assertTrue(e.getMessage().contains("Unbound variable: boom"), e.getMessage());
    assertTrue(e.getMessage().contains("(at 1:"), e.getMessage());
  }

  @Test
  void nonExhaustiveMatchReportsItsLocation() {
    String src =
        """
        f x =
            case x of
                1 -> 1
        main = f 2
        """;
    ElmRuntimeError e =
        assertThrows(ElmRuntimeError.class, () -> Interpreter.load(src).value("main"));
    assertNotNull(e.position, e.getMessage());
    assertEquals(2, e.position.line(), e.getMessage()); // the `case` is on line 2
  }

  private static void assertEquals(int expected, int actual) {
    org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
  }

  private static void assertEquals(int expected, int actual, String msg) {
    org.junit.jupiter.api.Assertions.assertEquals(expected, actual, msg);
  }
}

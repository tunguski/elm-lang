package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

class ModuleCheckTest {

  @Test
  void infersTopLevelTypes() {
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            double n = n * 2
            greeting name = "Hi " ++ name
            main = double 21
            """);
    assertEquals("number -> number", types.get("double"));
    assertEquals("String -> String", types.get("greeting"));
    assertEquals("number", types.get("main"));
  }

  @Test
  void customTypesAndCase() {
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            type Shape = Circle Float | Rect Float Float
            area shape =
                case shape of
                    Circle r -> pi * r * r
                    Rect w h -> w * h
            """);
    // pi isn't in our prelude signatures; area still resolves Float from the multiplications.
    assertEquals("Shape -> Float", types.get("area"));
  }

  @Test
  void recordAliasConstructorAndAccess() {
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            type alias Point = { x : Float, y : Float }
            origin = Point 0.0 0.0
            getX p = p.x
            """);
    assertEquals("{ x : Float, y : Float }", types.get("origin"));
  }

  @Test
  void mutualRecursion() {
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            isEven n = if n == 0 then True else isOdd (n - 1)
            isOdd n = if n == 0 then False else isEven (n - 1)
            """);
    assertEquals("number -> Bool", types.get("isEven"));
  }

  @Test
  void annotationMismatchIsCaught() {
    assertThrows(
        ElmTypeError.class,
        () ->
            TypeChecker.checkModule(
                """
                bad : Int -> String
                bad n = n + 1
                """));
  }

  @Test
  void typeMismatchInBodyIsCaught() {
    assertThrows(
        ElmTypeError.class,
        () -> TypeChecker.checkModule("main = String.length 5\n"));
  }
}

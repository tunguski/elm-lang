package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

/** Multi-module ("project") type checking: names, constructors and aliases cross module boundaries. */
class ProjectCheckTest {

  private static final String LIB =
      """
      module Lib exposing (..)
      type Shape = Circle Float | Square Float
      type alias Point = { x : Float, y : Float }
      area shape =
          case shape of
              Circle r -> 3.14 * r * r
              Square s -> s * s
      origin = Point 0.0 0.0
      """;

  @Test
  void resolvesImportedValuesConstructorsAndAliases() {
    String main =
        """
        module Main exposing (..)
        import Lib exposing (..)
        main = area (Circle 2.0) + origin.x
        shifted = { origin | x = 5.0 }
        """;
    Map<String, String> types = TypeChecker.checkProject(LIB, main);
    assertEquals("Float", types.get("main")); // uses Lib.area, Lib.Circle and Lib.origin
    assertEquals("{ x : Float, y : Float }", types.get("shifted")); // uses Lib.Point alias
  }

  @Test
  void qualifiedImportResolvesAcrossModules() {
    String main =
        """
        module Main exposing (..)
        import Lib
        main = Lib.area (Lib.Square 3.0)
        """;
    assertEquals("Float", TypeChecker.checkProject(LIB, main).get("main"));
  }

  @Test
  void elmPlaygroundGameTypeChecks() throws Exception {
    // The full ~1700-line evancz/elm-playground plus a game type-check end to end — this needs
    // module-level let-generalization (SCC ordering) so shared helpers like `render` stay
    // polymorphic across picture/animation/game.
    String playground = resource("/Playground.elm");
    String mario = resource("/examples/mario.elm");
    Map<String, String> types = TypeChecker.checkProject(playground, mario);
    assertTrue(types.get("main").startsWith("Program"), types.get("main"));
  }

  private static String resource(String path) {
    try (var in = ProjectCheckTest.class.getResourceAsStream(path)) {
      return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void crossModuleTypeMismatchIsCaught() {
    String main =
        """
        module Main exposing (..)
        import Lib exposing (..)
        main = area "not a shape"
        """;
    assertThrows(ElmTypeError.class, () -> TypeChecker.checkProject(LIB, main));
  }
}

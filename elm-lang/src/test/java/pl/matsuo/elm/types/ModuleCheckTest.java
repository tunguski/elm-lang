package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

class ModuleCheckTest {

  private static String example(String slug) throws Exception {
    try (InputStream in = ModuleCheckTest.class.getResourceAsStream("/examples/" + slug + ".elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void buttonsExampleTypeChecks() throws Exception {
    Map<String, String> types = TypeChecker.checkModule(example("buttons"));
    assertTrue(types.get("main").startsWith("Program"), types.get("main"));
    // String.fromInt forces the model to Int (not a polymorphic number).
    assertEquals("Msg -> Int -> Int", types.get("update"));
  }

  @Test
  void textFieldsExampleTypeChecks() throws Exception {
    // Record alias, record update and Browser.sandbox must all unify.
    Map<String, String> types = TypeChecker.checkModule(example("text-fields"));
    assertTrue(types.get("main").startsWith("Program"), types.get("main"));
  }

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

  @Test
  void multipleRecordUpdatesTerminate() {
    // Regression: unifying several open records that update distinct fields of the same value used
    // to route empty rows through fresh `{ | r }` records and loop forever. It must now converge.
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            update msg model =
                case msg of
                    1 -> { model | name = "a" }
                    2 -> { model | password = "b" }
                    _ -> { model | passwordAgain = "c" }
            """);
    assertTrue(types.containsKey("update"), types.toString());
  }

  @Test
  void qualifiedNameWinsOverOpenImport() {
    // Regression: `D.map` must resolve to Json.Decode.map, not the `map` that `Html exposing (..)`
    // brings into scope (which would make the decoder unify with Html and fail).
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            import Html exposing (..)
            import Json.Decode as D
            decode = D.map (\\n -> n + 1) (D.succeed 1)
            """);
    assertEquals("Decoder number", types.get("decode"));
  }

  @Test
  void svgViewUnifiesWithHtml() {
    // `Svg msg` is `Html msg`; an svg-returning helper annotated `Svg msg` must satisfy a
    // `view : model -> Html msg` Browser program.
    Map<String, String> types = checkExample("clock");
    assertTrue(types.get("main").startsWith("Program"), types.get("main"));
  }

  /** Every single-module elm-lang.org example must type-check end to end. */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "hello", "groceries", "shapes", "buttons", "text-fields", "forms", "numbers", "cards",
        "positions", "book", "quotes", "time", "clock", "upload", "drag-and-drop", "image-previews",
        "triangle", "cube", "crate", "thwomp", "first-person"
      })
  void exampleTypeChecks(String slug) throws Exception {
    // No ElmTypeError is thrown, and `main` gets a type (a static `Html msg` for the HTML examples,
    // a `Program ...` for the Browser programs).
    Map<String, String> types = TypeChecker.checkModule(example(slug));
    String main = types.get("main");
    assertTrue(
        main != null && (main.startsWith("Program") || main.startsWith("Html")),
        slug + ": main = " + main);
  }

  private static Map<String, String> checkExample(String slug) {
    try {
      return TypeChecker.checkModule(example(slug));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}

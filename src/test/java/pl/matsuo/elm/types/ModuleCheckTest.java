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
    // With module-level let-generalization, `update` is its own SCC and generalizes to a
    // polymorphic number; the Int specialization happens where `main` ties it to `view`.
    assertEquals("Msg -> number -> number", types.get("update"));
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
  void typeErrorReportsLocationAndHint() {
    ElmTypeError e =
        assertThrows(
            ElmTypeError.class,
            () -> TypeChecker.checkModule("foo = 1\nmain = \"x\" + 1\n"));
    assertEquals(2, e.position.line(), e.getMessage());
    String msg = e.getMessage();
    assertTrue(msg.contains("2 | main = \"x\" + 1"), msg); // source excerpt
    assertTrue(msg.contains("^"), msg); // caret under the offending expression
    assertTrue(msg.contains("Hint:"), msg); // a helpful hint
  }

  @Test
  void reportsEveryIndependentTypeError() {
    // Three unrelated bad definitions: inference must recover after each and report all of them,
    // in source order, rather than stopping at the first.
    pl.matsuo.elm.error.ElmTypeErrors e =
        assertThrows(
            pl.matsuo.elm.error.ElmTypeErrors.class,
            () ->
                TypeChecker.checkModule(
                    """
                    a = "x" + 1
                    b = String.length 5
                    c = 2 + "y"
                    """));
    assertEquals(3, e.errors.size(), e.getMessage());
    assertEquals(1, e.errors.get(0).position.line(), e.getMessage());
    assertEquals(2, e.errors.get(1).position.line(), e.getMessage());
    assertEquals(3, e.errors.get(2).position.line(), e.getMessage());
    assertTrue(e.getMessage().contains("Found 3 type errors:"), e.getMessage());
  }

  @Test
  void recoversFromAFailedDefinitionWithoutCascading() {
    // `bad` fails, but `good` (which does not depend on it) still type-checks, and the failed
    // definition does not produce a spurious second error in its (clean) caller.
    pl.matsuo.elm.error.ElmTypeErrors e =
        assertThrows(
            pl.matsuo.elm.error.ElmTypeErrors.class,
            () ->
                TypeChecker.checkModule(
                    """
                    bad = "x" + 1
                    user = bad ++ "!"
                    other = String.length 5
                    """));
    // Only the two genuinely-wrong definitions are reported; `user` (which uses the recovered
    // `bad`) is not flagged.
    assertEquals(2, e.errors.size(), e.getMessage());
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

  @Test
  void unknownNameSuggestsAClosePrelude() {
    // A misspelled stdlib function gets a "Did you mean …?" suggestion.
    ElmTypeError e =
        assertThrows(
            ElmTypeError.class, () -> TypeChecker.checkModule("main = List.lenght [ 1, 2 ]\n"));
    assertTrue(e.getMessage().contains("Unknown name"), e.getMessage());
    assertTrue(e.getMessage().contains("Did you mean") && e.getMessage().contains("length"), e.getMessage());
  }

  @Test
  void unknownLocalNameSuggestsACloseDefinition() {
    ElmTypeError e =
        assertThrows(
            ElmTypeError.class,
            () -> TypeChecker.checkModule("greeting = \"hi\"\nmain = greting\n"));
    assertTrue(e.getMessage().contains("greeting"), e.getMessage());
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

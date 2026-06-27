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
  void openVariantImportBringsConstructorsIntoScope() {
    // `import M exposing (Type(..))` must expose the union's constructors unqualified. The parser
    // discards the `(..)`, leaving just the type name, so the checker resolves a bare constructor
    // against every module's constructors (as the runtime does). Regression: this previously failed
    // with "Unknown name: SetSection" even though the program compiles and runs.
    String model =
        """
        module DocsModel exposing (Msg(..))
        type Msg = SetSection String | NoOp
        """;
    String update =
        """
        module DocsUpdate exposing (describe)
        import DocsModel exposing (Msg(..))
        describe msg =
            case msg of
                SetSection s -> s
                NoOp -> ""
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> TypeChecker.checkProject(model, update));
  }

  @Test
  void sameNamedAliasesInDifferentModulesDoNotCollide() {
    // Two modules each define `type alias Model`. A module that imports one Model must see THAT
    // Model, not whichever was checked last project-wide. Regression: with a flat alias table,
    // `UserA`'s `: Model` annotation expanded to ModelB's record, so `m.a` reported a field mismatch.
    String modelA =
        """
        module ModelA exposing (Model)
        type alias Model = { a : Int }
        """;
    String modelB =
        """
        module ModelB exposing (Model)
        type alias Model = { b : String }
        """;
    String userA =
        """
        module UserA exposing (f)
        import ModelA exposing (Model)
        f : Model -> Int
        f m = m.a
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> TypeChecker.checkProject(modelA, modelB, userA));
  }

  @Test
  void crossModuleModelsConstructorsAndNestedAliasesResolvePerModule() {
    // The multi-page-app shape that exposed three compounding cross-module resolution bugs, all of
    // which must now type-check together:
    //  (1) a record alias (`Row`) from a third module, used inside a page's `Model`, stayed opaque
    //      in an importer that didn't import it — so `Model` expanded two different ways and broke
    //      record-update accumulation in `update`;
    //  (2) a constructor name (`Got`) shared by two pages' `Msg` collided via a global table, so the
    //      wrong payload type was picked;
    //  (3) `A.Model` and `B.Model` (qualified) collapsed to one record because aliases resolved by
    //      simple name, confusing the `Page` union's branches.
    String shared =
        """
        module Shared exposing (Remote(..), Row)
        type Remote a = Loading | Loaded a
        type alias Row = { id : Int, label : String }
        """;
    String pageA =
        """
        module PageA exposing (Model, Msg(..))
        import Shared exposing (Remote, Row)
        type alias Model = { tab : String, rows : Remote (List Row), note : String }
        type Msg = SetTab String | Got (Result String (List Row))
        """;
    String pageB =
        """
        module PageB exposing (Model, Msg(..))
        import Shared exposing (Remote, Row)
        type alias Model = { name : String, items : Remote (List Row) }
        type Msg = SetName String | Got (Result String Int)
        """;
    // Imports PageA's Msg(..) (so `Got` must be PageA's, payload `List Row`) and Shared's Remote, but
    // NOT Row — so `Model.rows`'s `Row` must already be inlined for `update` to accumulate fields.
    String pageAUpdate =
        """
        module PageAUpdate exposing (update)
        import PageA exposing (Model, Msg(..))
        import Shared exposing (Remote(..))
        store : Result e a -> Remote a
        store r =
            case r of
                Ok a -> Loaded a
                Err _ -> Loading
        setNote : String -> Model -> Model
        setNote s m = { m | note = s }
        update : Msg -> Model -> Model
        update msg model =
            case msg of
                SetTab t -> { model | tab = t }
                Got r -> setNote "ok" { model | rows = store r }
                _ -> model
        """;
    String main =
        """
        module Main exposing (label)
        import PageA as A
        import PageB as B
        type Page = APage A.Model | BPage B.Model
        label : Page -> String
        label page =
            case page of
                APage m -> m.tab
                BPage m -> m.name
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> TypeChecker.checkProject(shared, pageA, pageB, pageAUpdate, main));
  }

  @Test
  void everyElmPlaygroundGameTypeChecks() throws Exception {
    // The full ~1700-line evancz/elm-playground plus each game type-checks end to end — this needs
    // module-level let-generalization (SCC ordering) so shared helpers like `render` stay
    // polymorphic across picture/animation/game, and row-polymorphic records for the games whose
    // memory is a record (turtle's { x : Float }, mario's { y : Float }).
    String playground = resource("/elm/examples/Playground.elm");
    for (String game : new String[] {"Picture", "Animation", "Mouse", "Keyboard", "Turtle", "Mario"}) {
      Map<String, String> types = TypeChecker.checkProject(playground, resource("/elm/examples/" + game + ".elm"));
      assertTrue(
          types.get("main") != null && types.get("main").startsWith("Program"),
          game + " main: " + types.get("main"));
    }
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

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
  void parameterisedAliasEmbeddedCrossModuleExpands() {
    // The vendored Workspace engine's shape: a parameterised record alias whose field is ANOTHER
    // module's parameterised record alias, the outer alias's own type variable passed as the
    // argument (`Site.Model`'s `ws : Workspace.Model doc`). Expanding the outer alias must expand the
    // nested cross-module one — but the alias-cycle guard was keyed by SIMPLE name, so a nested
    // `A.Model` sharing the name `Model` with the enclosing `B.Model` was mistaken for a self-cycle
    // and left opaque, so `m.ws.n` reported "expected `Model a` but got `{ … }`". (This is the
    // checker false positive that forced the workspace bundle onto --no-check.)
    String workspace =
        """
        module Workspace exposing (Model)
        type alias Model doc =
            { open : Maybe doc, count : Int }
        """;
    String site =
        """
        module Site exposing (Model, openCount)
        import Workspace
        type alias Model doc lmodel =
            { ws : Workspace.Model doc, landing : lmodel, hash : String }
        openCount : Model doc lmodel -> Int
        openCount m = m.ws.count + String.length m.hash
        """;
    // Constructing the outer alias from an inner-alias value, and updating it, must also check.
    String use =
        """
        module Use exposing (mk, rehash)
        import Workspace
        import Site
        mk : Workspace.Model doc -> lmodel -> Site.Model doc lmodel
        mk w l = { ws = w, landing = l, hash = "" }
        rehash : String -> Site.Model doc lmodel -> Site.Model doc lmodel
        rehash h m = { m | hash = h }
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> TypeChecker.checkProject(workspace, site, use));
  }

  @Test
  void qualifiedTypeDoesNotResolveToUnrelatedSameNamedAliasInScope() {
    // Two modules define a type named `Rule`: one a record ALIAS (Style), one a UNION (Validation).
    // A third module imports Style's `Rule` UNQUALIFIED and refers to Validation's by QUALIFIED name
    // `Validation.Rule`. Regression: a qualified reference whose module defines no alias of that name
    // (it's a union) fell through to the flat by-simple-name alias table, where the importer's
    // unqualified `Style.Rule` shadowed it — so `Validation.Rule` wrongly expanded to Style's record
    // and `Validation.check` reported the value as `{ range, condition, style }` instead of `Rule`.
    String style =
        """
        module Style exposing (Rule)
        type alias Rule = { range : Int, condition : Int, style : Int }
        """;
    String validation =
        """
        module Validation exposing (Rule, check)
        type Rule = Rule Int
        check : Rule -> Int -> Int
        check (Rule n) v = n + v
        """;
    String sheet =
        """
        module Sheet exposing (validate)
        import Style exposing (Rule)
        import Validation
        validationAt : Int -> Maybe Validation.Rule
        validationAt i = Nothing
        emptyRule : Rule
        emptyRule = { range = 0, condition = 0, style = 0 }
        validate : Int -> Int -> Int
        validate ref input =
            case validationAt ref of
                Just rule -> Validation.check rule input
                Nothing -> input
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> TypeChecker.checkProject(style, validation, sheet));
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
  void unresolvedImportReportsMissingModuleNotUnknownName() {
    // Referencing a sibling module's value when that module isn't loaded (e.g. `make` on one file
    // without `--project`) must blame the unresolved import, not read like a typo in a loaded module.
    String main =
        """
        module Main exposing (x)
        import Sheet
        x = Sheet.rawAt 0
        """;
    ElmTypeError err = assertThrows(ElmTypeError.class, () -> TypeChecker.checkProject(main));
    assertTrue(
        err.getMessage().contains("Cannot find module `Sheet`"),
        "expected a missing-module message, got: " + err.getMessage());
  }

  @Test
  void projectErrorIsLocatedAndNamedInItsOwnModuleNotTheEntryFile() {
    // The error is in a NON-entry module. Regression: project errors were rendered against the entry
    // (last) source with no module name, so the excerpt showed the wrong line and was unlocatable
    // across many modules. The report must now name the originating module and show ITS source line.
    String helper =
        """
        module Helper exposing (bad)
        bad : Int
        bad = "not an int"
        """;
    String main =
        """
        module Main exposing (main)
        import Helper exposing (bad)
        main = bad
        """;
    ElmTypeError err = assertThrows(ElmTypeError.class, () -> TypeChecker.checkProject(helper, main));
    String msg = err.getMessage();
    assertTrue(msg.contains("module Helper"), "should name the originating module, got: " + msg);
    assertTrue(
        msg.contains("\"not an int\""),
        "should show Helper's own source line, not the entry file's, got: " + msg);
  }

  @Test
  void qualifiedRefToMissingModuleDoesNotGrabSameNamedLocal() {
    // `Workspace.Browser.backend config.namespace` referencing an unloaded module, with a let-bound
    // local `backend` in scope: the qualified ref must NOT silently fall back to the local (whose
    // placeholder, applied to an argument, self-unifies into "Infinite type: a occurs in a -> b").
    // It must report the genuinely-missing module instead. Only a SELF-qualified ref may fall back.
    String src =
        """
        module Site exposing (program)
        program config =
            let
                backend =
                    config.namespace
            in
            Workspace.Browser.backend backend
        """;
    ElmTypeError err = assertThrows(ElmTypeError.class, () -> TypeChecker.checkModule(src));
    assertTrue(
        err.getMessage().contains("Cannot find module"),
        "should blame the missing module, not produce an Infinite type, got: " + err.getMessage());
  }

  @Test
  void selfQualifiedReferenceStillResolves() {
    // A module referring to its OWN top-level by qualified name (`M.a` inside `M`) must still resolve
    // — its prefixed global isn't registered until the module finishes, so the unqualified fallback
    // legitimately applies for the current module (and only it).
    String src =
        """
        module M exposing (a, b)
        a : Int
        a = 1
        b : Int
        b = M.a + 1
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> TypeChecker.checkModule(src));
  }

  @Test
  void recordAliasFieldUnionTypeBindsInItsDefiningModuleNotTheUseSite() {
    // A record alias whose field is the DEFINING module's own union, used (and elaborated) by an
    // importer that defines a same-named type. Regression: the stored alias body kept the union as a
    // bare unqualified `Con`, so when the importer elaborated `WS.Access` the name `P` re-resolved in
    // the importer's scope and bound to ITS `P` — `WS.empty` (List WS.P) then mismatched the annotation
    // (List importer-P). The defining module is now baked into the stored body so it can't re-resolve.
    String ws =
        """
        module WS exposing (Access, empty)
        type P = U String
        type alias Access = { owners : List P }
        empty : Access
        empty = { owners = [] }
        """;
    String main =
        """
        module Main exposing (out)
        import WS
        type alias P = { x : Int }
        out : WS.Access
        out = WS.empty
        """;
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> TypeChecker.checkProject(ws, main));
  }

  @Test
  void recordAliasFieldUnionStillRejectsAGenuinelyWrongValue() {
    // The companion to the above: pinning the field to WS.P must not collapse the type — a local
    // record value of a same-named importer type is still a real mismatch and must be rejected.
    String ws =
        """
        module WS exposing (Access)
        type P = U String
        type alias Access = { owners : List P }
        """;
    String main =
        """
        module Main exposing (bad)
        import WS
        type alias P = { x : Int }
        bad : WS.Access
        bad = { owners = [ { x = 1 } ] }
        """;
    assertThrows(ElmTypeError.class, () -> TypeChecker.checkProject(ws, main));
  }

  @Test
  void aReexportedAliasResolvesThroughTheFacadeModule() {
    // A record alias defined in one module and RE-EXPOSED by a facade module (which imports it and
    // lists it in its own `exposing (...)`) must expand for an importer of the facade — otherwise the
    // name stays opaque and a field access reports "expected Game but got { ... }". This is the shape
    // of elm-rogue's `Rogue.Game` re-exposing `Rogue.Game.Types.Game`.
    String types =
        """
        module Types exposing (Game)
        type alias Game = { hero : { level : Int }, turn : Int }
        """;
    String facade =
        """
        module Facade exposing (Game)
        import Types exposing (Game)
        """;
    String main =
        """
        module Main exposing (turnOf)
        import Facade exposing (Game)
        turnOf : Game -> Int
        turnOf g = g.turn
        """;
    // Before the fix, `turnOf` failed to check (Game opaque); it must now type-check cleanly.
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> TypeChecker.checkProject(types, facade, main));
  }

  @Test
  void pageCaretEditorExtensionTypeChecks() {
    // Browser.Dom.pageCaret is an elm-lang runtime extension (dom.js) for code editors; its signature
    // must be in scope so editor-vendoring apps type-check without --no-check.
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                module M exposing (move)
                import Browser.Dom
                import Task
                move : (Result Browser.Dom.Error Int -> msg) -> Cmd msg
                move toMsg = Task.attempt toMsg (Browser.Dom.pageCaret "ed" "pagedown")
                """));
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

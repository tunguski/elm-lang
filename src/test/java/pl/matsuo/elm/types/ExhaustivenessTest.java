package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

/** Tests for missing/unreachable {@code case} branch detection (see {@link Exhaustiveness}). */
class ExhaustivenessTest {

  private ElmTypeError reject(String source) {
    return assertThrows(ElmTypeError.class, () -> TypeChecker.checkModule(source));
  }

  @Test
  void completeUnionMatchIsAccepted() {
    assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                type Color = Red | Green | Blue
                name c =
                    case c of
                        Red -> "r"
                        Green -> "g"
                        Blue -> "b"
                """));
  }

  @Test
  void missingUnionConstructorIsReported() {
    ElmTypeError e =
        reject(
            """
            type Color = Red | Green | Blue
            name c =
                case c of
                    Red -> "r"
                    Green -> "g"
            """);
    assertTrue(e.getMessage().contains("does not handle all"), e.getMessage());
    assertTrue(e.hint != null && e.hint.contains("Blue"), String.valueOf(e.hint));
  }

  @Test
  void wildcardMakesItExhaustive() {
    assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                type Color = Red | Green | Blue
                name c =
                    case c of
                        Red -> "r"
                        _ -> "other"
                """));
  }

  @Test
  void missingNothingOnBuiltinMaybeIsReported() {
    ElmTypeError e =
        reject(
            """
            f x =
                case x of
                    Just n -> n
            """);
    assertTrue(e.hint != null && e.hint.contains("Nothing"), String.valueOf(e.hint));
  }

  @Test
  void listMatchNeedsBothNilAndCons() {
    assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                len xs =
                    case xs of
                        [] -> 0
                        first :: rest -> 1
                """));
    ElmTypeError e =
        reject(
            """
            len xs =
                case xs of
                    first :: rest -> 1
            """);
    assertTrue(e.hint != null && e.hint.contains("[]"), String.valueOf(e.hint));
  }

  @Test
  void intMatchWithoutWildcardIsNonExhaustive() {
    ElmTypeError e =
        reject(
            """
            f n =
                case n of
                    1 -> "one"
                    2 -> "two"
            """);
    assertTrue(e.getMessage().contains("does not handle all"), e.getMessage());
  }

  @Test
  void unreachableBranchAfterCatchAllIsReported() {
    ElmTypeError e =
        reject(
            """
            type Color = Red | Green | Blue
            name c =
                case c of
                    _ -> "x"
                    Red -> "r"
            """);
    assertTrue(e.getMessage().contains("unreachable"), e.getMessage());
  }

  @Test
  void singleConstructorUnionNeedsNoWildcard() {
    // A one-constructor union (a common "wrapper" type) is covered by that one constructor alone.
    assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                type Wrapper a = Wrapper a
                unwrap w =
                    case w of
                        Wrapper x -> x
                """));
  }

  @Test
  void nestedMaybeExhaustivenessIsChecked() {
    // Just Nothing / Just (Just _) / Nothing covers Maybe (Maybe a).
    assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                f x =
                    case x of
                        Nothing -> 0
                        Just Nothing -> 1
                        Just (Just _) -> 2
                """));
    // Dropping `Just Nothing` leaves a gap.
    ElmTypeError e =
        reject(
            """
            f x =
                case x of
                    Nothing -> 0
                    Just (Just _) -> 2
            """);
    assertTrue(e.getMessage().contains("does not handle all"), e.getMessage());
  }
}

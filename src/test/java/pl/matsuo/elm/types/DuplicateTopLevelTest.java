package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

/** The checker must reject two top-level definitions of the same name (previously last-wins, silent). */
class DuplicateTopLevelTest {

  @Test
  void twoTopLevelDefinitionsOfTheSameNameAreRejected() {
    // Regression: a second `pivotSheet` silently overwrote the first, so old tests quietly used the
    // new data with no error. It must now be a checker error.
    String src =
        """
        module M exposing (pivotSheet)
        pivotSheet = 1
        other = 2
        pivotSheet = 3
        """;
    ElmTypeError err = assertThrows(ElmTypeError.class, () -> TypeChecker.checkModule(src));
    org.junit.jupiter.api.Assertions.assertTrue(
        err.getMessage().contains("pivotSheet") && err.getMessage().contains("more than once"),
        err.getMessage());
  }

  @Test
  void anAnnotationPlusItsDefinitionIsNotADuplicate() {
    // `f : T` merges into `f = …`; it must not be misread as a redefinition.
    assertDoesNotThrow(
        () ->
            TypeChecker.checkModule(
                """
                module M exposing (f)
                f : Int
                f = 1
                """));
  }
}

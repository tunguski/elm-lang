package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

/** The quality of type-error reporting: located source excerpt with a caret, expected/got types,
 *  a tailored hint, and a did-you-mean for a misspelled record field. */
class ErrorMessageTest {

  private static ElmTypeError error(String source) {
    return assertThrows(ElmTypeError.class, () -> TypeChecker.checkModule(source));
  }

  @Test
  void mismatchShowsExpectedGotCaretAndHint() {
    // An annotation says Int but the body is a String: a clean expected/got mismatch.
    ElmTypeError e = error("foo : Int\nfoo = \"hello\"\nmain = foo\n");
    String m = e.getMessage();
    assertTrue(m.contains("Type mismatch") && m.contains("but got"), m);
    assertTrue(m.contains("Int") && m.contains("String"), m);
    assertTrue(m.contains("^"), m); // caret under the offending code
    assertTrue(m.contains("String.fromInt") || m.contains("String.toInt"), m); // tailored hint
  }

  @Test
  void ifConditionMustBeBool() {
    String m = error("main = if \"x\" then 1 else 2\n").getMessage();
    assertTrue(m.contains("Type mismatch") && m.contains("Bool"), m);
  }

  @Test
  void misspelledVariableSuggestsTheRightName() {
    // An unknown name close to one in scope gets a did-you-mean hint.
    String m = error("greeting = \"hi\"\nmain = greteing\n").getMessage();
    assertTrue(m.contains("Unknown name"), m);
    assertTrue(m.contains("Did you mean `greeting`?"), m);
  }

  @Test
  void misspelledStdlibCallSuggestsTheQualifiedName() {
    // A typo of a qualified stdlib function suggests it (matched on the final segment).
    String m = error("main = List.lenght [ 1, 2, 3 ]\n").getMessage();
    assertTrue(m.contains("Did you mean") && m.contains("length"), m);
  }

  @Test
  void misspelledConstructorInAPatternSuggestsTheRightName() {
    String src = "main =\n    case Just 1 of\n        Jist x -> x\n        Nothing -> 0\n";
    String m = error(src).getMessage();
    assertTrue(m.contains("Did you mean `Just`?"), m);
  }

  @Test
  void misspelledRecordFieldSuggestsTheRightName() {
    String src =
        """
        type alias P = { name : String }
        f : P -> String
        f r = r.name
        main = f { naem = "x" }
        """;
    String m = error(src).getMessage();
    assertTrue(m.contains("Record mismatch"), m);
    assertTrue(m.contains("naem") && m.contains("name"), m);
    assertTrue(m.contains("Did you mean `name`?"), m);
  }

  @Test
  void heterogeneousListReportsListContextNotABareLeafMismatch() {
    // [1, "two"] used to surface a bare "expected Int but got String"; now it names the list context
    // (which element, against the earlier ones) and hints that a list is homogeneous.
    String m = error("main = [ 1, \"two\" ]\n").getMessage();
    assertTrue(m.contains("All elements of a list must have the same type"), m);
    // element 2 is a String; the earlier element is still an unconstrained `number` literal.
    assertTrue(m.contains("element 2") && m.contains("String") && m.contains("number"), m);
    assertTrue(m.contains("Hint:") && m.contains("single type"), m);
    assertTrue(m.contains("^"), m); // located at the list
  }

  @Test
  void overApplicationHintsAboutArgumentCount() {
    // `not` takes one argument but is given two — a function/non-function mismatch with a hint.
    String src = "main = not True False\n";
    String m = error(src).getMessage();
    assertTrue(m.contains("Hint:") && m.contains("arguments"), m);
  }
}

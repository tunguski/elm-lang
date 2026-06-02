package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmTypeError;

class TypeInferenceTest {

  private String t(String src) {
    return TypeChecker.infer(src);
  }

  @Test
  void literalsAndNumbers() {
    assertEquals("number", t("1"));
    assertEquals("Float", t("1.5"));
    assertEquals("String", t("\"hi\""));
    assertEquals("Bool", t("True"));
    assertEquals("number", t("1 + 2"));
    assertEquals("Float", t("1.5 + 2.5"));
    assertEquals("Float", t("1 + 1.5")); // number unifies with Float
    assertEquals("Float", t("7 / 2"));
    assertEquals("Int", t("7 // 2"));
  }

  @Test
  void collections() {
    assertEquals("List number", t("[1, 2, 3]"));
    assertEquals("List String", t("[\"a\", \"b\"]"));
    assertEquals("(number, String, Bool)", t("(1, \"a\", True)"));
    assertEquals("List Int", t("List.range 1 5"));
  }

  @Test
  void functionsAndApplication() {
    assertEquals("a -> a", t("\\x -> x"));
    assertEquals("number -> number", t("\\x -> x + 1"));
    assertEquals("(a -> b) -> List a -> List b", t("List.map"));
    assertEquals("String -> Int", t("String.length"));
    assertEquals("Int", t("String.length \"hello\""));
    assertEquals("List String", t("List.map String.fromInt [1, 2, 3]"));
    assertEquals("number", t("List.foldl (+) 0 [1, 2, 3]"));
  }

  @Test
  void controlAndLet() {
    assertEquals("number", t("if True then 1 else 2"));
    assertEquals("Int", t("let n = 5 in String.length (String.fromInt n)"));
    // let-generalization: id is polymorphic and used at two types.
    assertEquals("(number, String)", t("let id = \\x -> x in (id 1, id \"a\")"));
    // A `type` declared in a let: its constructors are in scope for the rest of the let.
    assertEquals(
        "Int",
        t(
            """
            let
                type Box = Box Int
                unbox b = case b of
                    Box n -> n
            in
            unbox (Box 7)
            """));
    // A record `type alias` in a let introduces its constructor function.
    assertEquals(
        "Int",
        t(
            """
            let
                type alias P = { x : Int, y : Int }
            in
            (P 3 4).x
            """));
  }

  @Test
  void records() {
    assertEquals("{ x : number, y : Bool }", t("{ x = 1, y = True }"));
    assertTrue(t("\\r -> r.name").contains("name :"), t("\\r -> r.name"));
    assertEquals("number", t("{ x = 1 }.x"));
    assertEquals("{ count : number }", t("(\\r -> { r | count = 0 }) { count = 9 }"));
  }

  @Test
  void maybeAndPipes() {
    assertEquals("Maybe number", t("Just 1"));
    assertEquals("number", t("Maybe.withDefault 0 (Just 5)"));
    assertEquals("Int", t("5 |> String.fromInt |> String.length"));
  }

  @Test
  void comparableConstraint() {
    assertEquals("Bool", t("1 < 2"));
    assertEquals("Bool", t("\"a\" < \"b\""));
  }

  @Test
  void typeErrors() {
    assertThrows(ElmTypeError.class, () -> t("1 + \"a\"")); // String is not a number
    assertThrows(ElmTypeError.class, () -> t("if 1 then 2 else 3")); // condition not Bool
    assertThrows(ElmTypeError.class, () -> t("\"a\" + 1")); // appendable vs number
    assertThrows(ElmTypeError.class, () -> t("[1, \"a\"]")); // heterogeneous list
    assertThrows(ElmTypeError.class, () -> t("\\f -> f f")); // infinite type
  }
}

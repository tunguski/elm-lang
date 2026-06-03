package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Exercises the bundled {@code Json.Decode.Extra} library (auto-resolved, no explicit lib source). */
class JsonDecodeExtraLibraryTest {

  private static final String SRC =
      """
      module Main exposing (pairV, wdV, optAbsentV, pIntV, pFloatV, fromResV)

      import Json.Decode as D
      import Json.Decode.Extra as DE

      pairV =
          Result.withDefault ( 0, 0 )
              (D.decodeString
                  (D.succeed Tuple.pair |> DE.andMap (D.index 0 D.int) |> DE.andMap (D.index 1 D.int))
                  "[1,2]")

      wdV =
          Result.withDefault -1 (D.decodeString (DE.withDefault 99 D.int) "true")

      optAbsentV =
          Result.withDefault (Just -1) (D.decodeString (DE.optionalField "a" D.int) "[1,2]")

      pIntV =
          Result.withDefault 0 (D.decodeString DE.parseInt "\\"42\\"")

      pFloatV =
          Result.withDefault 0 (D.decodeString DE.parseFloat "\\"3.5\\"")

      fromResV =
          Result.withDefault 0 (D.decodeString (DE.fromResult (Ok 7)) "null")
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC).value("Main", name));
  }

  @Test
  void andMapWithDefaultOptionalParse() {
    assertEquals("(1,2)", value("pairV")); // pipeline applicative over array indices
    assertEquals("99", value("wdV")); // int decoder fails on `true` -> fallback
    assertEquals("Nothing", value("optAbsentV")); // field "a" absent on an array
    assertEquals("42", value("pIntV"));
    assertEquals("3.5", value("pFloatV"));
    assertEquals("7", value("fromResV"));
  }
}

package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.doc.ApiDocs;
import pl.matsuo.elm.pkg.ApiDiff.Magnitude;

/** docs.json extraction and the semver magnitude derived from an API diff (elm diff / elm bump). */
class ApiDiffTest {

  private static final String BASE =
      """
      module M exposing (..)
      inc x = x + 1
      type Color = Red | Green
      type alias Point = { x : Int, y : Int }
      """;

  private static Magnitude diff(String oldSrc, String newSrc) {
    return ApiDiff.compare(ApiDocs.of(oldSrc), ApiDocs.of(newSrc)).magnitude();
  }

  @Test
  void docsJsonCapturesValuesTypesAndAliases() {
    String json = ApiDocs.of(BASE).toJson();
    assertTrue(json.contains("\"inc\""), json);
    assertTrue(json.contains("Color") && json.contains("Red") && json.contains("Green"), json);
    assertTrue(json.contains("Point") && json.contains("\"unions\"") && json.contains("\"aliases\""), json);
  }

  @Test
  void identicalApiIsPatch() {
    assertEquals(Magnitude.PATCH, diff(BASE, BASE));
  }

  @Test
  void addingAValueIsMinor() {
    String now = BASE + "dec x = x - 1\n";
    assertEquals(Magnitude.MINOR, diff(BASE, now));
  }

  @Test
  void removingAValueIsMajor() {
    String now = "module M exposing (..)\ntype Color = Red | Green\ntype alias Point = { x : Int, y : Int }\n";
    assertEquals(Magnitude.MAJOR, diff(BASE, now)); // inc removed
  }

  @Test
  void changingAValueTypeIsMajor() {
    // inc's inferred type changes from `number -> number` to `a -> a`.
    String now = "module M exposing (..)\ninc x = x\ntype Color = Red | Green\ntype alias Point = { x : Int, y : Int }\n";
    assertEquals(Magnitude.MAJOR, diff(BASE, now));
  }

  @Test
  void addingAConstructorIsMajor() {
    String now = BASE.replace("Red | Green", "Red | Green | Blue");
    assertEquals(Magnitude.MAJOR, diff(BASE, now)); // a changed custom type
  }

  @Test
  void bumpFollowsMagnitude() {
    Version v = Version.parse("1.2.3");
    assertEquals(Version.parse("2.0.0"), ApiDiff.bump(v, Magnitude.MAJOR));
    assertEquals(Version.parse("1.3.0"), ApiDiff.bump(v, Magnitude.MINOR));
    assertEquals(Version.parse("1.2.4"), ApiDiff.bump(v, Magnitude.PATCH));
  }
}

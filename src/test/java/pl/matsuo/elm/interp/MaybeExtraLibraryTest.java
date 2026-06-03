package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Maybe.Extra} library through the interpreter. */
class MaybeExtraLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Maybe/Extra.elm");

  private static final String SRC =
      """
      module Main exposing (isJ, isN, joinV, orV, orElseV, valsV, combOk, combNo, travOk, travNo, filtV, unwrapV, toListV, oneOfV)

      import Maybe.Extra as ME

      isJ = ME.isJust (Just 1)
      isN = ME.isNothing (Just 1)
      joinV = ME.join (Just (Just 5))
      orV = ME.or Nothing (Just 2)
      orElseV = ME.orElse (Just 9) Nothing
      valsV = ME.values [ Just 1, Nothing, Just 3 ]
      combOk = ME.combine [ Just 1, Just 2, Just 3 ]
      combNo = ME.combine [ Just 1, Nothing, Just 3 ]
      travOk = ME.traverse (\\n -> if n > 0 then Just (n * 2) else Nothing) [ 1, 2, 3 ]
      travNo = ME.traverse (\\n -> if n > 0 then Just (n * 2) else Nothing) [ 1, -1, 3 ]
      filtV = ME.filter (\\n -> n > 2) (Just 5)
      unwrapV = ME.unwrap 0 (\\n -> n + 1) (Just 10)
      toListV = ME.toList (Just 7)
      oneOfV = ME.oneOf [ Nothing, Just 2, Just 3 ]
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void predicatesJoinAndOr() {
    assertEquals("True", value("isJ"));
    assertEquals("False", value("isN"));
    assertEquals("Just 5", value("joinV"));
    assertEquals("Just 2", value("orV"));
    assertEquals("Just 9", value("orElseV")); // orElse fallback applied to Nothing
    assertEquals("Just 2", value("oneOfV"));
  }

  @Test
  void valuesCombineTraverse() {
    assertEquals("[1,3]", value("valsV"));
    assertEquals("Just [1,2,3]", value("combOk"));
    assertEquals("Nothing", value("combNo"));
    assertEquals("Just [2,4,6]", value("travOk"));
    assertEquals("Nothing", value("travNo"));
  }

  @Test
  void filterUnwrapToList() {
    assertEquals("Just 5", value("filtV"));
    assertEquals("11", value("unwrapV"));
    assertEquals("[7]", value("toListV"));
  }
}

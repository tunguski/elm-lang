package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Result.Extra} library through the interpreter. */
class ResultExtraLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Result/Extra.elm");

  private static final String SRC =
      """
      module Main exposing (okV, errV, combOk, combErr, partV, mapBothV, mergeOk, mergeErr, orV, orElseV, unwrapV, extractV)

      import Result.Extra as RE

      okV = RE.isOk (Ok 1)
      errV = RE.isErr (Ok 1)
      combOk = RE.combine [ Ok 1, Ok 2, Ok 3 ]
      combErr = RE.combine [ Ok 1, Err "bad", Ok 3 ]
      partV = RE.partition [ Ok 1, Err "a", Ok 2, Err "b" ]
      mapBothV = RE.mapBoth (\\e -> e ++ "!") (\\n -> n + 1) (Ok 5)
      mergeOk = RE.merge (Ok 3)
      mergeErr = RE.merge (Err 7)
      orV = RE.or (Err "x") (Ok 2)
      orElseV = RE.orElse (Ok 9) (Err "x")
      unwrapV = RE.unwrap 0 (\\n -> n + 1) (Ok 10)
      extractV = RE.extract String.length (Err "oops")
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void predicatesCombinePartition() {
    assertEquals("True", value("okV"));
    assertEquals("False", value("errV"));
    assertEquals("Ok [1,2,3]", value("combOk"));
    assertEquals("Err \"bad\"", value("combErr"));
    assertEquals("([1,2],[\"a\",\"b\"])", value("partV"));
  }

  @Test
  void mapBothMergeOrUnwrapExtract() {
    assertEquals("Ok 6", value("mapBothV"));
    assertEquals("3", value("mergeOk"));
    assertEquals("7", value("mergeErr"));
    assertEquals("Ok 2", value("orV"));
    assertEquals("Ok 9", value("orElseV"));
    assertEquals("11", value("unwrapV"));
    assertEquals("4", value("extractV")); // String.length "oops" = 4
  }
}

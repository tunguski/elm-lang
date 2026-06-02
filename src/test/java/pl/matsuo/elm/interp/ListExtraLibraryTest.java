package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code List.Extra} library through the interpreter. */
class ListExtraLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/List/Extra.elm");

  private static final String SRC =
      """
      module Main exposing (lastV, initV, getV, setV, removeV, findIdx, cnt, splitV, takeW, uniq, uniqByV, groups, fold1, scan1, maxByV, zipV, weave, notMem)

      import List.Extra as LE

      lastV = LE.last [ 1, 2, 3 ]
      initV = LE.init [ 1, 2, 3 ]
      getV = LE.getAt 1 [ "a", "b", "c" ]
      setV = LE.setAt 1 9 [ 1, 2, 3 ]
      removeV = LE.removeAt 0 [ 1, 2, 3 ]
      findIdx = LE.findIndex (\\n -> n > 2) [ 1, 2, 3, 4 ]
      cnt = LE.count (\\n -> modBy 2 n == 0) [ 1, 2, 3, 4 ]
      splitV = LE.splitAt 2 [ 1, 2, 3, 4 ]
      takeW = LE.takeWhile (\\n -> n < 3) [ 1, 2, 3, 1 ]
      uniq = LE.unique [ 1, 1, 2, 3, 2 ]
      uniqByV = LE.uniqueBy (\\n -> modBy 3 n) [ 1, 2, 3, 4, 5 ]
      groups = LE.groupsOf 2 [ 1, 2, 3, 4, 5 ]
      fold1 = LE.foldl1 (+) [ 1, 2, 3, 4 ]
      scan1 = LE.scanl1 (+) [ 1, 2, 3, 4 ]
      maxByV = LE.maximumBy String.length [ "a", "ccc", "bb" ]
      zipV = LE.zip [ 1, 2, 3 ] [ "a", "b" ]
      weave = LE.interweave [ 1, 3, 5 ] [ 2, 4 ]
      notMem = LE.notMember 9 [ 1, 2, 3 ]
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void indexingAndSearch() {
    assertEquals("Just 3", value("lastV"));
    assertEquals("Just [1,2]", value("initV"));
    assertEquals("Just \"b\"", value("getV"));
    assertEquals("[1,9,3]", value("setV"));
    assertEquals("[2,3]", value("removeV"));
    assertEquals("Just 2", value("findIdx")); // 3 is the first element > 2, at index 2
    assertEquals("2", value("cnt"));
    assertEquals("True", value("notMem"));
  }

  @Test
  void slicingAndDeduplication() {
    assertEquals("([1,2],[3,4])", value("splitV"));
    assertEquals("[1,2]", value("takeW"));
    assertEquals("[1,2,3]", value("uniq"));
    assertEquals("[1,2,3]", value("uniqByV")); // keys mod 3: 1,2,0 keep 1,2,3
    assertEquals("[[1,2],[3,4],[5]]", value("groups"));
  }

  @Test
  void foldsScansExtremaAndCombiners() {
    assertEquals("Just 10", value("fold1"));
    assertEquals("[1,3,6,10]", value("scan1"));
    assertEquals("Just \"ccc\"", value("maxByV"));
    assertEquals("[(1,\"a\"),(2,\"b\")]", value("zipV"));
    assertEquals("[1,2,3,4,5]", value("weave"));
  }
}

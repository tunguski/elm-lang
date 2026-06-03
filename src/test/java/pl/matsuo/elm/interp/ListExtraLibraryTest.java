package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code List.Extra} library through the interpreter. */
class ListExtraLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/List/Extra.elm");

  private static final String SRC =
      """
      module Main exposing (lastV, initV, getV, setV, removeV, findIdx, cnt, splitV, takeW, uniq, uniqByV, groups, fold1, scan1, maxByV, zipV, weave, notMem, foldr1V, interc, transp, grp, cart, andMapV, iterV, removeVal, swap, pre, suf, strip, grpW, fmap, z3, ifold, unf, scan)

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
      foldr1V = LE.foldr1 (-) [ 1, 2, 3 ]
      interc = LE.intercalate [ 0 ] [ [ 1, 2 ], [ 3 ], [ 4, 5 ] ]
      transp = LE.transpose [ [ 1, 2, 3 ], [ 4, 5, 6 ] ]
      grp = LE.group [ 1, 1, 2, 3, 3, 3 ]
      cart = LE.cartesianProduct [ [ 1, 2 ], [ 3, 4 ] ]
      andMapV = LE.andMap [ 1, 2, 3 ] [ \\x -> x + 1, \\x -> x * 2, negate ]
      iterV = LE.iterate (\\n -> if n > 1 then Just (n // 2) else Nothing) 8
      removeVal = LE.remove 2 [ 1, 2, 3, 2 ]
      swap = LE.swapAt 0 2 [ 1, 2, 3 ]
      pre = LE.isPrefixOf [ 1, 2 ] [ 1, 2, 3 ]
      suf = LE.isSuffixOf [ 2, 3 ] [ 1, 2, 3 ]
      strip = LE.stripPrefix [ 1, 2 ] [ 1, 2, 3, 4 ]
      grpW = LE.groupWhile (\\a b -> a == b) [ 1, 1, 2, 1 ]
      fmap = LE.findMap (\\n -> if n > 2 then Just (n * 10) else Nothing) [ 1, 2, 3, 4 ]
      z3 = LE.zip3 [ 1, 2 ] [ "a", "b" ] [ True, False ]
      ifold = LE.indexedFoldl (\\i x acc -> acc + i * x) 0 [ 10, 20, 30 ]
      unf = LE.unfoldr (\\n -> if n <= 0 then Nothing else Just ( n, n - 1 )) 3
      scan = LE.scanl (+) 0 [ 1, 2, 3 ]
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

  @Test
  void additionalCombinatorsAndTransforms() {
    assertEquals("Just 2", value("foldr1V")); // 1 - (2 - 3) = 2
    assertEquals("[1,2,0,3,0,4,5]", value("interc"));
    assertEquals("[[1,4],[2,5],[3,6]]", value("transp"));
    assertEquals("[[1,1],[2],[3,3,3]]", value("grp"));
    assertEquals("[[1,3],[1,4],[2,3],[2,4]]", value("cart"));
    assertEquals("[2,4,-3]", value("andMapV"));
    assertEquals("[8,4,2,1]", value("iterV"));
    assertEquals("[1,3,2]", value("removeVal")); // removes the first 2
    assertEquals("[3,2,1]", value("swap"));
  }

  @Test
  void prefixGroupFindZipFoldUnfoldScan() {
    assertEquals("True", value("pre"));
    assertEquals("True", value("suf"));
    assertEquals("Just [3,4]", value("strip"));
    assertEquals("[[1,1],[2],[1]]", value("grpW"));
    assertEquals("Just 30", value("fmap"));
    assertEquals("[(1,\"a\",True),(2,\"b\",False)]", value("z3"));
    assertEquals("80", value("ifold")); // 0*10 + 1*20 + 2*30
    assertEquals("[3,2,1]", value("unf"));
    assertEquals("[0,1,3,6]", value("scan"));
  }
}

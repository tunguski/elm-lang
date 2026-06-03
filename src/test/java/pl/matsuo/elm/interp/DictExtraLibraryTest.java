package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Exercises the bundled {@code Dict.Extra} library (auto-resolved, no explicit lib source). */
class DictExtraLibraryTest {

  private static final String SRC =
      """
      module Main exposing (grp, fromBy, freq, mk, fmap, rw, anyV, findV)

      import Dict
      import Dict.Extra as DE

      grp = Dict.toList (DE.groupBy (\\n -> modBy 2 n) [ 1, 2, 3, 4 ])
      fromBy = Dict.toList (DE.fromListBy String.length [ "a", "bb", "cc" ])
      freq = Dict.toList (DE.frequencies [ "a", "b", "a", "a" ])
      mk = Dict.toList (DE.mapKeys (\\k -> k + 10) (Dict.fromList [ ( 1, "a" ), ( 2, "b" ) ]))
      fmap = Dict.toList (DE.filterMap (\\k v -> if modBy 2 v == 0 then Just (v * 100) else Nothing) (Dict.fromList [ ( 1, 1 ), ( 2, 2 ), ( 3, 4 ) ]))
      rw = Dict.toList (DE.removeWhen (\\k v -> v > 1) (Dict.fromList [ ( 1, 1 ), ( 2, 2 ) ]))
      anyV = DE.any (\\k v -> v > 1) (Dict.fromList [ ( 1, 1 ), ( 2, 2 ) ])
      findV = DE.find (\\k v -> v == 2) (Dict.fromList [ ( 1, 1 ), ( 2, 2 ) ])
      """;

  private static String value(String name) {
    // No Dict.Extra source supplied — BundledLibs resolves it from the import.
    return Show.plain(Project.load(SRC).value("Main", name));
  }

  @Test
  void groupingAndBuilding() {
    assertEquals("[(0,[2,4]),(1,[1,3])]", value("grp"));
    assertEquals("[(1,\"a\"),(2,\"cc\")]", value("fromBy")); // later duplicate (cc) wins
    assertEquals("[(\"a\",3),(\"b\",1)]", value("freq"));
  }

  @Test
  void transformsAndQueries() {
    assertEquals("[(11,\"a\"),(12,\"b\")]", value("mk"));
    assertEquals("[(2,200),(3,400)]", value("fmap"));
    assertEquals("[(1,1)]", value("rw"));
    assertEquals("True", value("anyV"));
    assertEquals("Just (2,2)", value("findV"));
  }
}

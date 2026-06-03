package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Exercises the bundled {@code Set.Extra} library (auto-resolved, no explicit lib source). */
class SetExtraLibraryTest {

  private static final String SRC =
      """
      module Main exposing (tog, togIn, cmap, fmap, disjT, disjF, symm, subT, subF, uni)

      import Set
      import Set.Extra as SE

      tog = Set.toList (SE.toggle 2 (Set.fromList [ 1, 2, 3 ]))
      togIn = Set.toList (SE.toggle 9 (Set.fromList [ 1, 2 ]))
      cmap = Set.toList (SE.concatMap (\\x -> Set.fromList [ x, x * 10 ]) (Set.fromList [ 1, 2 ]))
      fmap = Set.toList (SE.filterMap (\\x -> if modBy 2 x == 0 then Just (x * 100) else Nothing) (Set.fromList [ 1, 2, 3, 4 ]))
      disjT = SE.areDisjoint (Set.fromList [ 1, 2 ]) (Set.fromList [ 3, 4 ])
      disjF = SE.areDisjoint (Set.fromList [ 1, 2 ]) (Set.fromList [ 2, 3 ])
      symm = Set.toList (SE.symmetricDifference (Set.fromList [ 1, 2, 3 ]) (Set.fromList [ 2, 3, 4 ]))
      subT = SE.isSubsetOf (Set.fromList [ 1, 2, 3 ]) (Set.fromList [ 1, 2 ])
      subF = SE.isSubsetOf (Set.fromList [ 1, 2 ]) (Set.fromList [ 1, 3 ])
      uni = Set.toList (SE.unions [ Set.fromList [ 1, 2 ], Set.fromList [ 2, 3 ] ])
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC).value("Main", name));
  }

  @Test
  void toggleConcatMapFilterMap() {
    assertEquals("[1,3]", value("tog")); // 2 removed
    assertEquals("[1,2,9]", value("togIn")); // 9 inserted
    assertEquals("[1,2,10,20]", value("cmap"));
    assertEquals("[200,400]", value("fmap"));
  }

  @Test
  void disjointAndSymmetricDifference() {
    assertEquals("True", value("disjT"));
    assertEquals("False", value("disjF"));
    assertEquals("[1,4]", value("symm"));
  }

  @Test
  void subsetAndUnions() {
    assertEquals("True", value("subT"));
    assertEquals("False", value("subF"));
    assertEquals("[1,2,3]", value("uni"));
  }
}

package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Exercises the bundled {@code Tuple.Extra} library (auto-resolved, no explicit lib source). */
class TupleExtraLibraryTest {

  private static final String SRC =
      """
      module Main exposing (app, sw, seqJust, seqNo, seqFirst, seqSecond)

      import Tuple.Extra as TE

      app = TE.apply (+) ( 3, 4 )
      sw = TE.swap ( 1, "a" )
      seqJust = TE.sequenceMaybe ( Just 1, Just 2 )
      seqNo = TE.sequenceMaybe ( Just 1, Nothing )
      seqFirst = TE.sequenceFirstMaybe ( Just 1, "x" )
      seqSecond = TE.sequenceSecondMaybe ( 1, Just "y" )
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC).value("Main", name));
  }

  @Test
  void applySwapSequence() {
    assertEquals("7", value("app"));
    assertEquals("(\"a\",1)", value("sw"));
    assertEquals("Just (1,2)", value("seqJust"));
    assertEquals("Nothing", value("seqNo"));
    assertEquals("Just (1,\"x\")", value("seqFirst"));
    assertEquals("Just (1,\"y\")", value("seqSecond"));
  }
}

package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure seeded randomness: Random.initialSeed / step / independentSeed are deterministic and thread
 *  the seed, so the same generator and seed always produce the same value. */
class RandomSeedTest {

  private static String eval(String expr) {
    return Show.plain(Interpreter.eval(expr));
  }

  @Test
  void sameSeedAndGeneratorGiveTheSameValue() {
    assertEquals(
        "True",
        eval(
            "Tuple.first (Random.step (Random.int 1 1000000) (Random.initialSeed 42))"
                + " == Tuple.first (Random.step (Random.int 1 1000000) (Random.initialSeed 42))"));
  }

  @Test
  void stepThreadsTheSeedSoSuccessiveDrawsDiffer() {
    // Drawing from the returned seed gives a different value than drawing from the initial seed.
    assertEquals(
        "True",
        eval(
            "let\n  ( a, s ) = Random.step (Random.int 1 1000000) (Random.initialSeed 5)\n"
                + "  ( b, _ ) = Random.step (Random.int 1 1000000) s\nin\na /= b"));
  }

  @Test
  void differentSeedsGiveDifferentValues() {
    assertEquals(
        "True",
        eval(
            "Tuple.first (Random.step (Random.int 1 1000000) (Random.initialSeed 1))"
                + " /= Tuple.first (Random.step (Random.int 1 1000000) (Random.initialSeed 2))"));
  }

  @Test
  void aListGeneratorIsReproducible() {
    String expr =
        "Tuple.first (Random.step (Random.list 5 (Random.int 1 6)) (Random.initialSeed 99))";
    assertEquals(eval(expr), eval(expr), "stepping is pure: identical across runs");
  }
}

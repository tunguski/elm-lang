package pl.matsuo.elm.bytecode;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Show;

/**
 * The portable {@code .elmbc} artifact: a module compiled to bytecode must serialize and deserialize
 * losslessly, and the program reloaded purely from the bytes (no Elm source, no re-compilation) must
 * evaluate to the same values — so the bytecode can be shipped and run by the pure-Java VM on any
 * platform, including Android's ART.
 */
class BytecodePortabilityTest {

  /** Compiles, serializes, reloads from the bytes alone, and asserts `main` matches direct execution. */
  private void roundTrips(String source) {
    BytecodeInterpreter direct = BytecodeInterpreter.load(source);
    byte[] bytes = BytecodeWriter.toBytes(direct.toProgram());
    BytecodeInterpreter reloaded =
        BytecodeInterpreter.fromProgram(BytecodeReader.fromBytes(bytes));
    assertEquals(
        Show.plain(direct.value("main")),
        Show.plain(reloaded.value("main")),
        "reloaded-from-bytecode result differs for: " + source);
    // Re-serializing the reloaded program reproduces identical bytes (stable, deterministic format).
    assertArrayEquals(bytes, BytecodeWriter.toBytes(reloaded.toProgram()), "re-emit is byte-stable");
  }

  @Test
  void arithmeticAndClosures() {
    roundTrips("main = 1 + 2 * 3 - 10 // 4\n");
    roundTrips("sq x = x * x\nmain = List.map sq [ 1, 2, 3 ]\n");
    roundTrips("main = (\\x y -> x * y + 1) 6 7\n");
  }

  @Test
  void patternsCustomTypesRecordsLists() {
    // Custom type + case (Ctor patterns), records, cons/list patterns, tuples and an as-pattern.
    roundTrips(
        "type Tree = Leaf Int | Node Tree Tree\n"
            + "sum t = case t of\n"
            + "  Leaf n -> n\n"
            + "  Node l r -> sum l + sum r\n"
            + "main = sum (Node (Node (Leaf 1) (Leaf 2)) (Leaf 3))\n");
    roundTrips(
        "first xs = case xs of\n"
            + "  ((a, _) :: _) as whole -> ( a, List.length whole )\n"
            + "  _ -> ( 0, 0 )\n"
            + "main = first [ ( 7, \"a\" ), ( 8, \"b\" ) ]\n");
    roundTrips("main = (\\r -> { r | x = r.x + 1 }) { x = 41, y = 0 }\n");
    roundTrips("main = String.toUpper (String.reverse \"hello\")\n");
  }

  @Test
  void tailRecursionSurvivesSerialization() {
    roundTrips("sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)\nmain = sumTo 100000 0\n");
  }

  @Test
  void rejectsBytesThatAreNotElmbc() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BytecodeReader.fromBytes(new byte[] {1, 2, 3, 4, 5, 6}));
  }

  @Test
  void artifactCarriesTheMagicHeader() {
    byte[] bytes = BytecodeWriter.toBytes(BytecodeInterpreter.load("main = 1\n").toProgram());
    assertTrue(
        bytes.length > 6 && bytes[0] == 'E' && bytes[1] == 'L' && bytes[2] == 'M'
            && bytes[3] == 'B' && bytes[4] == 'C',
        "starts with the ELMBC magic");
  }
}

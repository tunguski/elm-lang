package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.types.TypeChecker;

/** The {@code Chunk.load} code-splitting API: it type-checks and binds across the backends. */
class ChunkApiTest {

  @Test
  void chunkLoadTypeChecks() {
    Map<String, String> types =
        TypeChecker.checkModule(
            """
            module M exposing (f)
            f toMsg = Chunk.load "heavy" toMsg
            """);
    assertTrue(
        types.get("f").contains("Cmd"),
        "Chunk.load yields a Cmd; f : (Result String () -> msg) -> Cmd msg, got: " + types.get("f"));
  }

  @Test
  void chunkLoadIsBoundInTheInterpreter() {
    // Headless backends are eager, so this is a no-op Cmd — but it must be BOUND (not Unbound:), so a
    // program using Chunk.load runs under the interpreter/bytecode too.
    assertDoesNotThrow(() -> Interpreter.eval("Chunk.load \"heavy\" (\\r -> r)"));
  }
}

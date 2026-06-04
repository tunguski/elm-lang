package pl.matsuo.elm.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.ast.Module;

/** Stress-tests the parser against the real evancz/elm-playground source (1700+ lines). */
class ParsePlaygroundTest {

  @Test
  void parsesPlayground() throws Exception {
    String src;
    try (InputStream in = getClass().getResourceAsStream("/elm/examples/Playground.elm")) {
      src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Module m = Parser.parseModule(src);
    assertTrue(m.decls().size() > 50, "decls: " + m.decls().size());
  }
}

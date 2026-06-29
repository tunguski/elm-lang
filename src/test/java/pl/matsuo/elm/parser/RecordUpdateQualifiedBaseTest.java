package pl.matsuo.elm.parser;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.types.TypeChecker;

/**
 * Record update with a NON-local base — e.g. {@code { Chart.defaults | width = 800 }}. Standard Elm
 * allows only a bare variable as the update base; this compiler accepts any record expression there
 * (a qualified value, a field access) by desugaring {@code { e | fs }} to {@code let b = e in { b |
 * fs }}. Reported by elm-svg, whose {@code with*} constructors expose {@code Chart.defaults} as the
 * public API users update directly.
 */
class RecordUpdateQualifiedBaseTest {

  private static final String CFG =
      """
      module Cfg exposing (defaults)
      defaults = { width = 100, height = 50 }
      """;
  private static final String USE =
      """
      module UseCfg exposing (out)
      import Cfg
      out = let c = { Cfg.defaults | width = 800 } in c.width + c.height
      """;

  @Test
  void qualifiedBaseTypeChecks() {
    assertDoesNotThrow(() -> TypeChecker.checkProject(CFG, USE));
  }

  @Test
  void qualifiedBaseEvaluatesCorrectly() {
    // { Cfg.defaults | width = 800 } updates Cfg's record: 800 + 50 = 850.
    assertEquals("850", Show.plain(Project.load(CFG, USE).value("UseCfg", "out")));
  }

  @Test
  void fieldAccessBaseAlsoWorks() {
    // A non-qualified non-local base (a field access) goes through the same desugar.
    String src =
        """
        module M exposing (out)
        wrap = { inner = { a = 1, b = 2 } }
        out = .a { wrap.inner | a = 9 }
        """;
    assertEquals("9", Show.plain(Project.load(src).value("M", "out")));
  }

  @Test
  void simpleLocalBaseAndPlainLiteralStillParse() {
    // Regression: the new branch must not disturb the simple `{ x | … }` update or a plain literal.
    String src =
        """
        module M exposing (a, b)
        a = let r = { x = 1, y = 2 } in { r | x = 9 }
        b = { x = 3, y = 4 }
        """;
    assertDoesNotThrow(() -> TypeChecker.checkProject(src));
    assertEquals("{ x = 9, y = 2 }", Show.plain(Project.load(src).value("M", "a")));
    assertEquals("{ x = 3, y = 4 }", Show.plain(Project.load(src).value("M", "b")));
  }
}

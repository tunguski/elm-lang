package pl.matsuo.elm.lint;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the lint rules: NoDebug and NoUnused. */
class LinterTest {

  private List<Linter.Finding> lint(String src) {
    return Linter.lint(src);
  }

  @Test
  void flagsLeftoverDebugCalls() {
    var f = lint("main = Debug.log \"here\" (1 + 2)\n");
    assertTrue(f.stream().anyMatch(x -> x.rule().equals("NoDebug")), f.toString());
  }

  @Test
  void flagsUnusedTopLevelDefinitions() {
    // `helper` is defined, not exposed, and never used.
    var f = lint("module M exposing (main)\nhelper = 1\nmain = 2\n");
    assertTrue(
        f.stream().anyMatch(x -> x.rule().equals("NoUnused") && x.message().contains("helper")),
        f.toString());
  }

  @Test
  void usedAndExposedDefinitionsAreClean() {
    // `helper` is used by main; `also` is exposed — neither is flagged. No Debug.
    var f = lint("module M exposing (main, also)\nhelper n = n + 1\nalso = 0\nmain = helper 41\n");
    assertTrue(f.isEmpty(), f.toString());
  }

  @Test
  void parseErrorsAreNotLintFindings() {
    assertTrue(lint("main = (1 + \n").isEmpty()); // malformed -> no lint noise
  }
}

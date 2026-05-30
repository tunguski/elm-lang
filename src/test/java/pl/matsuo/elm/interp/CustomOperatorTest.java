package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.types.TypeChecker;

/** User/package-defined infix operators: definition `(op) a b = …` and use `a op b`. */
class CustomOperatorTest {

  @Test
  void interpreterRunsACustomOperator() {
    String src = "(+++) a b = a + b * 2\nmain = 1 +++ 2\n";
    assertEquals("5", Show.plain(Interpreter.load(src).value("main"))); // 1 + 2*2
  }

  @Test
  void customOperatorTypeChecks() {
    Map<String, String> t =
        TypeChecker.checkModule("(|>>) x f = f x\nmain = 21 |>> (\\n -> n * 2)\n");
    assertEquals("number", t.get("main"));
    assertTrue(t.containsKey("|>>"), t.toString());
  }

  @Test
  void customOperatorUsedAcrossDefinitionsResolves() {
    // `combine` uses (+++) defined later — the dependency must be tracked for ordering.
    String src =
        "combine xs = List.foldl (+++) 0 xs\n(+++) a b = a + b\nmain = combine [ 1, 2, 3, 4 ]\n";
    assertEquals("10", Show.plain(Interpreter.load(src).value("main")));
    assertEquals("number", TypeChecker.checkModule(src).get("main"));
  }
}

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

  // with the declared precedence: 1 ^^ (2 ~~ 3) = 1 + 6 = 7
  private static final String INFIX_MODULE =
      """
      add a b = a + b
      mul a b = a * b
      infix left 6 (^^) = add
      infix left 7 (~~) = mul
      main = 1 ^^ 2 ~~ 3
      """;

  @Test
  void infixDeclarationGivesOperatorItsDeclaredPrecedence() {
    // (~~) is declared tighter (7) than (^^) (6), so it binds first: add 1 (mul 2 3) = 7,
    // not (1 ^^ 2) ~~ 3 = 9 (which is what the default lowest-precedence fallback would give).
    assertEquals("7", Show.plain(Interpreter.load(INFIX_MODULE).value("main")));
    assertEquals("number", TypeChecker.checkModule(INFIX_MODULE).get("main"));
  }

  @Test
  void crossModuleOperatorUsesItsDeclaredPrecedence() {
    // (^^) is declared infix 6 in module Ops and (~~) infix 7; used in module Main they must bind by
    // those declarations (1 ^^ (2 ~~ 3) = 7), even though Main never declares them itself.
    String ops =
        """
        module Ops exposing (..)
        add a b = a + b
        mul a b = a * b
        infix left 6 (^^) = add
        infix left 7 (~~) = mul
        """;
    String main =
        """
        module Main exposing (..)
        import Ops exposing (..)
        main = 1 ^^ 2 ~~ 3
        """;
    assertEquals("7", Show.plain(Project.load(ops, main).value("Main", "main")));
  }

  @Test
  void customOperatorRunsInTheBytecodeVm() {
    assertEquals(
        "5",
        Show.plain(
            pl.matsuo.elm.bytecode.BytecodeInterpreter.load("(+++) a b = a + b * 2\nmain = 1 +++ 2\n")
                .value("main")));
    // The infix-declared, precedence-sensitive module agrees with the tree interpreter.
    assertEquals(
        Show.plain(Interpreter.load(INFIX_MODULE).value("main")),
        Show.plain(pl.matsuo.elm.bytecode.BytecodeInterpreter.load(INFIX_MODULE).value("main")));
  }
}

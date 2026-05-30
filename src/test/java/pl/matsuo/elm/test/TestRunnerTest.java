package pl.matsuo.elm.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Tests the Elm test runner (discovery of Test values, running expectations). */
class TestRunnerTest {

  @Test
  void runsTheBundledExampleSuite() {
    String suite = Resources.read("/elm/demos/example-test.elm");
    TestRunner.Result r = TestRunner.run(List.of(suite));
    assertEquals(0, r.exitCode(), r.report());
    assertEquals(7, r.passed(), r.report()); // 3 arithmetic + 3 list + 1 comparison
    assertEquals(0, r.failed(), r.report());
    assertTrue(r.report().contains("example › arithmetic › addition"), r.report());
  }

  @Test
  void reportsFailuresWithMessagesAndExitsNonZero() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (test, describe)
        suite =
            describe "demo"
                [ test "passes" (\\_ -> Expect.equal 2 (1 + 1))
                , test "fails" (\\_ -> Expect.equal 5 (1 + 1))
                ]
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(1, r.passed(), r.report());
    assertEquals(1, r.failed(), r.report());
    assertEquals(1, r.exitCode());
    assertTrue(r.report().contains("✗ demo › fails"), r.report());
    assertTrue(r.report().contains("but got 2"), r.report()); // the Expect.equal message
  }

  @Test
  void noTestsIsACleanPass() {
    TestRunner.Result r = TestRunner.run(List.of("module M exposing (..)\nanswer = 42\n"));
    assertEquals(0, r.passed());
    assertEquals(0, r.exitCode());
  }
}

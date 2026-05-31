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
    assertEquals(9, r.passed(), r.report()); // 3 arithmetic + 3 list + 1 comparison + 2 fuzz
    assertEquals(0, r.failed(), r.report());
    assertTrue(r.report().contains("example › arithmetic › addition"), r.report());
    assertTrue(r.report().contains("(100 passed)"), r.report()); // fuzz ran many inputs
  }

  @Test
  void fuzzTestReportsTheFailingInput() {
    // A false property: not every Int is positive. The runner must find a counterexample within its
    // sample budget, fail, and report the offending value via the `Given …` context.
    String src =
        """
        module T exposing (suite)
        import Expect
        import Fuzz
        import Test exposing (fuzz)
        suite = fuzz Fuzz.int "all ints are positive" (\\n -> Expect.greaterThan 0 n)
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(0, r.passed(), r.report());
    assertEquals(1, r.failed(), r.report());
    assertEquals(1, r.exitCode());
    assertTrue(r.report().contains("Given "), r.report()); // shows the counterexample
  }

  @Test
  void richerExpectMatchers() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (Test, describe, test)
        suite =
            describe "expect"
                [ test "within" (\\_ -> Expect.within 0.001 0.3 (0.1 + 0.2))
                , test "ok" (\\_ -> Expect.ok (Ok 5))
                , test "err" (\\_ -> Expect.err (Err "boom"))
                , test "all" (\\_ -> Expect.all [ Expect.atLeast 0, Expect.atMost 10 ] 5)
                ]
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(4, r.passed(), r.report());
    assertEquals(0, r.failed(), r.report());
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
  void filterRunsOnlyMatchingTestsAndSkipsTheRest() {
    String suite = Resources.read("/elm/demos/example-test.elm");
    TestRunner.Result r =
        TestRunner.run(List.of(suite), new TestRunner.Options(100, 0x5eedL, "addition"));
    assertEquals(1, r.passed(), r.report()); // only "addition" runs
    assertEquals(0, r.failed(), r.report());
    assertTrue(r.skipped() > 0, r.report()); // the rest are skipped
    assertTrue(r.report().contains("skipped"), r.report());
  }

  @Test
  void fuzzRunCountIsConfigurable() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Fuzz
        import Test exposing (fuzz)
        suite = fuzz Fuzz.int "identity" (\\n -> Expect.equal n n)
        """;
    TestRunner.Result r = TestRunner.run(List.of(src), new TestRunner.Options(7, 1L, null));
    assertEquals(1, r.passed(), r.report());
    assertTrue(r.report().contains("(7 passed)"), r.report()); // honored the --fuzz count
  }

  @Test
  void coverageReportsExercisedAndUnexercisedFunctions() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (test)
        used n = n + 1
        unused n = n - 1
        suite = test "uses used" (\\_ -> Expect.equal 2 (used 1))
        """;
    TestRunner.Result r =
        TestRunner.run(List.of(src), new TestRunner.Options(100, 0x5eedL, null, true));
    assertEquals(0, r.failed(), r.report());
    assertTrue(r.report().contains("Coverage"), r.report());
    assertTrue(r.report().contains("✓ used"), r.report()); // exercised by the test
    assertTrue(r.report().contains("✗ unused"), r.report()); // never called
  }

  @Test
  void noTestsIsACleanPass() {
    TestRunner.Result r = TestRunner.run(List.of("module M exposing (..)\nanswer = 42\n"));
    assertEquals(0, r.passed());
    assertEquals(0, r.exitCode());
  }
}

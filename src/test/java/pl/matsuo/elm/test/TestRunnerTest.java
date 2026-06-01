package pl.matsuo.elm.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.util.Resources;

/** Tests the Elm test runner (discovery of Test values, running expectations). */
class TestRunnerTest {

  @Test
  void skipMarksTestsAsSkippedWithoutRunning() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (Test, describe, test, skip)
        suite =
            describe "s"
                [ test "runs" (\\_ -> Expect.equal 1 1)
                , skip (test "ignored" (\\_ -> Expect.equal 1 2))
                ]
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(1, r.passed(), r.report());
    assertEquals(0, r.failed(), r.report()); // the skipped failing test does NOT fail the run
    assertEquals(1, r.skipped(), r.report());
  }

  @Test
  void onlyFocusesTheRunOnMarkedTests() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (Test, describe, test, only)
        suite =
            describe "s"
                [ only (test "focused" (\\_ -> Expect.equal 2 2))
                , test "other" (\\_ -> Expect.equal 1 2)
                ]
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(1, r.passed(), r.report()); // only the focused test ran
    assertEquals(0, r.failed(), r.report()); // the (failing) unfocused test was skipped, not run
    assertEquals(1, r.skipped(), r.report());
  }

  @Test
  void todoIsReportedAsAFailure() {
    String src =
        """
        module T exposing (suite)
        import Test exposing (Test, describe, test, todo)
        import Expect
        suite =
            describe "s"
                [ test "done" (\\_ -> Expect.equal 1 1)
                , todo "implement the edge case"
                ]
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(1, r.passed(), r.report());
    assertEquals(1, r.failed(), r.report());
    assertTrue(r.report().contains("TODO: implement the edge case"), r.report());
  }

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
    // sample budget, fail, and report the offending value via the `Given …` context — shrunk to the
    // minimal failing Int, which for "> 0" is exactly 0.
    String src =
        """
        module T exposing (suite)
        import Expect
        import Fuzz
        import Test exposing (fuzz)
        suite = fuzz Fuzz.int "all ints are positive" (\\n -> Expect.greaterThan 0 n)
        """;
    TestRunner.Result r = TestRunner.run(List.of(src), new TestRunner.Options(100, 1234L, null));
    assertEquals(0, r.passed(), r.report());
    assertEquals(1, r.failed(), r.report());
    assertEquals(1, r.exitCode());
    assertTrue(r.report().contains("Given 0"), r.report()); // shrunk to the minimal counterexample
    assertTrue(r.report().contains("reproduce with --seed 1234"), r.report()); // reproducible
  }

  @Test
  void fuzzFailuresAreShrunkToAMinimalInput() {
    // "every Int is at most 3" fails for any n > 3; shrinking a random 9-digit failure must land on
    // the boundary value 4 — the smallest Int that still violates the property.
    String src =
        """
        module T exposing (suite)
        import Expect
        import Fuzz
        import Test exposing (fuzz)
        suite = fuzz Fuzz.int "ints are at most three" (\\n -> Expect.atMost 3 n)
        """;
    TestRunner.Result r = TestRunner.run(List.of(src), new TestRunner.Options(100, 99L, null));
    assertEquals(1, r.failed(), r.report());
    assertTrue(r.report().contains("Given 4"), r.report());
  }

  @Test
  void parallelAndSequentialFuzzingAgree() {
    // The same property reported at a parallel run count (>= the parallel threshold) and a
    // sequential sub-threshold count, with the same seed, must report the same minimal failing input
    // — parallel evaluation reports the lowest-index failure, exactly as sequential does.
    String src =
        """
        module T exposing (suite)
        import Expect
        import Fuzz
        import Test exposing (fuzz)
        suite = fuzz Fuzz.int "ints are at most three" (\\n -> Expect.atMost 3 n)
        """;
    TestRunner.Result parallel = TestRunner.run(List.of(src), new TestRunner.Options(200, 99L, null));
    TestRunner.Result sequential = TestRunner.run(List.of(src), new TestRunner.Options(50, 99L, null));
    assertEquals(1, parallel.failed(), parallel.report());
    assertEquals(1, sequential.failed(), sequential.report());
    assertTrue(parallel.report().contains("Given 4"), parallel.report());
    assertTrue(sequential.report().contains("Given 4"), sequential.report());
    // Re-running the parallel suite reports the same failing input every time (timing aside).
    assertTrue(
        TestRunner.run(List.of(src), new TestRunner.Options(200, 99L, null)).report().contains("Given 4"),
        "parallel fuzzing is deterministic");
  }

  @Test
  void fuzzShrinksListsToTheSmallestFailingList() {
    // "every list has length <= 2" fails for longer lists; shrinking drops elements down to the
    // minimal failing length of 3 (the elements themselves shrink toward 0 too).
    String src =
        """
        module T exposing (suite)
        import Expect
        import Fuzz
        import Test exposing (fuzz)
        suite = fuzz (Fuzz.list Fuzz.int) "short lists" (\\xs -> Expect.atMost 2 (List.length xs))
        """;
    TestRunner.Result r = TestRunner.run(List.of(src), new TestRunner.Options(200, 7L, null));
    assertEquals(1, r.failed(), r.report());
    assertTrue(r.report().contains("Given [0,0,0]"), r.report()); // 3 zeros: minimal failing list
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
                , test "equalLists" (\\_ -> Expect.equalLists [ 1, 2, 3 ] (List.map (\\n -> n + 1) [ 0, 1, 2 ]))
                ]
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(5, r.passed(), r.report());
    assertEquals(0, r.failed(), r.report());
  }

  @Test
  void equalListsReportsLengthMismatch() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (test)
        suite = test "lists" (\\_ -> Expect.equalLists [ 1, 2, 3 ] [ 1, 2 ])
        """;
    TestRunner.Result r = TestRunner.run(List.of(src));
    assertEquals(1, r.failed(), r.report());
    assertTrue(r.report().contains("list lengths differ"), r.report());
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
  void machineReadableReporters() {
    String src =
        """
        module T exposing (suite)
        import Expect
        import Test exposing (describe, test)
        suite =
            describe "demo"
                [ test "ok" (\\_ -> Expect.equal 2 (1 + 1))
                , test "bad" (\\_ -> Expect.equal 5 (1 + 1))
                ]
        """;
    String tap = TestRunner.run(List.of(src), new TestRunner.Options(100, 0L, null, false, "tap")).report();
    assertTrue(tap.startsWith("TAP version 13"), tap);
    assertTrue(tap.contains("ok 1 - demo › ok"), tap);
    assertTrue(tap.contains("not ok 2 - demo › bad"), tap);

    String junit = TestRunner.run(List.of(src), new TestRunner.Options(100, 0L, null, false, "junit")).report();
    assertTrue(junit.contains("<testsuite") && junit.contains("tests=\"2\"") && junit.contains("failures=\"1\""), junit);
    assertTrue(junit.contains("<failure>"), junit);

    String json = TestRunner.run(List.of(src), new TestRunner.Options(100, 0L, null, false, "json")).report();
    assertTrue(json.contains("\"passed\":1") && json.contains("\"failed\":1"), json);
    assertTrue(json.contains("\"status\":\"fail\""), json);
  }

  @Test
  void noTestsIsACleanPass() {
    TestRunner.Result r = TestRunner.run(List.of("module M exposing (..)\nanswer = 42\n"));
    assertEquals(0, r.passed());
    assertEquals(0, r.exitCode());
  }
}

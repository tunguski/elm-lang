package pl.matsuo.elm.test;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmUnit;
import pl.matsuo.elm.util.Resources;

/**
 * Discovers and runs Elm tests. The bundled {@code Test}/{@code Expect} modules let a file expose
 * top-level {@code Test} values (built with {@code test}/{@code describe}); the runner evaluates
 * every such value on the JIT interpreter, applies each test body to {@code ()}, and reports each
 * {@code Expect.Expectation} as a pass or a failure with its message.
 */
public final class TestRunner {

  private TestRunner() {}

  /** The outcome of a run: counts and a human-readable report; non-zero exit iff something failed. */
  public record Result(int passed, int failed, int skipped, String report) {
    public int exitCode() {
      return failed == 0 ? 0 : 1;
    }
  }

  /** Run options: how many random inputs each {@code fuzz} test gets, the seed that makes those
   * inputs reproducible, an optional case-insensitive substring filter on a test's full path,
   * whether to report which top-level functions of the test files the suite exercised, and the
   * report format ({@code "console"} / {@code "tap"} / {@code "junit"} / {@code "json"}). */
  public record Options(int fuzzRuns, long seed, String filter, boolean coverage, String report) {
    public static final Options DEFAULTS = new Options(100, 0x5eedL, null, false, "console");

    public Options(int fuzzRuns, long seed, String filter) {
      this(fuzzRuns, seed, filter, false, "console");
    }

    public Options(int fuzzRuns, long seed, String filter, boolean coverage) {
      this(fuzzRuns, seed, filter, coverage, "console");
    }
  }

  /** One test's outcome. */
  private enum Outcome {
    PASS,
    FAIL,
    SKIP
  }

  /** A single test result: its full path, outcome, an optional failure message and an optional
   * detail (e.g. a fuzz test's run count). */
  private record Case(String name, Outcome outcome, String message, String detail) {}

  private static final String TEST_LIB = Resources.read("/elm/lib/Test.elm");
  private static final String EXPECT_LIB = Resources.read("/elm/lib/Expect.elm");
  private static final String FUZZ_LIB = Resources.read("/elm/lib/Fuzz.elm");

  /** Runs every top-level {@code Test} value found in {@code userSources} with default options. */
  public static Result run(List<String> userSources) {
    return run(userSources, Options.DEFAULTS);
  }

  /** Runs every top-level {@code Test} value found in {@code userSources}. */
  public static Result run(List<String> userSources, Options opts) {
    List<String> all = new ArrayList<>(userSources);
    all.add(TEST_LIB);
    all.add(EXPECT_LIB);
    all.add(FUZZ_LIB);
    Project project;
    if (opts.coverage()) {
      // Track only the user test files' modules (not the bundled Test/Expect/Fuzz libraries).
      java.util.Set<String> userModules = new java.util.HashSet<>();
      for (String src : userSources) {
        userModules.add(Parser.parseModule(src).name());
      }
      project = Project.loadWithCoverage(all, userModules);
    } else {
      project = Project.load(all.toArray(new String[0]));
    }

    List<Case> cases = new ArrayList<>();
    long start = System.nanoTime();
    for (String src : userSources) {
      Module m = Parser.parseModule(src);
      for (Decl d : m.decls()) {
        if (d instanceof Decl.Value v && v.params().isEmpty()) {
          Object val;
          try {
            val = project.value(m.name(), v.name());
          } catch (RuntimeException e) {
            continue; // not evaluable in isolation — not a test value
          }
          if (val instanceof ElmData t && isTest(t)) {
            walk("", t, cases, opts);
          }
        }
      }
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    int passed = (int) cases.stream().filter(c -> c.outcome() == Outcome.PASS).count();
    int failed = (int) cases.stream().filter(c -> c.outcome() == Outcome.FAIL).count();
    int skipped = (int) cases.stream().filter(c -> c.outcome() == Outcome.SKIP).count();
    String coverage = opts.coverage() ? project.coverageReport() : null;
    String report =
        switch (opts.report()) {
          case "tap" -> tap(cases);
          case "junit" -> junit(cases, ms);
          case "json" -> json(cases);
          default -> console(cases, passed, failed, skipped, ms, coverage);
        };
    return new Result(passed, failed, skipped, report);
  }

  /** Fuzz runs at or above this count are evaluated in parallel (the interpreter is thread-safe for
   * the pure property evaluation; below this the thread-pool overhead isn't worth it). */
  private static final int PARALLEL_THRESHOLD = 64;

  /** A fuzz failure for one input: the reason (the rendered offending value, or an error message),
   * and whether it came from a thrown error rather than a failed expectation. */
  private record Failure(String reason, boolean error) {}

  /** Evaluates the property on one seed; returns null if it passed, else the failure. */
  private static Failure fuzzOutcome(Object body, long seed) {
    Object expectation;
    try {
      expectation = Thunk.resolve(Apply.apply(body, seed));
    } catch (RuntimeException e) {
      return new Failure(e.getMessage(), true);
    }
    if (expectation instanceof ElmData ed && ed.ctor().equals("Pass")) {
      return null;
    }
    String reason =
        expectation instanceof ElmData ed && ed.args().length > 0
            ? String.valueOf(Thunk.resolve(ed.arg(0)))
            : "failed";
    return new Failure(reason, false);
  }

  private static boolean isTest(ElmData d) {
    return d.ctor().equals("UnitTest") || d.ctor().equals("Labeled") || d.ctor().equals("FuzzTest");
  }

  private static void walk(String prefix, ElmData t, List<Case> cases, Options opts) {
    switch (t.ctor()) {
      case "Labeled" -> {
        String label = String.valueOf(Thunk.resolve(t.arg(0)));
        String p = label.isEmpty() ? prefix : prefix + label + " › ";
        if (Thunk.resolve(t.arg(1)) instanceof ElmList kids) {
          for (Object kid : kids.toJava()) {
            if (Thunk.resolve(kid) instanceof ElmData kt) {
              walk(p, kt, cases, opts);
            }
          }
        }
      }
      case "UnitTest" -> {
        String desc = String.valueOf(Thunk.resolve(t.arg(0)));
        if (filteredOut(prefix + desc, opts)) {
          cases.add(new Case(prefix + desc, Outcome.SKIP, null, null));
          return;
        }
        Object expectation;
        try {
          expectation = Thunk.resolve(Apply.apply(Thunk.resolve(t.arg(1)), ElmUnit.INSTANCE));
        } catch (RuntimeException e) {
          cases.add(new Case(prefix + desc, Outcome.FAIL, "error: " + e.getMessage(), null));
          return;
        }
        cases.add(record(prefix + desc, expectation));
      }
      case "FuzzTest" -> {
        String desc = String.valueOf(Thunk.resolve(t.arg(0)));
        if (filteredOut(prefix + desc, opts)) {
          cases.add(new Case(prefix + desc, Outcome.SKIP, null, null));
          return;
        }
        Object body = Thunk.resolve(t.arg(1)); // Int -> Expectation
        // Deterministic seed sequence from the master seed (independent of evaluation order).
        long[] seeds = new long[opts.fuzzRuns()];
        java.util.Random rng = new java.util.Random(opts.seed());
        for (int i = 0; i < seeds.length; i++) {
          seeds[i] = rng.nextInt(); // a 32-bit Elm Int the Fuzzer scrambles
        }

        // Evaluate the property over every seed and report the LOWEST-index failure — the same input
        // a sequential "stop at the first failure" run would report. Coverage tracking mutates shared
        // state, so it forces sequential evaluation; otherwise large runs go in parallel.
        boolean parallel = !opts.coverage() && seeds.length >= PARALLEL_THRESHOLD;
        Failure failure;
        if (parallel) {
          java.util.concurrent.ConcurrentSkipListMap<Integer, Failure> fails =
              new java.util.concurrent.ConcurrentSkipListMap<>();
          java.util.stream.IntStream.range(0, seeds.length)
              .parallel()
              .forEach(
                  i -> {
                    Failure f = fuzzOutcome(body, seeds[i]);
                    if (f != null) {
                      fails.put(i, f);
                    }
                  });
          failure = fails.isEmpty() ? null : fails.firstEntry().getValue();
        } else {
          failure = null;
          for (int i = 0; i < seeds.length && failure == null; i++) {
            failure = fuzzOutcome(body, seeds[i]);
          }
        }

        if (failure == null) {
          cases.add(new Case(prefix + desc, Outcome.PASS, null, opts.fuzzRuns() + " passed"));
        } else if (failure.error) {
          cases.add(new Case(prefix + desc, Outcome.FAIL, "error: " + failure.reason, null));
        } else {
          // The whole run is deterministic from the master seed, so re-running with it reproduces it.
          String reason = failure.reason + " (reproduce with --seed " + opts.seed() + ")";
          cases.add(new Case(prefix + desc + " (fuzz)", Outcome.FAIL, reason, null));
        }
      }
      default -> {}
    }
  }

  /** Whether a test's full path fails the (case-insensitive substring) filter, if one is set. */
  private static boolean filteredOut(String fullPath, Options opts) {
    return opts.filter() != null
        && !fullPath.toLowerCase().contains(opts.filter().toLowerCase());
  }

  /** Turns a resolved {@code Expectation} (Pass / Fail message) into a pass or failure case. */
  private static Case record(String name, Object expectation) {
    if (expectation instanceof ElmData ed && ed.ctor().equals("Pass")) {
      return new Case(name, Outcome.PASS, null, null);
    }
    String reason =
        expectation instanceof ElmData ed && ed.args().length > 0
            ? String.valueOf(Thunk.resolve(ed.arg(0)))
            : "failed";
    return new Case(name, Outcome.FAIL, reason, null);
  }

  // --- reporters ---------------------------------------------------------

  private static String console(
      List<Case> cases, int passed, int failed, int skipped, long ms, String coverage) {
    StringBuilder b = new StringBuilder();
    for (Case c : cases) {
      if (c.outcome() == Outcome.SKIP) {
        continue;
      }
      if (c.outcome() == Outcome.PASS) {
        b.append("✓ ").append(c.name());
        if (c.detail() != null) {
          b.append(" (").append(c.detail()).append(")");
        }
        b.append("\n");
      } else {
        b.append("✗ ").append(c.name()).append("\n    ")
            .append(c.message().replace("\n", "\n    ")).append("\n");
      }
    }
    b.append("\n").append(passed + failed).append(" tests, ").append(passed).append(" passed, ")
        .append(failed).append(" failed");
    if (skipped > 0) {
      b.append(", ").append(skipped).append(" skipped");
    }
    b.append(" (in ").append(ms).append(" ms)\n");
    if (coverage != null) {
      b.append("\nCoverage (test-file functions):\n").append(coverage);
    }
    return b.toString();
  }

  /** Test Anything Protocol (skips counted as `ok … # SKIP`). */
  private static String tap(List<Case> cases) {
    StringBuilder b = new StringBuilder("TAP version 13\n1..").append(cases.size()).append("\n");
    for (int i = 0; i < cases.size(); i++) {
      Case c = cases.get(i);
      int n = i + 1;
      switch (c.outcome()) {
        case PASS -> b.append("ok ").append(n).append(" - ").append(c.name()).append("\n");
        case SKIP -> b.append("ok ").append(n).append(" - ").append(c.name()).append(" # SKIP\n");
        case FAIL ->
            b.append("not ok ").append(n).append(" - ").append(c.name()).append("\n")
                .append("  ---\n  message: ").append(c.message().replace("\n", " ")).append("\n  ...\n");
      }
    }
    return b.toString();
  }

  /** JUnit XML (a single <testsuite>), for CI ingestion. */
  private static String junit(List<Case> cases, long ms) {
    long failed = cases.stream().filter(c -> c.outcome() == Outcome.FAIL).count();
    long skipped = cases.stream().filter(c -> c.outcome() == Outcome.SKIP).count();
    StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    b.append("<testsuite name=\"elm-test\" tests=\"").append(cases.size()).append("\" failures=\"")
        .append(failed).append("\" skipped=\"").append(skipped).append("\" time=\"")
        .append(ms / 1000.0).append("\">\n");
    for (Case c : cases) {
      b.append("  <testcase name=\"").append(xml(c.name())).append("\">");
      if (c.outcome() == Outcome.FAIL) {
        b.append("<failure>").append(xml(c.message())).append("</failure>");
      } else if (c.outcome() == Outcome.SKIP) {
        b.append("<skipped/>");
      }
      b.append("</testcase>\n");
    }
    return b.append("</testsuite>\n").toString();
  }

  /** A compact JSON object: counts plus a per-test array. */
  private static String json(List<Case> cases) {
    StringBuilder b = new StringBuilder("{\"tests\":[");
    for (int i = 0; i < cases.size(); i++) {
      Case c = cases.get(i);
      if (i > 0) {
        b.append(",");
      }
      b.append("{\"name\":\"").append(jsonStr(c.name())).append("\",\"status\":\"")
          .append(c.outcome().name().toLowerCase()).append("\"");
      if (c.message() != null) {
        b.append(",\"message\":\"").append(jsonStr(c.message())).append("\"");
      }
      b.append("}");
    }
    long pass = cases.stream().filter(c -> c.outcome() == Outcome.PASS).count();
    long fail = cases.stream().filter(c -> c.outcome() == Outcome.FAIL).count();
    long skip = cases.stream().filter(c -> c.outcome() == Outcome.SKIP).count();
    return b.append("],\"passed\":").append(pass).append(",\"failed\":").append(fail)
        .append(",\"skipped\":").append(skip).append("}\n").toString();
  }

  private static String xml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  private static String jsonStr(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
  }
}

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
   * inputs reproducible, an optional case-insensitive substring filter on a test's full path, and
   * whether to report which top-level functions of the test files the suite exercised. */
  public record Options(int fuzzRuns, long seed, String filter, boolean coverage) {
    public static final Options DEFAULTS = new Options(100, 0x5eedL, null, false);

    public Options(int fuzzRuns, long seed, String filter) {
      this(fuzzRuns, seed, filter, false);
    }
  }

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

    StringBuilder report = new StringBuilder();
    int[] counts = {0, 0, 0}; // passed, failed, skipped
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
            walk("", t, report, counts, opts);
          }
        }
      }
    }
    long ms = (System.nanoTime() - start) / 1_000_000;
    report
        .append("\n")
        .append(counts[0] + counts[1])
        .append(" tests, ")
        .append(counts[0])
        .append(" passed, ")
        .append(counts[1])
        .append(" failed");
    if (counts[2] > 0) {
      report.append(", ").append(counts[2]).append(" skipped");
    }
    report.append(" (in ").append(ms).append(" ms)\n");
    if (opts.coverage()) {
      report.append("\nCoverage (test-file functions):\n").append(project.coverageReport());
    }
    return new Result(counts[0], counts[1], counts[2], report.toString());
  }

  private static boolean isTest(ElmData d) {
    return d.ctor().equals("UnitTest") || d.ctor().equals("Labeled") || d.ctor().equals("FuzzTest");
  }

  private static void walk(String prefix, ElmData t, StringBuilder report, int[] counts, Options opts) {
    switch (t.ctor()) {
      case "Labeled" -> {
        String label = String.valueOf(Thunk.resolve(t.arg(0)));
        String p = label.isEmpty() ? prefix : prefix + label + " › ";
        if (Thunk.resolve(t.arg(1)) instanceof ElmList kids) {
          for (Object kid : kids.toJava()) {
            if (Thunk.resolve(kid) instanceof ElmData kt) {
              walk(p, kt, report, counts, opts);
            }
          }
        }
      }
      case "UnitTest" -> {
        String desc = String.valueOf(Thunk.resolve(t.arg(0)));
        if (filteredOut(prefix + desc, opts)) {
          counts[2]++;
          return;
        }
        Object expectation;
        try {
          expectation = Thunk.resolve(Apply.apply(Thunk.resolve(t.arg(1)), ElmUnit.INSTANCE));
        } catch (RuntimeException e) {
          fail(prefix, desc, "error: " + e.getMessage(), report, counts);
          return;
        }
        record(prefix, desc, expectation, report, counts);
      }
      case "FuzzTest" -> {
        String desc = String.valueOf(Thunk.resolve(t.arg(0)));
        if (filteredOut(prefix + desc, opts)) {
          counts[2]++;
          return;
        }
        Object body = Thunk.resolve(t.arg(1)); // Int -> Expectation
        // Replay the property over many deterministic seeds; report the first failing input.
        java.util.Random seeds = new java.util.Random(opts.seed());
        for (int i = 0; i < opts.fuzzRuns(); i++) {
          long seed = seeds.nextInt(); // a 32-bit Elm Int the Fuzzer scrambles
          Object expectation;
          try {
            expectation = Thunk.resolve(Apply.apply(body, seed));
          } catch (RuntimeException e) {
            fail(prefix, desc, "error: " + e.getMessage(), report, counts);
            return;
          }
          if (!(expectation instanceof ElmData ed && ed.ctor().equals("Pass"))) {
            String reason =
                expectation instanceof ElmData ed && ed.args().length > 0
                    ? String.valueOf(Thunk.resolve(ed.arg(0)))
                    : "failed";
            fail(prefix, desc + " (fuzz)", reason, report, counts);
            return;
          }
        }
        counts[0]++;
        report.append("✓ ").append(prefix).append(desc).append(" (").append(opts.fuzzRuns()).append(" passed)\n");
      }
      default -> {}
    }
  }

  /** Whether a test's full path fails the (case-insensitive substring) filter, if one is set. */
  private static boolean filteredOut(String fullPath, Options opts) {
    return opts.filter() != null
        && !fullPath.toLowerCase().contains(opts.filter().toLowerCase());
  }

  /** Records a resolved {@code Expectation} (Pass / Fail message) as a pass or a located failure. */
  private static void record(
      String prefix, String desc, Object expectation, StringBuilder report, int[] counts) {
    if (expectation instanceof ElmData ed && ed.ctor().equals("Pass")) {
      counts[0]++;
      report.append("✓ ").append(prefix).append(desc).append("\n");
    } else {
      String reason =
          expectation instanceof ElmData ed && ed.args().length > 0
              ? String.valueOf(Thunk.resolve(ed.arg(0)))
              : "failed";
      fail(prefix, desc, reason, report, counts);
    }
  }

  private static void fail(
      String prefix, String desc, String reason, StringBuilder report, int[] counts) {
    counts[1]++;
    report
        .append("✗ ")
        .append(prefix)
        .append(desc)
        .append("\n    ")
        .append(reason.replace("\n", "\n    "))
        .append("\n");
  }
}

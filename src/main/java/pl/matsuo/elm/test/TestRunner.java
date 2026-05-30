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
  public record Result(int passed, int failed, String report) {
    public int exitCode() {
      return failed == 0 ? 0 : 1;
    }
  }

  private static final String TEST_LIB = Resources.read("/elm/lib/Test.elm");
  private static final String EXPECT_LIB = Resources.read("/elm/lib/Expect.elm");

  /** Runs every top-level {@code Test} value found in {@code userSources}. */
  public static Result run(List<String> userSources) {
    List<String> all = new ArrayList<>(userSources);
    all.add(TEST_LIB);
    all.add(EXPECT_LIB);
    Project project = Project.load(all.toArray(new String[0]));

    StringBuilder report = new StringBuilder();
    int[] counts = {0, 0}; // passed, failed
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
            walk("", t, report, counts);
          }
        }
      }
    }
    report
        .append("\n")
        .append(counts[0] + counts[1])
        .append(" tests, ")
        .append(counts[0])
        .append(" passed, ")
        .append(counts[1])
        .append(" failed\n");
    return new Result(counts[0], counts[1], report.toString());
  }

  private static boolean isTest(ElmData d) {
    return d.ctor().equals("UnitTest") || d.ctor().equals("Labeled");
  }

  private static void walk(String prefix, ElmData t, StringBuilder report, int[] counts) {
    switch (t.ctor()) {
      case "Labeled" -> {
        String label = String.valueOf(Thunk.resolve(t.arg(0)));
        String p = label.isEmpty() ? prefix : prefix + label + " › ";
        if (Thunk.resolve(t.arg(1)) instanceof ElmList kids) {
          for (Object kid : kids.toJava()) {
            if (Thunk.resolve(kid) instanceof ElmData kt) {
              walk(p, kt, report, counts);
            }
          }
        }
      }
      case "UnitTest" -> {
        String desc = String.valueOf(Thunk.resolve(t.arg(0)));
        Object expectation;
        try {
          expectation = Thunk.resolve(Apply.apply(Thunk.resolve(t.arg(1)), ElmUnit.INSTANCE));
        } catch (RuntimeException e) {
          counts[1]++;
          report.append("✗ ").append(prefix).append(desc).append("\n    error: ").append(e.getMessage()).append("\n");
          return;
        }
        if (expectation instanceof ElmData ed && ed.ctor().equals("Pass")) {
          counts[0]++;
          report.append("✓ ").append(prefix).append(desc).append("\n");
        } else {
          counts[1]++;
          String reason =
              expectation instanceof ElmData ed && ed.args().length > 0
                  ? String.valueOf(Thunk.resolve(ed.arg(0)))
                  : "failed";
          report
              .append("✗ ")
              .append(prefix)
              .append(desc)
              .append("\n    ")
              .append(reason.replace("\n", "\n    "))
              .append("\n");
        }
      }
      default -> {}
    }
  }
}

package pl.matsuo.elm.script;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;

/**
 * Interprets the {@code Posix.Io} effect description produced by a script's {@code main}, performing
 * the real side effects (stdout, stdin, files, process arguments) and returning the exit code. The
 * {@code Io} value is a free structure of constructors (see {@code Posix.elm}); effects that produce
 * a value carry a continuation function which is applied to the result and yields the next step.
 */
public final class ScriptRunner {

  private ScriptRunner() {}

  /** Walks the {@code io} description to completion, returning the process exit code. */
  public static int run(Object io, List<String> args, BufferedReader in, PrintStream out) {
    Object cur = Thunk.resolve(io);
    while (true) {
      if (!(cur instanceof ElmData d)) {
        throw new ElmRuntimeError("script main must be a Posix.Io value, got: " + cur);
      }
      switch (d.ctor()) {
        case "Print" -> {
          out.println(str(d.arg(0)));
          cur = Thunk.resolve(d.arg(1));
        }
        case "ReadLine" -> {
          String line;
          try {
            line = in.readLine();
          } catch (IOException e) {
            line = null;
          }
          cur = Thunk.resolve(Apply.apply(d.arg(0), line == null ? "" : line));
        }
        case "ReadFile" -> {
          String path = str(d.arg(0));
          Object result;
          try {
            result = ok(Files.readString(Path.of(path), StandardCharsets.UTF_8));
          } catch (IOException | RuntimeException e) {
            result = err(e.getMessage() == null ? e.toString() : e.getMessage());
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "WriteFile" -> {
          try {
            Files.writeString(Path.of(str(d.arg(0))), str(d.arg(1)), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new ElmRuntimeError("could not write " + str(d.arg(0)) + ": " + e.getMessage());
          }
          cur = Thunk.resolve(d.arg(2));
        }
        case "GetArgs" -> cur = Thunk.resolve(Apply.apply(d.arg(0), ElmList.fromJava(args)));
        case "GetEnv" -> {
          String v = System.getenv(str(d.arg(0)));
          Object maybe =
              v == null
                  ? new ElmData("Nothing", new Object[0])
                  : new ElmData("Just", new Object[] {v});
          cur = Thunk.resolve(Apply.apply(d.arg(1), maybe));
        }
        case "ListDir" -> {
          Object result;
          try (var entries = Files.list(Path.of(str(d.arg(0))))) {
            java.util.List<Object> names =
                entries
                    .map(p -> (Object) p.getFileName().toString())
                    .sorted(java.util.Comparator.comparing(Object::toString))
                    .toList();
            result = ok(ElmList.fromJava(names));
          } catch (IOException | RuntimeException e) {
            result = err(e.getMessage() == null ? e.toString() : e.getMessage());
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Exit" -> {
          return (int) ((Number) Thunk.resolve(d.arg(0))).longValue();
        }
        case "Done" -> {
          return 0;
        }
        default -> throw new ElmRuntimeError("unknown Posix.Io effect: " + d.ctor());
      }
    }
  }

  private static String str(Object o) {
    return (String) Thunk.resolve(o);
  }

  private static ElmData ok(Object v) {
    return new ElmData("Ok", new Object[] {v});
  }

  private static ElmData err(String msg) {
    return new ElmData("Err", new Object[] {msg});
  }
}

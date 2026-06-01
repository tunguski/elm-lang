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
    // Reproducible when ELM_SCRIPT_SEED is set (e.g. in tests); otherwise nondeterministic.
    String seed = System.getenv("ELM_SCRIPT_SEED");
    java.util.Random rng = seed == null ? new java.util.Random() : new java.util.Random(Long.parseLong(seed));
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
        case "Ls" -> {
          Object result;
          try (var entries = Files.list(Path.of(str(d.arg(0))))) {
            List<Object> items =
                entries
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> (Object) entry(p))
                    .toList();
            result = ok(ElmList.fromJava(items));
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Walk" -> {
          Object result;
          try (var paths = Files.walk(Path.of(str(d.arg(0))))) {
            List<Object> items =
                paths
                    .sorted(java.util.Comparator.comparing(Path::toString))
                    .map(p -> (Object) entry(p))
                    .toList();
            result = ok(ElmList.fromJava(items));
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Grep" -> {
          String pattern = str(d.arg(0));
          Object result;
          try {
            List<String> lines = Files.readAllLines(Path.of(str(d.arg(1))), StandardCharsets.UTF_8);
            List<Object> matches = new java.util.ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
              if (lines.get(i).contains(pattern)) {
                matches.add(match(i + 1L, lines.get(i)));
              }
            }
            result = ok(ElmList.fromJava(matches));
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(2), result));
        }
        case "Wc" -> {
          Object result;
          try {
            String text = Files.readString(Path.of(str(d.arg(0))), StandardCharsets.UTF_8);
            long lines = text.isEmpty() ? 0 : text.lines().count();
            long words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
            long chars = text.codePointCount(0, text.length());
            result = ok(counts(lines, words, chars));
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Pwd" -> {
          String dir = Path.of("").toAbsolutePath().toString();
          cur = Thunk.resolve(Apply.apply(d.arg(0), dir));
        }
        case "Mkdir" -> {
          String path = str(d.arg(0));
          Object result;
          try {
            Files.createDirectories(Path.of(path));
            result = ok(path);
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Rm" -> {
          String path = str(d.arg(0));
          Object result;
          try {
            deleteRecursively(Path.of(path));
            result = ok(path);
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Cp" -> {
          String dest = str(d.arg(1));
          Object result;
          try {
            Files.copy(
                Path.of(str(d.arg(0))),
                Path.of(dest),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            result = ok(dest);
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(2), result));
        }
        case "Mv" -> {
          String dest = str(d.arg(1));
          Object result;
          try {
            Files.move(
                Path.of(str(d.arg(0))),
                Path.of(dest),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            result = ok(dest);
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(2), result));
        }
        case "EnvAll" -> {
          List<Object> pairs =
              System.getenv().entrySet().stream()
                  .sorted(java.util.Map.Entry.comparingByKey())
                  .map(
                      e ->
                          (Object)
                              new pl.matsuo.elm.runtime.ElmTuple(
                                  new Object[] {e.getKey(), e.getValue()}))
                  .toList();
          cur = Thunk.resolve(Apply.apply(d.arg(0), ElmList.fromJava(pairs)));
        }
        case "Which" -> {
          Object found = which(str(d.arg(0)));
          cur = Thunk.resolve(Apply.apply(d.arg(1), found));
        }
        case "Stat" -> {
          String path = str(d.arg(0));
          Object result;
          Path p = Path.of(path);
          if (Files.exists(p)) {
            result = ok(entry(p));
          } else {
            result = err("no such file: " + path);
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Du" -> {
          Object result;
          try (var paths = Files.walk(Path.of(str(d.arg(0))))) {
            long total = 0;
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
              total += Files.size(p);
            }
            result = ok(total);
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Touch" -> {
          String path = str(d.arg(0));
          Object result;
          try {
            Path p = Path.of(path);
            if (Files.exists(p)) {
              Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            } else {
              Files.createFile(p);
            }
            result = ok(path);
          } catch (IOException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(1), result));
        }
        case "Head" -> {
          int n = (int) ((Number) Thunk.resolve(d.arg(0))).longValue();
          cur = Thunk.resolve(Apply.apply(d.arg(2), lines(str(d.arg(1)), ls -> ls.stream().limit(Math.max(0, n)).toList())));
        }
        case "Tail" -> {
          int n = (int) ((Number) Thunk.resolve(d.arg(0))).longValue();
          cur = Thunk.resolve(Apply.apply(d.arg(2), lines(str(d.arg(1)), ls -> ls.subList(Math.max(0, ls.size() - Math.max(0, n)), ls.size()))));
        }
        case "SortLines" ->
            cur =
                Thunk.resolve(
                    Apply.apply(
                        d.arg(1),
                        lines(str(d.arg(0)), ls -> ls.stream().sorted().toList())));
        case "UniqLines" ->
            cur = Thunk.resolve(Apply.apply(d.arg(1), lines(str(d.arg(0)), ScriptRunner::dropAdjacentDuplicates)));
        case "Exec" -> {
          Object result;
          try {
            java.util.List<String> argv = new java.util.ArrayList<>();
            argv.add(str(d.arg(0)));
            for (Object a : ((ElmList) Thunk.resolve(d.arg(1))).toJava()) {
              argv.add((String) Thunk.resolve(a));
            }
            Process pr = new ProcessBuilder(argv).start();
            String so = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String se = new String(pr.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = pr.waitFor();
            java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("exitCode", (long) exit);
            fields.put("stdout", so);
            fields.put("stderr", se);
            result = ok(new pl.matsuo.elm.runtime.ElmRecord(fields));
          } catch (IOException | InterruptedException | RuntimeException e) {
            result = err(message(e));
          }
          cur = Thunk.resolve(Apply.apply(d.arg(2), result));
        }
        case "Now" -> {
          cur = Thunk.resolve(Apply.apply(d.arg(0), nowMillis()));
        }
        case "RandomInt" -> {
          long lo = ((Number) Thunk.resolve(d.arg(0))).longValue();
          long hi = ((Number) Thunk.resolve(d.arg(1))).longValue();
          long value = hi <= lo ? lo : lo + Math.floorMod(rng.nextLong(), hi - lo + 1);
          cur = Thunk.resolve(Apply.apply(d.arg(2), value));
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

  /** Current epoch millis, pinned by {@code ELM_SCRIPT_NOW} when set (for reproducible tests). */
  private static long nowMillis() {
    String fixed = System.getenv("ELM_SCRIPT_NOW");
    return fixed == null ? System.currentTimeMillis() : Long.parseLong(fixed);
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

  private static String message(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  /** Reads a file's lines, applies {@code transform}, and wraps the result as {@code Ok (List String)}
   * (or {@code Err message} on failure) — the shared shape of head/tail/sort/uniq. */
  private static ElmData lines(
      String path, java.util.function.Function<List<String>, List<String>> transform) {
    try {
      List<String> all = Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
      List<Object> out = new java.util.ArrayList<>(transform.apply(all));
      return ok(ElmList.fromJava(out));
    } catch (IOException | RuntimeException e) {
      return err(message(e));
    }
  }

  /** Drops runs of equal adjacent lines (like {@code uniq}). */
  private static List<String> dropAdjacentDuplicates(List<String> in) {
    List<String> out = new java.util.ArrayList<>();
    String prev = null;
    for (String line : in) {
      if (!line.equals(prev)) {
        out.add(line);
      }
      prev = line;
    }
    return out;
  }

  /** Builds a {@code Posix.Entry} record from a path, reading its metadata (lenient on errors). */
  private static pl.matsuo.elm.runtime.ElmRecord entry(Path p) {
    boolean isDir = Files.isDirectory(p);
    long size = 0;
    long modified = 0;
    try {
      size = Files.isRegularFile(p) ? Files.size(p) : 0;
      modified = Files.getLastModifiedTime(p).toMillis();
    } catch (IOException ignored) {
      // Metadata may be unreadable (e.g. a vanished file); report zeroes rather than failing.
    }
    Path name = p.getFileName();
    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("name", name == null ? p.toString() : name.toString());
    fields.put("path", p.toString());
    fields.put("isDir", isDir);
    fields.put("size", size);
    fields.put("modified", modified);
    return new pl.matsuo.elm.runtime.ElmRecord(fields);
  }

  /** Builds a {@code Posix.Match} record (1-based line number + line text). */
  private static pl.matsuo.elm.runtime.ElmRecord match(long lineNumber, String line) {
    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("lineNumber", lineNumber);
    fields.put("line", line);
    return new pl.matsuo.elm.runtime.ElmRecord(fields);
  }

  /** Builds a {@code Posix.Counts} record. */
  private static pl.matsuo.elm.runtime.ElmRecord counts(long lines, long words, long chars) {
    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("lines", lines);
    fields.put("words", words);
    fields.put("chars", chars);
    return new pl.matsuo.elm.runtime.ElmRecord(fields);
  }

  /** Deletes a file, or a directory and its contents (children first), like {@code rm -r}. */
  private static void deleteRecursively(Path root) throws IOException {
    if (Files.isDirectory(root)) {
      try (var children = Files.list(root)) {
        for (Path child : children.toList()) {
          deleteRecursively(child);
        }
      }
    }
    Files.deleteIfExists(root);
  }

  /** Looks up an executable on {@code PATH}, returning {@code Just absolutePath} or {@code Nothing}. */
  private static ElmData which(String name) {
    String path = System.getenv("PATH");
    if (path != null) {
      String[] exts = System.getProperty("os.name", "").toLowerCase().contains("win")
          ? new String[] {"", ".exe", ".bat", ".cmd"}
          : new String[] {""};
      for (String dir : path.split(java.io.File.pathSeparator)) {
        for (String ext : exts) {
          Path candidate = Path.of(dir, name + ext);
          if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return new ElmData("Just", new Object[] {candidate.toString()});
          }
        }
      }
    }
    return new ElmData("Nothing", new Object[0]);
  }
}

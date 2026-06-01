package pl.matsuo.elm.build;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.codegen.wasm.WasmCompiler;
import pl.matsuo.elm.codegen.wasm.WasmGc;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.test.TestRunner;

/**
 * Executes a build plan produced by the bundled {@code Build} library: an ordered list of
 * {@code Step}s, each a phase/module/goal label plus a list of {@code Task} values. Tasks are run in
 * order and the build stops at the first failure (fail-fast, Maven-style), returning that task's exit
 * code. The {@code Task} kinds map onto the toolchain (compile a module, run tests), the filesystem
 * (make/copy/remove/write) and an escape hatch ({@code Run} an external command).
 */
public final class BuildRunner {

  private BuildRunner() {}

  /** Walks a {@code List Step} (already computed by {@code Build.plan}), returning the exit code.
   * Relative task paths resolve against {@code baseDir} — the build file's directory, like Maven's
   * {@code pom.xml} dir — so a build runs the same regardless of the process working directory. */
  public static int run(Object planList, Path baseDir, PrintStream out) {
    for (Object stepObj : ((ElmList) Thunk.resolve(planList)).toJava()) {
      ElmRecord step = (ElmRecord) Thunk.resolve(stepObj);
      String phase = str(step.get("phase"));
      String module = str(step.get("moduleName"));
      String goal = str(step.get("goal"));
      out.println("[" + phase + "] " + module + " :: " + goal);
      for (Object taskObj : ((ElmList) Thunk.resolve(step.get("tasks"))).toJava()) {
        int code = runTask((ElmData) Thunk.resolve(taskObj), baseDir, out);
        if (code != 0) {
          out.println("BUILD FAILED — " + phase + ":" + module + ":" + goal + " (exit " + code + ")");
          return code;
        }
      }
    }
    out.println("BUILD SUCCESS");
    return 0;
  }

  /** Prints the plan — every phase/module/goal and the tasks it would run — without executing any of
   * them (a {@code --dry-run}). Always succeeds. */
  public static int dryRun(Object planList, PrintStream out) {
    for (Object stepObj : ((ElmList) Thunk.resolve(planList)).toJava()) {
      ElmRecord step = (ElmRecord) Thunk.resolve(stepObj);
      out.println(
          "[" + str(step.get("phase")) + "] " + str(step.get("moduleName")) + " :: "
              + str(step.get("goal")));
      for (Object taskObj : ((ElmList) Thunk.resolve(step.get("tasks"))).toJava()) {
        out.println("  - " + describe((ElmData) Thunk.resolve(taskObj)));
      }
    }
    out.println("(dry run — nothing executed)");
    return 0;
  }

  /** A one-line, human-readable description of a task (for {@code --dry-run}). */
  private static String describe(ElmData task) {
    return switch (task.ctor()) {
      case "Log" -> "log " + quote(str(task.arg(0)));
      case "MakeDir" -> "makeDir " + str(task.arg(0));
      case "Remove" -> "remove " + str(task.arg(0));
      case "WriteFile" -> "writeFile " + str(task.arg(0));
      case "Copy" -> "copy " + str(task.arg(0)) + " -> " + str(task.arg(1));
      case "Run" -> "exec " + str(task.arg(0)) + " " + String.join(" ", strings(task.arg(1)));
      case "Check" -> "check " + str(task.arg(0));
      case "CompileModule" ->
          "compile " + str(task.arg(1)) + " ("
              + str(((ElmData) Thunk.resolve(task.arg(0))).ctor()) + ") -> " + str(task.arg(2));
      case "RunTests" -> "test " + str(task.arg(0));
      default -> task.ctor();
    };
  }

  private static String quote(String s) {
    return "\"" + s + "\"";
  }

  /** Performs one task, returning its exit code (0 = success). */
  private static int runTask(ElmData task, Path baseDir, PrintStream out) {
    try {
      switch (task.ctor()) {
        case "Log" -> out.println("  " + str(task.arg(0)));
        case "MakeDir" -> Files.createDirectories(at(baseDir, str(task.arg(0))));
        case "Remove" -> deleteRecursively(at(baseDir, str(task.arg(0))));
        case "WriteFile" -> {
          Path target = at(baseDir, str(task.arg(0)));
          if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
          }
          Files.writeString(target, str(task.arg(1)), StandardCharsets.UTF_8);
        }
        case "Copy" -> copy(at(baseDir, str(task.arg(0))), at(baseDir, str(task.arg(1))));
        case "Run" -> {
          return exec(str(task.arg(0)), strings(task.arg(1)), baseDir, out);
        }
        case "Check" -> {
          return check(str(task.arg(0)), baseDir, out);
        }
        case "CompileModule" -> compile(str(((ElmData) Thunk.resolve(task.arg(0))).ctor()),
            str(task.arg(1)), str(task.arg(2)), baseDir, out);
        case "RunTests" -> {
          return runTests(str(task.arg(0)), baseDir, out);
        }
        default -> {
          out.println("  ! unknown task: " + task.ctor());
          return 1;
        }
      }
      return 0;
    } catch (IOException | InterruptedException | RuntimeException e) {
      out.println("  ! " + task.ctor() + " failed: " + message(e));
      return 1;
    }
  }

  /** Compiles {@code entry} to {@code target} with the named backend (JS page / wasm binary). */
  private static void compile(String backend, String entry, String target, Path baseDir, PrintStream out)
      throws IOException {
    String source = Files.readString(at(baseDir, entry), StandardCharsets.UTF_8);
    Path path = at(baseDir, target);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    switch (backend) {
      case "JS" -> Files.writeString(path, JsCompiler.htmlPage(source, null), StandardCharsets.UTF_8);
      case "Wasm" -> Files.write(path, WasmCompiler.moduleFromSource(source));
      case "WasmGc" -> Files.write(path, WasmGc.module(source));
      default -> throw new IllegalArgumentException("unknown backend: " + backend);
    }
    out.println("  compiled " + entry + " (" + backend + ") -> " + target);
  }

  /** Type-checks a module's entry file, returning 0 if it checks or 1 (with the error) otherwise. */
  private static int check(String entry, Path baseDir, PrintStream out) throws IOException {
    String source = Files.readString(at(baseDir, entry), StandardCharsets.UTF_8);
    try {
      pl.matsuo.elm.types.TypeChecker.checkModule(source);
      out.println("  ok " + entry + " type-checks");
      return 0;
    } catch (pl.matsuo.elm.error.ElmTypeError e) {
      out.println("  x type error in " + entry + ": " + message(e));
      return 1;
    }
  }

  /** Runs the tests under {@code dir} (a no-op if it is absent), returning the test exit code. */
  private static int runTests(String dir, Path baseDir, PrintStream out) throws IOException {
    Path root = at(baseDir, dir);
    if (!Files.isDirectory(root)) {
      out.println("  no tests in " + dir + " (skipped)");
      return 0;
    }
    List<String> sources = new ArrayList<>();
    try (var walk = Files.walk(root)) {
      for (Path p : walk.filter(p -> p.toString().endsWith(".elm")).sorted().toList()) {
        sources.add(Files.readString(p, StandardCharsets.UTF_8));
      }
    }
    if (sources.isEmpty()) {
      out.println("  no tests in " + dir + " (skipped)");
      return 0;
    }
    TestRunner.Result result = TestRunner.run(sources);
    out.print(result.report());
    return result.exitCode();
  }

  /** Runs an external command in {@code baseDir}, streaming its (merged) output; returns the exit code. */
  private static int exec(String cmd, List<String> args, Path baseDir, PrintStream out)
      throws IOException, InterruptedException {
    List<String> argv = new ArrayList<>();
    argv.add(cmd);
    argv.addAll(args);
    out.println("  $ " + String.join(" ", argv));
    ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
    if (baseDir != null) {
      pb.directory(baseDir.toFile());
    }
    Process p = pb.start();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int code = p.waitFor();
    if (!output.isEmpty()) {
      out.print(output);
    }
    return code;
  }

  /** Copies a file (or, recursively, a directory tree), creating parent directories. */
  private static void copy(Path src, Path dest) throws IOException {
    if (Files.isDirectory(src)) {
      try (var walk = Files.walk(src)) {
        for (Path p : walk.toList()) {
          Path rel = dest.resolve(src.relativize(p));
          if (Files.isDirectory(p)) {
            Files.createDirectories(rel);
          } else {
            if (rel.getParent() != null) {
              Files.createDirectories(rel.getParent());
            }
            Files.copy(p, rel, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
      return;
    }
    if (dest.getParent() != null) {
      Files.createDirectories(dest.getParent());
    }
    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    if (Files.isDirectory(root)) {
      try (var children = Files.list(root)) {
        for (Path child : children.toList()) {
          deleteRecursively(child);
        }
      }
    }
    Files.deleteIfExists(root);
  }

  /** Resolves a (possibly relative) task path against the build's base directory. */
  private static Path at(Path baseDir, String p) {
    Path path = Path.of(p);
    return baseDir == null || path.isAbsolute() ? path : baseDir.resolve(path);
  }

  private static List<String> strings(Object listValue) {
    List<String> out = new ArrayList<>();
    for (Object o : ((ElmList) Thunk.resolve(listValue)).toJava()) {
      out.add(str(o));
    }
    return out;
  }

  private static String str(Object o) {
    return (String) Thunk.resolve(o);
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }
}

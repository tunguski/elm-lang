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
    return run(planList, baseDir, false, out);
  }

  /** As {@link #run(Object, Path, PrintStream)}, optionally skipping {@code compile}/{@code archive}
   * tasks whose output is already newer than their inputs (incremental builds). */
  public static int run(Object planList, Path baseDir, boolean incremental, PrintStream out) {
    return run(planList, baseDir, incremental, false, out);
  }

  /** One planned step, materialised from the Elm plan: a phase/module/goal and its tasks. */
  private record PlannedStep(String phase, String module, String goal, List<ElmData> tasks) {}

  /**
   * Runs the plan. With {@code parallel}, modules within a phase build concurrently — within a phase
   * each module's work is independent (a module reads other modules' <em>sources</em>, never their
   * build output), and dependency order is already encoded across phases by {@code Build.plan}. Each
   * module's output is buffered and flushed in declared order so logs stay readable.
   */
  public static int run(Object planList, Path baseDir, boolean incremental, boolean parallel, PrintStream out) {
    List<PlannedStep> steps = materialize(planList);
    int i = 0;
    while (i < steps.size()) {
      String phase = steps.get(i).phase();
      int j = i;
      while (j < steps.size() && steps.get(j).phase().equals(phase)) {
        j++;
      }
      List<PlannedStep> phaseSteps = steps.subList(i, j);
      int code =
          parallel ? runPhaseParallel(phaseSteps, baseDir, incremental, out)
                   : runPhaseSequential(phaseSteps, baseDir, incremental, out);
      if (code != 0) {
        return code;
      }
      i = j;
    }
    out.println("BUILD SUCCESS");
    return 0;
  }

  private static List<PlannedStep> materialize(Object planList) {
    List<PlannedStep> steps = new ArrayList<>();
    for (Object stepObj : ((ElmList) Thunk.resolve(planList)).toJava()) {
      ElmRecord step = (ElmRecord) Thunk.resolve(stepObj);
      List<ElmData> tasks = new ArrayList<>();
      for (Object taskObj : ((ElmList) Thunk.resolve(step.get("tasks"))).toJava()) {
        tasks.add((ElmData) Thunk.resolve(taskObj));
      }
      steps.add(new PlannedStep(str(step.get("phase")), str(step.get("moduleName")), str(step.get("goal")), tasks));
    }
    return steps;
  }

  private static int runPhaseSequential(List<PlannedStep> steps, Path baseDir, boolean incremental, PrintStream out) {
    for (PlannedStep s : steps) {
      int code = runStep(s, baseDir, incremental, out);
      if (code != 0) {
        return code;
      }
    }
    return 0;
  }

  /** Runs a phase's modules concurrently (each module's steps stay sequential), preserving log order. */
  private static int runPhaseParallel(List<PlannedStep> steps, Path baseDir, boolean incremental, PrintStream out) {
    java.util.LinkedHashMap<String, List<PlannedStep>> byModule = new java.util.LinkedHashMap<>();
    for (PlannedStep s : steps) {
      byModule.computeIfAbsent(s.module(), k -> new ArrayList<>()).add(s);
    }
    if (byModule.size() <= 1) {
      return runPhaseSequential(steps, baseDir, incremental, out);
    }
    var pool = java.util.concurrent.Executors.newFixedThreadPool(
        Math.min(byModule.size(), Runtime.getRuntime().availableProcessors()));
    try {
      List<java.util.concurrent.Future<int[]>> futures = new ArrayList<>();
      List<java.io.ByteArrayOutputStream> buffers = new ArrayList<>();
      for (List<PlannedStep> group : byModule.values()) {
        var buf = new java.io.ByteArrayOutputStream();
        var ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
        buffers.add(buf);
        futures.add(pool.submit(() -> new int[] {runPhaseSequential(group, baseDir, incremental, ps)}));
      }
      int failure = 0;
      for (int k = 0; k < futures.size(); k++) {
        int code;
        try {
          code = futures.get(k).get()[0];
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
          Thread.currentThread().interrupt();
          code = 1;
        }
        out.print(buffers.get(k).toString(StandardCharsets.UTF_8));
        if (code != 0 && failure == 0) {
          failure = code;
        }
      }
      return failure;
    } finally {
      pool.shutdown();
    }
  }

  /** Runs one step's tasks in order, printing a header and a BUILD FAILED line on the first failure. */
  private static int runStep(PlannedStep s, Path baseDir, boolean incremental, PrintStream out) {
    out.println("[" + s.phase() + "] " + s.module() + " :: " + s.goal());
    for (ElmData task : s.tasks()) {
      int code = runTask(task, baseDir, incremental, out);
      if (code != 0) {
        out.println("BUILD FAILED — " + s.phase() + ":" + s.module() + ":" + s.goal() + " (exit " + code + ")");
        return code;
      }
    }
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
      case "Archive" -> "archive " + str(task.arg(0)) + " -> " + str(task.arg(1));
      case "Bundle" -> "bundle " + String.join(" + ", strings(task.arg(0))) + " -> " + str(task.arg(1));
      case "Run" -> "exec " + str(task.arg(0)) + " " + String.join(" ", strings(task.arg(1)));
      case "Check" -> "check " + String.join(", ", strings(task.arg(0)));
      case "CompileModule" ->
          "compile " + String.join(", ", strings(task.arg(1))) + " ("
              + str(((ElmData) Thunk.resolve(task.arg(0))).ctor()) + ") -> " + str(task.arg(2));
      case "RunTests" -> "test " + str(task.arg(0));
      default -> task.ctor();
    };
  }

  private static String quote(String s) {
    return "\"" + s + "\"";
  }

  /** Performs one task, returning its exit code (0 = success). */
  private static int runTask(ElmData task, Path baseDir, boolean incremental, PrintStream out) {
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
        case "Archive" -> archive(at(baseDir, str(task.arg(0))), at(baseDir, str(task.arg(1))), incremental, out);
        case "Bundle" -> bundle(strings(task.arg(0)), str(task.arg(1)), baseDir, out);
        case "Run" -> {
          return exec(str(task.arg(0)), strings(task.arg(1)), baseDir, out);
        }
        case "Check" -> {
          return check(strings(task.arg(0)), baseDir, out);
        }
        case "CompileModule" -> compile(str(((ElmData) Thunk.resolve(task.arg(0))).ctor()),
            strings(task.arg(1)), str(task.arg(2)), baseDir, incremental, out);
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

  /** Compiles a module's source files (entry first) to {@code target} with the named backend. The JS
   * and linear-WASM backends bundle all the sources; WasmGC compiles the entry source only. */
  private static void compile(String backend, List<String> entries, String target, Path baseDir,
      boolean incremental, PrintStream out) throws IOException {
    if (entries.isEmpty()) {
      throw new IOException("compile: no source files");
    }
    Path path = at(baseDir, target);
    List<Path> inputs = new ArrayList<>();
    for (String e : entries) {
      inputs.add(at(baseDir, e));
    }
    if (incremental && upToDate(path, inputs)) {
      out.println("  up to date " + target);
      return;
    }
    List<String> sources = new ArrayList<>();
    for (Path p : inputs) {
      sources.add(Files.readString(p, StandardCharsets.UTF_8));
    }
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    String[] arr = sources.toArray(new String[0]);
    switch (backend) {
      case "JS" -> {
        String page =
            sources.size() == 1
                ? JsCompiler.htmlPage(arr[0], null)
                : JsCompiler.htmlPageProject(null, arr);
        Files.writeString(path, page, StandardCharsets.UTF_8);
      }
      case "Wasm" -> Files.write(path, WasmCompiler.moduleFromSources(sources));
      case "WasmGc" -> Files.write(path, WasmGc.module(arr[0]));
      default -> throw new IllegalArgumentException("unknown backend: " + backend);
    }
    out.println("  compiled " + entries.get(0)
        + (sources.size() > 1 ? " (+" + (sources.size() - 1) + ")" : "")
        + " (" + backend + ") -> " + target);
  }

  /** Type-checks a module's source files together (so cross-module imports resolve), returning 0 if
   * they check or 1 (with the error) otherwise. */
  private static int check(List<String> entries, Path baseDir, PrintStream out) throws IOException {
    if (entries.isEmpty()) {
      return 0;
    }
    List<String> sources = new ArrayList<>();
    for (String e : entries) {
      sources.add(Files.readString(at(baseDir, e), StandardCharsets.UTF_8));
    }
    try {
      pl.matsuo.elm.types.TypeChecker.checkProject(sources.toArray(new String[0]));
      out.println("  ok " + entries.get(0) + " type-checks");
      return 0;
    } catch (pl.matsuo.elm.error.ElmTypeError e) {
      out.println("  x type error in " + entries.get(0) + ": " + message(e));
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

  /** Zips a directory tree (or a single file) into {@code zip}, with entries relative to {@code src}. */
  private static void archive(Path src, Path zip, boolean incremental, PrintStream out) throws IOException {
    if (!Files.exists(src)) {
      throw new IOException("nothing to archive at " + src);
    }
    if (incremental) {
      List<Path> inputs = new ArrayList<>();
      try (var walk = Files.walk(src)) {
        walk.filter(Files::isRegularFile).forEach(inputs::add);
      }
      if (upToDate(zip, inputs)) {
        out.println("  up to date " + zip);
        return;
      }
    }
    if (zip.getParent() != null) {
      Files.createDirectories(zip.getParent());
    }
    Path baseForEntries = Files.isDirectory(src) ? src : src.getParent();
    try (var zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
        var walk = Files.walk(src)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
        String entry =
            (baseForEntries == null ? p.getFileName() : baseForEntries.relativize(p)).toString()
                .replace('\\', '/');
        zos.putNextEntry(new java.util.zip.ZipEntry(entry));
        Files.copy(p, zos);
        zos.closeEntry();
      }
    }
    out.println("  archived " + src + " -> " + zip);
  }

  /** Concatenates already-built artifact files into {@code target} — links a module against its
   * dependencies' compiled output. */
  private static void bundle(List<String> inputs, String target, Path baseDir, PrintStream out)
      throws IOException {
    Path path = at(baseDir, target);
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    StringBuilder sb = new StringBuilder();
    for (String in : inputs) {
      sb.append(Files.readString(at(baseDir, in), StandardCharsets.UTF_8)).append('\n');
    }
    Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    out.println("  bundled " + inputs.size() + " artifact(s) -> " + target);
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

  /** Whether {@code target} exists and is at least as new as every input — i.e. nothing to rebuild. */
  private static boolean upToDate(Path target, List<Path> inputs) throws IOException {
    if (!Files.exists(target)) {
      return false;
    }
    long out = Files.getLastModifiedTime(target).toMillis();
    for (Path in : inputs) {
      if (!Files.exists(in) || Files.getLastModifiedTime(in).toMillis() > out) {
        return false;
      }
    }
    return true;
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

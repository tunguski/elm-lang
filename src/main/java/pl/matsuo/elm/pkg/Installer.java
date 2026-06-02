package pl.matsuo.elm.pkg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Implements {@code elm install <author/name>}: add a package to an application's {@code elm.json}
 * and re-solve the whole dependency set against a {@link Registry}. Existing direct dependencies are
 * pinned to their current versions; the new package is taken at its newest registry version, and the
 * solver fills in the resulting transitive (indirect) closure. The updated solution is written back.
 */
public final class Installer {

  /** The outcome of an install: the chosen version, and the new direct/indirect dependency maps. */
  public record Result(
      Version installed,
      boolean alreadyPresent,
      Map<String, Version> direct,
      Map<String, Version> indirect) {}

  /** Installs {@code pkg} into the application {@code elm.json} in {@code projectDir}. */
  public static Result install(Path projectDir, String pkg, Registry registry) throws IOException {
    return install(projectDir, pkg, registry, false);
  }

  /** Installs {@code pkg}; when {@code dryRun}, solves the dependencies and reports the result without
   *  writing {@code elm.json} or the lockfile. */
  public static Result install(Path projectDir, String pkg, Registry registry, boolean dryRun)
      throws IOException {
    Path elmJsonPath = projectDir.resolve("elm.json");
    if (!Files.exists(elmJsonPath)) {
      throw new IllegalStateException("no elm.json in " + projectDir.toAbsolutePath()
          + " — run `elm init` first");
    }
    ElmJson elm = ElmJson.parse(Files.readString(elmJsonPath, StandardCharsets.UTF_8));
    if (elm.direct().containsKey(pkg)) {
      return new Result(elm.direct().get(pkg), true, elm.direct(), elm.indirect());
    }
    var available = registry.versions(pkg);
    if (available.isEmpty()) {
      throw new Solver.Unsolvable("unknown package: " + pkg);
    }
    Version latest = available.get(available.size() - 1);

    Map<String, Constraint> roots = new TreeMap<>();
    elm.direct().forEach((p, v) -> roots.put(p, Constraint.parse(v.toString()))); // keep current pins
    roots.put(pkg, Constraint.forNewDependency(latest));

    Map<String, Version> solution = new Solver(registry).solve(roots);

    if (!dryRun) {
      Set<String> newDirect = new TreeSet<>(elm.direct().keySet());
      newDirect.add(pkg);
      elm.setSolution(newDirect, solution);
      Files.writeString(elmJsonPath, elm.render(), StandardCharsets.UTF_8);
      // Pin the exact solution with integrity hashes so the install is reproducible and tamper-evident.
      Lockfile.write(projectDir, solution, registry);
    }
    return new Result(solution.get(pkg), false, elm.direct(), elm.indirect());
  }

  /** The outcome of an upgrade: each direct dependency's version before and after re-solving. */
  public record Upgrade(Map<String, Version> before, Map<String, Version> after) {
    public boolean changed() {
      return !before.equals(after);
    }
  }

  /** Re-solves the application's direct dependencies allowing the latest available version of each,
   *  upgrading their pins. When {@code dryRun}, computes the new versions without writing anything. */
  public static Upgrade upgrade(Path projectDir, Registry registry, boolean dryRun)
      throws IOException {
    Path elmJsonPath = projectDir.resolve("elm.json");
    if (!Files.exists(elmJsonPath)) {
      throw new IllegalStateException("no elm.json in " + projectDir.toAbsolutePath()
          + " — run `elm init` first");
    }
    ElmJson elm = ElmJson.parse(Files.readString(elmJsonPath, StandardCharsets.UTF_8));
    Map<String, Version> before = new TreeMap<>(elm.direct());

    Map<String, Constraint> roots = new TreeMap<>();
    for (var entry : elm.direct().entrySet()) {
      var available = registry.versions(entry.getKey());
      // Allow up to the latest available; if the package is unknown to the registry, keep its pin.
      roots.put(
          entry.getKey(),
          available.isEmpty()
              ? Constraint.parse(entry.getValue().toString())
              : Constraint.forNewDependency(available.get(available.size() - 1)));
    }

    Map<String, Version> solution = new Solver(registry).solve(roots);
    Map<String, Version> after = new TreeMap<>();
    for (String p : elm.direct().keySet()) {
      after.put(p, solution.get(p));
    }

    if (!dryRun && !before.equals(after)) {
      elm.setSolution(elm.direct().keySet(), solution);
      Files.writeString(elmJsonPath, elm.render(), StandardCharsets.UTF_8);
      Lockfile.write(projectDir, solution, registry);
    }
    return new Upgrade(before, after);
  }

  /** The outcome of an uninstall: whether the package was a direct dependency, and the new direct
   *  dependency set after re-solving. */
  public record Uninstall(boolean wasPresent, Map<String, Version> direct) {}

  /** Removes {@code pkg} from the application's direct dependencies and re-solves the rest, writing
   *  elm.json and the lockfile. A no-op (wasPresent=false) if it isn't a direct dependency. -}*/
  public static Uninstall uninstall(Path projectDir, String pkg, Registry registry)
      throws IOException {
    Path elmJsonPath = projectDir.resolve("elm.json");
    if (!Files.exists(elmJsonPath)) {
      throw new IllegalStateException("no elm.json in " + projectDir.toAbsolutePath());
    }
    ElmJson elm = ElmJson.parse(Files.readString(elmJsonPath, StandardCharsets.UTF_8));
    if (!elm.direct().containsKey(pkg)) {
      return new Uninstall(false, elm.direct());
    }
    Map<String, Constraint> roots = new TreeMap<>();
    elm.direct().forEach((p, v) -> {
      if (!p.equals(pkg)) {
        roots.put(p, Constraint.parse(v.toString()));
      }
    });
    Map<String, Version> solution = new Solver(registry).solve(roots);
    Set<String> newDirect = new TreeSet<>(elm.direct().keySet());
    newDirect.remove(pkg);
    elm.setSolution(newDirect, solution);
    Files.writeString(elmJsonPath, elm.render(), StandardCharsets.UTF_8);
    Lockfile.write(projectDir, solution, registry);
    Map<String, Version> direct = new TreeMap<>();
    for (String p : newDirect) {
      direct.put(p, solution.get(p));
    }
    return new Uninstall(true, direct);
  }

  /** Direct dependencies for which the registry has a newer version: pkg -> {current, latest}. */
  public record Outdated(Map<String, Version[]> behind) {}

  public static Outdated outdated(Path projectDir, Registry registry) throws IOException {
    Path elmJsonPath = projectDir.resolve("elm.json");
    if (!Files.exists(elmJsonPath)) {
      throw new IllegalStateException("no elm.json in " + projectDir.toAbsolutePath());
    }
    ElmJson elm = ElmJson.parse(Files.readString(elmJsonPath, StandardCharsets.UTF_8));
    Map<String, Version[]> behind = new TreeMap<>();
    for (var e : elm.direct().entrySet()) {
      var available = registry.versions(e.getKey());
      if (!available.isEmpty()) {
        Version latest = available.get(available.size() - 1);
        if (latest.compareTo(e.getValue()) > 0) {
          behind.put(e.getKey(), new Version[] {e.getValue(), latest});
        }
      }
    }
    return new Outdated(behind);
  }

  /** The default package-cache directory: {@code $ELM_REGISTRY} if set, else {@code ~/.elm/registry}. */
  public static Path defaultRegistryRoot() {
    String env = System.getenv("ELM_REGISTRY");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    return Path.of(System.getProperty("user.home"), ".elm", "registry");
  }

  private Installer() {}
}

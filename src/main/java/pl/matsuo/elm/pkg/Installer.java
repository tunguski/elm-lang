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

    Set<String> newDirect = new TreeSet<>(elm.direct().keySet());
    newDirect.add(pkg);
    elm.setSolution(newDirect, solution);
    Files.writeString(elmJsonPath, elm.render(), StandardCharsets.UTF_8);
    // Pin the exact solution with integrity hashes so the install is reproducible and tamper-evident.
    Lockfile.write(projectDir, solution, registry);
    return new Result(solution.get(pkg), false, elm.direct(), elm.indirect());
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

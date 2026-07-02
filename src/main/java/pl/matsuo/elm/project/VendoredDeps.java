package pl.matsuo.elm.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import pl.matsuo.elm.json.JsonParse;

/**
 * Source dependencies on other repos, declared in a project's {@code elm.vendored.json} and resolved
 * into compilable source at build time — a lightweight, git-native alternative to hand-copying code
 * into {@code vendor/}. Each entry names a repo, a revision (commit SHA, tag or branch), the source
 * subdirectory to pull, and optional {@code include}/{@code exclude} globs to select a subset of its
 * modules (so a library repo's own {@code Main.elm} doesn't collide with the consumer's).
 *
 * <p>Resolution: each dep is cloned into {@code git-deps/<name>} and checked out at its {@code ref}
 * (or updated in place), and its (filtered) modules are added to the build path by {@link
 * ProjectLoader}. A sibling {@code elm.vendored.local.json} maps a dep name to a local path; when that
 * path exists, the working tree there is used verbatim instead of cloning — so a developer editing two
 * repos side by side sees live changes with no re-vendor step. {@code git-deps/} and {@code
 * elm.vendored.local.json} are build artifacts / per-developer and belong in {@code .gitignore}.
 */
public final class VendoredDeps {

  private VendoredDeps() {}

  public static final String MANIFEST = "elm.vendored.json";
  public static final String LOCAL_MANIFEST = "elm.vendored.local.json";
  public static final String DEPS_DIR = "git-deps";

  /** One declared dependency. {@code source} defaults to {@code "src"}; the glob lists default empty
   * (empty {@code include} = all modules under {@code source}; {@code exclude} is applied after). */
  public record Dep(
      String name, String repo, String ref, String source, List<String> include, List<String> exclude) {}

  /** A resolved dependency: the on-disk source root to gather from, and the filters to apply. */
  public record Resolved(Path sourceRoot, List<String> include, List<String> exclude) {}

  /** The dependencies declared in {@code <projectRoot>/elm.vendored.json}, or empty if there is none. */
  @SuppressWarnings("unchecked")
  public static List<Dep> read(Path projectRoot) {
    Path manifest = projectRoot.resolve(MANIFEST);
    if (!Files.isRegularFile(manifest)) {
      return List.of();
    }
    Object parsed;
    try {
      parsed = JsonParse.parse(Files.readString(manifest, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Object deps = parsed instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("dependencies") : null;
    if (!(deps instanceof List<?> list)) {
      throw new IllegalStateException(MANIFEST + ": expected a top-level { \"dependencies\": [ ... ] }");
    }
    List<Dep> out = new ArrayList<>();
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> dm)) {
        throw new IllegalStateException(MANIFEST + ": each dependency must be an object");
      }
      Map<String, Object> d = (Map<String, Object>) dm;
      String name = str(d, "name", manifest);
      out.add(
          new Dep(
              name,
              str(d, "repo", manifest),
              str(d, "ref", manifest),
              d.get("source") instanceof String s ? s : "src",
              strings(d.get("include")),
              strings(d.get("exclude"))));
    }
    return out;
  }

  /**
   * Ensures every declared dependency is present at its pinned revision (cloning or updating {@code
   * git-deps/<name>}, unless a local override applies) and returns the source roots + filters to
   * compile. With {@code frozen}, a dependency that isn't already present at its ref is an error and
   * nothing is fetched — for hermetic/offline builds after a cache restore.
   */
  public static List<Resolved> resolve(Path projectRoot, boolean frozen) {
    List<Dep> deps = read(projectRoot);
    if (deps.isEmpty()) {
      return List.of();
    }
    Map<String, String> local = readLocal(projectRoot);
    List<Resolved> resolved = new ArrayList<>();
    for (Dep dep : deps) {
      Path root;
      String override = local.get(dep.name());
      Path overridePath = override == null ? null : projectRoot.resolve(override).normalize();
      if (overridePath != null && Files.isDirectory(overridePath)) {
        root = overridePath; // local working tree — used verbatim, no clone
      } else {
        root = ensureCheckout(projectRoot.resolve(DEPS_DIR).resolve(dep.name()), dep, frozen);
      }
      resolved.add(new Resolved(root.resolve(dep.source()), dep.include(), dep.exclude()));
    }
    return resolved;
  }

  /** The {@code name -> path} overrides in {@code elm.vendored.local.json}, or empty. */
  @SuppressWarnings("unchecked")
  private static Map<String, String> readLocal(Path projectRoot) {
    Path f = projectRoot.resolve(LOCAL_MANIFEST);
    if (!Files.isRegularFile(f)) {
      return Map.of();
    }
    Object parsed;
    try {
      parsed = JsonParse.parse(Files.readString(f, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, String> out = new LinkedHashMap<>();
    if (parsed instanceof Map<?, ?> m) {
      ((Map<String, Object>) m)
          .forEach(
              (k, v) -> {
                if (v instanceof String s) {
                  out.put(k, s);
                }
              });
    }
    return out;
  }

  /** Clone (or update) {@code dest} to {@code dep.ref}, returning {@code dest}. A branch ref tracks its
   * remote tip; a tag/SHA is checked out detached and is fully reproducible. */
  private static Path ensureCheckout(Path dest, Dep dep, boolean frozen) {
    boolean present = Files.isDirectory(dest.resolve(".git"));
    if (!present) {
      if (frozen) {
        throw new IllegalStateException(
            "vendored dependency `" + dep.name() + "` is not checked out at " + dest
                + " and --frozen forbids fetching. Run `elm vendor` first.");
      }
      try {
        Files.createDirectories(dest.getParent());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      git(null, "clone", "--quiet", dep.repo(), dest.toString());
    }
    if (!frozen) {
      git(dest, "fetch", "--quiet", "--tags", "--force", "origin");
    }
    // A branch: check out its remote tip (latest). A tag/SHA: check it out as-is (detached, pinned).
    String target =
        gitOk(dest, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/" + dep.ref())
            ? "origin/" + dep.ref()
            : dep.ref();
    git(dest, "-c", "advice.detachedHead=false", "checkout", "--force", "--quiet", target);
    return dest;
  }

  // --- helpers -----------------------------------------------------------

  private static String str(Map<String, Object> d, String key, Path manifest) {
    if (d.get(key) instanceof String s && !s.isBlank()) {
      return s;
    }
    throw new IllegalStateException(manifest + ": dependency is missing required string `" + key + "`");
  }

  @SuppressWarnings("unchecked")
  private static List<String> strings(Object o) {
    if (!(o instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object x : list) {
      if (x instanceof String s) {
        out.add(s);
      }
    }
    return out;
  }

  /** Runs a git command, throwing on non-zero exit. {@code cwd} may be null (the process default). */
  private static void git(Path cwd, String... args) {
    int code = runGit(cwd, args);
    if (code != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed (exit " + code + ")");
    }
  }

  /** Runs a git command, returning whether it succeeded (exit 0) without throwing. */
  private static boolean gitOk(Path cwd, String... args) {
    return runGit(cwd, args) == 0;
  }

  private static int runGit(Path cwd, String... args) {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    for (String a : args) {
      cmd.add(a);
    }
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
    try {
      Process p = pb.start();
      p.getInputStream().readAllBytes(); // drain so the pipe never blocks
      if (!p.waitFor(120, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
      }
      return p.exitValue();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("git interrupted", e);
    }
  }
}

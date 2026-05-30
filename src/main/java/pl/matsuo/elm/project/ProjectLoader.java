package pl.matsuo.elm.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import pl.matsuo.elm.json.JsonParse;
import pl.matsuo.elm.pkg.ElmJson;
import pl.matsuo.elm.pkg.Installer;
import pl.matsuo.elm.pkg.Version;

/**
 * Loads an Elm project's module sources from an {@code elm.json}: the local modules under its
 * {@code "source-directories"}, <b>plus</b> the sources of every resolved dependency that lives in
 * the package cache (laid out as {@code <cache>/<author>/<name>/<version>/src}). This is what makes
 * an installed third-party package actually usable — its modules are handed to the same type checker
 * and interpreter as the local code, so {@code import}s of it resolve, type-check and run.
 *
 * <p>Packages provided by the built-in standard library (see {@link #BUNDLED}) are skipped, since
 * the interpreter, type checker and JS kernel already supply them; loading their sources too would
 * double-define those modules.
 */
public final class ProjectLoader {

  private ProjectLoader() {}

  /** Packages whose modules are built in, so their cached sources must not be loaded again. */
  public static final Set<String> BUNDLED =
      Set.of(
          "elm/core",
          "elm/html",
          "elm/browser",
          "elm/json",
          "elm/time",
          "elm/url",
          "elm/virtual-dom",
          "elm/random",
          "elm/svg",
          "elm/http",
          "elm/bytes",
          "elm/file",
          "elm/regex",
          "elm/parser",
          "elm-explorations/webgl",
          "elm-explorations/linear-algebra",
          "evancz/elm-playground");

  /** Loads local + cached-dependency sources, using the default package cache. */
  public static List<String> loadSources(Path elmJsonOrDir) {
    return loadSources(elmJsonOrDir, Installer.defaultRegistryRoot());
  }

  /** Accepts a path to an {@code elm.json} file or a directory containing one, plus the cache root. */
  @SuppressWarnings("unchecked")
  public static List<String> loadSources(Path elmJsonOrDir, Path registryRoot) {
    Path elmJson =
        Files.isDirectory(elmJsonOrDir) ? elmJsonOrDir.resolve("elm.json") : elmJsonOrDir;
    Path root = elmJson.toAbsolutePath().getParent();
    String text;
    try {
      text = Files.readString(elmJson, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    List<String> sources = new ArrayList<>();

    // 1) Local modules under the project's source-directories.
    List<String> dirs = new ArrayList<>();
    Object cfg = JsonParse.parse(text);
    Object sd = cfg instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("source-directories") : null;
    if (sd instanceof List<?> list) {
      for (Object d : list) {
        dirs.add(String.valueOf(d));
      }
    }
    if (dirs.isEmpty()) {
      dirs.add("src"); // Elm's default
    }
    for (String dir : dirs) {
      gatherElm(root.resolve(dir), sources);
    }

    // 2) Resolved dependencies' sources from the package cache (skipping the built-in packages).
    for (Map.Entry<String, Version> dep : resolvedDependencies(text).entrySet()) {
      if (BUNDLED.contains(dep.getKey())) {
        continue;
      }
      Path pkgSrc = packageSrc(registryRoot, dep.getKey(), dep.getValue());
      if (Files.isDirectory(pkgSrc)) {
        gatherElm(pkgSrc, sources);
      }
    }
    return sources;
  }

  /** The pinned dependencies (direct + indirect) of an application {@code elm.json}; empty if it
   *  isn't an application or declares none. */
  private static Map<String, Version> resolvedDependencies(String elmJsonText) {
    try {
      return ElmJson.parse(elmJsonText).all();
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  private static Path packageSrc(Path registryRoot, String pkg, Version version) {
    int slash = pkg.indexOf('/');
    Path base =
        slash < 0
            ? registryRoot.resolve(pkg)
            : registryRoot.resolve(pkg.substring(0, slash)).resolve(pkg.substring(slash + 1));
    return base.resolve(version.toString()).resolve("src");
  }

  /** Appends the contents of every {@code .elm} file beneath {@code base} (sorted) to {@code out}. */
  private static void gatherElm(Path base, List<String> out) {
    if (!Files.isDirectory(base)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(base)) {
      walk.filter(p -> p.toString().endsWith(".elm"))
          .sorted()
          .forEach(
              p -> {
                try {
                  out.add(Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

package pl.matsuo.elm.pkg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Publishes a package into a {@link DirectoryRegistry} — the registry handshake: refuse a version
 * that is already published or not newer than the latest, then record the package's {@code elm.json}
 * and {@code docs.json} under {@code <root>/<author>/<name>/<version>/}. This is the on-disk form a
 * remote registry mirrors, so the same checks apply offline.
 */
public final class Publisher {

  private Publisher() {}

  /** The outcome of a publish attempt: whether it was recorded, and a message to show. */
  public record Result(boolean ok, String message) {}

  /**
   * Records {@code name} {@code version} in the registry at {@code registryRoot} after the version
   * checks. {@code elmJson}/{@code docsJson} are the package manifest and its API docs.
   */
  public static Result publish(
      Path registryRoot, String name, Version version, String elmJson, String docsJson)
      throws IOException {
    DirectoryRegistry registry = new DirectoryRegistry(registryRoot);
    List<Version> existing = registry.versions(name);
    if (existing.contains(version)) {
      return new Result(false, name + " " + version + " is already published");
    }
    if (!existing.isEmpty()) {
      Version latest = existing.get(existing.size() - 1); // versions() returns them sorted ascending
      if (version.compareTo(latest) <= 0) {
        return new Result(
            false, "version " + version + " must be greater than the latest published " + latest);
      }
    }
    Path dir = versionDir(registryRoot, name, version);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("elm.json"), elmJson, StandardCharsets.UTF_8);
    Files.writeString(dir.resolve("docs.json"), docsJson, StandardCharsets.UTF_8);
    return new Result(true, "published " + name + " " + version + " to " + registryRoot);
  }

  /** A minimal package {@code elm.json} manifest for a single exposed module with no dependencies. */
  public static String manifest(String name, Version version, String exposedModule) {
    return """
        {
          "type": "package",
          "name": "__NAME__",
          "summary": "",
          "license": "BSD-3-Clause",
          "version": "__VERSION__",
          "exposed-modules": [ "__EXPOSED__" ],
          "elm-version": "0.19.1 <= v < 0.20.0",
          "dependencies": {},
          "test-dependencies": {}
        }
        """
        .replace("__NAME__", name)
        .replace("__VERSION__", String.valueOf(version))
        .replace("__EXPOSED__", exposedModule);
  }

  private static Path versionDir(Path root, String name, Version version) {
    int slash = name.indexOf('/');
    Path pkgDir =
        slash < 0
            ? root.resolve(name)
            : root.resolve(name.substring(0, slash)).resolve(name.substring(slash + 1));
    return pkgDir.resolve(version.toString());
  }
}

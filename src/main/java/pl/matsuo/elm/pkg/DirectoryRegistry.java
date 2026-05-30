package pl.matsuo.elm.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import pl.matsuo.elm.json.JsonParse;

/**
 * A {@link Registry} backed by an on-disk directory laid out as {@code
 * <root>/<author>/<name>/<version>/elm.json}, where each {@code elm.json} is a {@code "package"}
 * project declaring its own {@code dependencies} as constraint strings. This is the offline package
 * cache (and the form a remote registry would be mirrored into).
 */
public final class DirectoryRegistry implements Registry {

  private final Path root;

  public DirectoryRegistry(Path root) {
    this.root = root;
  }

  @Override
  public List<Version> versions(String pkg) {
    Path dir = packageDir(pkg);
    List<Version> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (Stream<Path> entries = Files.list(dir)) {
      entries
          .filter(Files::isDirectory)
          .forEach(
              p -> {
                try {
                  out.add(Version.parse(p.getFileName().toString()));
                } catch (IllegalArgumentException ignored) {
                  // a non-version directory — skip it
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    out.sort(null);
    return out;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Constraint> dependencies(String pkg, Version version) {
    Path elmJson = packageDir(pkg).resolve(version.toString()).resolve("elm.json");
    if (!Files.exists(elmJson)) {
      return Map.of();
    }
    try {
      Object root = JsonParse.parse(Files.readString(elmJson, StandardCharsets.UTF_8));
      if (root instanceof Map<?, ?> obj
          && obj.get("dependencies") instanceof Map<?, ?> deps) {
        return ElmJson.parseConstraints((Map<String, Object>) deps);
      }
      return Map.of();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Path packageDir(String pkg) {
    int slash = pkg.indexOf('/');
    if (slash < 0) {
      return root.resolve(pkg);
    }
    return root.resolve(pkg.substring(0, slash)).resolve(pkg.substring(slash + 1));
  }
}

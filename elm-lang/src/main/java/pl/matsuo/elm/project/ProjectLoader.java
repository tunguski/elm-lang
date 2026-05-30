package pl.matsuo.elm.project;

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
 * Loads an Elm project's local module sources from an {@code elm.json} file: it reads the
 * {@code "source-directories"} list and gathers every {@code .elm} file beneath those directories.
 * (No network/package-registry resolution — local source only.)
 */
public final class ProjectLoader {

  private ProjectLoader() {}

  /** Accepts a path to an {@code elm.json} file or to a directory containing one. */
  @SuppressWarnings("unchecked")
  public static List<String> loadSources(Path elmJsonOrDir) {
    Path elmJson =
        Files.isDirectory(elmJsonOrDir) ? elmJsonOrDir.resolve("elm.json") : elmJsonOrDir;
    Path root = elmJson.toAbsolutePath().getParent();
    List<String> dirs = new ArrayList<>();
    try {
      Object cfg = JsonParse.parse(Files.readString(elmJson, StandardCharsets.UTF_8));
      Object sd = cfg instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("source-directories") : null;
      if (sd instanceof List<?> list) {
        for (Object d : list) {
          dirs.add(String.valueOf(d));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (dirs.isEmpty()) {
      dirs.add("src"); // Elm's default
    }
    List<String> sources = new ArrayList<>();
    for (String dir : dirs) {
      Path base = root.resolve(dir);
      if (!Files.isDirectory(base)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(base)) {
        walk.filter(p -> p.toString().endsWith(".elm"))
            .sorted()
            .forEach(
                p -> {
                  try {
                    sources.add(Files.readString(p, StandardCharsets.UTF_8));
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return sources;
  }
}

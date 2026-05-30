package pl.matsuo.elm.pkg;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads a package's files into the on-disk cache, so an installed dependency's sources are
 * available to the project loader and compiler. The protocol matches {@link HttpRegistry}: a version
 * directory exposes {@code files.txt} listing every file relative to it ({@code elm.json},
 * {@code src/…}); the fetcher downloads each and writes it under
 * {@code <cache>/<author>/<name>/<version>/}.
 */
public final class PackageFetcher {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;

  public PackageFetcher(String base) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /** Fetches {@code pkg}@{@code version} into the cache; a no-op if already present. */
  public void fetch(String pkg, Version version, Path cacheRoot)
      throws IOException, InterruptedException {
    Path dest = versionDir(cacheRoot, pkg, version);
    if (Files.exists(dest.resolve("elm.json")) && Files.isDirectory(dest.resolve("src"))) {
      return; // already cached
    }
    String prefix = base + "/" + pkg + "/" + version;
    String list = get(prefix + "/files.txt");
    if (list == null) {
      throw new IOException("package not found at " + prefix);
    }
    for (String line : list.split("\\r?\\n")) {
      String rel = line.trim();
      if (rel.isEmpty()) {
        continue;
      }
      String content = get(prefix + "/" + rel);
      if (content == null) {
        throw new IOException("missing file " + rel + " for " + pkg + " " + version);
      }
      Path out = dest;
      for (String part : rel.split("/")) {
        out = out.resolve(part);
      }
      Files.createDirectories(out.getParent());
      Files.writeString(out, content, StandardCharsets.UTF_8);
    }
  }

  private static Path versionDir(Path cacheRoot, String pkg, Version version) {
    int slash = pkg.indexOf('/');
    Path base =
        slash < 0
            ? cacheRoot.resolve(pkg)
            : cacheRoot.resolve(pkg.substring(0, slash)).resolve(pkg.substring(slash + 1));
    return base.resolve(version.toString());
  }

  private String get(String url) throws IOException, InterruptedException {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return r.statusCode() == 200 ? r.body() : null;
  }
}

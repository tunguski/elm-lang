package pl.matsuo.elm.pkg;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import pl.matsuo.elm.json.JsonParse;

/**
 * Downloads a package's sources from a package.elm-lang.org-style registry into the on-disk cache,
 * matching the real protocol: {@code GET <base>/packages/<author>/<name>/<version>/endpoint.json}
 * yields {@code {"url": <zipball>, "hash": …}}, then the zipball (a GitHub source archive) is
 * fetched and unpacked, stripping its single top-level directory, into
 * {@code <cache>/<author>/<name>/<version>/}.
 */
public final class ElmPackageFetcher {

  // GitHub's zipball URL (github.com/<a>/<n>/zipball/<v>) answers 302 -> codeload.github.com, so the
  // client must follow redirects (the JDK default is NEVER) or the download silently yields no body.
  private final HttpClient http =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  private final String base;

  public ElmPackageFetcher(String base) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  /** Fetches {@code pkg}@{@code version} into the cache; a no-op if already present. */
  @SuppressWarnings("unchecked")
  public void fetch(String pkg, Version version, Path cacheRoot)
      throws IOException, InterruptedException {
    Path dest = versionDir(cacheRoot, pkg, version);
    if (Files.exists(dest.resolve("elm.json")) && Files.isDirectory(dest.resolve("src"))) {
      return; // already cached
    }
    String endpointJson = getString(base + "/packages/" + pkg + "/" + version + "/endpoint.json");
    if (endpointJson == null || !(JsonParse.parse(endpointJson) instanceof java.util.Map<?, ?> ep)) {
      throw new IOException("no endpoint for " + pkg + " " + version);
    }
    Object url = ((java.util.Map<String, Object>) ep).get("url");
    if (!(url instanceof String zipUrl)) {
      throw new IOException("endpoint has no url for " + pkg + " " + version);
    }
    byte[] zip = getBytes(zipUrl);
    if (zip == null) {
      throw new IOException("could not download " + zipUrl);
    }
    Object hash = ((java.util.Map<String, Object>) ep).get("hash");
    if (hash instanceof String h) {
      verifyChecksum(zip, h, pkg, version);
    }
    unpack(zip, dest);
  }

  /** Verifies the downloaded zipball against the endpoint's {@code hash} when it is a standard hex
   * digest (sha-256 = 64 hex chars, sha-1 = 40), throwing on a mismatch. A non-standard hash (e.g.
   * Elm's own content-hash scheme) is left unverified rather than wrongly rejected. */
  private static void verifyChecksum(byte[] zip, String expected, String pkg, Version version)
      throws IOException {
    String e = expected.trim().toLowerCase();
    String algorithm = e.matches("[0-9a-f]{64}") ? "SHA-256" : (e.matches("[0-9a-f]{40}") ? "SHA-1" : null);
    if (algorithm == null) {
      return; // not a recognised digest — don't risk a false rejection
    }
    try {
      byte[] digest = java.security.MessageDigest.getInstance(algorithm).digest(zip);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      if (!hex.toString().equals(e)) {
        throw new IOException(
            "checksum mismatch for " + pkg + " " + version + " (" + algorithm + "): expected " + e
                + " but got " + hex);
      }
    } catch (java.security.NoSuchAlgorithmException ex) {
      // every JRE has SHA-1/SHA-256; if somehow absent, skip verification
    }
  }

  /** Unpacks a GitHub-style source zip (single top-level dir) into {@code dest}. */
  private static void unpack(byte[] zip, Path dest) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        String name = stripTopDir(entry.getName());
        if (name.isEmpty()) {
          continue;
        }
        Path out = resolveSafely(dest, name);
        if (entry.isDirectory()) {
          Files.createDirectories(out);
        } else {
          Files.createDirectories(out.getParent());
          Files.write(out, zis.readAllBytes());
        }
      }
    }
  }

  /** Drops the archive's single top-level directory ({@code repo-sha/elm.json} -> {@code elm.json}). */
  private static String stripTopDir(String entryName) {
    String n = entryName.replace('\\', '/');
    int slash = n.indexOf('/');
    return slash < 0 ? "" : n.substring(slash + 1);
  }

  /** Resolves {@code rel} under {@code base}, refusing paths that escape it (zip-slip guard). */
  private static Path resolveSafely(Path base, String rel) throws IOException {
    Path resolved = base.resolve(rel).normalize();
    if (!resolved.startsWith(base.normalize())) {
      throw new IOException("unsafe zip entry: " + rel);
    }
    return resolved;
  }

  private static Path versionDir(Path cacheRoot, String pkg, Version version) {
    int slash = pkg.indexOf('/');
    Path b =
        slash < 0
            ? cacheRoot.resolve(pkg)
            : cacheRoot.resolve(pkg.substring(0, slash)).resolve(pkg.substring(slash + 1));
    return b.resolve(version.toString());
  }

  private String getString(String url) throws IOException, InterruptedException {
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return r.statusCode() == 200 ? r.body() : null;
  }

  private byte[] getBytes(String url) throws IOException, InterruptedException {
    HttpResponse<byte[]> r =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    return r.statusCode() == 200 ? r.body() : null;
  }
}

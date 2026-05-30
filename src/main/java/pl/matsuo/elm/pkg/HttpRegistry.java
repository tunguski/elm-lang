package pl.matsuo.elm.pkg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link Registry} served over HTTP, for solving against a remote package source. The protocol is
 * deliberately small and mirrors the on-disk cache layout, so any static file host works:
 *
 * <ul>
 *   <li>{@code GET <base>/<author>/<name>/versions.txt} — newline-separated versions;
 *   <li>{@code GET <base>/<author>/<name>/<version>/elm.json} — that version's package manifest,
 *       whose {@code dependencies} drive the solver.
 * </ul>
 *
 * <p>Source downloading is {@link PackageFetcher}'s job; this only supplies the metadata the solver
 * needs.
 */
public final class HttpRegistry implements Registry {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;

  public HttpRegistry(String base) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  @Override
  public List<Version> versions(String pkg) {
    List<Version> out = new ArrayList<>();
    String body = get(base + "/" + pkg + "/versions.txt");
    if (body == null) {
      return out;
    }
    for (String line : body.split("\\r?\\n")) {
      String t = line.trim();
      if (!t.isEmpty()) {
        try {
          out.add(Version.parse(t));
        } catch (IllegalArgumentException ignored) {
          // skip malformed lines
        }
      }
    }
    out.sort(null);
    return out;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Constraint> dependencies(String pkg, Version version) {
    String body = get(base + "/" + pkg + "/" + version + "/elm.json");
    if (body == null) {
      return Map.of();
    }
    Object root = pl.matsuo.elm.json.JsonParse.parse(body);
    if (root instanceof Map<?, ?> obj && obj.get("dependencies") instanceof Map<?, ?> deps) {
      return ElmJson.parseConstraints((Map<String, Object>) deps);
    }
    return Map.of();
  }

  /** GETs a URL as text, or null on a non-200 response (e.g. an unknown package). */
  private String get(String url) {
    try {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create(url)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      return r.statusCode() == 200 ? r.body() : null;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted fetching " + url, e);
    }
  }
}

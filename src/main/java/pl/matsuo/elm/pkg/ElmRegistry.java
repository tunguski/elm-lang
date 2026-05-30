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
import pl.matsuo.elm.json.JsonParse;

/**
 * A {@link Registry} that speaks the real <a href="https://package.elm-lang.org">package.elm-lang.org</a>
 * HTTP shape:
 *
 * <ul>
 *   <li>{@code GET <base>/all-packages} — a JSON object mapping {@code "author/name"} to a list of
 *       its published versions (fetched once and cached);
 *   <li>{@code GET <base>/packages/<author>/<name>/<version>/elm.json} — that version's manifest,
 *       whose {@code dependencies} drive the solver.
 * </ul>
 *
 * <p>Downloading a package's sources is {@link ElmPackageFetcher}'s job (via {@code endpoint.json} +
 * a zipball). This only supplies the metadata the solver needs.
 */
public final class ElmRegistry implements Registry {

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;
  private Map<String, List<Version>> allPackages; // lazily fetched index

  public ElmRegistry(String base) {
    this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
  }

  @Override
  public List<Version> versions(String pkg) {
    return index().getOrDefault(pkg, List.of());
  }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Constraint> dependencies(String pkg, Version version) {
    String body = get(base + "/packages/" + pkg + "/" + version + "/elm.json");
    if (body == null) {
      return Map.of();
    }
    Object root = JsonParse.parse(body);
    if (root instanceof Map<?, ?> obj && obj.get("dependencies") instanceof Map<?, ?> deps) {
      return ElmJson.parseConstraints((Map<String, Object>) deps);
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private synchronized Map<String, List<Version>> index() {
    if (allPackages == null) {
      allPackages = new java.util.HashMap<>();
      String body = get(base + "/all-packages");
      if (body != null && JsonParse.parse(body) instanceof Map<?, ?> obj) {
        for (Map.Entry<String, Object> e : ((Map<String, Object>) obj).entrySet()) {
          if (e.getValue() instanceof List<?> vs) {
            List<Version> versions = new ArrayList<>();
            for (Object v : vs) {
              try {
                versions.add(Version.parse(String.valueOf(v)));
              } catch (IllegalArgumentException ignored) {
                // skip a malformed version entry
              }
            }
            versions.sort(null);
            allPackages.put(e.getKey(), versions);
          }
        }
      }
    }
    return allPackages;
  }

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

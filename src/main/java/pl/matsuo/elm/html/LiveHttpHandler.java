package pl.matsuo.elm.html;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * An {@link HttpHandler} that performs real HTTP requests against a running server, the way a
 * browser would: it resolves the program's (often relative) request URLs against a configured
 * document base, preserves cookies across requests (so a session established by a login step carries
 * into later steps), and sends the actual method + body. This is what lets a headless UI journey
 * read <b>and</b> mutate a live backend and observe the results in subsequent snapshots.
 *
 * <p>An optional {@link RequestDecorator} can add per-request headers (e.g. a CSRF token echoed from
 * a cookie) — keeping framework-specific concerns out of this generic handler.
 */
public final class LiveHttpHandler implements HttpHandler {

  /** Adds headers to an outgoing request; may read the current cookies (e.g. to echo a CSRF token). */
  @FunctionalInterface
  public interface RequestDecorator {
    void decorate(HttpRequest.Builder builder, Request request, CookieManager cookies);
  }

  private final URI documentBase;
  private final CookieManager cookies;
  private final HttpClient client;
  private final RequestDecorator decorator;
  private final String defaultContentType;

  public LiveHttpHandler(URI documentBase) {
    this(documentBase, null, "application/json");
  }

  /**
   * @param documentBase the page URL the program's relative URLs resolve against (e.g. {@code
   *     http://localhost:8080/bbx/}); a request for {@code ../api/x} then hits {@code
   *     http://localhost:8080/api/x}, exactly as in the browser.
   * @param decorator optional per-request header decorator (nullable).
   * @param defaultContentType Content-Type set for a non-empty request body (nullable to omit).
   */
  public LiveHttpHandler(URI documentBase, RequestDecorator decorator, String defaultContentType) {
    this.documentBase = documentBase;
    this.decorator = decorator;
    this.defaultContentType = defaultContentType;
    this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    this.client =
        HttpClient.newBuilder()
            .cookieHandler(cookies)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
  }

  /** The cookie jar shared across requests — inspectable by tests and request decorators. */
  public CookieManager cookies() {
    return cookies;
  }

  @Override
  public Response handle(Request request) {
    try {
      URI target = documentBase.resolve(request.url());
      HttpRequest.Builder builder =
          HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30));
      String body = request.body() == null ? "" : request.body();
      if (body.isEmpty()) {
        builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
      } else {
        builder.method(request.method(), HttpRequest.BodyPublishers.ofString(body));
        if (defaultContentType != null) {
          builder.header("Content-Type", defaultContentType);
        }
      }
      if (decorator != null) {
        decorator.decorate(builder, request, cookies);
      }
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (Exception e) {
      return Response.NETWORK_ERROR;
    }
  }
}

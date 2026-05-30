package pl.matsuo.elm.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.runtime.ElmRecord;

/**
 * Serves HTTP requests with an Elm handler. The application exposes a pure {@code handle : Request
 * -> Response} (see {@code Server.elm}); for each request the runner builds the {@code Request}
 * record, applies the handler on the JIT interpreter, and writes the resulting {@code Response}.
 * Built on the JDK's {@link com.sun.net.httpserver.HttpServer}, so there are no dependencies.
 */
public final class ServerRunner {

  private ServerRunner() {}

  /** A decoded Elm {@code Response}. */
  public record Resp(int status, String contentType, String body) {}

  /** Applies the Elm handler to a request and decodes the response — pure, so it is unit-testable. */
  public static Resp dispatch(Object handler, String method, String path, String body) {
    Object request =
        new ElmRecord(Map.of("method", method, "path", path, "body", body == null ? "" : body));
    Object result = Thunk.resolve(Apply.apply(handler, request));
    if (!(result instanceof ElmRecord r)) {
      throw new IllegalStateException("handle must return a Server.Response record, got: " + result);
    }
    return new Resp(intOf(r.get("status")), str(r.get("contentType")), str(r.get("body")));
  }

  /** Binds and starts an HTTP server dispatching to {@code handler}; returns it (already started). */
  public static HttpServer start(Object handler, int port) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/",
        exchange -> {
          Resp resp;
          try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            resp = dispatch(handler, method, path, body);
          } catch (RuntimeException e) {
            resp = new Resp(500, "text/plain", "Server error: " + e.getMessage());
          }
          byte[] out = resp.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", resp.contentType());
          exchange.sendResponseHeaders(resp.status(), out.length == 0 ? -1 : out.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
          }
        });
    server.setExecutor(null); // default executor
    server.start();
    return server;
  }

  private static int intOf(Object o) {
    return (int) ((Number) Thunk.resolve(o)).longValue();
  }

  private static String str(Object o) {
    return String.valueOf(Thunk.resolve(o));
  }
}

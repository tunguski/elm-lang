package pl.matsuo.elm.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
  public static Resp dispatch(
      Object handler, String method, String path, String rawQuery, String body) {
    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("method", method);
    fields.put("path", path);
    fields.put("query", parseQuery(rawQuery));
    fields.put("body", body == null ? "" : body);
    Object result = Thunk.resolve(Apply.apply(handler, new ElmRecord(fields)));
    if (!(result instanceof ElmRecord r)) {
      throw new IllegalStateException("handle must return a Server.Response record, got: " + result);
    }
    return new Resp(intOf(r.get("status")), str(r.get("contentType")), str(r.get("body")));
  }

  /** Parses {@code a=1&b=2} into an Elm {@code List (String, String)} of decoded key/value tuples. */
  private static pl.matsuo.elm.runtime.ElmList parseQuery(String rawQuery) {
    java.util.List<Object> pairs = new java.util.ArrayList<>();
    if (rawQuery != null && !rawQuery.isEmpty()) {
      for (String part : rawQuery.split("&")) {
        int eq = part.indexOf('=');
        String k = eq < 0 ? part : part.substring(0, eq);
        String v = eq < 0 ? "" : part.substring(eq + 1);
        pairs.add(
            new pl.matsuo.elm.runtime.ElmTuple(new Object[] {urlDecode(k), urlDecode(v)}));
      }
    }
    return pl.matsuo.elm.runtime.ElmList.fromJava(pairs);
  }

  private static String urlDecode(String s) {
    return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
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
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            resp = dispatch(handler, method, path, rawQuery, body);
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

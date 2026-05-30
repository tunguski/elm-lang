package pl.matsuo.elm.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Thunk;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * Serves HTTP with an Elm program. A <b>stateless</b> app exposes {@code handle : Request ->
 * Response}; a <b>stateful</b> app exposes {@code main : Server.Program model} (an in-memory model,
 * an {@code onRequest} that returns {@code (model, Response)}, and a periodic {@code onTick}). For
 * each request the runner builds the {@code Request} record, runs the Elm function on the JIT
 * interpreter, and writes the {@code Response}. Built on the JDK's {@link HttpServer} — no deps.
 */
public final class ServerRunner {

  private ServerRunner() {}

  /** A decoded Elm {@code Response}. */
  public record Resp(int status, String contentType, String body) {}

  // --- stateless ---------------------------------------------------------

  /** Applies a stateless {@code handle} to a request and decodes the response (unit-testable). */
  public static Resp dispatch(
      Object handler, String method, String path, String rawQuery, String body) {
    return decodeResponse(Apply.apply(handler, buildRequest(method, path, rawQuery, body)));
  }

  /** Binds and starts an HTTP server dispatching to a stateless {@code handle}; returns it. */
  public static HttpServer start(Object handler, int port) throws IOException {
    return serve(
        port,
        exchange -> {
          var r = request(exchange);
          return dispatch(handler, r[0], r[1], r[2], r[3]);
        });
  }

  // --- stateful ----------------------------------------------------------

  /** A running stateful server: the model lives here, guarded for the request and tick threads. */
  public static final class Stateful {
    private final Object onRequest;
    private final Object onTick;
    private Object model;

    Stateful(ElmRecord program) {
      this.model = Thunk.resolve(program.get("init"));
      this.onRequest = Thunk.resolve(program.get("onRequest"));
      this.onTick = Thunk.resolve(program.get("onTick"));
    }

    /** Handles a request against the current model, updating it, and returns the response. */
    public synchronized Resp handle(String method, String path, String rawQuery, String body) {
      Object result =
          Thunk.resolve(Apply.applyAll(onRequest, buildRequest(method, path, rawQuery, body), model));
      if (!(result instanceof ElmTuple t)) {
        throw new IllegalStateException("onRequest must return ( model, Response ), got: " + result);
      }
      model = Thunk.resolve(t.get(0));
      return decodeResponse(t.get(1));
    }

    /** Advances the model by one background tick. */
    public synchronized void tick() {
      model = Thunk.resolve(Apply.apply(onTick, model));
    }

    public synchronized Object model() {
      return model;
    }
  }

  /**
   * Starts a stateful server for the given {@code Server.Program} record, ticking every
   * {@code tickMillis} on a daemon thread. Returns the {@link HttpServer} (call {@code stop}).
   */
  public static HttpServer startStateful(ElmRecord program, int port) throws IOException {
    Stateful state = new Stateful(program);
    int tickMillis = intOf(program.get("tickMillis"));
    HttpServer server =
        serve(
            port,
            exchange -> {
              var r = request(exchange);
              return state.handle(r[0], r[1], r[2], r[3]);
            });
    if (tickMillis > 0) {
      ScheduledExecutorService ticker =
          Executors.newSingleThreadScheduledExecutor(
              run -> {
                Thread t = new Thread(run, "elm-server-tick");
                t.setDaemon(true);
                return t;
              });
      ticker.scheduleAtFixedRate(state::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }
    return server;
  }

  // --- shared HTTP plumbing ----------------------------------------------

  /** A handler turning a decoded request into a {@link Resp}. */
  private interface Dispatcher {
    Resp handle(HttpExchange exchange) throws IOException;
  }

  private static HttpServer serve(int port, Dispatcher dispatcher) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/",
        exchange -> {
          Resp resp;
          try {
            resp = dispatcher.handle(exchange);
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
    server.setExecutor(null);
    server.start();
    return server;
  }

  /** Extracts {method, path, rawQuery, body} from an exchange. */
  private static String[] request(HttpExchange exchange) throws IOException {
    return new String[] {
      exchange.getRequestMethod(),
      exchange.getRequestURI().getPath(),
      exchange.getRequestURI().getRawQuery(),
      new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
    };
  }

  /** Builds the Elm {@code Request} record. */
  private static ElmRecord buildRequest(String method, String path, String rawQuery, String body) {
    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("method", method);
    fields.put("path", path);
    fields.put("query", parseQuery(rawQuery));
    fields.put("body", body == null ? "" : body);
    return new ElmRecord(fields);
  }

  /** Decodes an Elm {@code Response} record. */
  private static Resp decodeResponse(Object value) {
    if (!(Thunk.resolve(value) instanceof ElmRecord r)) {
      throw new IllegalStateException("expected a Server.Response record, got: " + value);
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
        pairs.add(new pl.matsuo.elm.runtime.ElmTuple(new Object[] {urlDecode(k), urlDecode(v)}));
      }
    }
    return pl.matsuo.elm.runtime.ElmList.fromJava(pairs);
  }

  private static String urlDecode(String s) {
    return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  private static int intOf(Object o) {
    return (int) ((Number) Thunk.resolve(o)).longValue();
  }

  private static String str(Object o) {
    return String.valueOf(Thunk.resolve(o));
  }
}

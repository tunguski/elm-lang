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
    return dispatch(handler, null, method, path, rawQuery, body);
  }

  /**
   * As {@link #dispatch(Object, String, String, String, String)} but for a database-backed handler
   * {@code handle : Request -> Db Response}: when the handler returns a {@code Db} effect, it is run
   * against a connection opened from {@code jdbcUrl} (any JDBC URL; {@code null} means a pure
   * handler) before the {@code Response} is decoded.
   */
  public static Resp dispatch(
      Object handler, String jdbcUrl, String method, String path, String rawQuery, String body) {
    Object result = Apply.apply(handler, buildRequest(method, path, rawQuery, body));
    return decodeResponse(runDbEffects(result, jdbcUrl));
  }

  /**
   * Interprets a {@code Db Response} effect against a fresh JDBC connection and returns the decoded
   * {@code Response}; a plain {@code Response} record (pure {@code Server} handler) passes straight
   * through. A query-free effect ({@code succeed}) needs no connection, so a url-less server can
   * still answer routes that don't touch the database.
   */
  private static Object runDbEffects(Object result, String jdbcUrl) {
    Object resolved = Thunk.resolve(result);
    if (!DbRunner.isDbEffect(resolved)) {
      return resolved;
    }
    if (jdbcUrl == null) {
      return DbRunner.run(resolved, null);
    }
    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl)) {
      return DbRunner.run(resolved, conn);
    } catch (java.sql.SQLException e) {
      String msg = e.getMessage() == null ? e.toString() : e.getMessage();
      throw new pl.matsuo.elm.error.ElmRuntimeError("database error: " + msg);
    }
  }

  /** Binds and starts an HTTP server dispatching to a stateless {@code handle}; returns it. */
  public static HttpServer start(Object handler, int port) throws IOException {
    return start(handler, port, null, null);
  }

  /**
   * As {@link #start(Object, int)} but first serves matching files from {@code staticDir} (text
   * assets: HTML/CSS/JS/JSON/SVG), falling through to the Elm handler when no file matches.
   */
  public static HttpServer start(Object handler, int port, java.nio.file.Path staticDir)
      throws IOException {
    return start(handler, port, staticDir, null);
  }

  /**
   * As {@link #start(Object, int, java.nio.file.Path)} but for a database-backed handler: a non-null
   * {@code jdbcUrl} makes each request run its {@code Db Response} against a JDBC connection.
   */
  public static HttpServer start(
      Object handler, int port, java.nio.file.Path staticDir, String jdbcUrl) throws IOException {
    return serve(
        port,
        staticDir,
        exchange -> {
          var r = request(exchange);
          return dispatch(handler, jdbcUrl, r[0], r[1], r[2], r[3]);
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
    return startStateful(program, port, null);
  }

  /** As {@link #startStateful(ElmRecord, int)} but serving {@code staticDir} files first. */
  public static HttpServer startStateful(ElmRecord program, int port, java.nio.file.Path staticDir)
      throws IOException {
    Stateful state = new Stateful(program);
    int tickMillis = intOf(program.get("tickMillis"));
    HttpServer server =
        serve(
            port,
            staticDir,
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

  private static HttpServer serve(int port, java.nio.file.Path staticDir, Dispatcher dispatcher)
      throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/",
        exchange -> {
          Resp resp;
          try {
            Resp staticResp = staticDir == null ? null : serveStatic(staticDir, exchange.getRequestURI().getPath());
            resp = staticResp != null ? staticResp : dispatcher.handle(exchange);
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

  /**
   * Serves a text asset from {@code dir} for the request path (a trailing {@code /} maps to
   * {@code index.html}), or {@code null} if there's no readable file. Path traversal outside
   * {@code dir} is refused. Bodies are read as UTF-8 text (binary assets aren't supported).
   */
  static Resp serveStatic(java.nio.file.Path dir, String path) {
    String rel = path.equals("/") || path.isEmpty() ? "index.html" : path.replaceFirst("^/+", "");
    java.nio.file.Path base = dir.toAbsolutePath().normalize();
    java.nio.file.Path file = base.resolve(rel).normalize();
    if (!file.startsWith(base) || !java.nio.file.Files.isRegularFile(file)) {
      return null; // missing, a directory, or an attempt to escape the static root
    }
    try {
      String body = java.nio.file.Files.readString(file, StandardCharsets.UTF_8);
      return new Resp(200, contentType(file.getFileName().toString()), body);
    } catch (IOException e) {
      return null;
    }
  }

  private static String contentType(String name) {
    int dot = name.lastIndexOf('.');
    return switch (dot < 0 ? "" : name.substring(dot + 1)) {
      case "html", "htm" -> "text/html";
      case "css" -> "text/css";
      case "js", "mjs" -> "application/javascript";
      case "json" -> "application/json";
      case "svg" -> "image/svg+xml";
      case "txt" -> "text/plain";
      default -> "text/plain";
    };
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

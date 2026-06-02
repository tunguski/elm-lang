package pl.matsuo.elm.server;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.types.TypeChecker;

/**
 * A from-scratch {@code elm reactor}: a development server that compiles a project's {@code .elm}
 * files to live pages <b>on the fly</b> and <b>hot-reloads</b> the browser when a source file
 * changes. {@code GET /} lists the modules; {@code GET /<Module>.elm} compiles that file (plus its
 * sibling modules, and any installed dependency sources when an {@code elm.json} is present) and
 * serves the running app with a small reload poller injected; {@code GET /_reload} returns a
 * generation counter that a background file watcher bumps on every change. Built on the JDK's
 * {@link HttpServer} and the existing JavaScript compiler — no external tooling.
 */
public final class ReactorServer {

  private ReactorServer() {}

  /** Starts the reactor over {@code dir} on {@code port}; returns the running server (call stop). */
  public static HttpServer start(Path dir, int port) throws IOException {
    clearCache(); // a fresh server starts with an empty compile cache
    AtomicLong generation = new AtomicLong(0);
    startWatcher(dir, generation);

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/_events")) {
              serveEvents(exchange, generation);
              return;
            }
            byte[] body;
            String contentType = "text/html; charset=utf-8";
            if (path.equals("/_reload")) {
              body = Long.toString(generation.get()).getBytes(StandardCharsets.UTF_8);
              contentType = "text/plain";
            } else if (path.equals("/") || path.isEmpty()) {
              body = index(dir).getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith(".elm")) {
              body = compilePage(dir, path.substring(1), generation.get()).getBytes(StandardCharsets.UTF_8);
            } else {
              exchange.sendResponseHeaders(404, -1);
              exchange.close();
              return;
            }
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(body);
            }
          } catch (RuntimeException e) {
            byte[] msg = ("Reactor error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(msg);
            }
          } finally {
            exchange.close();
          }
        });
    // A pool (not the default single caller-thread) so a held-open SSE connection never starves
    // other requests. Daemon threads so the JVM can still exit.
    server.setExecutor(
        java.util.concurrent.Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "reactor");
              t.setDaemon(true);
              return t;
            }));
    server.start();
    return server;
  }

  /** Server-Sent Events: holds the connection open and pushes a {@code reload} event whenever the
   * generation bumps, so the browser reloads instantly instead of polling. */
  private static void serveEvents(com.sun.net.httpserver.HttpExchange exchange, AtomicLong generation)
      throws java.io.IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().add("Cache-Control", "no-cache");
    exchange.sendResponseHeaders(200, 0); // chunked: an open-ended stream
    OutputStream os = exchange.getResponseBody();
    try {
      long last = generation.get();
      os.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
      os.flush();
      while (true) {
        long g = generation.get();
        if (g != last) {
          last = g;
          os.write(("data: " + g + "\n\n").getBytes(StandardCharsets.UTF_8));
          os.flush(); // a write failure (client gone) breaks the loop
        }
        Thread.sleep(300);
      }
    } catch (Exception e) {
      // client disconnected or the server is shutting down
    } finally {
      exchange.close();
    }
  }

  /** The directory listing page: a link per {@code .elm} module. */
  static String index(Path dir) {
    StringBuilder b = new StringBuilder();
    b.append("<!doctype html><meta charset=utf-8><title>elm reactor</title>")
        .append("<body style=\"font-family:system-ui;max-width:700px;margin:40px auto\">")
        .append("<h1>elm reactor</h1>")
        .append(projectStatusBanner(dir))
        .append("<p>Pick a module to compile and run:</p><ul>");
    for (Path p : elmFiles(dir)) {
      String rel = dir.relativize(p).toString().replace('\\', '/');
      String status = moduleParseStatus(p);
      b.append("<li><a href=\"/").append(rel).append("\">").append(rel).append("</a>").append(status)
          .append("</li>");
    }
    b.append("</ul>").append(RELOAD_SCRIPT.replace("%GEN%", "-1")).append("</body>");
    return b.toString();
  }

  /** A project-wide type-check banner for the index: green when the whole project checks, red with the
   *  located message when it doesn't (the type checker reports the first error across all modules). */
  private static String projectStatusBanner(Path dir) {
    try {
      List<String> sources = projectSources(dir);
      TypeChecker.checkProject(sources.toArray(new String[0]));
      return "<p style=\"color:#2e7d32\">✓ project type-checks</p>";
    } catch (ElmTypeError te) {
      String first = te.getMessage() == null ? "type error" : te.getMessage().split("\n")[0];
      return "<p style=\"color:#c62828;font-weight:600\">✗ " + escape(first) + "</p>";
    } catch (RuntimeException e) {
      return ""; // a non-type error (e.g. parse) is surfaced per-module below
    }
  }

  /** A per-module parse badge for the index: a red ✗ with the message if the file doesn't parse. */
  private static String moduleParseStatus(Path file) {
    try {
      pl.matsuo.elm.parser.Parser.parseModule(readOrEmpty(file));
      return "";
    } catch (RuntimeException e) {
      return " <span style=\"color:#c62828\">✗ " + escape(String.valueOf(e.getMessage())) + "</span>";
    }
  }

  /** Compiles {@code relPath} (plus sibling modules / dependency sources) to a live, reloading page. */
  // A content-addressed compile cache: compiling a project is the slow part of a request, but the
  // sources only change when a file is saved. Keying the compiled page on the requested module plus
  // a digest of every source means repeated requests for an unchanged project (reconnecting tabs, the
  // live-reload poll, refreshes) are served from memory; any edit changes the digest and recompiles.
  private static final java.util.concurrent.ConcurrentHashMap<String, String> PAGE_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final java.util.concurrent.atomic.AtomicLong CACHE_HITS =
      new java.util.concurrent.atomic.AtomicLong();

  /** Compiled-page cache hits so far (for diagnostics/tests). */
  static long cacheHits() {
    return CACHE_HITS.get();
  }

  /** Empties the compile cache and its hit counter (e.g. when a fresh server starts). */
  static void clearCache() {
    PAGE_CACHE.clear();
    CACHE_HITS.set(0);
  }

  static String compilePage(Path dir, String relPath, long generation) {
    Path target = dir.resolve(relPath);
    List<String> sources;
    try {
      sources = projectSources(dir);
      if (!sources.contains(readOrEmpty(target))) {
        sources.add(readOrEmpty(target)); // ensure the requested module is present
      }
    } catch (RuntimeException e) {
      return errorPage(relPath, e.getMessage(), generation);
    }
    String key = relPath + " " + digest(sources);
    String cached = PAGE_CACHE.get(key);
    if (cached != null) {
      CACHE_HITS.incrementAndGet();
      return inject(cached, generation); // the only per-request part: the live-reload generation
    }
    try {
      // Type-check first, so type errors surface as a located excerpt+caret (the JS backend itself
      // doesn't type-check). A checker limitation (any non-type-error) is ignored — fall through to
      // compilation, as `run`/`make` do.
      try {
        TypeChecker.checkProject(sources.toArray(new String[0]));
      } catch (ElmTypeError te) {
        return errorPage(relPath, te.getMessage(), generation);
      } catch (RuntimeException ignored) {
        // not a type error we can locate; let compilation report parse/codegen problems
      }
      String page = JsCompiler.htmlPageProject(null, sources.toArray(new String[0]));
      if (PAGE_CACHE.size() > 64) {
        PAGE_CACHE.clear(); // bound memory across long sessions of many edits
      }
      PAGE_CACHE.put(key, page);
      return inject(page, generation);
    } catch (RuntimeException e) {
      return errorPage(relPath, e.getMessage(), generation);
    }
  }

  /** A SHA-256 hex digest of all source contents (collision-free key material for the cache). */
  private static String digest(List<String> sources) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      for (String s : sources) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      StringBuilder sb = new StringBuilder();
      for (byte b : md.digest()) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      return Integer.toHexString(sources.hashCode()); // fallback (SHA-256 is always present)
    }
  }

  /** All local module sources, including installed dependency sources if an elm.json is present. */
  private static List<String> projectSources(Path dir) {
    Path elmJson = dir.resolve("elm.json");
    if (Files.exists(elmJson)) {
      return new ArrayList<>(pl.matsuo.elm.project.ProjectLoader.loadSources(dir));
    }
    List<String> sources = new ArrayList<>();
    for (Path p : elmFiles(dir)) {
      sources.add(readOrEmpty(p));
    }
    return sources;
  }

  private static String inject(String page, long generation) {
    String script = RELOAD_SCRIPT.replace("%GEN%", Long.toString(generation));
    int end = page.lastIndexOf("</body>");
    return end < 0 ? page + script : page.substring(0, end) + script + page.substring(end);
  }

  static String errorPage(String relPath, String message, long generation) {
    return "<!doctype html><meta charset=utf-8>"
        + "<body style=\"margin:0;background:#1e1e1e;color:#e0e0e0;font:14px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace\">"
        + "<div style=\"max-width:920px;margin:0 auto;padding:28px\">"
        + "<div style=\"color:#ff6b6b;font-size:18px;font-weight:700;margin-bottom:14px\">✗ Could not compile "
        + escape(relPath)
        + "</div>"
        + "<pre style=\"white-space:pre-wrap;background:#141414;border:1px solid #383838;border-left:4px solid #ff6b6b;border-radius:8px;padding:16px;overflow:auto;margin:0\">"
        + escape(message)
        + "</pre>"
        + "<p style=\"color:#888;margin-top:14px\">Fix the error and save — this page reloads automatically.</p>"
        + "</div>"
        + RELOAD_SCRIPT.replace("%GEN%", Long.toString(generation))
        + "</body>";
  }

  /** Reloads the page when the source changes. Primary: a Server-Sent-Events push from
   * {@code /_events} (instant, no chattiness). Fallback: polling {@code /_reload} where EventSource
   * is unavailable. A page generation of {@code -1} (the index) never auto-reloads. */
  private static final String RELOAD_SCRIPT =
      "<script>(function(){var g='%GEN%';if(g==='-1')return;"
          + "if(window.EventSource){var es=new EventSource('/_events');"
          + "es.onmessage=function(){location.reload();};es.onerror=function(){};}"
          + "else{setInterval(function(){fetch('/_reload').then(function(r){return r.text();})"
          + ".then(function(t){if(t!==g){location.reload();}}).catch(function(){});},700);}})();</script>";

  /** Watches {@code dir} for {@code .elm} changes on a daemon thread, bumping {@code generation}. */
  private static void startWatcher(Path dir, AtomicLong generation) {
    Thread t =
        new Thread(
            () -> {
              long last = snapshot(dir);
              while (!Thread.currentThread().isInterrupted()) {
                try {
                  Thread.sleep(400);
                } catch (InterruptedException e) {
                  return;
                }
                long now = snapshot(dir);
                if (now != last) {
                  last = now;
                  generation.incrementAndGet();
                }
              }
            },
            "elm-reactor-watcher");
    t.setDaemon(true);
    t.start();
  }

  /** A cheap fingerprint of the directory's .elm files plus the project manifest/lockfile (names +
   *  sizes + mtimes). Watching elm.json/elm.lock means adding or changing a dependency triggers a
   *  rebuild (the next compile re-resolves dependency sources), not just editing .elm files. */
  static long snapshot(Path dir) {
    long h = 1;
    List<Path> watched = new ArrayList<>(elmFiles(dir));
    for (String manifest : new String[] {"elm.json", "elm.lock"}) {
      Path m = dir.resolve(manifest);
      if (Files.isRegularFile(m)) {
        watched.add(m);
      }
    }
    for (Path p : watched) {
      try {
        h = h * 31 + p.toString().hashCode();
        h = h * 31 + Files.size(p);
        h = h * 31 + Files.getLastModifiedTime(p).toMillis();
      } catch (IOException ignored) {
        // a file vanished mid-scan; the next tick will settle
      }
    }
    return h;
  }

  private static List<Path> elmFiles(Path dir) {
    List<Path> out = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(p -> p.toString().endsWith(".elm") && Files.isRegularFile(p)).sorted().forEach(out::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out;
  }

  private static String readOrEmpty(Path p) {
    try {
      return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String escape(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}

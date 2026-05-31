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
    AtomicLong generation = new AtomicLong(0);
    startWatcher(dir, generation);

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            String path = exchange.getRequestURI().getPath();
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
    server.start();
    return server;
  }

  /** The directory listing page: a link per {@code .elm} module. */
  static String index(Path dir) {
    StringBuilder b = new StringBuilder();
    b.append("<!doctype html><meta charset=utf-8><title>elm reactor</title>")
        .append("<body style=\"font-family:system-ui;max-width:700px;margin:40px auto\">")
        .append("<h1>elm reactor</h1><p>Pick a module to compile and run:</p><ul>");
    for (Path p : elmFiles(dir)) {
      String rel = dir.relativize(p).toString().replace('\\', '/');
      b.append("<li><a href=\"/").append(rel).append("\">").append(rel).append("</a></li>");
    }
    b.append("</ul>").append(RELOAD_SCRIPT.replace("%GEN%", "-1")).append("</body>");
    return b.toString();
  }

  /** Compiles {@code relPath} (plus sibling modules / dependency sources) to a live, reloading page. */
  static String compilePage(Path dir, String relPath, long generation) {
    Path target = dir.resolve(relPath);
    try {
      List<String> sources = projectSources(dir);
      if (!sources.contains(readOrEmpty(target))) {
        sources.add(readOrEmpty(target)); // ensure the requested module is present
      }
      String page = JsCompiler.htmlPageProject(null, sources.toArray(new String[0]));
      return inject(page, generation);
    } catch (RuntimeException e) {
      return errorPage(relPath, e.getMessage(), generation);
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
    return "<!doctype html><meta charset=utf-8><body style=\"font-family:monospace;padding:20px\">"
        + "<h2 style=\"color:#a00\">Could not compile " + relPath + "</h2><pre style=\"white-space:pre-wrap\">"
        + escape(message) + "</pre>"
        + RELOAD_SCRIPT.replace("%GEN%", Long.toString(generation)) + "</body>";
  }

  /** Polls {@code /_reload}; when the generation differs from the page's, reloads. */
  private static final String RELOAD_SCRIPT =
      "<script>(function(){var g='%GEN%';setInterval(function(){"
          + "fetch('/_reload').then(function(r){return r.text();}).then(function(t){"
          + "if(g!=='-1'&&t!==g){location.reload();}}).catch(function(){});},700);})();</script>";

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

  /** A cheap fingerprint of the directory's .elm files (names + sizes + mtimes). */
  static long snapshot(Path dir) {
    long h = 1;
    for (Path p : elmFiles(dir)) {
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

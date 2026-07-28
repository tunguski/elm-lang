package pl.matsuo.elm.bundle;

import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.script.ScriptRunner;
import pl.matsuo.elm.server.ServerRunner;
import pl.matsuo.elm.util.Resources;

/**
 * Entry point baked into a bundled standalone artifact (see {@link Bundler}). The Elm source and the
 * run mode are embedded as jar resources; {@link #main} reads them and runs the script or server
 * with the interpreter, so the produced JAR / native binary needs no project files at runtime.
 */
public final class Standalone {

  /** Jar resource paths holding the embedded application. */
  public static final String APP_RESOURCE = "/META-INF/elm/app.elm";

  public static final String MODE_RESOURCE = "/META-INF/elm/mode";
  public static final String PORT_RESOURCE = "/META-INF/elm/port";

  private Standalone() {}

  public static void main(String[] args) throws Exception {
    String source = readResource(APP_RESOURCE);
    String mode = readResource(MODE_RESOURCE);
    if (source == null || mode == null) {
      System.err.println("This JAR is not a bundled Elm app (missing " + APP_RESOURCE + ").");
      System.exit(70);
    }
    if (mode.trim().equals("server")) {
      String portText = readResource(PORT_RESOURCE);
      int port = portText == null ? 8080 : Integer.parseInt(portText.trim());
      // A bundled `handle : Request -> Db Response` server reaches its database through the DB_URL
      // environment variable (any JDBC URL; H2 ships on the classpath) — the runtime equivalent of
      // `elm server --db <url>`. Unset means a pure server that runs no queries.
      String dbUrl = System.getenv("DB_URL");
      // STATIC_DIR lets a bundled server also serve files (its own app's HTML/CSS/JS) from a
      // directory, checked before the Elm handler — the runtime equivalent of `elm server --static`.
      String staticEnv = System.getenv("STATIC_DIR");
      Path staticDir =
          (staticEnv == null || staticEnv.isBlank()) ? null : Path.of(staticEnv.trim());
      runServerBlocking(source, port, staticDir, dbUrl);
    } else {
      System.exit(runScript(source, List.of(args), System.in, System.out));
    }
  }

  /** Runs a `main : Posix.Io` script (with the Posix and Bash modules bundled). Returns the exit code. */
  public static int runScript(String source, List<String> args, InputStream in, PrintStream out) {
    String posix = Resources.read("/elm/lib/Posix.elm");
    String bash = Resources.read("/elm/lib/Bash.elm");
    Object main = Project.load(source, posix, bash).main();
    return ScriptRunner.run(
        main, args, new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), out);
  }

  /**
   * Starts an HTTP server from a `handle`/`main` Elm app (the Server and Db modules are bundled). A
   * non-null {@code jdbcUrl} makes a {@code handle : Request -> Db Response} app run its queries
   * against that JDBC connection; a stateful {@code main : Server.Program} app ignores it.
   */
  public static HttpServer startServer(String source, int port, Path staticDir, String jdbcUrl)
      throws IOException {
    String lib = Resources.read("/elm/lib/Server.elm");
    String dbLib = Resources.read("/elm/lib/Db.elm");
    String backendLib = Resources.read("/elm/lib/Backend.elm");
    Project project = Project.load(source, lib, dbLib, backendLib);
    Object main = null;
    try {
      main = project.entryValue("main");
    } catch (RuntimeException ignored) {
      // No `main` -> a stateless `handle` app.
    }
    if (main instanceof ElmRecord r && r.has("onRequest")) {
      return ServerRunner.startStateful(r, port, staticDir);
    }
    return ServerRunner.start(project.entryValue("handle"), port, staticDir, jdbcUrl);
  }

  /** Starts the server and blocks until the process is interrupted. */
  public static void runServerBlocking(String source, int port, Path staticDir, String jdbcUrl)
      throws Exception {
    HttpServer server = startServer(source, port, staticDir, jdbcUrl);
    String dbNote = jdbcUrl == null ? "" : " (db: " + jdbcUrl + ")";
    System.out.println("Serving on http://localhost:" + port + dbNote + " (Ctrl-C to stop)");
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    Thread.currentThread().join();
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = Standalone.class.getResourceAsStream(path)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

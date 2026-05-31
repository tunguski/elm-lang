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
      runServerBlocking(source, port, null);
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

  /** Starts an HTTP server from a `handle`/`main` Elm app (the Server module is bundled). */
  public static HttpServer startServer(String source, int port, Path staticDir) throws IOException {
    String lib = Resources.read("/elm/lib/Server.elm");
    Project project = Project.load(source, lib);
    Object main = null;
    try {
      main = project.entryValue("main");
    } catch (RuntimeException ignored) {
      // No `main` -> a stateless `handle` app.
    }
    if (main instanceof ElmRecord r && r.has("onRequest")) {
      return ServerRunner.startStateful(r, port, staticDir);
    }
    return ServerRunner.start(project.entryValue("handle"), port, staticDir);
  }

  /** Starts the server and blocks until the process is interrupted. */
  public static void runServerBlocking(String source, int port, Path staticDir) throws Exception {
    HttpServer server = startServer(source, port, staticDir);
    System.out.println("Serving on http://localhost:" + port + " (Ctrl-C to stop)");
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    Thread.currentThread().join();
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = Standalone.class.getResourceAsStream(path)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

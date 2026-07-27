package pl.matsuo.elm.bundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bundled-app launcher must run a script (Posix/Bash) and start a server from embedded source. */
class StandaloneTest {

  private String runScript(String source, List<String> args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int code =
        Standalone.runScript(
            source,
            args,
            new ByteArrayInputStream(new byte[0]),
            new PrintStream(out, true, StandardCharsets.UTF_8));
    assertEquals(0, code, out.toString(StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void runsAPosixScriptWithArgs() {
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            getArgs (\\args -> print (String.join " " args) done)
        """;
    assertEquals("hello world", runScript(src, List.of("hello", "world")).strip());
  }

  @Test
  void runsAScriptUsingStructuredBashCommands() {
    // pwd is structured-free but exercises the Bash facade end to end through the launcher.
    String src =
        """
        module Main exposing (main)
        import Bash exposing (..)
        main : Io
        main =
            pwd (\\dir -> print (if String.isEmpty dir then "empty" else "ok") done)
        """;
    assertEquals("ok", runScript(src, List.of()).strip());
  }

  @Test
  void startsAServerFromEmbeddedSource() throws Exception {
    String src =
        """
        module Main exposing (handle)
        import Server exposing (..)
        handle : Request -> Response
        handle request =
            text "pong"
        """;
    HttpServer server = Standalone.startServer(src, 0, null, null); // port 0 -> ephemeral, no db
    try {
      int port = server.getAddress().getPort();
      HttpResponse<String> resp =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).build(),
                  HttpResponse.BodyHandlers.ofString());
      assertTrue(resp.body().contains("pong"), resp.body());
    } finally {
      server.stop(0);
    }
  }
}

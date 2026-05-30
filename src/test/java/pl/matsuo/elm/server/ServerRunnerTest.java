package pl.matsuo.elm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.util.Resources;

/** Tests the HTTP server runner: pure request dispatch and a real round-trip over a socket. */
class ServerRunnerTest {

  private static final String LIB = Resources.read("/elm/lib/Server.elm");
  private static final String APP = Resources.read("/elm/demos/simple-server-showcase.elm");
  private static final Object HANDLER = Project.load(APP, LIB).entryValue("handle");

  @Test
  void routesAndStatusesAreCorrect() {
    assertEquals(200, ServerRunner.dispatch(HANDLER, "GET", "/", "", "").status());
    assertTrue(ServerRunner.dispatch(HANDLER, "GET", "/", "", "").body().contains("Hello from Elm"));

    ServerRunner.Resp ping = ServerRunner.dispatch(HANDLER, "GET", "/ping", "", "");
    assertEquals("pong", ping.body());
    assertEquals("text/plain", ping.contentType());

    ServerRunner.Resp js = ServerRunner.dispatch(HANDLER, "GET", "/json", "", "");
    assertEquals("application/json", js.contentType());
    assertTrue(js.body().contains("\"lang\":\"elm\""), js.body());

    assertEquals(404, ServerRunner.dispatch(HANDLER, "GET", "/missing", "", "").status());
  }

  @Test
  void echoesPostBodyButRejectsGet() {
    assertEquals(
        "you said: hi there", ServerRunner.dispatch(HANDLER, "POST", "/echo", "", "hi there").body());
    assertEquals(405, ServerRunner.dispatch(HANDLER, "GET", "/echo", "", "").status());
  }

  @Test
  void resolvesPathParameters() {
    // /users/7 -> the `id` segment is captured and echoed as JSON.
    ServerRunner.Resp r = ServerRunner.dispatch(HANDLER, "GET", "/users/7", "", "");
    assertEquals("application/json", r.contentType());
    assertTrue(r.body().contains("\"id\":\"7\""), r.body());
  }

  @Test
  void readsQueryParameters() {
    assertEquals(
        "Hello, Ada!", ServerRunner.dispatch(HANDLER, "GET", "/hello", "name=Ada", "").body());
    // URL-encoded values are decoded.
    assertEquals(
        "Hello, Ada Lovelace!",
        ServerRunner.dispatch(HANDLER, "GET", "/hello", "name=Ada%20Lovelace", "").body());
    // Missing parameter -> the Nothing branch.
    assertEquals("Hello!", ServerRunner.dispatch(HANDLER, "GET", "/hello", "", "").body());
  }

  @Test
  void servesOverRealHttp() throws Exception {
    HttpServer server = ServerRunner.start(HANDLER, 0); // ephemeral port
    try {
      int port = server.getAddress().getPort();
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

      HttpResponse<String> ping =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ping")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, ping.statusCode());
      assertEquals("pong", ping.body());

      HttpResponse<String> echo =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/echo"))
                  .POST(HttpRequest.BodyPublishers.ofString("ahoy"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("you said: ahoy", echo.body());

      HttpResponse<String> missing =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/nope")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(404, missing.statusCode());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void servesStaticFilesBeforeTheHandler() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("static-");
    java.nio.file.Files.writeString(dir.resolve("index.html"), "<h1>static home</h1>");
    java.nio.file.Files.writeString(dir.resolve("style.css"), "body{color:red}");
    ServerRunner.Resp home = ServerRunner.serveStatic(dir, "/"); // "/" -> index.html
    assertEquals("text/html", home.contentType());
    assertTrue(home.body().contains("static home"));
    assertEquals("text/css", ServerRunner.serveStatic(dir, "/style.css").contentType());
    org.junit.jupiter.api.Assertions.assertNull(ServerRunner.serveStatic(dir, "/nope.js")); // falls through
    org.junit.jupiter.api.Assertions.assertNull(ServerRunner.serveStatic(dir, "/../secret")); // traversal refused
  }

  @Test
  void staticDirIsServedOverHttpThenHandler() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("static-http-");
    java.nio.file.Files.writeString(dir.resolve("app.js"), "console.log('hi')");
    HttpServer server = ServerRunner.start(HANDLER, 0, dir);
    try {
      int port = server.getAddress().getPort();
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpResponse<String> js =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/app.js")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertTrue(js.body().contains("console.log"), js.body()); // served from disk
      HttpResponse<String> ping =
          client.send(
              HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ping")).build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals("pong", ping.body()); // no file -> Elm handler
    } finally {
      server.stop(0);
    }
  }
}

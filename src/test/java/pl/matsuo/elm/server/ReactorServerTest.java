package pl.matsuo.elm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reactor dev server: serves a module list, compiles a module on the fly, shows compile errors,
 *  and bumps the hot-reload generation when a source file changes. */
class ReactorServerTest {

  private HttpServer server;
  private final HttpClient http = HttpClient.newHttpClient();

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String base() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private String get(String path) throws Exception {
    HttpResponse<String> r =
        http.send(HttpRequest.newBuilder(URI.create(base() + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return r.body();
  }

  @Test
  void servesIndexCompilesAModuleAndHotReloads(@TempDir Path dir) throws Exception {
    Files.writeString(
        dir.resolve("Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nmain = text (greet \"world\")\n"
            + "greet name = \"Hello, \" ++ name ++ \"!\"\n",
        StandardCharsets.UTF_8);
    server = ReactorServer.start(dir, 0);

    // Index lists the module.
    assertTrue(get("/").contains("Main.elm"), "index lists modules");

    // Compiling the module yields a page with the compiled app and the reload poller.
    String page = get("/Main.elm");
    assertTrue(page.contains("greet"), "compiled the module into the page");
    assertTrue(page.contains("/_reload"), "injected the hot-reload poller");

    // The reload generation bumps after a source change.
    long before = Long.parseLong(get("/_reload").trim());
    Thread.sleep(50);
    Files.writeString(dir.resolve("Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nmain = text \"edited\"\n",
        StandardCharsets.UTF_8);
    long deadline = System.currentTimeMillis() + 5000;
    long after = before;
    while (System.currentTimeMillis() < deadline && after == before) {
      Thread.sleep(150);
      after = Long.parseLong(get("/_reload").trim());
    }
    assertTrue(after > before, "file change bumped the reload generation");
  }

  @Test
  void showsACompileErrorPage(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("Bad.elm"), "module Bad exposing (main)\nmain = (1 +\n",
        StandardCharsets.UTF_8);
    server = ReactorServer.start(dir, 0);
    String page = get("/Bad.elm");
    assertTrue(page.contains("Could not compile Bad.elm"), page);
  }
}

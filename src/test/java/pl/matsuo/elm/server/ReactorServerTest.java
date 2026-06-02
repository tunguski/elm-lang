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
  void pushesAReloadEventOverServerSentEvents(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("Main.elm"),
        "module Main exposing (main)\nimport Html exposing (text)\nmain = text \"a\"\n",
        StandardCharsets.UTF_8);
    server = ReactorServer.start(dir, 0);
    int portNo = server.getAddress().getPort();
    try (java.net.Socket sock = new java.net.Socket("127.0.0.1", portNo)) {
      sock.setSoTimeout(5000);
      sock.getOutputStream().write("GET /_events HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
          .getBytes(StandardCharsets.UTF_8));
      sock.getOutputStream().flush();
      var in = new java.io.BufferedReader(
          new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
      // Read past the headers and the initial ": connected" comment.
      String line;
      boolean connected = false;
      while ((line = in.readLine()) != null && !connected) {
        if (line.startsWith(": connected")) {
          connected = true;
        }
      }
      assertTrue(connected, "received the SSE stream's initial comment");
      // Change the source -> the watcher bumps the generation -> a data event is pushed.
      Thread.sleep(50);
      Files.writeString(dir.resolve("Main.elm"),
          "module Main exposing (main)\nimport Html exposing (text)\nmain = text \"b\"\n",
          StandardCharsets.UTF_8);
      boolean gotData = false;
      while ((line = in.readLine()) != null) {
        if (line.startsWith("data:")) {
          gotData = true;
          break;
        }
      }
      assertTrue(gotData, "a reload event was pushed after the source changed");
    }
  }

  @Test
  void showsACompileErrorPage(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("Bad.elm"), "module Bad exposing (main)\nmain = (1 +\n",
        StandardCharsets.UTF_8);
    server = ReactorServer.start(dir, 0);
    String page = get("/Bad.elm");
    assertTrue(page.contains("Could not compile Bad.elm"), page);
  }

  @Test
  void showsALocatedTypeErrorWithExcerptAndCaret(@TempDir Path dir) throws Exception {
    // A type error (which the JS backend itself wouldn't catch) is surfaced with the located
    // excerpt and caret from the type checker.
    Files.writeString(
        dir.resolve("Mismatch.elm"),
        "module Mismatch exposing (main)\nmain = \"x\" + 1\n",
        StandardCharsets.UTF_8);
    server = ReactorServer.start(dir, 0);
    String page = get("/Mismatch.elm");
    assertTrue(page.contains("Could not compile Mismatch.elm"), page);
    assertTrue(page.contains("2 | main = \"x\" + 1"), page); // source excerpt
    assertTrue(page.contains("^"), page); // caret under the offending expression
  }

  @Test
  void compilesOnceThenServesAnUnchangedProjectFromCache(@TempDir Path dir) throws Exception {
    Files.writeString(
        dir.resolve("Main.elm"),
        "module Main exposing (main)\nimport Html\nmain = Html.text \"hi\"\n",
        StandardCharsets.UTF_8);
    ReactorServer.clearCache();
    String first = ReactorServer.compilePage(dir, "Main.elm", 1);
    long hits = ReactorServer.cacheHits();
    // A second request for the unchanged project is a cache hit (no recompile).
    String second = ReactorServer.compilePage(dir, "Main.elm", 2);
    assertEquals(hits + 1, ReactorServer.cacheHits());
    assertTrue(first.contains("hi") && second.contains("hi"));
    // The generation is still injected fresh per request even on a hit.
    assertTrue(second.contains("2"), second);

    // Editing the source changes the digest, so it recompiles instead of hitting the cache.
    Files.writeString(
        dir.resolve("Main.elm"),
        "module Main exposing (main)\nimport Html\nmain = Html.text \"bye\"\n",
        StandardCharsets.UTF_8);
    long before = ReactorServer.cacheHits();
    String third = ReactorServer.compilePage(dir, "Main.elm", 3);
    assertEquals(before, ReactorServer.cacheHits()); // recompiled, not served from cache
    assertTrue(third.contains("bye"), third);
  }
}

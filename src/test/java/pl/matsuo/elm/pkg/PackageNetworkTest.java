package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the remote package path end to end against an in-process HTTP server (no external
 * network): {@link HttpRegistry} solves from the served metadata and {@link PackageFetcher}
 * downloads the sources into the cache, where the project loader then finds them.
 */
class PackageNetworkTest {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** Serves files from {@code root} over HTTP; returns the base URL. */
  private String serve(Path root) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          Path file = root.resolve(exchange.getRequestURI().getPath().substring(1));
          if (Files.isRegularFile(file)) {
            byte[] body = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(body);
            }
          } else {
            exchange.sendResponseHeaders(404, -1);
          }
          exchange.close();
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Lays out acme/strings 1.0.0 in the remote tree, with the metadata files the protocol needs. */
  private static void writeRemotePackage(Path remote) throws IOException {
    Path v = remote.resolve("acme").resolve("strings").resolve("1.0.0");
    Files.createDirectories(v.resolve("src").resolve("Acme"));
    Files.writeString(remote.resolve("acme").resolve("strings").resolve("versions.txt"), "1.0.0\n");
    Files.writeString(
        v.resolve("elm.json"),
        "{ \"type\": \"package\", \"name\": \"acme/strings\", \"version\": \"1.0.0\","
            + " \"exposed-modules\": [\"Acme.Strings\"], \"dependencies\": {} }");
    Files.writeString(v.resolve("files.txt"), "elm.json\nsrc/Acme/Strings.elm\n");
    Files.writeString(
        v.resolve("src").resolve("Acme").resolve("Strings.elm"),
        "module Acme.Strings exposing (shout)\n\nshout s =\n    s ++ \"!\"\n");
  }

  @Test
  void remoteRegistryReportsVersionsAndDependencies(@TempDir Path remote) throws IOException {
    writeRemotePackage(remote);
    HttpRegistry reg = new HttpRegistry(serve(remote));
    assertEquals(List.of(Version.parse("1.0.0")), reg.versions("acme/strings"));
    assertTrue(reg.dependencies("acme/strings", Version.parse("1.0.0")).isEmpty());
    assertTrue(reg.versions("acme/missing").isEmpty(), "unknown package -> no versions");
  }

  @Test
  void fetcherDownloadsSourcesIntoTheCache(@TempDir Path root) throws Exception {
    Path remote = root.resolve("remote");
    writeRemotePackage(remote);
    String base = serve(remote);

    Path cache = root.resolve("cache");
    new PackageFetcher(base).fetch("acme/strings", Version.parse("1.0.0"), cache);

    Path landed = cache.resolve("acme").resolve("strings").resolve("1.0.0");
    assertTrue(Files.exists(landed.resolve("elm.json")), "elm.json downloaded");
    String module =
        Files.readString(landed.resolve("src").resolve("Acme").resolve("Strings.elm"), StandardCharsets.UTF_8);
    assertTrue(module.contains("module Acme.Strings"), module);
  }
}

package pl.matsuo.elm.pkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the package.elm-lang.org-style registry client and zipball fetcher against an in-process
 * HTTP server mimicking the real endpoints (all-packages, per-version elm.json, endpoint.json and a
 * GitHub-style source zip). No external network.
 */
class ElmRegistryTest {

  private HttpServer server;
  private String base;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private static byte[] sourceZip() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      // GitHub archives have a single top-level <repo>-<ref>/ directory the fetcher strips.
      zip.putNextEntry(new ZipEntry("strings-1.0.0/elm.json"));
      zip.write("{ \"type\": \"package\", \"dependencies\": {} }".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("strings-1.0.0/src/Acme/Strings.elm"));
      zip.write(
          "module Acme.Strings exposing (shout)\n\nshout s = s ++ \"!\"\n".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private static String sha256(byte[] data) {
    try {
      byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(data);
      StringBuilder hex = new StringBuilder();
      for (byte b : d) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  private void serve() throws IOException {
    byte[] zip = sourceZip();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          byte[] body;
          if (path.equals("/all-packages")) {
            body = "{ \"acme/strings\": [\"1.0.0\"] }".getBytes(StandardCharsets.UTF_8);
          } else if (path.equals("/packages/acme/strings/1.0.0/elm.json")) {
            body = "{ \"type\": \"package\", \"dependencies\": {} }".getBytes(StandardCharsets.UTF_8);
          } else if (path.equals("/packages/acme/strings/1.0.0/endpoint.json")) {
            // A real sha-256 of the zipball, so the fetcher's checksum verification runs and passes.
            body = ("{ \"url\": \"" + base + "/zip\", \"hash\": \"" + sha256(zip) + "\" }").getBytes(StandardCharsets.UTF_8);
          } else if (path.equals("/packages/acme/strings/1.0.0/docs.json")) {
            body = "[{\"name\":\"Acme.Strings\",\"comment\":\"\",\"values\":[{\"name\":\"shout\",\"comment\":\"\",\"type\":\"String -> String\"}]}]".getBytes(StandardCharsets.UTF_8);
          } else if (path.equals("/zip")) {
            body = zip;
          } else {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
          exchange.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @Test
  void elmRegistryReadsAllPackagesAndManifests() throws IOException {
    serve();
    ElmRegistry reg = new ElmRegistry(base);
    assertEquals(List.of(Version.parse("1.0.0")), reg.versions("acme/strings"));
    assertTrue(reg.dependencies("acme/strings", Version.parse("1.0.0")).isEmpty());
    assertTrue(reg.versions("acme/unknown").isEmpty());
  }

  @Test
  void fetchesPublishedDocsJsonAndLatestVersion() throws IOException {
    serve();
    ElmRegistry reg = new ElmRegistry(base);
    assertEquals(Version.parse("1.0.0"), reg.latest("acme/strings"));
    String docs = reg.docsJson("acme/strings", Version.parse("1.0.0"));
    assertTrue(docs.contains("Acme.Strings") && docs.contains("shout"), docs);
    assertEquals(null, reg.docsJson("acme/strings", Version.parse("9.9.9"))); // missing -> null
  }

  @Test
  void zipballFetcherUnpacksIntoTheCache(@TempDir Path cache) throws Exception {
    serve();
    new ElmPackageFetcher(base).fetch("acme/strings", Version.parse("1.0.0"), cache);
    Path landed = cache.resolve("acme").resolve("strings").resolve("1.0.0");
    assertTrue(Files.exists(landed.resolve("elm.json")), "elm.json unpacked (top dir stripped)");
    String module =
        Files.readString(landed.resolve("src").resolve("Acme").resolve("Strings.elm"), StandardCharsets.UTF_8);
    assertTrue(module.contains("module Acme.Strings"), module);
  }
}

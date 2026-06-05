package pl.matsuo.elm.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.util.Resources;
import org.junit.jupiter.api.Test;

/** Tests the stateful server runner and the live-dashboard example (in-memory series + ticks). */
class LiveDashboardTest {

  private static final String LIB = Resources.read("/elm/lib/Server.elm");
  private static final String APP = Resources.read("/elm/servers/LiveDashboard.elm");

  private static ElmRecord program() {
    return (ElmRecord) Project.load(APP, LIB).entryValue("main");
  }

  private static int seriesLength(ServerRunner.Stateful state) {
    return ((ElmList) ((ElmRecord) state.model()).get("series")).toJava().size();
  }

  @Test
  void servesItsOwnClientAssets() {
    ServerRunner.Stateful state = new ServerRunner.Stateful(program());
    assertEquals("text/html", state.handle("GET", "/", "", "").contentType());
    assertTrue(state.handle("GET", "/", "", "").body().contains("random walk"));
    assertEquals("text/css", state.handle("GET", "/style.css", "", "").contentType());
    assertEquals("application/javascript", state.handle("GET", "/app.js", "", "").contentType());
    ServerRunner.Resp api = state.handle("GET", "/api/series", "", "");
    assertEquals("application/json", api.contentType());
    assertEquals("[50]", api.body()); // the initial in-memory series
    assertEquals(404, state.handle("GET", "/nope", "", "").status());
  }

  @Test
  void tickAdvancesAndCapsTheInMemorySeries() {
    ServerRunner.Stateful state = new ServerRunner.Stateful(program());
    assertEquals(1, seriesLength(state));
    for (int i = 0; i < 5; i++) {
      state.tick();
    }
    assertEquals(6, seriesLength(state)); // grew by one per tick
    for (int i = 0; i < 100; i++) {
      state.tick();
    }
    assertEquals(60, seriesLength(state)); // capped at 60
    // Every value stays within the clamped 0..100 range.
    for (Object v : ((ElmList) ((ElmRecord) state.model()).get("series")).toJava()) {
      long n = (Long) v;
      assertTrue(n >= 0 && n <= 100, "value in range: " + n);
    }
  }

  @Test
  void servesAndPollsOverRealHttp() throws Exception {
    HttpServer server = ServerRunner.startStateful(program(), 0);
    try {
      int port = server.getAddress().getPort();
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      assertTrue(get(client, port, "/").contains("Server-side random walk"));
      assertTrue(get(client, port, "/app.js").contains("fetch('/api/series')"));
      String series = get(client, port, "/api/series");
      assertTrue(series.startsWith("[") && series.endsWith("]"), series);
    } finally {
      server.stop(0);
    }
  }

  private static String get(HttpClient c, int port, String path) throws Exception {
    return c.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.ofString())
        .body();
  }
}

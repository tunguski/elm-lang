package pl.matsuo.elm.html;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;

/**
 * `Tea.startLive` performs real HTTP for a Browser.element program's init command (what `elm run`
 * uses), against an in-process server — so effectful programs work outside the browser. Offline
 * `Tea.start` stays deterministic (canned/NetworkError), keeping other tests hermetic.
 */
class TeaLiveHttpTest {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String serve(int status, String body) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] b = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(b);
      }
      exchange.close();
    });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
  }

  private static String app(String url) {
    return """
        module Main exposing (main)
        import Browser
        import Html exposing (text)
        import Http
        main = Browser.element { init = init, update = update, view = view, subscriptions = subs }
        init _ = ( "loading", Http.get { url = "__URL__", expect = Http.expectString Got } )
        type Msg = Got (Result Http.Error String)
        update msg model = case msg of
            Got (Ok s) -> ( s, Cmd.none )
            Got (Err e) -> ( "error", Cmd.none )
        subs model = Sub.none
        view model = text model
        """
        .replace("__URL__", url);
  }

  @Test
  void liveModeFetchesRealHttpForElementInit() throws Exception {
    String url = serve(200, "hello from the server");
    Object program = Interpreter.load(app(url)).value("main");
    assertTrue(Tea.startLive(program).html().contains("hello from the server"),
        Tea.startLive(program).html());
  }

  @Test
  void liveModeReportsAnErrorOnNon200() throws Exception {
    String url = serve(500, "boom");
    Object program = Interpreter.load(app(url)).value("main");
    assertTrue(Tea.startLive(program).html().contains("error"), "non-200 -> Http.Error");
  }

  @Test
  void offlineModeDoesNotHitTheNetwork() throws Exception {
    Object program = Interpreter.load(app("http://127.0.0.1:0/never")).value("main");
    assertTrue(Tea.start(program).html().contains("error"), "offline -> NetworkError, no real request");
  }
}

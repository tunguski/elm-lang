package pl.matsuo.elm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;

/**
 * Verifies the pluggable {@link HttpHandler} seam: the handler sees the request method and body, a
 * 2xx response is decoded and delivered, and a non-2xx status becomes {@code Http.BadStatus}.
 */
class HttpHandlerTest {

  private static final String SRC =
      """
      module Main exposing (main)

      import Browser
      import Html exposing (text)
      import Http
      import Json.Decode as D
      import Json.Encode as E

      type Msg = Saved (Result Http.Error String)

      describe result =
          case result of
              Ok name -> "ok:" ++ name
              Err (Http.BadStatus code) -> "status:" ++ String.fromInt code
              Err Http.NetworkError -> "offline"
              Err _ -> "other"

      init flags =
          ( "pending"
          , Http.post
              { url = "/api/save"
              , body = Http.jsonBody (E.object [ ( "name", E.string "milk" ) ])
              , expect = Http.expectJson Saved (D.field "name" D.string)
              }
          )

      update msg model =
          case msg of
              Saved r -> ( describe r, Cmd.none )

      view model = text model

      main =
          Browser.element
              { init = init, update = update, subscriptions = \\_ -> Sub.none, view = view }
      """;

  @Test
  void handlerSeesMethodAndBodyAndDeliversDecodedOk() {
    List<HttpHandler.Request> seen = new ArrayList<>();
    HttpHandler handler =
        request -> {
          seen.add(request);
          return HttpHandler.Response.of(200, "{\"name\":\"milk\"}");
        };

    Interpreter interp = Interpreter.load(SRC);
    Tea app = Tea.start(interp.value("main"), handler);

    assertEquals(1, seen.size());
    assertEquals("POST", seen.get(0).method());
    assertEquals("/api/save", seen.get(0).url());
    assertTrue(seen.get(0).body().contains("\"name\""), seen.get(0).body());
    assertTrue(seen.get(0).body().contains("milk"), seen.get(0).body());
    assertTrue(app.html().contains("ok:milk"), app.html());
  }

  @Test
  void nonSuccessStatusBecomesBadStatus() {
    HttpHandler handler = request -> HttpHandler.Response.of(503, "nope");
    Interpreter interp = Interpreter.load(SRC);
    Tea app = Tea.start(interp.value("main"), handler);
    assertTrue(app.html().contains("status:503"), app.html());
  }

  @Test
  void networkErrorWhenHandlerReportsFailure() {
    HttpHandler handler = request -> HttpHandler.Response.NETWORK_ERROR;
    Interpreter interp = Interpreter.load(SRC);
    Tea app = Tea.start(interp.value("main"), handler);
    assertTrue(app.html().contains("offline"), app.html());
  }
}

package pl.matsuo.elm.html;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;

/** Drives the fuller Http surface — Http.request with a method, headers, a body and
 *  Http.expectWhatever — headlessly, plus Http.Error construction. */
class HttpRequestTest {

  @Test
  void requestWithHeadersBodyAndExpectWhatever() {
    String src =
        """
        module Main exposing (main)

        import Browser
        import Html exposing (text)
        import Http

        type Msg = Done (Result Http.Error ())

        init flags =
            ( "pending"
            , Http.request
                { method = "POST"
                , headers = [ Http.header "X-Test" "1" ]
                , url = "/save"
                , body = Http.stringBody "text/plain" "hi"
                , expect = Http.expectWhatever Done
                , timeout = Nothing
                , tracker = Nothing
                }
            )

        update msg model =
            case msg of
                Done (Ok ()) -> ( "saved", Cmd.none )
                Done (Err _) -> ( "failed", Cmd.none )

        view model = text model

        main =
            Browser.element
                { init = init, update = update, subscriptions = \\_ -> Sub.none, view = view }
        """;
    Interpreter interp = Interpreter.load(src);
    // A canned response for /save makes expectWhatever succeed with Ok ().
    Tea app = Tea.start(interp.value("main"), Map.of("/save", ""));
    assertTrue(app.html().contains("saved"), app.html());
  }

  @Test
  void offlineRequestDeliversNetworkError() {
    String src =
        """
        module Main exposing (main)

        import Browser
        import Html exposing (text)
        import Http

        type Msg = Done (Result Http.Error ())

        describe err =
            case err of
                Http.BadStatus code -> "status " ++ String.fromInt code
                Http.NetworkError -> "offline"
                _ -> "other"

        init flags =
            ( "pending"
            , Http.request
                { method = "GET", headers = [], url = "/missing"
                , body = Http.emptyBody, expect = Http.expectWhatever Done
                , timeout = Nothing, tracker = Nothing
                }
            )

        update msg model =
            case msg of
                Done (Ok ()) -> ( "ok", Cmd.none )
                Done (Err e) -> ( describe e, Cmd.none )

        view model = text model

        main =
            Browser.element
                { init = init, update = update, subscriptions = \\_ -> Sub.none, view = view }
        """;
    Interpreter interp = Interpreter.load(src);
    Tea app = Tea.start(interp.value("main")); // offline, no canned response
    assertTrue(app.html().contains("offline"), app.html());
  }
}

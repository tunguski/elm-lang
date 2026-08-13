package pl.matsuo.elm.html;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;

/**
 * Verifies {@code Cmd.map} composes the message tagger onto a command (here an {@code Http.get}), so
 * a nested TEA program's HTTP result is delivered to the parent's {@code update} through the mapping
 * — the pattern every {@code Browser.application} with sub-pages relies on.
 */
class CmdMapTest {

  @Test
  void cmdMapTagsAWrappedHttpResult() {
    String src =
        """
        module Main exposing (main)

        import Browser
        import Html exposing (text)
        import Http

        type Child = GotData (Result Http.Error String)
        type Msg = Wrap Child

        childInit =
            Http.get { url = "/data", expect = Http.expectString GotData }

        init flags =
            ( "start", Cmd.map Wrap childInit )

        update msg model =
            case msg of
                Wrap (GotData (Ok body)) -> ( "wrapped:" ++ body, Cmd.none )
                Wrap (GotData (Err _)) -> ( "wrapped-error", Cmd.none )

        view model = text model

        main =
            Browser.element
                { init = init, update = update, subscriptions = \\_ -> Sub.none, view = view }
        """;
    Interpreter interp = Interpreter.load(src);
    Tea app = Tea.start(interp.value("main"), Map.of("/data", "hello"));
    assertTrue(app.html().contains("wrapped:hello"), app.html());
  }
}

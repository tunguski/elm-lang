package pl.matsuo.elm.html;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;

/** Drives a {@code Browser.application} and a {@code Browser.Dom.getElement} task headlessly. */
class BrowserApplicationTest {

  @Test
  void runsABrowserApplicationHeadlessly() {
    String src =
        """
        module Main exposing (main)

        import Browser
        import Html exposing (text)

        type Msg = Bump

        init flags url key = ( 0, Cmd.none )

        update msg model = ( model + 1, Cmd.none )

        view model = { title = "App", body = [ text (String.fromInt model) ] }

        main =
            Browser.application
                { init = init
                , update = update
                , subscriptions = \\_ -> Sub.none
                , view = view
                , onUrlRequest = \\_ -> Bump
                , onUrlChange = \\_ -> Bump
                }
        """;
    Interpreter interp = Interpreter.load(src);
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("0"), app.html());
    app.send(interp.evalExpr("Bump"));
    assertTrue(app.html().contains("1"), app.html());
  }

  @Test
  void getElementTaskDeliversAnElementBox() {
    // init runs Browser.Dom.getElement through Task.attempt; the result box's width reaches the model.
    String src =
        """
        module Main exposing (main)

        import Browser
        import Browser.Dom
        import Html exposing (text)
        import Task

        type Msg = Got (Result Browser.Dom.Error Browser.Dom.Element)

        init flags =
            ( 0, Task.attempt Got (Browser.Dom.getElement "x") )

        update msg model =
            case msg of
                Got (Ok box) -> ( round box.element.width, Cmd.none )
                Got (Err _) -> ( -1, Cmd.none )

        view model = text (String.fromInt model)

        main =
            Browser.element
                { init = init
                , update = update
                , subscriptions = \\_ -> Sub.none
                , view = view
                }
        """;
    Interpreter interp = Interpreter.load(src);
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains("100"), app.html()); // the stub element box is 100 wide
  }
}

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

  @Test
  void taskMap2Through5CombineTaskResults() {
    // Task.map2..map5 run each task (left to right) and apply the function to the results. Each app's
    // init performs the combined task; the resolved sum lands in the model and renders.
    checkTask("Task.map2 (\\a b -> a + b) (Task.succeed 1) (Task.succeed 2)", "3");
    checkTask("Task.map3 (\\a b c -> a + b + c) (Task.succeed 1) (Task.succeed 2) (Task.succeed 3)", "6");
    checkTask(
        "Task.map4 (\\a b c d -> a + b + c + d) "
            + "(Task.succeed 1) (Task.succeed 2) (Task.succeed 3) (Task.succeed 4)",
        "10");
    checkTask(
        "Task.map5 (\\a b c d e -> a + b + c + d + e) "
            + "(Task.succeed 1) (Task.succeed 2) (Task.succeed 3) (Task.succeed 4) (Task.succeed 5)",
        "15");
  }

  @Test
  void processSpawnAndKillRunHeadlessly() {
    // Process.spawn runs the task (headlessly, synchronously) and yields a process id; piping that to
    // Process.kill succeeds with (); the whole chain performs and flips the model.
    checkTask(
        "Process.spawn (Task.succeed 1) |> Task.andThen Process.kill |> Task.map (\\_ -> 7)", "7");
  }

  private static void checkTask(String taskExpr, String expected) {
    String src =
        """
        module Main exposing (main)

        import Browser
        import Html exposing (text)
        import Task

        type Msg = Got Int

        main =
            Browser.element
                { init = \\_ -> ( 0, Task.perform Got (%s) )
                , update = \\msg model ->
                    case msg of
                        Got n -> ( n, Cmd.none )
                , view = \\m -> text (String.fromInt m)
                , subscriptions = \\_ -> Sub.none
                }
        """
            .formatted(taskExpr);
    Interpreter interp = Interpreter.load(src);
    Tea app = Tea.start(interp.value("main"));
    assertTrue(app.html().contains(expected), "expected " + expected + " in: " + app.html());
  }
}

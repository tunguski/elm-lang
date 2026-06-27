package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.types.TypeChecker;

/** A `port module` type-checks and compiles to a JS bundle exposing the ports (no browser needed). */
class PortTest {

  private static final String PORTS =
      """
      port module Main exposing (main)
      import Browser
      import Html exposing (text)
      port save : String -> Cmd msg
      port load : (String -> msg) -> Sub msg
      type Msg = Loaded String
      main = Browser.element
          { init = \\_ -> ( "", save "hello" )
          , update = \\msg model -> case msg of
              Loaded s -> ( s, Cmd.none )
          , view = \\model -> text model
          , subscriptions = \\_ -> load Loaded
          }
      """;

  @Test
  void portModuleTypeChecks() {
    Map<String, String> t = TypeChecker.checkModule(PORTS);
    // The outgoing port is a function to a Cmd; the incoming port produces a Sub.
    assertTrue(t.containsKey("save"), t.toString());
    assertTrue(t.get("save").contains("Cmd"), "save : String -> Cmd msg, got " + t.get("save"));
    assertTrue(t.get("load").contains("Sub"), "load : (String -> msg) -> Sub msg, got " + t.get("load"));
  }

  @Test
  void portModuleCompilesToABundleWithPorts() {
    String page = JsCompiler.htmlPage(PORTS, null);
    assertTrue(page.contains("$start"), "compiles to a runnable bundle");
    // Both port names reach the generated code (wired to the $ports runtime).
    assertTrue(page.contains("save"), "outgoing port present");
    assertTrue(page.contains("load"), "incoming port present");
  }
}

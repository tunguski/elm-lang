package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.html.Tea;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.types.TypeChecker;

/** End-to-end coverage for `port module` programs across the type checker, interpreter and JS. */
class PortsTest {

  private static String ports() throws Exception {
    try (InputStream in = PortsTest.class.getResourceAsStream("/elm/examples/Ports.elm")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void portModuleTypeChecks() throws Exception {
    Map<String, String> types = TypeChecker.checkModule(ports());
    assertTrue(types.get("main").startsWith("Program"), types.get("main"));
    // Outgoing port: a function from the sent value to a Cmd (the msg var is generalized).
    assertEquals("String -> Cmd a", types.get("toJs"));
    // Incoming port: from a tagger to a Sub.
    assertEquals("(String -> a) -> Sub a", types.get("fromJs"));
  }

  @Test
  void portModuleRunsHeadlessly() throws Exception {
    // The Browser.element program mounts and renders; the outgoing port Cmd is inert headlessly.
    Object program = Interpreter.load(ports()).value("main");
    String html = Tea.start(program).html();
    assertTrue(html.contains("waiting"), html);
    assertTrue(html.contains("send"), html);
  }

  @Test
  void jsBundleWiresPortsToTheKernel() throws Exception {
    String bundle = JsCompiler.appBundle(ports());
    assertTrue(bundle.contains("$portOut(\"toJs\")"), "outgoing port compiled");
    assertTrue(bundle.contains("$portIn(\"fromJs\")"), "incoming port compiled");
    assertTrue(bundle.contains("ports: $portsApi()"), "app exposes a ports object");
  }
}

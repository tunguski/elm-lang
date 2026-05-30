package pl.matsuo.elm.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LspServerTest {

  private final LspServer server = new LspServer();

  @Test
  void cleanModuleHasNoDiagnostics() {
    assertTrue(server.diagnose("main = 1 + 2\n").isEmpty());
  }

  @Test
  void typeErrorBecomesALocatedDiagnostic() {
    List<LspServer.Diagnostic> diags = server.diagnose("foo = 1\nmain = \"x\" + 1\n");
    assertEquals(1, diags.size(), diags.toString());
    assertEquals(1, diags.get(0).line()); // 0-based -> line 2 of source
    assertTrue(diags.get(0).message().toLowerCase().contains("number"), diags.get(0).message());
  }

  @Test
  void syntaxErrorBecomesADiagnostic() {
    assertFalse(server.diagnose("main = (1 + \n").isEmpty());
  }

  @Test
  void hoverReturnsInferredType() {
    String src = "double n = n * 2\nmain = double 21\n";
    assertEquals(Optional.of("double : number -> number"), server.hoverType(src, 0));
    assertEquals(Optional.of("main : number"), server.hoverType(src, 1));
  }

  @Test
  void initializeAdvertisesCapabilitiesOverJsonRpc() throws Exception {
    String body =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    String framed = "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    server.handle(
        LspServer.readMessage(new ByteArrayInputStream(framed.getBytes(StandardCharsets.UTF_8))), out);
    String response = out.toString(StandardCharsets.UTF_8);
    assertTrue(response.contains("Content-Length:"), response);
    assertTrue(response.contains("hoverProvider"), response);
    assertTrue(response.contains("textDocumentSync"), response);
  }
}

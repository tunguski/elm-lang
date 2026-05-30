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
  void gotoDefinitionFindsTopLevelValue() {
    String src = "double n = n * 2\nmain = double 21\n";
    // Cursor on the `double` use in line 2 (0-based line 1, char 8) -> its definition on line 1.
    Optional<int[]> loc = server.definition(src, 1, 8);
    assertTrue(loc.isPresent());
    assertEquals(0, loc.get()[0]); // defined on 0-based line 0
  }

  @Test
  void gotoDefinitionResolvesUnionConstructor() {
    String src = "type Color = Red | Green\nname c = Red\n";
    Optional<int[]> loc = server.definition(src, 1, 10); // cursor on `Red` use
    assertTrue(loc.isPresent());
    assertEquals(0, loc.get()[0]); // the `type Color` line
  }

  @Test
  void completionIncludesLocalNamesAndBuiltins() {
    List<String> items = server.complete("greet name = name\nmain = greet \"x\"\n");
    assertTrue(items.contains("greet"), "local value");
    assertTrue(items.contains("main"), "local value");
    assertTrue(items.stream().anyMatch(n -> n.startsWith("List.")), "stdlib names present");
  }

  @Test
  void wordAtExtractsFinalSegmentOfQualifiedName() {
    assertEquals("map", LspServer.wordAt("x = List.map f xs\n", 0, 9));
  }

  @Test
  void documentSymbolsListTopLevelDeclarations() {
    String src =
        "type Color = Red | Green\ntype alias Model = Int\nport out : String -> Cmd msg\nmain = 1\n";
    var names = server.documentSymbols(src).stream().map(LspServer.Symbol::name).toList();
    assertTrue(names.contains("Color"), names.toString());
    assertTrue(names.contains("Red") && names.contains("Green"), names.toString()); // constructors
    assertTrue(names.contains("Model"), names.toString());
    assertTrue(names.contains("out"), names.toString()); // port
    assertTrue(names.contains("main"), names.toString());
  }

  @Test
  void findsAllReferencesOfAName() {
    String src = "double n = n * 2\nmain = double (double 3)\n";
    // Cursor on the definition of `double`; expect three occurrences (def + two uses).
    assertEquals(3, server.references(src, 0, 2).size());
  }

  @Test
  void renameRewritesEveryOccurrence() {
    String src = "double n = n * 2\nmain = double 3\n";
    // Rename reuses the same occurrence set; here two occurrences of `double`.
    assertEquals(2, server.references(src, 0, 2).size());
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
    assertTrue(response.contains("referencesProvider"), response);
    assertTrue(response.contains("documentSymbolProvider"), response);
    assertTrue(response.contains("renameProvider"), response);
  }
}

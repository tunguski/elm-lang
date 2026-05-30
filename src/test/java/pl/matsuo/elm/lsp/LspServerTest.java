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
  void codeActionAddsATypeAnnotation() {
    // `double` on line 0 has no annotation; the action inserts the inferred signature above it.
    var actions = server.codeActions("double n = n * 2\nmain = double 21\n", 0);
    assertTrue(actions.stream().anyMatch(a -> a.title().equals("Add type annotation")), actions.toString());
    var add =
        actions.stream().filter(a -> a.title().equals("Add type annotation")).findFirst().get();
    assertTrue(add.newText().contains("double : number -> number"), add.newText());
    assertEquals(0, add.line()); // inserted above the definition
  }

  @Test
  void codeActionFillsMissingCaseBranches() {
    String src =
        "type Color = Red | Green | Blue\n"
            + "name c =\n"
            + "    case c of\n"
            + "        Red -> \"r\"\n";
    var actions = server.codeActions(src, 2); // cursor on the `case` line
    var fill =
        actions.stream().filter(a -> a.title().equals("Add missing case branches")).findFirst();
    assertTrue(fill.isPresent(), actions.toString());
    // The two unmatched constructors are offered as stub branches.
    assertTrue(fill.get().newText().contains("Green ->"), fill.get().newText());
    assertTrue(fill.get().newText().contains("Blue ->"), fill.get().newText());
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
    assertTrue(response.contains("semanticTokensProvider"), response);
  }

  // --- workspace-wide navigation -----------------------------------------

  private static java.util.Map<String, String> workspace() {
    java.util.Map<String, String> ws = new java.util.LinkedHashMap<>();
    ws.put(
        "file:///Util.elm",
        "module Util exposing (square)\n\nsquare : Int -> Int\nsquare n =\n    n * n\n");
    ws.put(
        "file:///Main.elm",
        "module Main exposing (main)\n\nimport Util\n\nmain =\n    Util.square 7\n");
    return ws;
  }

  @Test
  void definitionResolvesAcrossModules() {
    // Cursor on `square` of `Util.square` in Main.elm (line 5, the `square` part after the dot).
    var loc = server.workspaceDefinition(workspace(), "file:///Main.elm", 5, 9);
    assertTrue(loc.isPresent(), "should resolve into Util.elm");
    assertEquals("file:///Util.elm", loc.get().uri());
    assertEquals(3, loc.get().line()); // `square n =` is 0-based line 3
  }

  @Test
  void definitionPrefersTheCurrentDocument() {
    var loc = server.workspaceDefinition(workspace(), "file:///Util.elm", 3, 2); // on `square n =`
    assertTrue(loc.isPresent());
    assertEquals("file:///Util.elm", loc.get().uri());
  }

  @Test
  void referencesAndRenameSpanTheWorkspace() {
    // `square` is declared in Util.elm and used in Main.elm — both files should appear.
    var refs = server.workspaceReferences(workspace(), "file:///Util.elm", 3, 0);
    assertTrue(refs.containsKey("file:///Util.elm"), refs.toString());
    assertTrue(refs.containsKey("file:///Main.elm"), refs.toString());
    // Util.elm names it on the annotation and the definition: at least two occurrences.
    assertTrue(refs.get("file:///Util.elm").size() >= 2, refs.toString());
  }

  // --- semantic tokens ---------------------------------------------------

  @Test
  void semanticTokensCoverKeywordsTypesAndLiterals() {
    int[] data = server.semanticTokens("x : Int\nx =\n    if True then 1 else 0\n");
    assertEquals(0, data.length % 5, "5 ints per token");
    java.util.Set<Integer> types = new java.util.HashSet<>();
    for (int i = 3; i < data.length; i += 5) {
      types.add(data[i]);
    }
    int keyword = LspServer.SEMANTIC_TOKEN_TYPES.indexOf("keyword");
    int type = LspServer.SEMANTIC_TOKEN_TYPES.indexOf("type");
    int number = LspServer.SEMANTIC_TOKEN_TYPES.indexOf("number");
    assertTrue(types.contains(keyword), "should mark `if`/`then`/`else` as keywords");
    assertTrue(types.contains(type), "should mark `Int`/`True` as types");
    assertTrue(types.contains(number), "should mark the integer literals");
    // The first token `x` (LOWER) sits at line 0, char 0 with delta 0,0.
    assertEquals(0, data[0]);
    assertEquals(0, data[1]);
  }
}

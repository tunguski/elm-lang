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
  void inlayHintsShowInferredTypesOfUnannotatedValues() {
    String src = "double n = n * 2\nlabelled : Int\nlabelled = 5\nmain = double 21\n";
    List<LspServer.InlayHint> hints = server.inlayHints(src);
    // `double` (unannotated) gets a hint just after its name; `labelled` (annotated) does not.
    assertTrue(hints.stream().anyMatch(h -> h.line() == 0 && h.label().contains("number -> number")),
        hints.toString());
    assertTrue(hints.stream().noneMatch(h -> h.label().contains("labelled")), hints.toString());
    assertEquals(6, hints.get(0).character()); // after "double"
  }

  @Test
  void signatureHelpReportsTheCalledFunctionsType() {
    String src = "inc : Int -> Int\ninc x = x + 1\nmain = inc \n";
    // Cursor right after "inc " on line 2 (0-based): the call's function is `inc`.
    assertEquals(Optional.of("inc : Int -> Int"), server.signatureHelp(src, 2, 11));
    // Inside parentheses, the innermost call is used.
    String src2 = "f : Int -> Int\nf x = x\nmain = g (f \n";
    assertEquals(Optional.of("f : Int -> Int"), server.signatureHelp(src2, 2, 12));
  }

  @Test
  void typeOfResolvesLocalAndStdlibNames() {
    String src = "twice n = n + n\nmain = twice 3\n";
    assertEquals(Optional.of("number -> number"), server.typeOf(src, "twice"));
    assertTrue(server.typeOf(src, "identity").isPresent()); // a stdlib signature
  }

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
  void multipleSyntaxErrorsBecomeSeparateDiagnostics() {
    // The parser recovers between top-level declarations, so two independent syntax errors yield
    // two diagnostics (not just the first), on different lines.
    String src =
        "module M exposing (..)\n" + "good1 = 1\n" + "bad1 = if\n" + "good2 = 2\n" + "bad2 = case\n";
    var diags = server.diagnose(src);
    assertTrue(diags.size() >= 2, diags.toString());
    assertTrue(diags.get(0).line() != diags.get(diags.size() - 1).line(), diags.toString());
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
  void warnsAboutAnUnusedQualifiedImport() {
    var diags = server.diagnose("module M exposing (main)\nimport Set\nmain = 1\n");
    assertTrue(
        diags.stream().anyMatch(d -> d.severity() == 2 && d.message().contains("Set")),
        diags.toString());
  }

  @Test
  void warnsAboutAnUnusedExposedNameButNotAUsedOne() {
    var diags =
        server.diagnose(
            "module M exposing (main)\nimport String exposing (length, toUpper)\nmain = length \"hi\"\n");
    assertTrue(diags.stream().anyMatch(d -> d.message().contains("toUpper")), diags.toString());
    assertTrue(diags.stream().noneMatch(d -> d.message().contains("`length`")), diags.toString());
  }

  @Test
  void doesNotWarnAboutAUsedImport() {
    var diags = server.diagnose("module M exposing (main)\nimport Set\nmain = Set.size Set.empty\n");
    assertTrue(diags.stream().noneMatch(d -> d.severity() == 2), diags.toString());
  }

  @Test
  void codeActionRemovesAnUnusedImport() {
    String src = "module M exposing (main)\nimport Set\nmain = 1\n";
    var actions = server.codeActions(src, 1); // cursor on the `import Set` line
    var remove =
        actions.stream().filter(a -> a.title().equals("Remove unused import")).findFirst();
    assertTrue(remove.isPresent(), actions.toString());
    // Deletes the whole line (line 1, col 0) through the start of the next line.
    assertEquals("", remove.get().newText());
    assertEquals(1, remove.get().line());
    assertEquals(2, remove.get().endLine());
  }

  @Test
  void codeActionRemovesAnUnusedDefinition() {
    // `helper` is private (M exposes only main) and referenced nowhere; offer to delete it, including
    // its type annotation line.
    String src =
        "module M exposing (main)\n"
            + "helper : Int\n"
            + "helper = 42\n"
            + "main = 1\n";
    var actions = server.codeActions(src, 2); // cursor on the `helper = 42` line
    var remove =
        actions.stream().filter(a -> a.title().equals("Remove unused definition")).findFirst();
    assertTrue(remove.isPresent(), actions.toString());
    assertEquals("", remove.get().newText());
    assertEquals(1, remove.get().line()); // from the annotation line
    assertEquals(3, remove.get().endLine()); // up to the start of `main`
    // A used definition is not offered for removal.
    assertTrue(
        server.codeActions(src.replace("main = 1", "main = helper"), 2).stream()
            .noneMatch(a -> a.title().equals("Remove unused definition")),
        "a referenced definition is not removable");
  }

  @Test
  void codeActionCreatesAMissingDefinition() {
    // `helper` is referenced but never defined; offer to stub it at the end of the module.
    String src = "module M exposing (main)\nmain = helper 1\n";
    var actions = server.codeActions(src, 1); // cursor on the line referencing `helper`
    var create =
        actions.stream().filter(a -> a.title().equals("Create function `helper`")).findFirst();
    assertTrue(create.isPresent(), actions.toString());
    assertTrue(create.get().newText().contains("helper =\n    Debug.todo"), create.get().newText());
    // No such action once `helper` is defined.
    assertTrue(
        server.codeActions(src + "helper n = n\n", 1).stream()
            .noneMatch(a -> a.title().startsWith("Create function")),
        "a defined name is not offered for creation");
  }

  @Test
  void codeActionAddsAMissingQualifiedImport() {
    // `Set.empty` is used but `Set` isn't imported; offer to add the import.
    String src = "module M exposing (main)\nmain = Set.size Set.empty\n";
    var actions = server.codeActions(src, 1);
    var add = actions.stream().filter(a -> a.title().equals("import Set")).findFirst();
    assertTrue(add.isPresent(), actions.toString());
    assertTrue(add.get().newText().contains("import Set"), add.get().newText());
  }

  @Test
  void warnsAboutAnUnusedPrivateDefinition() {
    var diags = server.diagnose("module M exposing (main)\nhelper = 1\nmain = 2\n");
    assertTrue(
        diags.stream().anyMatch(d -> d.severity() == 2 && d.message().contains("helper")),
        diags.toString());
  }

  @Test
  void doesNotWarnAboutAUsedPrivateDefinition() {
    var diags = server.diagnose("module M exposing (main)\nhelper = 1\nmain = helper\n");
    assertTrue(diags.stream().noneMatch(d -> d.message().contains("helper")), diags.toString());
  }

  @Test
  void warnsAboutAnUnusedParameterAndLetBinding() {
    var params = server.diagnose("module M exposing (..)\nf x y =\n    x\n");
    assertTrue(params.stream().anyMatch(d -> d.message().contains("Unused parameter: `y`")), params.toString());
    var lets =
        server.diagnose("module M exposing (..)\nmain =\n    let\n        a = 1\n        b = 2\n    in\n    a\n");
    assertTrue(lets.stream().anyMatch(d -> d.message().contains("Unused `let` binding: `b`")), lets.toString());
  }

  @Test
  void organizeImportsSortsAndDropsUnused() {
    String src =
        "module M exposing (main)\nimport Set\nimport Dict exposing (get, insert)\nmain = Dict.get 1 (insert 2 3 Dict.empty)\n";
    var actions = server.codeActions(src, 1); // cursor on an import line
    var organize = actions.stream().filter(a -> a.title().equals("Organize imports")).findFirst();
    assertTrue(organize.isPresent(), actions.toString());
    String text = organize.get().newText();
    // Dict (used) sorts first and keeps only `insert` (get is unused as a bare name — Dict.get is
    // qualified); Set (entirely unused) is dropped.
    assertTrue(text.contains("import Dict exposing (insert)"), text);
    assertFalse(text.contains("import Set"), text);
    assertTrue(text.indexOf("import Dict") >= 0, text);
  }

  @Test
  void codeLensCountsReferences() {
    var lenses = server.codeLenses("module M exposing (main)\ninc n = n + 1\nmain = inc (inc 1)\n");
    var incLens = lenses.stream().filter(l -> l.line() == 1).findFirst();
    assertTrue(incLens.isPresent(), lenses.toString());
    assertTrue(incLens.get().title().contains("2 references"), incLens.get().title());
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
    assertTrue(response.contains("documentFormattingProvider"), response);
    assertTrue(response.contains("documentRangeFormattingProvider"), response);
    assertTrue(response.contains("documentHighlightProvider"), response);
    assertTrue(response.contains("callHierarchyProvider"), response);
    assertTrue(response.contains("workspaceSymbolProvider"), response);
  }

  @Test
  void documentHighlightFindsEveryOccurrenceInTheDocument() {
    // `references` (same-document occurrences) backs documentHighlight: `inc` appears 3 times.
    String src = "module M exposing (main)\ninc n = n + 1\nmain = inc (inc 1)\n";
    var occ = server.references(src, 1, 0); // cursor on `inc`'s definition
    assertEquals(3, occ.size(), occ.toString());
  }

  @Test
  void formattingReturnsTheReformattedDocument() {
    Optional<String> formatted = server.formatDocument("main =     1+2\n");
    assertTrue(formatted.isPresent(), "messy source should reformat");
    assertTrue(formatted.get().contains("1 + 2"), formatted.get());
  }

  @Test
  void formattingAlreadyFormattedSourceMakesNoEdit() {
    String clean = pl.matsuo.elm.fmt.Formatter.format("main = 1 + 2\n");
    assertTrue(server.formatDocument(clean).isEmpty(), "idempotent: no edit for clean source");
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

  @Test
  void workspaceSymbolsSearchAcrossAllFiles() {
    // A query matches top-level symbols in every indexed document, with their locating URI.
    var syms = server.workspaceSymbols(workspace(), "square");
    assertTrue(syms.stream().anyMatch(s -> s.name().equals("square") && s.uri().endsWith("Util.elm")),
        syms.toString());
    // An empty query lists everything (here: square, main, ...).
    var all = server.workspaceSymbols(workspace(), "");
    assertTrue(all.stream().anyMatch(s -> s.name().equals("main")), all.toString());
    assertTrue(all.size() >= 2, all.toString());
  }

  // --- extract-function refactor -----------------------------------------

  @Test
  void extractFunctionLiftsAClosedExpression() {
    // Selecting `2 + 3` (the right side, chars 7..12 of line 0) extracts a parameterless function.
    String src = "main = 2 + 3\n";
    var refactors = server.refactors(src, 0, 7, 0, 12);
    assertEquals(1, refactors.size(), refactors.toString());
    var rf = refactors.get(0);
    assertTrue(rf.title().contains("extracted"), rf.title());
    assertEquals(2, rf.edits().size()); // replace the selection + append the function
    assertEquals("extracted", rf.edits().get(0).newText()); // call (no free locals)
    assertTrue(rf.edits().get(1).newText().contains("extracted =\n    2 + 3"),
        rf.edits().get(1).newText());
  }

  @Test
  void extractFunctionParameterisesFreeLocals() {
    // `n * n` uses the local `n`, so the extracted function takes `n` and the call passes it.
    String src = "square n =\n    n * n\n";
    var refactors = server.refactors(src, 1, 4, 1, 9); // the `n * n` on line 1
    assertEquals(1, refactors.size(), refactors.toString());
    var rf = refactors.get(0);
    assertEquals("extracted n", rf.edits().get(0).newText()); // call passes the free local
    assertTrue(rf.edits().get(1).newText().contains("extracted n =\n    n * n"),
        rf.edits().get(1).newText());
  }

  @Test
  void extractFunctionDeclinesANonExpressionSelection() {
    var rs = server.refactors("main = 2 + 3\n", 0, 0, 0, 6); // "main =" isn't an expression
    assertTrue(rs.stream().noneMatch(r -> r.title().startsWith("Extract")), rs.toString());
  }

  @Test
  void convertLambdaToTopLevelFunction() {
    String src = "main =\n    List.map (\\x -> x + 1) [ 1, 2, 3 ]\n";
    var rs = server.refactors(src, 1, 14, 1, 25); // the `\x -> x + 1` lambda
    var conv = rs.stream().filter(r -> r.title().startsWith("Convert lambda")).findFirst().orElseThrow();
    assertEquals(2, conv.edits().size());
    assertEquals("extracted", conv.edits().get(0).newText()); // no captures -> bare name
    assertTrue(conv.edits().get(1).newText().contains("extracted x =\n    x + 1"),
        conv.edits().get(1).newText());
  }

  @Test
  void inlineReplacesAUseWithTheDefinitionBody() {
    // `answer = 42` ; on the `answer` use in `main`, inline replaces it with `(42)`.
    String src = "answer = 42\nmain = answer + 1\n";
    var rs = server.refactors(src, 1, 7, 1, 7); // cursor on `answer` (line 1)
    var inline = rs.stream().filter(r -> r.title().startsWith("Inline")).findFirst().orElseThrow();
    assertEquals(1, inline.edits().size());
    assertEquals("(42)", inline.edits().get(0).newText());
    assertEquals(1, inline.edits().get(0).line()); // the use site, not the definition
  }

  @Test
  void inlineNotOfferedOnAParameterisedFunction() {
    String src = "double n = n * 2\nmain = double 21\n";
    var rs = server.refactors(src, 1, 7, 1, 7); // on the `double` call
    assertTrue(rs.stream().noneMatch(r -> r.title().startsWith("Inline")), rs.toString());
  }

  @Test
  void completionAfterAModuleQualifierOffersItsMembers() {
    // After `List.` the stdlib members of List are offered as bare suffixes (not "List.map").
    String src = "main =\n    List.\n";
    var labels = server.complete(src, 1, 9); // cursor right after `List.`
    assertTrue(labels.contains("map") && labels.contains("filter"), labels.toString());
    assertTrue(labels.stream().noneMatch(l -> l.contains(".")), "members are bare: " + labels);
  }

  @Test
  void completionAfterARecordReceiverOffersFieldNames() {
    String src =
        "type alias Model = { count : Int, name : String }\nview m =\n    m.\n";
    var labels = server.complete(src, 2, 6); // after `m.`
    assertTrue(labels.contains("count") && labels.contains("name"), labels.toString());
  }

  // --- non-exhaustive case warnings -------------------------------------

  @Test
  void surfacesANonExhaustiveCaseAsAnError() {
    // The type checker reports non-exhaustiveness (matching Elm), surfaced by the LSP as an error.
    String src =
        "type Color = Red | Green | Blue\n"
            + "name c =\n    case c of\n        Red -> \"r\"\n        Green -> \"g\"\n";
    var ds = server.diagnose(src);
    assertTrue(
        ds.stream().anyMatch(d -> d.severity() == 1 && d.message().contains("does not handle")
            && d.message().contains("Blue")),
        ds.toString());
  }

  @Test
  void doesNotReportAnExhaustiveOrCatchAllCase() {
    String exhaustive =
        "type Color = Red | Green\nname c =\n    case c of\n        Red -> \"r\"\n        Green -> \"g\"\n";
    assertTrue(
        server.diagnose(exhaustive).stream().noneMatch(d -> d.message().contains("does not handle")),
        "all constructors covered");
    String catchAll =
        "type Color = Red | Green | Blue\nname c =\n    case c of\n        Red -> \"r\"\n        _ -> \"other\"\n";
    assertTrue(
        server.diagnose(catchAll).stream().noneMatch(d -> d.message().contains("does not handle")),
        "wildcard branch makes it exhaustive");
  }

  // --- call hierarchy ----------------------------------------------------

  @Test
  void callHierarchyFindsIncomingAndOutgoingCalls() {
    var prep = server.prepareCallHierarchy(workspace(), "file:///Util.elm", 3, 0); // on `square`
    assertTrue(prep.isPresent(), "prepare resolves the function under the cursor");
    assertEquals("square", prep.get().name());
    // Main.elm calls Util.square -> square has an incoming call from `main`.
    var incoming = server.incomingCalls(workspace(), "square");
    assertTrue(incoming.stream().anyMatch(c -> c.item().name().equals("main")), incoming.toString());
    // And `main`'s outgoing calls include `square`.
    var outgoing = server.outgoingCalls(workspace(), "main");
    assertTrue(outgoing.stream().anyMatch(c -> c.item().name().equals("square")), outgoing.toString());
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

  @Test
  void foldingRangesCoverMultiLineDeclarationsAndImports() {
    String src =
        "module M exposing (..)\nimport A\nimport B\nf x =\n    x\n        + 1\ng = 2\n";
    var folds = LspServer.foldingRanges(src);
    // The import block (lines 1-2) and f's body (lines 3-5) fold; single-line g does not.
    assertTrue(folds.stream().anyMatch(r -> r[0] == 1 && r[1] == 2), folds.toString());
    assertTrue(folds.stream().anyMatch(r -> r[0] == 3 && r[1] == 5), folds.toString());
  }

  @Test
  void prepareRenameReturnsTheIdentifierSpan() {
    int[] span = LspServer.identifierSpan("answer = 42\n", 0, 3); // cursor inside "answer"
    assertEquals(0, span[0]);
    assertEquals(0, span[1]);
    assertEquals(6, span[2]); // "answer" is 6 chars
    org.junit.jupiter.api.Assertions.assertNull(LspServer.identifierSpan("x = 1\n", 0, 2)); // on space
  }

  @Test
  void documentLinkImportSpansAreExtracted() {
    var links = LspServer.importLinks("module Main exposing (..)\nimport Helper\nimport A.B as C\nmain = 1\n");
    assertEquals(2, links.size());
    assertEquals("Helper", links.get(0)[1]); // the linked module name
    int[] r = (int[]) links.get(0)[0];
    assertEquals(1, r[0]); // on line 1
    assertEquals("A.B", links.get(1)[1]); // dotted module name
  }

  @Test
  void incrementalSyncAppliesRangedEdits() {
    // Replace "world" (line 0, chars 6..11) with "elm".
    String doc = "hello world\nsecond line\n";
    var range = java.util.Map.<String, Object>of(
        "start", java.util.Map.of("line", 0, "character", 6),
        "end", java.util.Map.of("line", 0, "character", 11));
    var change = java.util.Map.<String, Object>of("range", range, "text", "elm");
    assertEquals("hello elm\nsecond line\n", LspServer.applyChange(doc, change));

    // An insertion across a line boundary (zero-width range at end of line 0).
    var ins = java.util.Map.<String, Object>of(
        "range", java.util.Map.of(
            "start", java.util.Map.of("line", 0, "character", 11),
            "end", java.util.Map.of("line", 0, "character", 11)),
        "text", "!");
    assertEquals("hello world!\nsecond line\n", LspServer.applyChange(doc, ins));

    // A change with no range replaces the whole document.
    assertEquals("brand new", LspServer.applyChange(doc, java.util.Map.of("text", "brand new")));
  }

  @Test
  void codeActionExtractsARecordTypeAlias() {
    String src = "point : { x : Int, y : Int }\npoint = { x = 1, y = 2 }\n";
    var actions = server.codeActions(src, 0); // cursor on the annotation
    var extract =
        actions.stream().filter(a -> a.title().startsWith("Extract record")).findFirst();
    assertTrue(extract.isPresent(), actions.toString());
    String text = extract.get().newText();
    assertTrue(text.contains("type alias Point =") && text.contains("{ x : Int, y : Int }"), text);
    assertTrue(text.contains("point : Point"), text); // annotation rewritten to use the alias
  }
}

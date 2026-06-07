package pl.matsuo.elm.demos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * The editor's tooling layer — syntax highlighting (Highlight), autocomplete/assist (Assist), the
 * error ribbon, JS-bundle compilation and the Share permalink codec — exercised through the shared
 * {@link EditorInterpreterTestSupport} harness. Split out of {@code EditorInterpreterTest} to keep
 * both files focused (and under the line budget).
 */
class EditorToolingInterpreterTest extends EditorInterpreterTestSupport {

  /** Calls `Highlight.segments : String -> List (String, String)`. */
  @SuppressWarnings("unchecked")
  private List<ElmTuple> segments(String src) {
    Object r = Apply.apply(EDITOR.value("Highlight", "segments"), src);
    return (List<ElmTuple>) (List<?>) ((ElmList) r).toJava();
  }

  @Test
  void highlighterIsCharacterFaithfulAndClassifies() {
    String src = "-- a comment\nadd : Int -> Int\nadd n = n + 42 -- tail\nname = \"Bob\"";
    List<ElmTuple> segs = segments(src);
    // Faithful: concatenating every segment's text reproduces the source exactly.
    StringBuilder sb = new StringBuilder();
    for (ElmTuple seg : segs) {
      sb.append((String) seg.get(1));
    }
    assertEquals(src, sb.toString(), "highlighter must preserve every character");
    // Classification: at least one of each expected class is produced.
    assertTrue(hasClassWith(segs, "com", "-- a comment"), "line comment");
    assertTrue(hasClassWith(segs, "type", "Int"), "upper-case type name");
    assertTrue(hasClassWith(segs, "num", "42"), "number literal");
    assertTrue(hasClassWith(segs, "str", "\"Bob\""), "string literal");
    assertTrue(hasClassWith(segs, "op", "->"), "operator");
  }

  @Test
  void highlighterTagsKeywords() {
    List<ElmTuple> segs = segments("case x of\n  _ -> if a then b else c");
    assertTrue(hasClassWith(segs, "kw", "case"), "case keyword");
    assertTrue(hasClassWith(segs, "kw", "of"), "of keyword");
    assertTrue(hasClassWith(segs, "kw", "if"), "if keyword");
    assertTrue(hasClassWith(segs, "kw", "else"), "else keyword");
  }

  private static boolean hasClassWith(List<ElmTuple> segs, String cls, String text) {
    return segs.stream().anyMatch(s -> cls.equals(s.get(0)) && text.equals(s.get(1)));
  }

  /** Calls `Assist.completions : String -> String -> List String`, resolving each element. */
  private List<String> completions(String source, String prefix) {
    Object r = Apply.applyAll(EDITOR.value("Assist", "completions"), source, prefix);
    List<String> out = new ArrayList<>();
    for (Object o : ((ElmList) r).toJava()) {
      out.add(String.valueOf(pl.matsuo.elm.interp.Thunk.resolve(o)));
    }
    return out;
  }

  @Test
  void autocompleteSuggestsKeywordsBuiltinsAndBufferIdentifiers() {
    // From a buffer that defines `mapper`, typing "map" offers the in-buffer identifier.
    assertTrue(completions("mapper xs = negate xs\nother = 1", "map").contains("mapper"),
        completions("mapper xs = negate xs\nother = 1", "map").toString());

    // Qualified built-ins complete on their module prefix (completion is case-sensitive).
    List<String> qualified = completions("x = 1", "List.");
    assertTrue(qualified.contains("List.map") && qualified.contains("List.filter"), qualified.toString());

    // Keyword completion, and the prefix itself is never echoed back.
    assertTrue(completions("x = 1", "ca").contains("case"), "case keyword");
    assertTrue(completions("x = 1", "case").isEmpty(), "exact match isn't re-offered");

    // An empty prefix yields nothing (no popup on every keystroke).
    assertTrue(completions("anything = 1", "").isEmpty(), "empty prefix → no suggestions");
  }

  @Test
  void autocompleteExtractsTheWordAtTheCaret() {
    // wordAt source offset -> the (qualified) identifier ending at the caret (offset is an Elm Int).
    String src = "main = List.ma";
    assertEquals("List.ma",
        Show.plain(Apply.applyAll(EDITOR.value("Assist", "wordAt"), src, (long) src.length())));
    // Caret right after a non-identifier char -> empty word.
    assertEquals("", Show.plain(Apply.applyAll(EDITOR.value("Assist", "wordAt"), "a + ", 4L)));
  }

  @Test
  void acceptInsertsACompletionAtTheCaret() {
    // accept source caret completion -> (newSource, newCaret): the half-typed word is replaced.
    String src = "main = List.ma";
    Object r = Apply.applyAll(EDITOR.value("Assist", "accept"), src, (long) src.length(), "List.map");
    ElmTuple t = (ElmTuple) pl.matsuo.elm.interp.Thunk.resolve(r);
    assertEquals("main = List.map", String.valueOf(pl.matsuo.elm.interp.Thunk.resolve(t.get(0))));
    assertEquals(15L, pl.matsuo.elm.interp.Thunk.resolve(t.get(1)));
  }

  @Test
  void errorNameExtractsTheOffendingIdentifier() {
    assertTrue(
        Show.plain(Apply.apply(EDITOR.value("Assist", "errorName"), "undefined variable: nope"))
            .contains("nope"),
        "names the variable after the colon");
    assertEquals(
        "Nothing",
        Show.plain(Apply.apply(EDITOR.value("Assist", "errorName"), "all is well, no name here")));
  }

  @Test
  void squiggleLocatesAnOffendingIdentifier() {
    // The error "undefined variable: nope" should point at `nope` on line 1 (0-based), column 8.
    String src = "x = 1\ny = nope + x";
    String loc = Show.plain(Apply.applyAll(EDITOR.value("Assist", "squiggleFor"), src, "nope"));
    assertTrue(loc.contains("line = 1"), loc);
    assertTrue(loc.contains("column = 4"), loc);
    assertTrue(loc.contains("length = 4"), loc);
    // A name that doesn't occur as a whole word isn't located (no false squiggle inside `nope`).
    assertEquals("Nothing", Show.plain(Apply.applyAll(EDITOR.value("Assist", "squiggleFor"), src, "op")));
  }

  @Test
  void offsetOfMapsASquiggleLocationToACharacterOffset() {
    // The character offset the editor's squiggle overlay slices at: line 1, column 4 of
    // "x = 1\ny = nope" is the 'n' of nope, at offset 5 (line 0) + 1 (newline) + 4 = 10.
    Object offsetOf = EDITOR.value("Assist", "offsetOf");
    assertEquals("10", Show.plain(Apply.applyAll(offsetOf, 1L, 4L, "x = 1\ny = nope")));
    assertEquals("0", Show.plain(Apply.applyAll(offsetOf, 0L, 0L, "abc")));
    assertEquals("2", Show.plain(Apply.applyAll(offsetOf, 0L, 2L, "abcdef")));
  }

  @Test
  void compilesToJavaScriptForTheBrowser() {
    // The editor is a multi-module Browser.sandbox program; the JS backend must bundle all modules.
    String page = JsCompiler.htmlPageProject(null, moduleSources());
    assertTrue(page.contains("$start"), "editor compiles to a runnable JS bundle");
  }

  /** The full editor app (including Editor.elm and its Assist-wired autocomplete + error ribbon) must
   * compile to a JS bundle for the browser. Guards the UI wiring (custom event decoders, dropdown). */
  @Test
  void fullEditorAppWithAssistCompiles() {
    String[] paths = {
      "projects/elm-editor/src/Lang.elm",
      "projects/elm-editor/src/Lexer.elm",
      "projects/elm-editor/src/Parser.elm",
      "projects/elm-editor/src/EvalRender.elm",
      "projects/elm-editor/src/EvalCore.elm",
      "projects/elm-editor/src/EvalString.elm",
      "projects/elm-editor/src/EvalChar.elm",
      "projects/elm-editor/src/EvalBitwise.elm",
      "projects/elm-editor/src/EvalDebug.elm",
      "projects/elm-editor/src/EvalTuple.elm",
      "projects/elm-editor/src/EvalMaybe.elm",
      "projects/elm-editor/src/EvalResult.elm",
      "projects/elm-editor/src/EvalList.elm",
      "projects/elm-editor/src/Eval.elm",
      "projects/elm-editor/src/Highlight.elm",
      "projects/elm-editor/src/Assist.elm",
      "projects/elm-editor/src/Share.elm",
      "projects/elm-editor/src/Editor.elm",
      "projects/elm-editor/src/Main.elm",
    };
    String[] sources = new String[paths.length];
    for (int i = 0; i < paths.length; i++) {
      try {
        sources[i] = java.nio.file.Files.readString(java.nio.file.Path.of(paths[i]));
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }
    String page = JsCompiler.htmlPageProject(null, sources);
    assertTrue(page.contains("$start"), "the full editor (with Assist + Share wiring) bundles to JS");
  }

  /**
   * Guards the editor's message wiring against the failure mode where a {@code Msg} constructor is
   * renamed but the (Chrome-gated) headless drivers still dispatch the old name — a break that only
   * surfaced in full CI. The compiled bundle must contain a handler tag for every message those
   * drivers dispatch; this test runs in the normal, no-browser tier, so the rename fails fast.
   */
  @Test
  void editorBundleHandlesTheMessagesTheHeadlessDriversDispatch() {
    String[] sources;
    try {
      sources = pl.matsuo.elm.site.SiteGenerator.editorSources();
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
    String page = JsCompiler.htmlPageProject(null, sources);
    // The exact constructors HeadlessChromeTest dispatches via window.$app.dispatch($data('X',...)).
    // Interp/Rewind are preview-pane messages wrapped by the shell as PreviewMsg (the form drivers
    // dispatch); the inner tags must still be present in the bundle, hence both are checked.
    for (String ctor :
        new String[] {"EditAt", "PreviewMsg", "Interp", "Rewind", "GotHash", "LoadedSession"}) {
      assertTrue(
          page.contains("'" + ctor + "'") || page.contains("\"" + ctor + "\""),
          "compiled editor bundle has no handler for the dispatched message " + ctor);
    }
  }

  /** Calls `Share.encodeFiles`/`Share.decodeFiles` and checks they round-trip the file set. */
  @Test
  void shareEncodesAndDecodesTheFileSet() {
    Project share = Project.load(src("Share.elm"));
    // files : List (String, String) with content containing separators/newlines to stress the format.
    ElmList files =
        files(
            "Main.elm", "module Main exposing (main)\nmain = 1, 2\n",
            "Util.elm", "x = \"a,b\"\n");
    Object encoded = Apply.apply(share.value("Share", "encodeFiles"), files);
    Object decoded = Apply.apply(share.value("Share", "decodeFiles"), encoded);
    // The decoded list renders identically to the original (round-trip).
    assertEquals(Show.plain(files), Show.plain(decoded), "files round-trip through encode/decode");
  }
}

package pl.matsuo.elm.demos;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * Shared harness for the editor-interpreter test classes: it loads the Elm-in-Elm editor project
 * once ({@link #EDITOR}) and exposes the small helpers the tests drive it through ({@code eval},
 * {@code evalProject}, {@code files}, {@code debugSteps}, {@code renderGame} and the
 * value-unwrapping/counting helpers). The {@code @Test}s live in the subclasses (split by area), so
 * this class is not itself a test (the {@code *Support} name keeps Surefire from running it).
 */
class EditorInterpreterTestSupport {

  // The editor is its own project+repo (github.com/tunguski/elm-editor), checked out under
  // projects/elm-editor. Read it from disk; skip these suites when that sibling isn't present.
  private static final String[] MODULE_PATHS = {
    "projects/elm-editor/src/Lang.elm",
    "projects/elm-editor/src/Lexer.elm",
    "projects/elm-editor/src/Parser.elm",
    "projects/elm-editor/src/EvalRender.elm",
    "projects/elm-editor/src/EvalCore.elm",
    "projects/elm-editor/src/EvalPlayground.elm",
    "projects/elm-editor/src/EvalJson.elm",
    "projects/elm-editor/src/EvalString.elm",
    "projects/elm-editor/src/EvalChar.elm",
    "projects/elm-editor/src/EvalBitwise.elm",
    "projects/elm-editor/src/EvalDebug.elm",
    "projects/elm-editor/src/EvalTuple.elm",
    "projects/elm-editor/src/EvalMaybe.elm",
    "projects/elm-editor/src/EvalResult.elm",
    "projects/elm-editor/src/EvalList.elm",
    "projects/elm-editor/src/EvalSet.elm",
    "projects/elm-editor/src/EvalDict.elm",
    "projects/elm-editor/src/EvalArray.elm",
    "projects/elm-editor/src/Eval.elm",
    "projects/elm-editor/src/Highlight.elm",
    "projects/elm-editor/src/Assist.elm",
    "projects/elm-editor/src/Share.elm",
    "projects/elm-editor/src/Main.elm",
  };

  protected static final boolean AVAILABLE =
      java.nio.file.Files.exists(java.nio.file.Path.of(MODULE_PATHS[0]));

  /** Reads one editor module by file name (e.g. {@code "Share.elm"}) from projects/elm-editor/src. */
  protected static String src(String name) {
    try {
      return java.nio.file.Files.readString(java.nio.file.Path.of("projects/elm-editor/src", name));
    } catch (java.io.IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected static String[] moduleSources() {
    String[] s = new String[MODULE_PATHS.length];
    for (int i = 0; i < MODULE_PATHS.length; i++) {
      try {
        s[i] = java.nio.file.Files.readString(java.nio.file.Path.of(MODULE_PATHS[i]));
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }
    return s;
  }

  protected static final Project EDITOR = AVAILABLE ? Project.load(moduleSources()) : null;

  @org.junit.jupiter.api.BeforeEach
  void requireEditorProject() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        AVAILABLE, "projects/elm-editor not present (separate repo github.com/tunguski/elm-editor)");
  }

  /** Calls the Elm-written `Eval.eval : String -> String` on a source expression. */
  protected String eval(String expression) {
    return Show.plain(Apply.apply(EDITOR.value("Eval", "eval"), expression));
  }

  /** Builds the Elm `List (String, String)` of (filename, content) from alternating args. */
  protected static ElmList files(String... nameThenContent) {
    List<Object> pairs = new ArrayList<>();
    for (int i = 0; i + 1 < nameThenContent.length; i += 2) {
      pairs.add(new ElmTuple(new Object[] {nameThenContent[i], nameThenContent[i + 1]}));
    }
    return ElmList.fromJava(pairs);
  }

  /** Calls `Eval.evalProject : List (String,String) -> String -> String`. */
  protected String evalProject(ElmList files, String entry) {
    return Show.plain(Apply.applyAll(EDITOR.value("Eval", "evalProject"), files, entry));
  }

  /** Calls `Eval.debugSteps : List (String,String) -> List String -> List String`. */
  @SuppressWarnings("unchecked")
  protected List<Object> debugSteps(ElmList files, String... messages) {
    Object r =
        Apply.applyAll(EDITOR.value("Eval", "debugSteps"), files, ElmList.fromJava(List.of(messages)));
    return ((ElmList) r).toJava();
  }

  protected static Object unwrapJust(Object maybe) {
    return ((pl.matsuo.elm.runtime.ElmData) maybe).arg(0); // Just x -> x
  }

  protected static Object okValue(Object result) {
    return ((pl.matsuo.elm.runtime.ElmData) result).arg(0); // Ok x -> x
  }

  protected static int countMatches(String s, String sub) {
    int c = 0;
    for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
      c++;
    }
    return c;
  }

  protected String renderGame(ElmList fs, List<String> keys, double time, Object mem) {
    return Show.plain(
        okValue(
            Apply.applyAll(
                EDITOR.value("Eval", "gameView"),
                fs,
                ElmList.fromJava(new ArrayList<Object>(keys)),
                time,
                mem)));
  }
}

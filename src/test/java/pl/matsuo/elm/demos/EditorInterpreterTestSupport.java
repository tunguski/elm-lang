package pl.matsuo.elm.demos;

import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.util.Resources;

/**
 * Shared harness for the editor-interpreter test classes: it loads the Elm-in-Elm editor project
 * once ({@link #EDITOR}) and exposes the small helpers the tests drive it through ({@code eval},
 * {@code evalProject}, {@code files}, {@code debugSteps}, {@code renderGame} and the
 * value-unwrapping/counting helpers). The {@code @Test}s live in the subclasses (split by area), so
 * this class is not itself a test (the {@code *Support} name keeps Surefire from running it).
 */
class EditorInterpreterTestSupport {

  private static final String[] MODULE_PATHS = {
    "/elm/editor/Lang.elm",
    "/elm/editor/Lexer.elm",
    "/elm/editor/Parser.elm",
    "/elm/editor/EvalRender.elm",
    "/elm/editor/EvalPlayground.elm",
    "/elm/editor/Eval.elm",
    "/elm/editor/Highlight.elm",
    "/elm/editor/Assist.elm",
    "/elm/editor/Share.elm",
    "/elm/editor/Main.elm",
  };

  protected static String[] moduleSources() {
    String[] s = new String[MODULE_PATHS.length];
    for (int i = 0; i < MODULE_PATHS.length; i++) {
      s[i] = Resources.read(MODULE_PATHS[i]);
    }
    return s;
  }

  protected static final Project EDITOR = Project.load(moduleSources());

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

package pl.matsuo.elm.interp;

import java.util.stream.Collectors;
import pl.matsuo.elm.runtime.ElmArray;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmDict;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmSet;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.runtime.ElmUnit;

/** Renders runtime values in Elm syntax (used by {@code Debug.toString} and tests). */
public final class Show {

  private Show() {}

  /** {@code Debug.toString}-style rendering: strings and chars are quoted. */
  public static String debug(Object v) {
    return render(v, true);
  }

  /** Plain rendering used by {@code String.fromInt}/{@code fromFloat} and friends. */
  public static String plain(Object v) {
    return render(v, false);
  }

  public static String fromFloat(double d) {
    if (Double.isNaN(d)) {
      return "NaN";
    }
    if (Double.isInfinite(d)) {
      return d > 0 ? "Infinity" : "-Infinity";
    }
    if (d == Math.rint(d) && Math.abs(d) < 1e16) {
      return Long.toString((long) d);
    }
    return Double.toString(d);
  }

  private static String render(Object v, boolean quote) {
    return switch (v) {
      case Long l -> Long.toString(l);
      case Double d -> fromFloat(d);
      case Boolean b -> b ? "True" : "False";
      case String s -> quote ? '"' + s + '"' : s;
      case ElmChar c -> quote ? c.toString() : new String(Character.toChars(c.codePoint()));
      case ElmUnit ignored -> "()";
      case ElmList list ->
          list.toJava().stream()
              .map(x -> render(x, true))
              .collect(Collectors.joining(",", "[", "]"));
      case ElmTuple t ->
          java.util.Arrays.stream(t.values())
              .map(x -> render(x, true))
              .collect(Collectors.joining(",", "(", ")"));
      case ElmRecord r ->
          r.fields().entrySet().stream()
              .map(e -> e.getKey() + " = " + render(e.getValue(), true))
              .collect(Collectors.joining(", ", "{ ", " }"));
      case ElmDict dict ->
          dict.entries().entrySet().stream()
              .map(e -> "(" + render(e.getKey(), true) + "," + render(e.getValue(), true) + ")")
              .collect(Collectors.joining(",", "Dict.fromList [", "]"));
      case ElmSet set ->
          set.elements().stream()
              .map(x -> render(x, true))
              .collect(Collectors.joining(",", "Set.fromList [", "]"));
      case ElmArray array ->
          java.util.Arrays.stream(array.items())
              .map(x -> render(x, true))
              .collect(Collectors.joining(",", "Array.fromList [", "]"));
      case ElmData d -> {
        if (d.args().length == 0) {
          yield d.ctor();
        }
        yield d.ctor()
            + " "
            + java.util.Arrays.stream(d.args())
                .map(x -> renderArg(x))
                .collect(Collectors.joining(" "));
      }
      default -> String.valueOf(v);
    };
  }

  private static String renderArg(Object v) {
    String s = render(v, true);
    // Parenthesize constructor applications and negative numbers when used as arguments.
    if (v instanceof ElmData d && d.args().length > 0) {
      return "(" + s + ")";
    }
    if (v instanceof Long l && l < 0) {
      return "(" + s + ")";
    }
    if (v instanceof Double dd && dd < 0) {
      return "(" + s + ")";
    }
    return s;
  }
}

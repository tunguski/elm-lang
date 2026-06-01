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

  /** Width past which a container is broken across multiple indented lines by {@link #pretty}. */
  private static final int MAX_WIDTH = 72;

  /**
   * Like {@link #debug} but laid out across multiple lines when a value is too wide to read on one —
   * lists, records, tuples, dicts/sets/arrays and constructor applications break onto indented lines
   * (elm-format-style leading separators). Small values stay on a single line. For human display
   * (REPL / editor), not for {@code Debug.toString} (which stays compact).
   */
  public static String pretty(Object v) {
    return lay(v, 0);
  }

  /** Lays out {@code v} with its continuation lines indented to {@code indent}; the first line is
   * positioned by the caller. Falls back to the compact form whenever it already fits. */
  private static String lay(Object v, int indent) {
    String compact = render(v, true);
    if (indent + compact.length() <= MAX_WIDTH && !compact.contains("\n")) {
      return compact;
    }
    return switch (v) {
      case ElmList list -> seq(list.toJava(), indent, "[", "]");
      case ElmTuple t -> seq(java.util.Arrays.asList(t.values()), indent, "(", ")");
      case ElmRecord r -> fields(r, indent);
      case ElmDict dict -> {
        java.util.List<Object> pairs = new java.util.ArrayList<>();
        dict.entries().forEach((k, val) -> pairs.add(new ElmTuple(new Object[] {k, val})));
        yield prefixed("Dict.fromList", pairs, indent);
      }
      case ElmSet set -> prefixed("Set.fromList", new java.util.ArrayList<>(set.elements()), indent);
      case ElmArray array ->
          prefixed("Array.fromList", java.util.Arrays.asList(array.items()), indent);
      case ElmData d when d.args().length > 0 -> ctor(d, indent);
      default -> compact;
    };
  }

  /** A bracketed sequence, one element per line with leading {@code [ } / {@code , } separators. */
  private static String seq(java.util.List<Object> items, int indent, String open, String close) {
    String pad = " ".repeat(indent);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      sb.append(i == 0 ? open + " " : "\n" + pad + ", ");
      sb.append(lay(items.get(i), indent + 2));
    }
    return sb.append("\n").append(pad).append(close).toString();
  }

  private static String fields(ElmRecord r, int indent) {
    String pad = " ".repeat(indent);
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (var e : r.fields().entrySet()) {
      sb.append(first ? "{ " : "\n" + pad + ", ");
      first = false;
      sb.append(e.getKey()).append(" = ").append(lay(e.getValue(), indent + 4));
    }
    return sb.append("\n").append(pad).append("}").toString();
  }

  /** A {@code Name.fromList}-style value: the prefix, then its element list broken below it. */
  private static String prefixed(String prefix, java.util.List<Object> items, int indent) {
    return prefix + "\n" + " ".repeat(indent + 4) + seq(items, indent + 4, "[", "]");
  }

  /** A constructor application: the constructor, then each argument on its own indented line. */
  private static String ctor(ElmData d, int indent) {
    String pad = " ".repeat(indent + 4);
    StringBuilder sb = new StringBuilder(d.ctor());
    for (Object arg : d.args()) {
      String laid = lay(arg, indent + 4);
      boolean parens = arg instanceof ElmData a && a.args().length > 0;
      sb.append("\n").append(pad).append(parens ? "(" + laid + ")" : laid);
    }
    return sb.toString();
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

package pl.matsuo.elm.interp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.Builtin;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * The Elm standard-library prelude implemented as Java builtins, keyed by canonical
 * {@code Module.name}. Also defines the default unqualified import bindings and core constructor
 * arities. Shared (immutable) across modules.
 */
public final class Prelude {

  private Prelude() {}

  private static final Map<String, Object> BUILTINS = new HashMap<>();
  private static final Map<String, String> UNQUALIFIED = new HashMap<>();
  private static final Map<String, Integer> CTOR_ARITY = new HashMap<>();

  public static Map<String, Object> builtins() {
    return BUILTINS;
  }

  public static Map<String, String> defaultUnqualified() {
    return new HashMap<>(UNQUALIFIED);
  }

  public static Map<String, Integer> defaultCtorArity() {
    return new HashMap<>(CTOR_ARITY);
  }

  // --- helpers -----------------------------------------------------------

  private static void fn(String name, int arity, Function<Object[], Object> impl) {
    BUILTINS.put(name, new Builtin(name, arity, impl));
  }

  /** Registers a builtin and also exposes its short name unqualified (as Basics does). */
  private static void basics(String shortName, int arity, Function<Object[], Object> impl) {
    String canonical = "Basics." + shortName;
    BUILTINS.put(canonical, new Builtin(canonical, arity, impl));
    UNQUALIFIED.put(shortName, canonical);
  }

  private static Object just(Object x) {
    return new ElmData("Just", new Object[] {x});
  }

  private static final ElmData NOTHING = new ElmData("Nothing", new Object[0]);

  private static Object ordering(int c) {
    return new ElmData(c < 0 ? "LT" : c == 0 ? "EQ" : "GT", new Object[0]);
  }

  private static List<Object> javaList(Object v) {
    return ((ElmList) v).toJava();
  }

  static {
    registerBasics();
    registerList();
    registerString();
    registerChar();
    registerMaybe();
    registerResult();
    registerTuple();
    registerDebug();
    registerConstructors();
  }

  private static void registerConstructors() {
    CTOR_ARITY.put("Just", 1);
    CTOR_ARITY.put("Nothing", 0);
    CTOR_ARITY.put("Ok", 1);
    CTOR_ARITY.put("Err", 1);
    CTOR_ARITY.put("LT", 0);
    CTOR_ARITY.put("EQ", 0);
    CTOR_ARITY.put("GT", 0);
  }

  // --- Basics ------------------------------------------------------------

  private static void registerBasics() {
    basics("identity", 1, a -> a[0]);
    basics("always", 2, a -> a[0]);
    basics("not", 1, a -> !(Boolean) a[0]);
    basics("xor", 2, a -> (Boolean) a[0] ^ (Boolean) a[1]);
    basics("negate", 1, a -> Operators.negate(a[0]));
    basics("abs", 1, a -> a[0] instanceof Long l ? (Object) Math.abs(l) : Math.abs((Double) a[0]));
    basics("sqrt", 1, a -> Math.sqrt(Operators.toDouble(a[0])));
    basics("toFloat", 1, a -> (double) Operators.asLong(a[0]));
    basics("round", 1, a -> Math.round(Operators.toDouble(a[0])));
    basics("floor", 1, a -> (long) Math.floor(Operators.toDouble(a[0])));
    basics("ceiling", 1, a -> (long) Math.ceil(Operators.toDouble(a[0])));
    basics("truncate", 1, a -> (long) (double) Operators.toDouble(a[0]));
    basics("modBy", 2, a -> Math.floorMod(Operators.asLong(a[1]), Operators.asLong(a[0])));
    basics("remainderBy", 2, a -> Operators.asLong(a[1]) % Operators.asLong(a[0]));
    basics("min", 2, a -> Operators.compareValues(a[0], a[1]) <= 0 ? a[0] : a[1]);
    basics("max", 2, a -> Operators.compareValues(a[0], a[1]) >= 0 ? a[0] : a[1]);
    basics("compare", 2, a -> ordering(Operators.compareValues(a[0], a[1])));
    basics(
        "clamp",
        3,
        a -> {
          if (Operators.compareValues(a[2], a[0]) < 0) {
            return a[0];
          }
          return Operators.compareValues(a[2], a[1]) > 0 ? a[1] : a[2];
        });
    basics("isNaN", 1, a -> Double.isNaN(Operators.toDouble(a[0])));
    basics("isInfinite", 1, a -> Double.isInfinite(Operators.toDouble(a[0])));
    basics("sin", 1, a -> Math.sin(Operators.toDouble(a[0])));
    basics("cos", 1, a -> Math.cos(Operators.toDouble(a[0])));
    basics("tan", 1, a -> Math.tan(Operators.toDouble(a[0])));
    basics("asin", 1, a -> Math.asin(Operators.toDouble(a[0])));
    basics("acos", 1, a -> Math.acos(Operators.toDouble(a[0])));
    basics("atan", 1, a -> Math.atan(Operators.toDouble(a[0])));
    basics("atan2", 2, a -> Math.atan2(Operators.toDouble(a[0]), Operators.toDouble(a[1])));
    basics("logBase", 2, a -> Math.log(Operators.toDouble(a[1])) / Math.log(Operators.toDouble(a[0])));
    basics("degrees", 1, a -> Operators.toDouble(a[0]) * Math.PI / 180.0);
    basics("radians", 1, a -> Operators.toDouble(a[0]));
    basics("turns", 1, a -> Operators.toDouble(a[0]) * 2 * Math.PI);
    basics("never", 1, a -> {
      throw new ElmRuntimeError("Basics.never was called");
    });
    BUILTINS.put("Basics.pi", Math.PI);
    UNQUALIFIED.put("pi", "Basics.pi");
    BUILTINS.put("Basics.e", Math.E);
    UNQUALIFIED.put("e", "Basics.e");
  }

  // --- List --------------------------------------------------------------

  private static void registerList() {
    fn("List.map", 2, a -> {
      List<Object> out = new ArrayList<>();
      for (Object x : javaList(a[1])) {
        out.add(Apply.apply(a[0], x));
      }
      return ElmList.fromJava(out);
    });
    fn("List.indexedMap", 2, a -> {
      List<Object> in = javaList(a[1]);
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < in.size(); i++) {
        out.add(Apply.applyAll(a[0], (long) i, in.get(i)));
      }
      return ElmList.fromJava(out);
    });
    fn("List.filter", 2, a -> {
      List<Object> out = new ArrayList<>();
      for (Object x : javaList(a[1])) {
        if ((Boolean) Apply.apply(a[0], x)) {
          out.add(x);
        }
      }
      return ElmList.fromJava(out);
    });
    fn("List.filterMap", 2, a -> {
      List<Object> out = new ArrayList<>();
      for (Object x : javaList(a[1])) {
        Object r = Apply.apply(a[0], x);
        if (r instanceof ElmData d && d.ctor().equals("Just")) {
          out.add(d.arg(0));
        }
      }
      return ElmList.fromJava(out);
    });
    fn("List.foldl", 3, a -> {
      Object acc = a[1];
      for (Object x : javaList(a[2])) {
        acc = Apply.applyAll(a[0], x, acc);
      }
      return acc;
    });
    fn("List.foldr", 3, a -> {
      List<Object> in = javaList(a[2]);
      Object acc = a[1];
      for (int i = in.size() - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], in.get(i), acc);
      }
      return acc;
    });
    fn("List.length", 1, a -> (long) javaList(a[0]).size());
    fn("List.reverse", 1, a -> {
      List<Object> in = new ArrayList<>(javaList(a[0]));
      java.util.Collections.reverse(in);
      return ElmList.fromJava(in);
    });
    fn("List.member", 2, a -> {
      for (Object x : javaList(a[1])) {
        if (Operators.equals(a[0], x)) {
          return true;
        }
      }
      return false;
    });
    fn("List.all", 2, a -> {
      for (Object x : javaList(a[1])) {
        if (!(Boolean) Apply.apply(a[0], x)) {
          return false;
        }
      }
      return true;
    });
    fn("List.any", 2, a -> {
      for (Object x : javaList(a[1])) {
        if ((Boolean) Apply.apply(a[0], x)) {
          return true;
        }
      }
      return false;
    });
    fn("List.sum", 1, a -> reduceNum(javaList(a[0]), "+"));
    fn("List.product", 1, a -> reduceNum(javaList(a[0]), "*"));
    fn("List.maximum", 1, a -> reduceMaybe(javaList(a[0]), true));
    fn("List.minimum", 1, a -> reduceMaybe(javaList(a[0]), false));
    fn("List.append", 2, a -> Operators.binary("++", a[0], a[1]));
    fn("List.concat", 1, a -> {
      List<Object> out = new ArrayList<>();
      for (Object sub : javaList(a[0])) {
        out.addAll(javaList(sub));
      }
      return ElmList.fromJava(out);
    });
    fn("List.concatMap", 2, a -> {
      List<Object> out = new ArrayList<>();
      for (Object x : javaList(a[1])) {
        out.addAll(javaList(Apply.apply(a[0], x)));
      }
      return ElmList.fromJava(out);
    });
    fn("List.range", 2, a -> {
      long lo = Operators.asLong(a[0]);
      long hi = Operators.asLong(a[1]);
      List<Object> out = new ArrayList<>();
      for (long i = lo; i <= hi; i++) {
        out.add(i);
      }
      return ElmList.fromJava(out);
    });
    fn("List.head", 1, a -> a[0] instanceof ElmList.Cons c ? just(c.head()) : NOTHING);
    fn("List.tail", 1, a -> a[0] instanceof ElmList.Cons c ? just(c.tail()) : NOTHING);
    fn("List.isEmpty", 1, a -> ((ElmList) a[0]).isEmpty());
    fn("List.take", 2, a -> {
      long n = Operators.asLong(a[0]);
      List<Object> in = javaList(a[1]);
      return ElmList.fromJava(in.subList(0, (int) Math.max(0, Math.min(n, in.size()))));
    });
    fn("List.drop", 2, a -> {
      long n = Operators.asLong(a[0]);
      List<Object> in = javaList(a[1]);
      return ElmList.fromJava(in.subList((int) Math.max(0, Math.min(n, in.size())), in.size()));
    });
    fn("List.singleton", 1, a -> ElmList.cons(a[0], ElmList.NIL));
    fn("List.repeat", 2, a -> {
      long n = Operators.asLong(a[0]);
      List<Object> out = new ArrayList<>();
      for (long i = 0; i < n; i++) {
        out.add(a[1]);
      }
      return ElmList.fromJava(out);
    });
    fn("List.map2", 3, a -> {
      List<Object> xs = javaList(a[1]);
      List<Object> ys = javaList(a[2]);
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < Math.min(xs.size(), ys.size()); i++) {
        out.add(Apply.applyAll(a[0], xs.get(i), ys.get(i)));
      }
      return ElmList.fromJava(out);
    });
    fn("List.sort", 1, a -> {
      List<Object> in = new ArrayList<>(javaList(a[0]));
      in.sort(Operators::compareValues);
      return ElmList.fromJava(in);
    });
    fn("List.sortBy", 2, a -> {
      List<Object> in = new ArrayList<>(javaList(a[1]));
      in.sort((x, y) -> Operators.compareValues(Apply.apply(a[0], x), Apply.apply(a[0], y)));
      return ElmList.fromJava(in);
    });
    fn("List.sortWith", 2, a -> {
      List<Object> in = new ArrayList<>(javaList(a[1]));
      in.sort((x, y) -> orderingToInt(Apply.applyAll(a[0], x, y)));
      return ElmList.fromJava(in);
    });
  }

  private static Object reduceNum(List<Object> xs, String op) {
    Object acc = op.equals("+") ? (Long) 0L : (Long) 1L;
    for (Object x : xs) {
      acc = Operators.binary(op, acc, x);
    }
    return acc;
  }

  private static Object reduceMaybe(List<Object> xs, boolean max) {
    if (xs.isEmpty()) {
      return NOTHING;
    }
    Object best = xs.get(0);
    for (Object x : xs) {
      int c = Operators.compareValues(x, best);
      if (max ? c > 0 : c < 0) {
        best = x;
      }
    }
    return just(best);
  }

  private static int orderingToInt(Object ordering) {
    return switch (((ElmData) ordering).ctor()) {
      case "LT" -> -1;
      case "GT" -> 1;
      default -> 0;
    };
  }

  // --- String ------------------------------------------------------------

  private static void registerString() {
    fn("String.isEmpty", 1, a -> ((String) a[0]).isEmpty());
    fn("String.length", 1, a -> (long) ((String) a[0]).length());
    fn("String.reverse", 1, a -> new StringBuilder((String) a[0]).reverse().toString());
    fn("String.append", 2, a -> (String) a[0] + a[1]);
    fn("String.concat", 1, a -> {
      StringBuilder sb = new StringBuilder();
      for (Object s : javaList(a[0])) {
        sb.append((String) s);
      }
      return sb.toString();
    });
    fn("String.join", 2, a -> {
      List<Object> parts = javaList(a[1]);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < parts.size(); i++) {
        if (i > 0) {
          sb.append((String) a[0]);
        }
        sb.append((String) parts.get(i));
      }
      return sb.toString();
    });
    fn("String.split", 2, a -> {
      String sep = (String) a[0];
      String str = (String) a[1];
      List<Object> out = new ArrayList<>();
      if (sep.isEmpty()) {
        for (int i = 0; i < str.length(); i++) {
          out.add(String.valueOf(str.charAt(i)));
        }
      } else {
        int idx = 0;
        int next;
        while ((next = str.indexOf(sep, idx)) >= 0) {
          out.add(str.substring(idx, next));
          idx = next + sep.length();
        }
        out.add(str.substring(idx));
      }
      return ElmList.fromJava(out);
    });
    fn("String.words", 1, a -> {
      String s = ((String) a[0]).trim();
      List<Object> out = new ArrayList<>();
      if (!s.isEmpty()) {
        for (String w : s.split("\\s+")) {
          out.add(w);
        }
      }
      return ElmList.fromJava(out);
    });
    fn("String.lines", 1, a -> {
      List<Object> out = new ArrayList<>();
      for (String l : ((String) a[0]).split("\n", -1)) {
        out.add(l);
      }
      return ElmList.fromJava(out);
    });
    fn("String.toUpper", 1, a -> ((String) a[0]).toUpperCase());
    fn("String.toLower", 1, a -> ((String) a[0]).toLowerCase());
    fn("String.trim", 1, a -> ((String) a[0]).trim());
    fn("String.trimLeft", 1, a -> ((String) a[0]).stripLeading());
    fn("String.trimRight", 1, a -> ((String) a[0]).stripTrailing());
    fn("String.fromInt", 1, a -> Long.toString(Operators.asLong(a[0])));
    fn("String.fromFloat", 1, a -> Show.fromFloat(Operators.toDouble(a[0])));
    fn("String.fromChar", 1, a -> new String(Character.toChars(((ElmChar) a[0]).codePoint())));
    fn("String.toInt", 1, a -> {
      try {
        return just(Long.parseLong(((String) a[0]).trim()));
      } catch (NumberFormatException ex) {
        return NOTHING;
      }
    });
    fn("String.toFloat", 1, a -> {
      try {
        return just(Double.parseDouble(((String) a[0]).trim()));
      } catch (NumberFormatException ex) {
        return NOTHING;
      }
    });
    fn("String.cons", 2, a -> new String(Character.toChars(((ElmChar) a[0]).codePoint())) + a[1]);
    fn("String.uncons", 1, a -> {
      String s = (String) a[0];
      if (s.isEmpty()) {
        return NOTHING;
      }
      int cp = s.codePointAt(0);
      return just(new ElmTuple(new Object[] {new ElmChar(cp), s.substring(Character.charCount(cp))}));
    });
    fn("String.left", 2, a -> {
      String s = (String) a[1];
      int n = (int) Math.max(0, Math.min(Operators.asLong(a[0]), s.length()));
      return s.substring(0, n);
    });
    fn("String.right", 2, a -> {
      String s = (String) a[1];
      int n = (int) Math.max(0, Math.min(Operators.asLong(a[0]), s.length()));
      return s.substring(s.length() - n);
    });
    fn("String.dropLeft", 2, a -> {
      String s = (String) a[1];
      int n = (int) Math.max(0, Math.min(Operators.asLong(a[0]), s.length()));
      return s.substring(n);
    });
    fn("String.dropRight", 2, a -> {
      String s = (String) a[1];
      int n = (int) Math.max(0, Math.min(Operators.asLong(a[0]), s.length()));
      return s.substring(0, s.length() - n);
    });
    fn("String.slice", 3, a -> {
      String s = (String) a[2];
      int len = s.length();
      int start = normalizeIndex((int) Operators.asLong(a[0]), len);
      int end = normalizeIndex((int) Operators.asLong(a[1]), len);
      return start < end ? s.substring(start, end) : "";
    });
    fn("String.contains", 2, a -> ((String) a[1]).contains((String) a[0]));
    fn("String.startsWith", 2, a -> ((String) a[1]).startsWith((String) a[0]));
    fn("String.endsWith", 2, a -> ((String) a[1]).endsWith((String) a[0]));
    fn("String.repeat", 2, a -> ((String) a[1]).repeat((int) Math.max(0, Operators.asLong(a[0]))));
    fn("String.replace", 3, a -> ((String) a[2]).replace((String) a[0], (String) a[1]));
    fn("String.toList", 1, a -> {
      String s = (String) a[0];
      List<Object> out = new ArrayList<>();
      s.codePoints().forEach(cp -> out.add(new ElmChar(cp)));
      return ElmList.fromJava(out);
    });
    fn("String.fromList", 1, a -> {
      StringBuilder sb = new StringBuilder();
      for (Object c : javaList(a[0])) {
        sb.appendCodePoint(((ElmChar) c).codePoint());
      }
      return sb.toString();
    });
    fn("String.map", 2, a -> {
      String s = (String) a[1];
      StringBuilder sb = new StringBuilder();
      s.codePoints()
          .forEach(cp -> sb.appendCodePoint(((ElmChar) Apply.apply(a[0], new ElmChar(cp))).codePoint()));
      return sb.toString();
    });
    fn("String.filter", 2, a -> {
      String s = (String) a[1];
      StringBuilder sb = new StringBuilder();
      s.codePoints()
          .forEach(cp -> {
            if ((Boolean) Apply.apply(a[0], new ElmChar(cp))) {
              sb.appendCodePoint(cp);
            }
          });
      return sb.toString();
    });
  }

  private static int normalizeIndex(int i, int len) {
    int v = i < 0 ? len + i : i;
    return Math.max(0, Math.min(v, len));
  }

  // --- Char --------------------------------------------------------------

  private static void registerChar() {
    fn("Char.toCode", 1, a -> (long) ((ElmChar) a[0]).codePoint());
    fn("Char.fromCode", 1, a -> new ElmChar((int) Operators.asLong(a[0])));
    fn("Char.toUpper", 1, a -> new ElmChar(Character.toUpperCase(((ElmChar) a[0]).codePoint())));
    fn("Char.toLower", 1, a -> new ElmChar(Character.toLowerCase(((ElmChar) a[0]).codePoint())));
    fn("Char.isDigit", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return c >= '0' && c <= '9';
    });
    fn("Char.isUpper", 1, a -> Character.isUpperCase(((ElmChar) a[0]).codePoint()));
    fn("Char.isLower", 1, a -> Character.isLowerCase(((ElmChar) a[0]).codePoint()));
    fn("Char.isAlpha", 1, a -> Character.isLetter(((ElmChar) a[0]).codePoint()));
    fn("Char.isAlphaNum", 1, a -> Character.isLetterOrDigit(((ElmChar) a[0]).codePoint()));
  }

  // --- Maybe -------------------------------------------------------------

  private static void registerMaybe() {
    fn("Maybe.withDefault", 2, a -> isJust(a[1]) ? justValue(a[1]) : a[0]);
    fn("Maybe.map", 2, a -> isJust(a[1]) ? just(Apply.apply(a[0], justValue(a[1]))) : NOTHING);
    fn("Maybe.andThen", 2, a -> isJust(a[1]) ? Apply.apply(a[0], justValue(a[1])) : NOTHING);
    fn("Maybe.map2", 3, a -> {
      if (isJust(a[1]) && isJust(a[2])) {
        return just(Apply.applyAll(a[0], justValue(a[1]), justValue(a[2])));
      }
      return NOTHING;
    });
  }

  private static boolean isJust(Object o) {
    return o instanceof ElmData d && d.ctor().equals("Just");
  }

  private static Object justValue(Object o) {
    return ((ElmData) o).arg(0);
  }

  // --- Result ------------------------------------------------------------

  private static void registerResult() {
    fn("Result.withDefault", 2, a -> isOk(a[1]) ? ((ElmData) a[1]).arg(0) : a[0]);
    fn("Result.map", 2, a ->
        isOk(a[1]) ? new ElmData("Ok", new Object[] {Apply.apply(a[0], ((ElmData) a[1]).arg(0))}) : a[1]);
    fn("Result.mapError", 2, a ->
        isOk(a[1]) ? a[1] : new ElmData("Err", new Object[] {Apply.apply(a[0], ((ElmData) a[1]).arg(0))}));
    fn("Result.andThen", 2, a -> isOk(a[1]) ? Apply.apply(a[0], ((ElmData) a[1]).arg(0)) : a[1]);
    fn("Result.toMaybe", 1, a -> isOk(a[0]) ? just(((ElmData) a[0]).arg(0)) : NOTHING);
    fn("Result.fromMaybe", 2, a ->
        isJust(a[1]) ? new ElmData("Ok", new Object[] {justValue(a[1])}) : new ElmData("Err", new Object[] {a[0]}));
  }

  private static boolean isOk(Object o) {
    return o instanceof ElmData d && d.ctor().equals("Ok");
  }

  // --- Tuple -------------------------------------------------------------

  private static void registerTuple() {
    fn("Tuple.pair", 2, a -> new ElmTuple(new Object[] {a[0], a[1]}));
    fn("Tuple.first", 1, a -> ((ElmTuple) a[0]).get(0));
    fn("Tuple.second", 1, a -> ((ElmTuple) a[0]).get(1));
    fn("Tuple.mapFirst", 2, a -> {
      ElmTuple t = (ElmTuple) a[1];
      return new ElmTuple(new Object[] {Apply.apply(a[0], t.get(0)), t.get(1)});
    });
    fn("Tuple.mapSecond", 2, a -> {
      ElmTuple t = (ElmTuple) a[1];
      return new ElmTuple(new Object[] {t.get(0), Apply.apply(a[0], t.get(1))});
    });
    fn("Tuple.mapBoth", 3, a -> {
      ElmTuple t = (ElmTuple) a[2];
      return new ElmTuple(new Object[] {Apply.apply(a[0], t.get(0)), Apply.apply(a[1], t.get(1))});
    });
  }

  // --- Debug -------------------------------------------------------------

  private static void registerDebug() {
    fn("Debug.toString", 1, a -> Show.debug(a[0]));
    fn("Debug.log", 2, a -> {
      System.out.println(a[0] + ": " + Show.debug(a[1]));
      return a[1];
    });
    fn("Debug.todo", 1, a -> {
      throw new ElmRuntimeError("TODO: " + a[0]);
    });
  }
}

package pl.matsuo.elm.interp;

import static pl.matsuo.elm.interp.Prelude.BUILTINS;
import static pl.matsuo.elm.interp.Prelude.NOTHING;
import static pl.matsuo.elm.interp.Prelude.UNQUALIFIED;
import static pl.matsuo.elm.interp.Prelude.basics;
import static pl.matsuo.elm.interp.Prelude.d;
import static pl.matsuo.elm.interp.Prelude.fn;
import static pl.matsuo.elm.interp.Prelude.javaList;
import static pl.matsuo.elm.interp.Prelude.just;
import static pl.matsuo.elm.interp.Prelude.ordering;
import static pl.matsuo.elm.interp.Prelude.split;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * The high-traffic core of the interpreter prelude: the Basics, List, String, Char and Bitwise
 * builtins. The most frequently changed group, kept in its own file. These register into
 * {@link Prelude}'s shared maps via its package-private helpers ({@code fn}/{@code basics}/
 * {@code javaList}/{@code ordering}/{@code split}/{@code d}); {@link Prelude}'s static initializer
 * calls {@link #registerBasics()}/{@link #registerList()}/{@link #registerString()}/
 * {@link #registerChar()}/{@link #registerBitwise()}.
 */
final class PreludeCore {

  private PreludeCore() {}

  // --- Basics ------------------------------------------------------------

  static void registerBasics() {
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

  static void registerList() {
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
    fn("List.partition", 2, a -> {
      List<Object> yes = new ArrayList<>();
      List<Object> no = new ArrayList<>();
      for (Object x : javaList(a[1])) {
        ((Boolean) Apply.apply(a[0], x) ? yes : no).add(x);
      }
      return new ElmTuple(new Object[] {ElmList.fromJava(yes), ElmList.fromJava(no)});
    });
    fn("List.unzip", 1, a -> {
      List<Object> firsts = new ArrayList<>();
      List<Object> seconds = new ArrayList<>();
      for (Object pair : javaList(a[0])) {
        ElmTuple t = (ElmTuple) pair;
        firsts.add(t.get(0));
        seconds.add(t.get(1));
      }
      return new ElmTuple(new Object[] {ElmList.fromJava(firsts), ElmList.fromJava(seconds)});
    });
    fn("List.unzip3", 1, a -> {
      List<Object> as = new ArrayList<>();
      List<Object> bs = new ArrayList<>();
      List<Object> cs = new ArrayList<>();
      for (Object triple : javaList(a[0])) {
        ElmTuple t = (ElmTuple) triple;
        as.add(t.get(0));
        bs.add(t.get(1));
        cs.add(t.get(2));
      }
      return new ElmTuple(
          new Object[] {ElmList.fromJava(as), ElmList.fromJava(bs), ElmList.fromJava(cs)});
    });
    fn("List.intersperse", 2, a -> {
      List<Object> out = new ArrayList<>();
      boolean first = true;
      for (Object x : javaList(a[1])) {
        if (!first) {
          out.add(a[0]);
        }
        out.add(x);
        first = false;
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
    fn("List.map3", 4, a -> {
      List<Object> xs = javaList(a[1]);
      List<Object> ys = javaList(a[2]);
      List<Object> zs = javaList(a[3]);
      int n = Math.min(xs.size(), Math.min(ys.size(), zs.size()));
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        out.add(Apply.applyAll(a[0], xs.get(i), ys.get(i), zs.get(i)));
      }
      return ElmList.fromJava(out);
    });
    fn("List.map4", 5, a -> {
      List<Object> w = javaList(a[1]);
      List<Object> x = javaList(a[2]);
      List<Object> y = javaList(a[3]);
      List<Object> z = javaList(a[4]);
      int n = Math.min(Math.min(w.size(), x.size()), Math.min(y.size(), z.size()));
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        out.add(Apply.applyAll(a[0], w.get(i), x.get(i), y.get(i), z.get(i)));
      }
      return ElmList.fromJava(out);
    });
    fn("List.map5", 6, a -> {
      List<Object> v = javaList(a[1]);
      List<Object> w = javaList(a[2]);
      List<Object> x = javaList(a[3]);
      List<Object> y = javaList(a[4]);
      List<Object> z = javaList(a[5]);
      int n =
          Math.min(v.size(), Math.min(Math.min(w.size(), x.size()), Math.min(y.size(), z.size())));
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        out.add(Apply.applyAll(a[0], v.get(i), w.get(i), x.get(i), y.get(i), z.get(i)));
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

  static void registerString() {
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
    fn("String.padLeft", 3, a -> padString((String) a[2], (int) Operators.asLong(a[0]), ((ElmChar) a[1]).codePoint(), true, false));
    fn("String.padRight", 3, a -> padString((String) a[2], (int) Operators.asLong(a[0]), ((ElmChar) a[1]).codePoint(), false, true));
    fn("String.pad", 3, a -> padString((String) a[2], (int) Operators.asLong(a[0]), ((ElmChar) a[1]).codePoint(), true, true));
    Function<Object[], Object> indexesFn = a -> {
      String sub = (String) a[0];
      String s = (String) a[1];
      List<Object> out = new ArrayList<>();
      if (!sub.isEmpty()) {
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) {
          out.add((long) i);
        }
      }
      return ElmList.fromJava(out);
    };
    fn("String.indexes", 2, indexesFn);
    fn("String.indices", 2, indexesFn);
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
    fn("String.foldl", 3, a -> {
      int[] cps = ((String) a[2]).codePoints().toArray();
      Object acc = a[1];
      for (int cp : cps) {
        acc = Apply.applyAll(a[0], new ElmChar(cp), acc);
      }
      return acc;
    });
    fn("String.foldr", 3, a -> {
      int[] cps = ((String) a[2]).codePoints().toArray();
      Object acc = a[1];
      for (int i = cps.length - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], new ElmChar(cps[i]), acc);
      }
      return acc;
    });
    fn("String.any", 2, a -> {
      return ((String) a[1]).codePoints().anyMatch(cp -> (Boolean) Apply.apply(a[0], new ElmChar(cp)));
    });
    fn("String.all", 2, a -> {
      return ((String) a[1]).codePoints().allMatch(cp -> (Boolean) Apply.apply(a[0], new ElmChar(cp)));
    });
  }

  private static int normalizeIndex(int i, int len) {
    int v = i < 0 ? len + i : i;
    return Math.max(0, Math.min(v, len));
  }

  /** Pads {@code s} with code-point {@code cp} to total (code-point) length {@code n}. For centering
   * ({@code left && right}) the larger half goes on the left, matching elm/core's {@code String.pad}. */
  private static String padString(String s, int n, int cp, boolean left, boolean right) {
    int len = s.codePointCount(0, s.length());
    int total = n - len;
    if (total <= 0) {
      return s;
    }
    int leftPad;
    int rightPad;
    if (left && right) {
      leftPad = (total + 1) / 2;
      rightPad = total / 2;
    } else if (left) {
      leftPad = total;
      rightPad = 0;
    } else {
      leftPad = 0;
      rightPad = total;
    }
    String fill = new String(Character.toChars(cp));
    return fill.repeat(leftPad) + s + fill.repeat(rightPad);
  }

  // --- Char --------------------------------------------------------------

  static void registerChar() {
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
    fn("Char.isHexDigit", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    });
    fn("Char.isOctDigit", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return c >= '0' && c <= '7';
    });
    // isSpace/isPunctuation/isControl: codepoint-based so the interpreter and JS backend agree.
    fn("Char.isSpace", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return c == ' ' || (c >= '\t' && c <= '\r'); // space, tab, LF, VT, FF, CR
    });
    fn("Char.isPunctuation", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return (c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126);
    });
    fn("Char.isControl", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return c < 32 || c == 127;
    });
  }

  // --- Bitwise (32-bit, matching elm/core and JavaScript's bitwise operators) ----------------------

  static void registerBitwise() {
    fn("Bitwise.and", 2, a -> (long) ((int) Operators.asLong(a[0]) & (int) Operators.asLong(a[1])));
    fn("Bitwise.or", 2, a -> (long) ((int) Operators.asLong(a[0]) | (int) Operators.asLong(a[1])));
    fn("Bitwise.xor", 2, a -> (long) ((int) Operators.asLong(a[0]) ^ (int) Operators.asLong(a[1])));
    fn("Bitwise.complement", 1, a -> (long) (~(int) Operators.asLong(a[0])));
    // shift*By n value: n is the first argument (elm/core's argument order).
    fn("Bitwise.shiftLeftBy", 2, a -> (long) ((int) Operators.asLong(a[1]) << (int) Operators.asLong(a[0])));
    fn("Bitwise.shiftRightBy", 2, a -> (long) ((int) Operators.asLong(a[1]) >> (int) Operators.asLong(a[0])));
    fn("Bitwise.shiftRightZfBy",
        2,
        a -> ((long) ((int) Operators.asLong(a[1]) >>> (int) Operators.asLong(a[0]))) & 0xFFFFFFFFL);
  }
}

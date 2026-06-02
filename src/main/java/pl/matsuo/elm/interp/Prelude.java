package pl.matsuo.elm.interp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.Builtin;
import pl.matsuo.elm.runtime.ElmArray;
import pl.matsuo.elm.runtime.ElmCallable;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmDict;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmSet;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.webgl.GL;

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

  // Html/Svg name tables (declared before the static initializer that reads them).
  private static final String[] HTML_TAGS = {
    "div", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "a", "img", "button",
    "input", "label", "form", "section", "header", "footer", "nav", "main_:main", "br", "hr",
    "table", "thead", "tbody", "tr", "td", "th", "pre", "code", "strong", "em", "i", "b", "small",
    "select", "option", "textarea", "canvas", "audio", "video", "fieldset", "legend", "figure",
    "blockquote", "cite", "figcaption", "caption", "abbr", "address", "article", "aside", "details",
    "summary", "mark", "time", "u", "s", "sub", "sup", "kbd", "samp", "var_:var", "dl", "dt", "dd",
    "ol", "menu", "progress", "meter", "output", "datalist", "iframe", "embed", "object_:object"
  };

  // elmName:htmlName pairs; when no colon, the names are identical.
  private static final String[] HTML_STRING_ATTRS = {
    "class", "id", "href", "src", "alt", "title", "placeholder", "value", "name", "type_:type",
    "for_:for", "rel", "target", "action", "method", "accept", "autocomplete", "min", "max", "step",
    "cols", "rows", "colspan:colspan", "tabindex", "width", "height"
  };

  private static final String[] HTML_BOOL_ATTRS = {
    "disabled", "checked", "selected", "readonly:readonly", "required", "autofocus", "hidden",
    "multiple"
  };

  private static final String[] SVG_TAGS = {
    "svg", "circle", "rect", "line", "polygon", "polyline", "ellipse", "g", "path", "image",
    "text_:text"
  };

  private static final String[] SVG_ATTRS = {
    "width", "height", "viewBox", "cx", "cy", "r", "x", "y", "x1", "y1", "x2", "y2", "rx", "ry",
    "fill", "stroke", "strokeWidth:stroke-width", "points", "d", "transform", "opacity",
    "fillOpacity:fill-opacity", "strokeLinecap:stroke-linecap", "fontSize:font-size",
    "textAnchor:text-anchor", "fontFamily:font-family", "xlinkHref:xlink:href",
    "dominantBaseline:dominant-baseline"
  };

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
    registerBitwise();
    registerMaybe();
    registerResult();
    registerTuple();
    registerDebug();
    registerHtml();
    registerSvg();
    registerBrowser();
    registerEffects();
    registerDict();
    registerSet();
    registerArray();
    registerConstructors();
  }

  // --- Array -------------------------------------------------------------

  private static Object[] arr(Object o) {
    return ((ElmArray) o).items();
  }

  private static int clampIndex(long i, int len) {
    long v = i < 0 ? len + i : i;
    return (int) Math.max(0, Math.min(v, len));
  }

  private static void registerArray() {
    BUILTINS.put("Array.empty", ElmArray.EMPTY);
    fn("Array.isEmpty", 1, a -> arr(a[0]).length == 0);
    fn("Array.length", 1, a -> (long) arr(a[0]).length);
    fn("Array.repeat", 2, a -> {
      int n = (int) Math.max(0, Operators.asLong(a[0]));
      Object[] out = new Object[n];
      java.util.Arrays.fill(out, a[1]);
      return new ElmArray(out);
    });
    fn("Array.initialize", 2, a -> {
      int n = (int) Math.max(0, Operators.asLong(a[0]));
      Object[] out = new Object[n];
      for (int i = 0; i < n; i++) {
        out[i] = Apply.apply(a[1], (long) i);
      }
      return new ElmArray(out);
    });
    fn("Array.fromList", 1, a -> new ElmArray(((ElmList) a[0]).toJava().toArray()));
    fn("Array.toList", 1, a -> ElmList.fromJava(java.util.Arrays.asList(arr(a[0]))));
    fn("Array.get", 2, a -> {
      int i = (int) Operators.asLong(a[0]);
      Object[] items = arr(a[1]);
      return (i >= 0 && i < items.length) ? just(items[i]) : NOTHING;
    });
    fn("Array.set", 3, a -> ((ElmArray) a[2]).set((int) Operators.asLong(a[0]), a[1]));
    fn("Array.push", 2, a -> ((ElmArray) a[1]).push(a[0]));
    fn("Array.append", 2, a -> {
      Object[] x = arr(a[0]);
      Object[] y = arr(a[1]);
      Object[] out = java.util.Arrays.copyOf(x, x.length + y.length);
      System.arraycopy(y, 0, out, x.length, y.length);
      return new ElmArray(out);
    });
    fn("Array.slice", 3, a -> {
      Object[] items = arr(a[2]);
      int from = clampIndex(Operators.asLong(a[0]), items.length);
      int to = clampIndex(Operators.asLong(a[1]), items.length);
      return from < to
          ? new ElmArray(java.util.Arrays.copyOfRange(items, from, to))
          : ElmArray.EMPTY;
    });
    fn("Array.map", 2, a -> {
      Object[] items = arr(a[1]);
      Object[] out = new Object[items.length];
      for (int i = 0; i < items.length; i++) {
        out[i] = Apply.apply(a[0], items[i]);
      }
      return new ElmArray(out);
    });
    fn("Array.indexedMap", 2, a -> {
      Object[] items = arr(a[1]);
      Object[] out = new Object[items.length];
      for (int i = 0; i < items.length; i++) {
        out[i] = Apply.applyAll(a[0], (long) i, items[i]);
      }
      return new ElmArray(out);
    });
    fn("Array.foldl", 3, a -> {
      Object acc = a[1];
      for (Object x : arr(a[2])) {
        acc = Apply.applyAll(a[0], x, acc);
      }
      return acc;
    });
    fn("Array.foldr", 3, a -> {
      Object[] items = arr(a[2]);
      Object acc = a[1];
      for (int i = items.length - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], items[i], acc);
      }
      return acc;
    });
    fn("Array.filter", 2, a -> {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object x : arr(a[1])) {
        if (Boolean.TRUE.equals(Apply.apply(a[0], x))) {
          out.add(x);
        }
      }
      return new ElmArray(out.toArray());
    });
    fn("Array.toIndexedList", 1, a -> {
      Object[] items = arr(a[0]);
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (int i = 0; i < items.length; i++) {
        out.add(new ElmTuple(new Object[] {(long) i, items[i]}));
      }
      return ElmList.fromJava(out);
    });
  }

  // --- Dict / Set --------------------------------------------------------

  private static final Comparator<Object> CMP = Operators::compareValues;

  private static ElmDict asDict(Object o) {
    return (ElmDict) o;
  }

  private static void registerDict() {
    BUILTINS.put("Dict.empty", ElmDict.empty(CMP));
    fn("Dict.singleton", 2, a -> ElmDict.empty(CMP).insert(a[0], a[1]));
    fn("Dict.insert", 3, a -> asDict(a[2]).insert(a[0], a[1]));
    fn("Dict.remove", 2, a -> asDict(a[1]).remove(a[0]));
    fn("Dict.member", 2, a -> asDict(a[1]).member(a[0]));
    fn("Dict.size", 1, a -> (long) asDict(a[0]).size());
    fn("Dict.isEmpty", 1, a -> asDict(a[0]).size() == 0);
    fn("Dict.get", 2, a -> {
      Object v = asDict(a[1]).getOrNull(a[0]);
      return v == null ? NOTHING : just(v);
    });
    fn("Dict.update", 3, a -> {
      ElmDict dict = asDict(a[2]);
      Object current = dict.getOrNull(a[0]);
      Object maybe = current == null ? NOTHING : just(current);
      Object result = Apply.apply(a[1], maybe);
      if (isJust(result)) {
        return dict.insert(a[0], justValue(result));
      }
      return dict.remove(a[0]);
    });
    fn("Dict.keys", 1, a -> ElmList.fromJava(new ArrayList<>(asDict(a[0]).entries().keySet())));
    fn("Dict.values", 1, a -> ElmList.fromJava(new ArrayList<>(asDict(a[0]).entries().values())));
    fn("Dict.toList", 1, a -> {
      List<Object> out = new ArrayList<>();
      asDict(a[0]).entries().forEach((k, v) -> out.add(new ElmTuple(new Object[] {k, v})));
      return ElmList.fromJava(out);
    });
    fn("Dict.fromList", 1, a -> {
      ElmDict dict = ElmDict.empty(CMP);
      for (Object pair : ((ElmList) a[0]).toJava()) {
        ElmTuple t = (ElmTuple) pair;
        dict = dict.insert(t.get(0), t.get(1));
      }
      return dict;
    });
    fn("Dict.map", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      for (Map.Entry<Object, Object> e : asDict(a[1]).entries().entrySet()) {
        out = out.insert(e.getKey(), Apply.applyAll(a[0], e.getKey(), e.getValue()));
      }
      return out;
    });
    fn("Dict.filter", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      for (Map.Entry<Object, Object> e : asDict(a[1]).entries().entrySet()) {
        if ((Boolean) Apply.applyAll(a[0], e.getKey(), e.getValue())) {
          out = out.insert(e.getKey(), e.getValue());
        }
      }
      return out;
    });
    fn("Dict.foldl", 3, a -> {
      Object acc = a[1];
      for (Map.Entry<Object, Object> e : asDict(a[2]).entries().entrySet()) {
        acc = Apply.applyAll(a[0], e.getKey(), e.getValue(), acc);
      }
      return acc;
    });
    fn("Dict.foldr", 3, a -> {
      Object acc = a[1];
      java.util.List<Map.Entry<Object, Object>> entries =
          new java.util.ArrayList<>(asDict(a[2]).entries().entrySet());
      for (int i = entries.size() - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], entries.get(i).getKey(), entries.get(i).getValue(), acc);
      }
      return acc;
    });
    fn("Dict.union", 2, a -> {
      ElmDict out = asDict(a[1]);
      for (Map.Entry<Object, Object> e : asDict(a[0]).entries().entrySet()) {
        out = out.insert(e.getKey(), e.getValue());
      }
      return out;
    });
    fn("Dict.intersect", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      ElmDict other = asDict(a[1]);
      for (Map.Entry<Object, Object> e : asDict(a[0]).entries().entrySet()) {
        if (other.member(e.getKey())) {
          out = out.insert(e.getKey(), e.getValue());
        }
      }
      return out;
    });
    fn("Dict.diff", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      ElmDict other = asDict(a[1]);
      for (Map.Entry<Object, Object> e : asDict(a[0]).entries().entrySet()) {
        if (!other.member(e.getKey())) {
          out = out.insert(e.getKey(), e.getValue());
        }
      }
      return out;
    });
    fn("Dict.partition", 2, a -> {
      ElmDict yes = ElmDict.empty(CMP);
      ElmDict no = ElmDict.empty(CMP);
      for (Map.Entry<Object, Object> e : asDict(a[1]).entries().entrySet()) {
        if ((Boolean) Apply.applyAll(a[0], e.getKey(), e.getValue())) {
          yes = yes.insert(e.getKey(), e.getValue());
        } else {
          no = no.insert(e.getKey(), e.getValue());
        }
      }
      return new ElmTuple(new Object[] {yes, no});
    });
    // Dict.merge leftStep bothStep rightStep leftDict rightDict initial -> result. Steps the two
    // dicts in ascending key order, calling leftStep / bothStep / rightStep per key.
    fn("Dict.merge", 6, a -> {
      List<Map.Entry<Object, Object>> ls = new ArrayList<>(asDict(a[3]).entries().entrySet());
      List<Map.Entry<Object, Object>> rs = new ArrayList<>(asDict(a[4]).entries().entrySet());
      Object acc = a[5];
      int i = 0;
      int j = 0;
      while (i < ls.size() && j < rs.size()) {
        Map.Entry<Object, Object> l = ls.get(i);
        Map.Entry<Object, Object> r = rs.get(j);
        int c = CMP.compare(l.getKey(), r.getKey());
        if (c < 0) {
          acc = Apply.applyAll(a[0], l.getKey(), l.getValue(), acc);
          i++;
        } else if (c > 0) {
          acc = Apply.applyAll(a[2], r.getKey(), r.getValue(), acc);
          j++;
        } else {
          acc = Apply.applyAll(a[1], l.getKey(), l.getValue(), r.getValue(), acc);
          i++;
          j++;
        }
      }
      for (; i < ls.size(); i++) {
        acc = Apply.applyAll(a[0], ls.get(i).getKey(), ls.get(i).getValue(), acc);
      }
      for (; j < rs.size(); j++) {
        acc = Apply.applyAll(a[2], rs.get(j).getKey(), rs.get(j).getValue(), acc);
      }
      return acc;
    });
  }

  private static ElmSet asSet(Object o) {
    return (ElmSet) o;
  }

  private static void registerSet() {
    BUILTINS.put("Set.empty", ElmSet.empty(CMP));
    fn("Set.singleton", 1, a -> ElmSet.empty(CMP).insert(a[0]));
    fn("Set.insert", 2, a -> asSet(a[1]).insert(a[0]));
    fn("Set.remove", 2, a -> asSet(a[1]).remove(a[0]));
    fn("Set.member", 2, a -> asSet(a[1]).member(a[0]));
    fn("Set.size", 1, a -> (long) asSet(a[0]).size());
    fn("Set.isEmpty", 1, a -> asSet(a[0]).size() == 0);
    fn("Set.toList", 1, a -> ElmList.fromJava(new ArrayList<>(asSet(a[0]).elements())));
    fn("Set.fromList", 1, a -> {
      ElmSet set = ElmSet.empty(CMP);
      for (Object x : ((ElmList) a[0]).toJava()) {
        set = set.insert(x);
      }
      return set;
    });
    fn("Set.union", 2, a -> {
      ElmSet out = asSet(a[1]);
      for (Object x : asSet(a[0]).elements()) {
        out = out.insert(x);
      }
      return out;
    });
    fn("Set.intersect", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      ElmSet other = asSet(a[1]);
      for (Object x : asSet(a[0]).elements()) {
        if (other.member(x)) {
          out = out.insert(x);
        }
      }
      return out;
    });
    fn("Set.diff", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      ElmSet other = asSet(a[1]);
      for (Object x : asSet(a[0]).elements()) {
        if (!other.member(x)) {
          out = out.insert(x);
        }
      }
      return out;
    });
    fn("Set.map", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      for (Object x : asSet(a[1]).elements()) {
        out = out.insert(Apply.apply(a[0], x));
      }
      return out;
    });
    fn("Set.filter", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      for (Object x : asSet(a[1]).elements()) {
        if ((Boolean) Apply.apply(a[0], x)) {
          out = out.insert(x);
        }
      }
      return out;
    });
    fn("Set.partition", 2, a -> {
      ElmSet yes = ElmSet.empty(CMP);
      ElmSet no = ElmSet.empty(CMP);
      for (Object x : asSet(a[1]).elements()) {
        if ((Boolean) Apply.apply(a[0], x)) {
          yes = yes.insert(x);
        } else {
          no = no.insert(x);
        }
      }
      return new ElmTuple(new Object[] {yes, no});
    });
    fn("Set.foldl", 3, a -> {
      Object acc = a[1];
      for (Object x : asSet(a[2]).elements()) {
        acc = Apply.applyAll(a[0], x, acc);
      }
      return acc;
    });
    fn("Set.foldr", 3, a -> {
      Object acc = a[1];
      java.util.List<Object> elems = new java.util.ArrayList<>(asSet(a[2]).elements());
      for (int i = elems.size() - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], elems.get(i), acc);
      }
      return acc;
    });
  }

  // --- Cmd / Sub / Random / Time / Task ----------------------------------

  private static ElmData d(String ctor, Object... args) {
    return new ElmData(ctor, args);
  }

  private static void registerEffects() {
    BUILTINS.put("Cmd.none", d("$CmdNone"));
    fn("Cmd.batch", 1, a -> d("$CmdBatch", a[0]));
    fn("Cmd.map", 2, a -> a[1]); // tagging is not tracked headlessly
    BUILTINS.put("Sub.none", d("$SubNone"));
    fn("Sub.batch", 1, a -> d("$SubBatch", a[0]));
    fn("Sub.map", 2, a -> a[1]);

    fn("Random.generate", 2, a -> d("$Cmd_Random", a[1], a[0])); // (toMsg, gen) -> [gen, toMsg]
    fn("Random.int", 2, a -> d("$Gen_Int", a[0], a[1]));
    fn("Random.float", 2, a -> d("$Gen_Float", a[0], a[1]));
    fn("Random.uniform", 2, a -> d("$Gen_Uniform", a[0], a[1]));
    fn("Random.list", 2, a -> d("$Gen_List", a[0], a[1]));
    fn("Random.pair", 2, a -> d("$Gen_Pair", a[0], a[1]));
    fn("Random.constant", 1, a -> d("$Gen_Const", a[0]));
    fn("Random.map", 2, a -> d("$Gen_Map", a[0], a[1]));
    fn("Random.map2", 3, a -> d("$Gen_Map2", a[0], a[1], a[2]));
    fn("Random.map3", 4, a -> d("$Gen_Map3", a[0], a[1], a[2], a[3]));
    fn("Random.map4", 5, a -> d("$Gen_Map4", a[0], a[1], a[2], a[3], a[4]));
    fn("Random.map5", 6, a -> d("$Gen_Map5", a[0], a[1], a[2], a[3], a[4], a[5]));
    fn("Random.weighted", 2, a -> d("$Gen_Weighted", a[0], a[1]));
    fn("Random.andThen", 2, a -> d("$Gen_AndThen", a[0], a[1]));
    // Pure seeded randomness: a Seed is $Seed[state]; step runs a generator deterministically.
    BUILTINS.put("Random.independentSeed", d("$Gen_IndependentSeed"));
    fn("Random.initialSeed", 1, a -> d("$Seed", scrambleSeed(Operators.asLong(a[0]))));
    fn("Random.step", 2, a -> {
      long state = (Long) ((ElmData) Thunk.resolve(a[1])).arg(0);
      Object[] r = stepGen(a[0], state);
      return new ElmTuple(new Object[] {r[0], d("$Seed", r[1])});
    });

    BUILTINS.put("Time.utc", d("$Zone", 0L));
    BUILTINS.put("Time.here", d("$Task_Const", d("$Zone", 0L)));
    BUILTINS.put("Time.now", d("$Task_Const", d("$Posix", 0L)));
    fn("Time.millisToPosix", 1, a -> d("$Posix", Operators.asLong(a[0])));
    fn("Time.posixToMillis", 1, a -> ((ElmData) a[0]).arg(0));
    fn("Time.every", 2, a -> d("$Sub_Every", a[0], a[1]));
    fn("Time.toHour", 2, a -> timePart(a[0], a[1], 3600000L, 24));
    fn("Time.toMinute", 2, a -> timePart(a[0], a[1], 60000L, 60));
    fn("Time.toSecond", 2, a -> timePart(a[0], a[1], 1000L, 60));
    fn("Time.toMillis", 2, a -> timePart(a[0], a[1], 1L, 1000));
    fn("Time.toYear", 2, a -> (long) zonedDate(a[0], a[1]).getYear());
    fn("Time.toMonth", 2, a -> d(MONTHS[zonedDate(a[0], a[1]).getMonthValue() - 1]));
    fn("Time.toDay", 2, a -> (long) zonedDate(a[0], a[1]).getDayOfMonth());
    fn("Time.toWeekday", 2, a -> d(WEEKDAYS[zonedDate(a[0], a[1]).getDayOfWeek().getValue() - 1]));
    fn("Time.customZone", 2, a -> d("$Zone", Operators.asLong(a[0]))); // eras (arg 1) are ignored

    fn("Task.perform", 2, a -> d("$Cmd_Task", a[1], a[0])); // (toMsg, task) -> [task, toMsg]
    fn("Task.attempt", 2, a -> d("$Cmd_TaskAttempt", a[1], a[0])); // delivers Ok value / Err
    fn("Task.succeed", 1, a -> d("$Task_Const", a[0]));
    fn("Task.fail", 1, a -> d("$Task_Fail", a[0]));
    fn("Task.map", 2, a -> d("$Task_Map", a[0], a[1]));
    fn("Task.andThen", 2, a -> d("$Task_AndThen", a[0], a[1]));
    fn("Task.mapError", 2, a -> d("$Task_MapError", a[0], a[1]));
    fn("Task.onError", 2, a -> d("$Task_OnError", a[0], a[1]));
    // Process.sleep: headlessly there is no real delay; it succeeds immediately with ().
    fn("Process.sleep", 1, a -> d("$Task_Const", pl.matsuo.elm.runtime.ElmUnit.INSTANCE));

    // Browser.Events: subscriptions to input/animation. Headlessly, the Tea driver fires
    // animation-frame and keyboard/mouse subs on demand; onResize/onVisibilityChange are inert.
    fn("Browser.Events.onResize", 1, a -> d("$Sub_Resize", a[0]));
    fn("Browser.Events.onVisibilityChange", 1, a -> d("$Sub_Visibility", a[0]));
    fn("Browser.Events.onAnimationFrameDelta", 1, a -> d("$Sub_FrameDelta", a[0]));
    fn("Browser.Events.onAnimationFrame", 1, a -> d("$Sub_Frame", a[0]));
    fn("Browser.Events.onKeyDown", 1, a -> d("$Sub_KeyDown", a[0]));
    fn("Browser.Events.onKeyUp", 1, a -> d("$Sub_KeyUp", a[0]));
    fn("Browser.Events.onClick", 1, a -> d("$Sub_Click", a[0]));
    fn("Browser.Events.onMouseDown", 1, a -> d("$Sub_MouseDown", a[0]));
    fn("Browser.Events.onMouseUp", 1, a -> d("$Sub_MouseUp", a[0]));
    fn("Browser.Events.onMouseMove", 1, a -> d("$Sub_MouseMove", a[0]));
    // Browser.Dom.getViewport: a Task yielding a fixed 600x600 viewport (headless stub).
    BUILTINS.put("Browser.Dom.getViewport", d("$Task_Const", viewport()));
    // Browser.Dom.getElement id: a Task yielding a fixed element box (headless stub).
    fn("Browser.Dom.getElement", 1, a -> d("$Task_Const", element()));

    registerHttp();
    registerJson();
    registerFile();
    registerMath();
    registerWebGL();
    registerRegex();
  }

  // --- Math.Vector3 / Math.Matrix4 (elm-explorations/linear-algebra) -----

  private static ElmData vec3(double x, double y, double z) {
    return new ElmData("$Vec3", new Object[] {x, y, z});
  }

  private static double v3(Object v, int i) {
    return Operators.toDouble(((ElmData) v).arg(i));
  }

  private static ElmData mat4(double[] m) {
    return new ElmData("$Mat4", new Object[] {m});
  }

  private static double[] m4(Object o) {
    return (double[]) ((ElmData) o).arg(0);
  }

  // --- Regex (elm/regex) -------------------------------------------------

  private static java.util.regex.Pattern pat(Object o) {
    return (java.util.regex.Pattern) ((ElmData) o).arg(0);
  }

  /** A `Regex.Match` record { match, index, number, submatches }. */
  private static ElmRecord matchRecord(java.util.regex.Matcher m, int number) {
    java.util.List<Object> subs = new java.util.ArrayList<>();
    for (int g = 1; g <= m.groupCount(); g++) {
      subs.add(m.group(g) == null ? NOTHING : just(m.group(g)));
    }
    java.util.Map<String, Object> f = new java.util.LinkedHashMap<>();
    f.put("match", m.group());
    f.put("index", (long) m.start());
    f.put("number", (long) (number + 1));
    f.put("submatches", ElmList.fromJava(subs));
    return new ElmRecord(f);
  }

  private static void registerRegex() {
    BUILTINS.put("Regex.never", new ElmData("$Regex", new Object[] {java.util.regex.Pattern.compile("(?!)")}));
    fn("Regex.fromString", 1, a -> {
      try {
        return just(new ElmData("$Regex", new Object[] {java.util.regex.Pattern.compile((String) a[0])}));
      } catch (RuntimeException e) {
        return NOTHING;
      }
    });
    fn("Regex.contains", 2, a -> pat(a[0]).matcher((String) a[1]).find());
    fn("Regex.split", 2, a -> {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (String part : pat(a[0]).split((String) a[1], -1)) {
        out.add(part);
      }
      return ElmList.fromJava(out);
    });
    fn("Regex.find", 2, a -> {
      java.util.regex.Matcher m = pat(a[0]).matcher((String) a[1]);
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (int n = 0; m.find(); n++) {
        out.add(matchRecord(m, n));
      }
      return ElmList.fromJava(out);
    });
    fn("Regex.replace", 3, a -> {
      java.util.regex.Matcher m = pat(a[0]).matcher((String) a[2]);
      StringBuilder sb = new StringBuilder();
      for (int n = 0; m.find(); n++) {
        String rep = (String) Apply.apply(a[1], matchRecord(m, n));
        m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
      }
      m.appendTail(sb);
      return sb.toString();
    });
  }

  private static void registerMath() {
    fn("Math.Vector3.vec3", 3,
        a -> vec3(Operators.toDouble(a[0]), Operators.toDouble(a[1]), Operators.toDouble(a[2])));
    BUILTINS.put("Math.Vector3.i", vec3(1, 0, 0));
    BUILTINS.put("Math.Vector3.j", vec3(0, 1, 0));
    BUILTINS.put("Math.Vector3.k", vec3(0, 0, 1));
    fn("Math.Vector3.getX", 1, a -> v3(a[0], 0));
    fn("Math.Vector3.getY", 1, a -> v3(a[0], 1));
    fn("Math.Vector3.getZ", 1, a -> v3(a[0], 2));
    fn("Math.Vector3.add", 2, a -> vec3(v3(a[0], 0) + v3(a[1], 0), v3(a[0], 1) + v3(a[1], 1), v3(a[0], 2) + v3(a[1], 2)));
    fn("Math.Vector3.sub", 2, a -> vec3(v3(a[0], 0) - v3(a[1], 0), v3(a[0], 1) - v3(a[1], 1), v3(a[0], 2) - v3(a[1], 2)));
    fn("Math.Vector3.scale", 2, a -> {
      double k = Operators.toDouble(a[0]);
      return vec3(k * v3(a[1], 0), k * v3(a[1], 1), k * v3(a[1], 2));
    });
    fn("Math.Vector3.negate", 1, a -> vec3(-v3(a[0], 0), -v3(a[0], 1), -v3(a[0], 2)));
    fn("Math.Vector3.dot", 2, a -> v3(a[0], 0) * v3(a[1], 0) + v3(a[0], 1) * v3(a[1], 1) + v3(a[0], 2) * v3(a[1], 2));
    fn("Math.Vector3.cross", 2, a -> vec3(
        v3(a[0], 1) * v3(a[1], 2) - v3(a[0], 2) * v3(a[1], 1),
        v3(a[0], 2) * v3(a[1], 0) - v3(a[0], 0) * v3(a[1], 2),
        v3(a[0], 0) * v3(a[1], 1) - v3(a[0], 1) * v3(a[1], 0)));
    fn("Math.Vector3.length", 1, a -> Math.sqrt(v3(a[0], 0) * v3(a[0], 0) + v3(a[0], 1) * v3(a[0], 1) + v3(a[0], 2) * v3(a[0], 2)));
    fn("Math.Vector2.vec2", 2, a -> new ElmData("$Vec2", new Object[] {Operators.toDouble(a[0]), Operators.toDouble(a[1])}));
    fn("Math.Vector2.getX", 1, a -> Operators.toDouble(((ElmData) a[0]).arg(0)));
    fn("Math.Vector2.getY", 1, a -> Operators.toDouble(((ElmData) a[0]).arg(1)));
    fn("Math.Vector3.normalize", 1, a -> {
      double len = Math.sqrt(v3(a[0], 0) * v3(a[0], 0) + v3(a[0], 1) * v3(a[0], 1) + v3(a[0], 2) * v3(a[0], 2));
      return len == 0 ? a[0] : vec3(v3(a[0], 0) / len, v3(a[0], 1) / len, v3(a[0], 2) / len);
    });

    BUILTINS.put("Math.Matrix4.identity", mat4(GL.identity()));
    fn("Math.Matrix4.mul", 2, a -> mat4(GL.mul(m4(a[0]), m4(a[1]))));
    fn("Math.Matrix4.makePerspective", 4,
        a -> mat4(GL.perspective(Operators.toDouble(a[0]), Operators.toDouble(a[1]), Operators.toDouble(a[2]), Operators.toDouble(a[3]))));
    fn("Math.Matrix4.makeLookAt", 3, a -> mat4(GL.lookAt(
        v3(a[0], 0), v3(a[0], 1), v3(a[0], 2),
        v3(a[1], 0), v3(a[1], 1), v3(a[1], 2),
        v3(a[2], 0), v3(a[2], 1), v3(a[2], 2))));
    fn("Math.Matrix4.makeRotate", 2,
        a -> mat4(GL.makeRotate(Operators.toDouble(a[0]), v3(a[1], 0), v3(a[1], 1), v3(a[1], 2))));
    fn("Math.Matrix4.makeTranslate", 1, a -> mat4(GL.makeTranslate(v3(a[0], 0), v3(a[0], 1), v3(a[0], 2))));
    fn("Math.Matrix4.makeTranslate3", 3,
        a -> mat4(GL.makeTranslate(Operators.toDouble(a[0]), Operators.toDouble(a[1]), Operators.toDouble(a[2]))));
    fn("Math.Matrix4.makeScale3", 3,
        a -> mat4(GL.makeScale(Operators.toDouble(a[0]), Operators.toDouble(a[1]), Operators.toDouble(a[2]))));
    fn("Math.Matrix4.transform", 2, a -> {
      double[] r = GL.transform(m4(a[0]), v3(a[1], 0), v3(a[1], 1), v3(a[1], 2));
      return vec3(r[0], r[1], r[2]);
    });
    fn("Math.Matrix4.rotate", 4, a -> mat4(GL.mul(m4(a[3]),
        GL.makeRotate(Operators.toDouble(a[0]), v3(a[1], 0), v3(a[1], 1), v3(a[1], 2)))));
  }

  // --- WebGL (elm-explorations/webgl) ------------------------------------

  private static Object webglCanvas(Object attrsList, Object entities) {
    long count = ((ElmList) entities).toJava().size();
    ElmList attrs =
        ElmList.cons(
            new ElmData("$Att", new Object[] {"data-entities", Long.toString(count)}),
            (ElmList) attrsList);
    return node("canvas", attrs, ElmList.NIL);
  }

  private static void registerWebGL() {
    fn("WebGL.toHtml", 2, a -> webglCanvas(a[0], a[1]));
    fn("WebGL.toHtmlWith", 3, a -> webglCanvas(a[1], a[2])); // drop the options argument
    // Render options for toHtmlWith (opaque headlessly).
    fn("WebGL.clearColor", 4, a -> d("$Option"));
    fn("WebGL.alpha", 1, a -> d("$Option"));
    fn("WebGL.depth", 1, a -> d("$Option"));
    fn("WebGL.stencil", 1, a -> d("$Option"));
    BUILTINS.put("WebGL.antialias", d("$Option"));
    fn("WebGL.entity", 4, a -> d("$Entity", a[0], a[1], a[2], a[3]));
    fn("WebGL.entityWith", 5, a -> d("$Entity", a[1], a[2], a[3], a[4]));
    fn("WebGL.triangles", 1, a -> d("$Mesh", "triangles", a[0]));
    fn("WebGL.indexedTriangles", 2, a -> d("$Mesh", "indexed", a[0], a[1]));
    fn("WebGL.lines", 1, a -> d("$Mesh", "lines", a[0]));
    fn("WebGL.lineStrip", 1, a -> d("$Mesh", "lineStrip", a[0]));
    fn("WebGL.lineLoop", 1, a -> d("$Mesh", "lineLoop", a[0]));
    fn("WebGL.points", 1, a -> d("$Mesh", "points", a[0]));
    fn("WebGL.triangleStrip", 1, a -> d("$Mesh", "triangleStrip", a[0]));
    fn("WebGL.triangleFan", 1, a -> d("$Mesh", "triangleFan", a[0]));
    // Textures (Crate/Thwomp/First-Person): load is a Task yielding a stub texture keyed by URL.
    fn("WebGL.Texture.load", 1, a -> d("$Task_Const", d("$Texture", a[0])));
    fn("WebGL.Texture.loadWith", 2, a -> d("$Task_Const", d("$Texture", a[1])));
    fn("WebGL.Texture.size", 1, a -> new ElmTuple(new Object[] {0L, 0L}));
    // Texture option enums (opaque values).
    for (String opt :
        new String[] {
          "nearest", "linear", "nearestMipmapNearest", "linearMipmapNearest",
          "nearestMipmapLinear", "linearMipmapLinear", "repeat", "clampToEdge", "mirroredRepeat"
        }) {
      BUILTINS.put("WebGL.Texture." + opt, d("$TexOpt", opt));
    }
    BUILTINS.put("WebGL.Texture.nonPowerOfTwoOptions", d("$TexOptions"));
    // WebGL.Settings enums/values used by some examples (opaque; ignored headlessly).
    BUILTINS.put("WebGL.Settings.DepthTest.default", d("$Setting"));
  }

  private static void registerFile() {
    // A File is represented as $File[name, mime, url]; toUrl yields the (stub) data URL.
    BUILTINS.put("File.decoder", d("$Dec_File"));
    fn("File.name", 1, a -> ((ElmData) a[0]).arg(0));
    fn("File.mime", 1, a -> ((ElmData) a[0]).arg(1));
    fn("File.size", 1, a -> 0L);
    fn("File.toUrl", 1, a -> d("$Task_Const", ((ElmData) a[0]).arg(2)));
    fn("File.toString", 1, a -> d("$Task_Const", ((ElmData) a[0]).arg(2)));
    // File.Select (often imported `as Select`): commands the Tea driver fulfils with stub files.
    fn("File.Select.file", 2, a -> d("$Cmd_SelectFile", a[1]));
    fn("File.Select.files", 2, a -> d("$Cmd_SelectFiles", a[1]));
    fn("Task.sequence", 1, a -> d("$Task_Seq", a[0]));
  }

  private static void registerHttp() {
    fn("Http.get", 1, a -> {
      ElmRecord r = (ElmRecord) a[0];
      return d("$Cmd_Http", r.get("url"), r.get("expect"));
    });
    fn("Http.post", 1, a -> {
      ElmRecord r = (ElmRecord) a[0];
      return d("$Cmd_Http", r.get("url"), r.get("expect"));
    });
    fn("Http.request", 1, a -> {
      ElmRecord r = (ElmRecord) a[0];
      return d("$Cmd_Http", r.get("url"), r.get("expect"));
    });
    fn("Http.expectString", 1, a -> d("$Expect_String", a[0]));
    fn("Http.expectJson", 2, a -> d("$Expect_Json", a[0], a[1]));
    fn("Http.expectWhatever", 1, a -> d("$Expect_Whatever", a[0]));
    // Request building (headers/bodies are opaque markers; the headless runner reads only url+expect).
    fn("Http.header", 2, a -> d("$Http_Header", a[0], a[1]));
    BUILTINS.put("Http.emptyBody", d("$Http_Body_Empty"));
    fn("Http.stringBody", 2, a -> d("$Http_Body_String", a[0], a[1]));
    fn("Http.jsonBody", 1, a -> d("$Http_Body_Json", a[0]));
    // Http.Error constructors (also produced by the runner; here so user code can build them).
    fn("Http.BadUrl", 1, a -> d("BadUrl", a[0]));
    BUILTINS.put("Http.Timeout", d("Timeout"));
    BUILTINS.put("Http.NetworkError", d("NetworkError"));
    fn("Http.BadStatus", 1, a -> d("BadStatus", a[0]));
    fn("Http.BadBody", 1, a -> d("BadBody", a[0]));
  }

  private static void registerJson() {
    BUILTINS.put("Json.Decode.string", d("$Dec_String"));
    BUILTINS.put("Json.Decode.int", d("$Dec_Int"));
    BUILTINS.put("Json.Decode.float", d("$Dec_Float"));
    BUILTINS.put("Json.Decode.bool", d("$Dec_Bool"));
    fn("Json.Decode.field", 2, a -> d("$Dec_Field", a[0], a[1]));
    fn("Json.Decode.at", 2, a -> d("$Dec_At", a[0], a[1]));
    fn("Json.Decode.index", 2, a -> d("$Dec_Index", a[0], a[1]));
    fn("Json.Decode.oneOf", 1, a -> d("$Dec_OneOf", a[0]));
    fn("Json.Decode.oneOrMore", 2, a -> d("$Dec_OneOrMore", a[0], a[1]));
    BUILTINS.put("Json.Decode.value", d("$Dec_Value"));
    fn("Json.Decode.list", 1, a -> d("$Dec_List", a[0]));
    fn("Json.Decode.map", 2, a -> d("$Dec_MapN", a[0], a[1]));
    fn("Json.Decode.map2", 3, a -> d("$Dec_MapN", a[0], a[1], a[2]));
    fn("Json.Decode.map3", 4, a -> d("$Dec_MapN", a[0], a[1], a[2], a[3]));
    fn("Json.Decode.map4", 5, a -> d("$Dec_MapN", a[0], a[1], a[2], a[3], a[4]));
    fn("Json.Decode.map5", 6, a -> d("$Dec_MapN", a[0], a[1], a[2], a[3], a[4], a[5]));
    fn("Json.Decode.map6", 7, a -> d("$Dec_MapN", a[0], a[1], a[2], a[3], a[4], a[5], a[6]));
    fn("Json.Decode.map7", 8, a -> d("$Dec_MapN", a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]));
    fn(
        "Json.Decode.map8",
        9,
        a -> d("$Dec_MapN", a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]));
    fn("Json.Decode.succeed", 1, a -> d("$Dec_Succeed", a[0]));
    fn("Json.Decode.andThen", 2, a -> d("$Dec_AndThen", a[0], a[1]));
    fn("Json.Decode.maybe", 1, a -> d("$Dec_Maybe", a[0]));
    fn("Json.Decode.nullable", 1, a -> d("$Dec_Nullable", a[0]));
    fn("Json.Decode.null", 1, a -> d("$Dec_Null", a[0]));
    fn("Json.Decode.fail", 1, a -> d("$Dec_Fail", a[0]));
    fn("Json.Decode.lazy", 1, a -> d("$Dec_Lazy", a[0]));
    fn("Json.Decode.dict", 1, a -> d("$Dec_Dict", a[0]));
    fn("Json.Decode.keyValuePairs", 1, a -> d("$Dec_KeyValuePairs", a[0]));
    fn("Json.Decode.decodeString", 2, a -> {
      Object json;
      try {
        json = pl.matsuo.elm.json.JsonParse.parse((String) a[1]);
      } catch (RuntimeException ex) {
        return new ElmData("Err", new Object[] {ex.getMessage()});
      }
      return pl.matsuo.elm.json.DecoderRunner.run(a[0], json);
    });
    fn("Json.Decode.decodeValue", 2, a ->
        pl.matsuo.elm.json.DecoderRunner.run(a[0], jsonTree(a[1])));
    fn("Json.Decode.errorToString", 1, a -> decodeErrorToString((ElmData) Thunk.resolve(a[0]), ""));

    // Json.Encode: a Value is $Json wrapping a plain Java tree; encode serializes it.
    fn("Json.Encode.int", 1, a -> d("$Json", a[0]));
    fn("Json.Encode.float", 1, a -> d("$Json", a[0]));
    fn("Json.Encode.string", 1, a -> d("$Json", a[0]));
    fn("Json.Encode.bool", 1, a -> d("$Json", a[0]));
    BUILTINS.put("Json.Encode.null", d("$Json", pl.matsuo.elm.json.JsonEncode.NULL));
    fn("Json.Encode.list", 2, a -> {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object item : ((ElmList) a[1]).toJava()) {
        out.add(jsonTree(Apply.apply(a[0], item)));
      }
      return d("$Json", out);
    });
    fn("Json.Encode.object", 1, a -> {
      java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
      for (Object pair : ((ElmList) a[0]).toJava()) {
        ElmTuple t = (ElmTuple) pair;
        map.put((String) t.get(0), jsonTree(t.get(1)));
      }
      return d("$Json", map);
    });
    fn("Json.Encode.dict", 3, a -> {
      // dict toKey toValue : a JSON object, keyed by `toKey` and valued by `toValue`.
      java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
      for (Map.Entry<Object, Object> e : asDict(a[2]).entries().entrySet()) {
        map.put((String) Apply.apply(a[0], e.getKey()), jsonTree(Apply.apply(a[1], e.getValue())));
      }
      return d("$Json", map);
    });
    fn("Json.Encode.set", 2, a -> {
      // set toValue : a JSON array of the set's elements (in order).
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object x : asSet(a[1]).elements()) {
        out.add(jsonTree(Apply.apply(a[0], x)));
      }
      return d("$Json", out);
    });
    fn("Json.Encode.encode", 2, a ->
        pl.matsuo.elm.json.JsonEncode.serialize(jsonTree(a[1]), (int) (long) (Long) a[0]));

    // Url / Browser.Navigation: minimal support (headless navigation is a no-op Cmd).
    fn("Url.toString", 1, a -> urlToString((ElmRecord) Thunk.resolve(a[0])));
    fn("Url.fromString", 1, a -> {
      ElmRecord r = parseUrl((String) Thunk.resolve(a[0]));
      return r == null ? d("Nothing") : d("Just", r);
    });
    fn("Browser.Navigation.load", 1, a -> d("$CmdNone"));
    fn("Browser.Navigation.pushUrl", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.replaceUrl", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.back", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.forward", 2, a -> d("$CmdNone"));
    // The permalink bridge is browser-only; headlessly there is no URL fragment, so both are no-ops.
    fn("Browser.Navigation.getHash", 1, a -> d("$CmdNone"));
    fn("Browser.Navigation.setHash", 1, a -> d("$CmdNone"));
    // localStorage is browser-only too; headlessly there is nothing to save to or load from.
    fn("Storage.save", 2, a -> d("$CmdNone"));
    fn("Storage.load", 2, a -> d("$CmdNone"));
  }

  // --- pure seeded randomness (Random.step) ------------------------------
  // Mirrors Tea's xorshift64*, but threaded purely: each draw advances the state and the new state
  // is the returned random word's source. A Seed is $Seed[state] (a Long).

  private static long advance(long s) {
    s ^= s >>> 12;
    s ^= s << 25;
    s ^= s >>> 27;
    return s;
  }

  private static long scrambleSeed(long n) {
    // SplitMix64-style mix so initialSeed 0 (and small values) still give a well-distributed state.
    long z = n + 0x9E3779B97F4A7C15L;
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  /** Flattens a weighted generator's first pair + rest list into parallel (abs-weight, value) lists. */
  private static void collectWeighted(
      Object first, Object rest, List<double[]> weights, List<Object> values) {
    ElmTuple f = (ElmTuple) Thunk.resolve(first);
    weights.add(new double[] {Math.abs(((Number) Thunk.resolve(f.get(0))).doubleValue())});
    values.add(f.get(1));
    for (Object t : ((ElmList) Thunk.resolve(rest)).toJava()) {
      ElmTuple tt = (ElmTuple) Thunk.resolve(t);
      weights.add(new double[] {Math.abs(((Number) Thunk.resolve(tt.get(0))).doubleValue())});
      values.add(tt.get(1));
    }
  }

  /** Runs {@code gen} from state {@code seed}, returning {@code [value, newState(Long)]}. */
  private static Object[] stepGen(Object gen, long seed) {
    ElmData g = (ElmData) Thunk.resolve(gen);
    switch (g.ctor()) {
      case "$Gen_Int" -> {
        long s = advance(seed);
        long lo = Operators.asLong(Thunk.resolve(g.arg(0)));
        long hi = Operators.asLong(Thunk.resolve(g.arg(1)));
        long word = s * 0x2545F4914F6CDD1DL;
        return new Object[] {lo + Math.floorMod(word, hi - lo + 1), s};
      }
      case "$Gen_Float" -> {
        long s = advance(seed);
        double lo = ((Number) Thunk.resolve(g.arg(0))).doubleValue();
        double hi = ((Number) Thunk.resolve(g.arg(1))).doubleValue();
        double unit = (s >>> 11) * (1.0 / (1L << 53));
        return new Object[] {lo + unit * (hi - lo), s};
      }
      case "$Gen_Uniform" -> {
        long s = advance(seed);
        java.util.List<Object> all = new java.util.ArrayList<>();
        all.add(g.arg(0));
        all.addAll(((ElmList) Thunk.resolve(g.arg(1))).toJava());
        long word = s * 0x2545F4914F6CDD1DL;
        return new Object[] {all.get((int) Math.floorMod(word, all.size())), s};
      }
      case "$Gen_List" -> {
        long n = Operators.asLong(Thunk.resolve(g.arg(0)));
        java.util.List<Object> out = new java.util.ArrayList<>();
        long s = seed;
        for (long i = 0; i < n; i++) {
          Object[] r = stepGen(g.arg(1), s);
          out.add(r[0]);
          s = (Long) r[1];
        }
        return new Object[] {ElmList.fromJava(out), s};
      }
      case "$Gen_Pair" -> {
        Object[] a = stepGen(g.arg(0), seed);
        Object[] b = stepGen(g.arg(1), (Long) a[1]);
        return new Object[] {new ElmTuple(new Object[] {a[0], b[0]}), b[1]};
      }
      case "$Gen_Const" -> {
        return new Object[] {g.arg(0), seed};
      }
      case "$Gen_Map" -> {
        Object[] r = stepGen(g.arg(1), seed);
        return new Object[] {Apply.apply(g.arg(0), r[0]), r[1]};
      }
      case "$Gen_Map2" -> {
        Object[] a = stepGen(g.arg(1), seed);
        Object[] b = stepGen(g.arg(2), (Long) a[1]);
        return new Object[] {Apply.applyAll(g.arg(0), a[0], b[0]), b[1]};
      }
      case "$Gen_Map3" -> {
        Object[] a = stepGen(g.arg(1), seed);
        Object[] b = stepGen(g.arg(2), (Long) a[1]);
        Object[] c = stepGen(g.arg(3), (Long) b[1]);
        return new Object[] {Apply.applyAll(g.arg(0), a[0], b[0], c[0]), c[1]};
      }
      case "$Gen_Map4" -> {
        Object[] a = stepGen(g.arg(1), seed);
        Object[] b = stepGen(g.arg(2), (Long) a[1]);
        Object[] c = stepGen(g.arg(3), (Long) b[1]);
        Object[] e = stepGen(g.arg(4), (Long) c[1]);
        return new Object[] {Apply.applyAll(g.arg(0), a[0], b[0], c[0], e[0]), e[1]};
      }
      case "$Gen_Map5" -> {
        Object[] a = stepGen(g.arg(1), seed);
        Object[] b = stepGen(g.arg(2), (Long) a[1]);
        Object[] c = stepGen(g.arg(3), (Long) b[1]);
        Object[] e = stepGen(g.arg(4), (Long) c[1]);
        Object[] f = stepGen(g.arg(5), (Long) e[1]);
        return new Object[] {Apply.applyAll(g.arg(0), a[0], b[0], c[0], e[0], f[0]), f[1]};
      }
      case "$Gen_Weighted" -> {
        long s = advance(seed);
        List<double[]> weights = new java.util.ArrayList<>();
        List<Object> values = new java.util.ArrayList<>();
        collectWeighted(g.arg(0), g.arg(1), weights, values);
        double total = 0;
        for (double[] w : weights) {
          total += w[0];
        }
        double r = ((s >>> 11) * (1.0 / (1L << 53))) * total;
        for (int i = 0; i < values.size(); i++) {
          r -= weights.get(i)[0];
          if (r <= 0) {
            return new Object[] {values.get(i), s};
          }
        }
        return new Object[] {values.get(values.size() - 1), s};
      }
      case "$Gen_AndThen" -> {
        Object[] r = stepGen(g.arg(1), seed);
        return stepGen(Apply.apply(g.arg(0), r[0]), (Long) r[1]);
      }
      case "$Gen_IndependentSeed" -> {
        long produced = advance(seed);
        long next = advance(produced);
        return new Object[] {d("$Seed", produced), next};
      }
      default -> throw new pl.matsuo.elm.error.ElmRuntimeError("Unsupported generator: " + g.ctor());
    }
  }

  /** Renders a Json.Decode.Error (Field/Index/OneOf/Failure) to a message with its json path. */
  private static String decodeErrorToString(ElmData error, String path) {
    switch (error.ctor()) {
      case "Field" -> {
        String name = (String) Thunk.resolve(error.arg(0));
        String step = name.matches("[a-zA-Z_][a-zA-Z0-9_]*") ? "." + name : "['" + name + "']";
        return decodeErrorToString((ElmData) Thunk.resolve(error.arg(1)), path + step);
      }
      case "Index" -> {
        return decodeErrorToString((ElmData) Thunk.resolve(error.arg(1)),
            path + "[" + Operators.asLong(Thunk.resolve(error.arg(0))) + "]");
      }
      case "OneOf" -> {
        java.util.List<Object> errs = ((ElmList) Thunk.resolve(error.arg(0))).toJava();
        if (errs.isEmpty()) {
          return "Ran into a Json.Decode.oneOf with no possibilities" + (path.isEmpty() ? "" : " at json" + path);
        }
        StringBuilder sb = new StringBuilder("oneOf failed" + (path.isEmpty() ? "" : " at json" + path) + ":");
        for (Object e : errs) {
          sb.append("\n  - ").append(decodeErrorToString((ElmData) Thunk.resolve(e), ""));
        }
        return sb.toString();
      }
      default -> { // Failure message value
        String message = (String) Thunk.resolve(error.arg(0));
        return path.isEmpty() ? message : "Problem with the value at json" + path + ":\n\n    " + message;
      }
    }
  }

  // --- Url (an elm/url-shaped record) ------------------------------------

  /** Parses an absolute URL into an elm/url-shaped record, or {@code null} if it isn't absolute. */
  private static ElmRecord parseUrl(String href) {
    java.net.URI u;
    try {
      u = new java.net.URI(href);
    } catch (java.net.URISyntaxException e) {
      return null;
    }
    String scheme = u.getScheme();
    if (scheme == null || u.getHost() == null) {
      return null;
    }
    Map<String, Object> r = new java.util.LinkedHashMap<>();
    r.put("protocol", "https".equalsIgnoreCase(scheme) ? d("Https") : d("Http"));
    r.put("host", u.getHost());
    r.put("port_", u.getPort() < 0 ? d("Nothing") : d("Just", (long) u.getPort()));
    String path = u.getRawPath();
    r.put("path", path == null || path.isEmpty() ? "/" : path);
    r.put("query", u.getRawQuery() == null ? d("Nothing") : d("Just", u.getRawQuery()));
    r.put("fragment", u.getRawFragment() == null ? d("Nothing") : d("Just", u.getRawFragment()));
    return new ElmRecord(r);
  }

  /** Rebuilds a URL string from an elm/url-shaped record. */
  private static String urlToString(ElmRecord u) {
    Object protocol = u.get("protocol");
    boolean https = protocol instanceof ElmData p && p.ctor().equals("Https");
    StringBuilder sb = new StringBuilder(https ? "https://" : "http://").append(u.get("host"));
    Object port = u.get("port_");
    if (port instanceof ElmData pd && pd.ctor().equals("Just")) {
      sb.append(':').append(pd.arg(0));
    }
    sb.append(u.get("path"));
    Object query = u.get("query");
    if (query instanceof ElmData qd && qd.ctor().equals("Just")) {
      sb.append('?').append(qd.arg(0));
    }
    Object frag = u.get("fragment");
    if (frag instanceof ElmData fd && fd.ctor().equals("Just")) {
      sb.append('#').append(fd.arg(0));
    }
    return sb.toString();
  }

  /** Unwraps a {@code Json.Encode.Value} ($Json) to its underlying Java JSON tree. */
  private static Object jsonTree(Object value) {
    return value instanceof ElmData dd && dd.ctor().equals("$Json") ? dd.arg(0) : value;
  }

  /** A fixed Browser.Dom Viewport record (600x600) for headless runs. */
  private static ElmRecord viewport() {
    Map<String, Object> size = new java.util.LinkedHashMap<>();
    size.put("width", 600.0);
    size.put("height", 600.0);
    Map<String, Object> vp = new java.util.LinkedHashMap<>();
    vp.put("x", 0.0);
    vp.put("y", 0.0);
    vp.put("width", 600.0);
    vp.put("height", 600.0);
    Map<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("scene", new ElmRecord(size));
    root.put("viewport", new ElmRecord(vp));
    return new ElmRecord(root);
  }

  /** A fixed Browser.Dom Element (a 600x600 scene, a 100x40 box at the origin) for headless runs. */
  private static ElmRecord element() {
    Map<String, Object> size = new java.util.LinkedHashMap<>();
    size.put("width", 600.0);
    size.put("height", 600.0);
    Map<String, Object> vp = new java.util.LinkedHashMap<>();
    vp.put("x", 0.0);
    vp.put("y", 0.0);
    vp.put("width", 600.0);
    vp.put("height", 600.0);
    Map<String, Object> box = new java.util.LinkedHashMap<>();
    box.put("x", 0.0);
    box.put("y", 0.0);
    box.put("width", 100.0);
    box.put("height", 40.0);
    Map<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("scene", new ElmRecord(size));
    root.put("viewport", new ElmRecord(vp));
    root.put("element", new ElmRecord(box));
    return new ElmRecord(root);
  }

  private static long timePart(Object zone, Object posix, long unit, int mod) {
    long offsetMinutes = Operators.asLong(((ElmData) zone).arg(0));
    long millis = Operators.asLong(((ElmData) posix).arg(0)) + offsetMinutes * 60000L;
    return Math.floorMod(millis / unit, (long) mod);
  }

  private static final String[] MONTHS =
      {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
  private static final String[] WEEKDAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

  /** The calendar date of a Posix instant in a Zone (offset applied, then read as a UTC date). */
  private static java.time.LocalDate zonedDate(Object zone, Object posix) {
    long offsetMinutes = Operators.asLong(((ElmData) zone).arg(0));
    long millis = Operators.asLong(((ElmData) posix).arg(0)) + offsetMinutes * 60000L;
    return java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate();
  }

  // --- Html / Svg / Browser ----------------------------------------------

  private static Object node(String tag, Object attrs, Object children) {
    return new ElmData("$Node", new Object[] {tag, attrs, children});
  }

  /** Extracts the child nodes from a keyed children list (List ( String, Html )), dropping keys. */
  private static Object keyedChildren(Object list) {
    List<Object> out = new ArrayList<>();
    for (Object pair : ((ElmList) Thunk.resolve(list)).toJava()) {
      out.add(((ElmTuple) Thunk.resolve(pair)).get(1));
    }
    return ElmList.fromJava(out);
  }

  /** Html.map / Svg.map: rebuild a node, routing every event's message through {@code f}. */
  private static Object mapHtml(Object f, Object vnode) {
    Object v = Thunk.resolve(vnode);
    if (v instanceof ElmData d && d.ctor().equals("$Node")) {
      List<Object> attrs = new ArrayList<>();
      for (Object a : ((ElmList) Thunk.resolve(d.arg(1))).toJava()) {
        attrs.add(mapAttr(f, Thunk.resolve(a)));
      }
      List<Object> kids = new ArrayList<>();
      for (Object k : ((ElmList) Thunk.resolve(d.arg(2))).toJava()) {
        kids.add(mapHtml(f, k));
      }
      return node((String) d.arg(0), ElmList.fromJava(attrs), ElmList.fromJava(kids));
    }
    return v; // $Text and anything without events is unchanged
  }

  /** Routes an attribute's event message through {@code f}; non-event attributes pass through. */
  private static Object mapAttr(Object f, Object attr) {
    if (!(attr instanceof ElmData a) || !a.ctor().equals("$On")) {
      return attr;
    }
    String event = (String) a.arg(0);
    Object handler = Thunk.resolve(a.arg(1));
    // input/check carry a value -> msg function; decoders (on/preventDefaultOn) carry a $Dec_*; the
    // rest carry the message directly.
    if (event.equals("input") || event.equals("check")) {
      ElmCallable mapped =
          new ElmCallable() {
            @Override
            public int arity() {
              return 1;
            }

            @Override
            public Object invoke(Object[] args) {
              return Apply.apply(f, Apply.apply(handler, args[0]));
            }

            @Override
            public String name() {
              return "Html.map handler";
            }
          };
      return new ElmData("$On", new Object[] {event, mapped});
    }
    if (handler instanceof ElmData h && h.ctor().startsWith("$Dec")) {
      return new ElmData("$On", new Object[] {event, d("$Dec_MapN", f, handler)});
    }
    return new ElmData("$On", new Object[] {event, Apply.apply(f, handler)});
  }

  private static String[] split(String pair) {
    int i = pair.indexOf(':');
    return i < 0 ? new String[] {pair, pair} : new String[] {pair.substring(0, i), pair.substring(i + 1)};
  }

  private static void registerHtml() {
    fn("Html.text", 1, a -> new ElmData("$Text", new Object[] {a[0]}));
    fn("Html.node", 3, a -> node((String) a[0], a[1], a[2]));
    fn("Html.map", 2, a -> mapHtml(a[0], a[1]));
    fn("Svg.map", 2, a -> mapHtml(a[0], a[1]));
    // Keyed nodes render statically like a plain element (keys matter only for the live DOM diff).
    fn("Html.Keyed.node", 3, a -> node((String) a[0], a[1], keyedChildren(a[2])));
    fn("Html.Keyed.ul", 2, a -> node("ul", a[0], keyedChildren(a[1])));
    fn("Html.Keyed.ol", 2, a -> node("ol", a[0], keyedChildren(a[1])));
    fn("Svg.Keyed.node", 3, a -> node((String) a[0], a[1], keyedChildren(a[2])));
    // Lazy nodes: the interpreter renders statically, so just force the view (memoization is a
    // live-DOM optimization only).
    fn("Html.Lazy.lazy", 2, a -> Apply.apply(a[0], a[1]));
    fn("Html.Lazy.lazy2", 3, a -> Apply.applyAll(a[0], a[1], a[2]));
    fn("Html.Lazy.lazy3", 4, a -> Apply.applyAll(a[0], a[1], a[2], a[3]));
    fn("Svg.Lazy.lazy", 2, a -> Apply.apply(a[0], a[1]));
    fn("Svg.Lazy.lazy2", 3, a -> Apply.applyAll(a[0], a[1], a[2]));
    fn("Svg.Lazy.lazy3", 4, a -> Apply.applyAll(a[0], a[1], a[2], a[3]));
    for (String spec : HTML_TAGS) {
      String[] nt = split(spec);
      String tag = nt[1];
      fn("Html." + nt[0], 2, a -> node(tag, a[0], a[1]));
    }
    for (String spec : HTML_STRING_ATTRS) {
      String[] nt = split(spec);
      String htmlName = nt[1];
      fn("Html.Attributes." + nt[0], 1, a -> new ElmData("$Att", new Object[] {htmlName, a[0]}));
    }
    for (String spec : HTML_BOOL_ATTRS) {
      String[] nt = split(spec);
      String htmlName = nt[1];
      fn("Html.Attributes." + nt[0], 1, a -> new ElmData("$Prop", new Object[] {htmlName, a[0]}));
    }
    fn("Html.Attributes.style", 2, a -> new ElmData("$Style", new Object[] {a[0], a[1]}));
    fn("Html.Attributes.classList", 1, a -> {
      StringBuilder sb = new StringBuilder();
      for (Object pair : ((ElmList) a[0]).toJava()) {
        ElmTuple t = (ElmTuple) pair;
        if (Boolean.TRUE.equals(t.get(1))) {
          if (sb.length() > 0) {
            sb.append(' ');
          }
          sb.append((String) t.get(0));
        }
      }
      return new ElmData("$Att", new Object[] {"class", sb.toString()});
    });
    fn("Html.Events.onClick", 1, a -> new ElmData("$On", new Object[] {"click", a[0]}));
    fn("Html.Events.onInput", 1, a -> new ElmData("$On", new Object[] {"input", a[0]}));
    fn("Html.Events.onCheck", 1, a -> new ElmData("$On", new Object[] {"check", a[0]}));
    fn("Html.Events.onSubmit", 1, a -> new ElmData("$On", new Object[] {"submit", a[0]}));
    fn("Html.Events.onMouseDown", 1, a -> new ElmData("$On", new Object[] {"mousedown", a[0]}));
    fn("Html.Events.onMouseUp", 1, a -> new ElmData("$On", new Object[] {"mouseup", a[0]}));
    // Custom event handlers with decoders (inert in static/headless rendering).
    fn("Html.Events.on", 2, a -> new ElmData("$On", new Object[] {a[0], a[1]}));
    fn("Html.Events.preventDefaultOn", 2, a -> new ElmData("$On", new Object[] {a[0], a[1]}));
    fn("Html.Events.stopPropagationOn", 2, a -> new ElmData("$On", new Object[] {a[0], a[1]}));
    fn("Html.Events.onFocus", 1, a -> new ElmData("$On", new Object[] {"focus", a[0]}));
    fn("Html.Events.onBlur", 1, a -> new ElmData("$On", new Object[] {"blur", a[0]}));
    fn("Html.Events.onDoubleClick", 1, a -> new ElmData("$On", new Object[] {"dblclick", a[0]}));
    fn("Html.Events.onMouseEnter", 1, a -> new ElmData("$On", new Object[] {"mouseenter", a[0]}));
    fn("Html.Events.onMouseLeave", 1, a -> new ElmData("$On", new Object[] {"mouseleave", a[0]}));
    fn("Html.Events.onMouseOver", 1, a -> new ElmData("$On", new Object[] {"mouseover", a[0]}));
    fn("Html.Events.onMouseOut", 1, a -> new ElmData("$On", new Object[] {"mouseout", a[0]}));
    fn("Html.Attributes.classList", 1, a -> {
      StringBuilder sb = new StringBuilder();
      for (Object pair : ((ElmList) a[0]).toJava()) {
        ElmTuple t = (ElmTuple) Thunk.resolve(pair);
        if (Boolean.TRUE.equals(Thunk.resolve(t.get(1)))) {
          if (sb.length() > 0) {
            sb.append(' ');
          }
          sb.append((String) Thunk.resolve(t.get(0)));
        }
      }
      return new ElmData("$Att", new Object[] {"class", sb.toString()});
    });
  }

  private static void registerSvg() {
    for (String spec : SVG_TAGS) {
      String[] nt = split(spec);
      String tag = nt[1];
      fn("Svg." + nt[0], 2, a -> node(tag, a[0], a[1]));
    }
    fn("Svg.text", 1, a -> new ElmData("$Text", new Object[] {a[0]}));
    for (String spec : SVG_ATTRS) {
      String[] nt = split(spec);
      String svgName = nt[1];
      fn("Svg.Attributes." + nt[0], 1, a -> new ElmData("$Att", new Object[] {svgName, a[0]}));
    }
  }

  private static void registerBrowser() {
    fn("Browser.sandbox", 1, a -> new ElmData("$Sandbox", new Object[] {a[0]}));
    fn("Browser.element", 1, a -> new ElmData("$Element", new Object[] {a[0]}));
    fn("Browser.document", 1, a -> new ElmData("$Document", new Object[] {a[0]}));
    fn("Browser.application", 1, a -> new ElmData("$Application", new Object[] {a[0]}));
    fn("Platform.worker", 1, a -> new ElmData("$Worker", new Object[] {a[0]}));
  }

  private static void registerConstructors() {
    CTOR_ARITY.put("Just", 1);
    CTOR_ARITY.put("Nothing", 0);
    CTOR_ARITY.put("Ok", 1);
    CTOR_ARITY.put("Err", 1);
    CTOR_ARITY.put("LT", 0);
    CTOR_ARITY.put("EQ", 0);
    CTOR_ARITY.put("GT", 0);
    // Browser.Events.Visibility, referenced as E.Visible / E.Hidden by elm-playground.
    CTOR_ARITY.put("Visible", 0);
    CTOR_ARITY.put("Hidden", 0);
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
    fn("Char.isHexDigit", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    });
    fn("Char.isOctDigit", 1, a -> {
      int c = ((ElmChar) a[0]).codePoint();
      return c >= '0' && c <= '7';
    });
  }

  // --- Bitwise (32-bit, matching elm/core and JavaScript's bitwise operators) ----------------------

  private static void registerBitwise() {
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
    fn("Maybe.map3", 4, a -> {
      if (isJust(a[1]) && isJust(a[2]) && isJust(a[3])) {
        return just(Apply.applyAll(a[0], justValue(a[1]), justValue(a[2]), justValue(a[3])));
      }
      return NOTHING;
    });
    fn("Maybe.map4", 5, a -> {
      if (isJust(a[1]) && isJust(a[2]) && isJust(a[3]) && isJust(a[4])) {
        return just(Apply.applyAll(a[0], justValue(a[1]), justValue(a[2]), justValue(a[3]), justValue(a[4])));
      }
      return NOTHING;
    });
    fn("Maybe.map5", 6, a -> {
      if (isJust(a[1]) && isJust(a[2]) && isJust(a[3]) && isJust(a[4]) && isJust(a[5])) {
        return just(Apply.applyAll(a[0], justValue(a[1]), justValue(a[2]), justValue(a[3]),
            justValue(a[4]), justValue(a[5])));
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
    fn("Result.map2", 3, a -> {
      if (!isOk(a[1])) {
        return a[1];
      }
      if (!isOk(a[2])) {
        return a[2];
      }
      return new ElmData("Ok",
          new Object[] {Apply.applyAll(a[0], ((ElmData) a[1]).arg(0), ((ElmData) a[2]).arg(0))});
    });
    fn("Result.map3", 4, a -> {
      if (!isOk(a[1])) {
        return a[1];
      }
      if (!isOk(a[2])) {
        return a[2];
      }
      if (!isOk(a[3])) {
        return a[3];
      }
      return new ElmData("Ok", new Object[] {
          Apply.applyAll(a[0], ((ElmData) a[1]).arg(0), ((ElmData) a[2]).arg(0), ((ElmData) a[3]).arg(0))});
    });
    fn("Result.map4", 5, a -> {
      for (int i = 1; i <= 4; i++) {
        if (!isOk(a[i])) {
          return a[i];
        }
      }
      return new ElmData("Ok", new Object[] {Apply.applyAll(a[0],
          ((ElmData) a[1]).arg(0), ((ElmData) a[2]).arg(0), ((ElmData) a[3]).arg(0), ((ElmData) a[4]).arg(0))});
    });
    fn("Result.map5", 6, a -> {
      for (int i = 1; i <= 5; i++) {
        if (!isOk(a[i])) {
          return a[i];
        }
      }
      return new ElmData("Ok", new Object[] {Apply.applyAll(a[0], ((ElmData) a[1]).arg(0),
          ((ElmData) a[2]).arg(0), ((ElmData) a[3]).arg(0), ((ElmData) a[4]).arg(0), ((ElmData) a[5]).arg(0))});
    });
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

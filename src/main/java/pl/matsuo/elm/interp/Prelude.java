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
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;
import pl.matsuo.elm.webgl.GL;

/**
 * The Elm standard-library prelude implemented as Java builtins, keyed by canonical
 * {@code Module.name}. Also defines the default unqualified import bindings and core constructor
 * arities. Shared (immutable) across modules.
 */
public final class Prelude {

  private Prelude() {}

  // Package-private so the per-module register classes (e.g. PreludeCollections) can write into it.
  static final Map<String, Object> BUILTINS = new HashMap<>();
  // Package-private so the per-module register classes (e.g. PreludeCore) can expose short names.
  static final Map<String, String> UNQUALIFIED = new HashMap<>();
  private static final Map<String, Integer> CTOR_ARITY = new HashMap<>();

  // Html/Svg name tables (declared before the static initializer that reads them).
  private static final String[] HTML_TAGS = {
    "div", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "a", "img", "button",
    "input", "label", "form", "section", "header", "footer", "nav", "main_:main", "br", "hr",
    "table", "thead", "tbody", "tr", "td", "th", "pre", "code", "strong", "em", "i", "b", "small",
    "select", "option", "textarea", "canvas", "audio", "video", "fieldset", "legend", "figure",
    "blockquote", "cite", "figcaption", "caption", "abbr", "address", "article", "aside", "details",
    "summary", "mark", "time", "u", "s", "sub", "sup", "kbd", "samp", "var_:var", "dl", "dt", "dd",
    "menu", "progress", "meter", "output", "datalist", "iframe", "embed", "object_:object",
    "colgroup", "col", "tfoot", "optgroup", "source", "track", "param", "ins", "del", "dfn", "q",
    "ruby", "rt", "rp", "bdi", "bdo", "wbr", "menuitem", "math"
  };

  // elmName:htmlName pairs; when no colon, the names are identical.
  private static final String[] HTML_STRING_ATTRS = {
    "class", "id", "href", "src", "alt", "title", "placeholder", "value", "name", "type_:type",
    "for_:for", "rel", "target", "action", "method", "accept", "autocomplete", "min", "max", "step",
    "cols", "rows", "colspan:colspan", "rowspan:rowspan", "tabindex", "width", "height"
  };

  private static final String[] HTML_BOOL_ATTRS = {
    "disabled", "checked", "selected", "readonly:readonly", "required", "autofocus", "hidden",
    "multiple", "spellcheck"
  };

  // The full elm/svg element set (elmName:tag where they differ). SVG tags are case-sensitive and
  // mostly verbatim; only text_ -> text and colorProfile -> color-profile are aliased.
  private static final String[] SVG_TAGS = {
    "svg", "foreignObject", "circle", "ellipse", "image", "line", "path", "polygon", "polyline",
    "rect", "use", "a", "defs", "g", "marker", "mask", "pattern", "switch", "symbol", "clipPath",
    "cursor", "filter", "style", "view", "desc", "metadata", "title", "linearGradient",
    "radialGradient", "stop", "text_:text", "textPath", "tref", "tspan", "altGlyph", "altGlyphDef",
    "altGlyphItem", "glyph", "glyphRef", "font", "colorProfile:color-profile", "animate",
    "animateColor", "animateMotion", "animateTransform", "mpath", "set", "feBlend", "feColorMatrix",
    "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting",
    "feDisplacementMap", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur",
    "feImage", "feMerge", "feMergeNode", "feMorphology", "feOffset", "feSpecularLighting", "feTile",
    "feTurbulence", "feDistantLight", "fePointLight", "feSpotLight"
  };

  private static final String[] SVG_ATTRS = {
    "width", "height", "viewBox", "preserveAspectRatio", "cx", "cy", "r", "x", "y", "x1", "y1", "x2",
    "y2", "rx", "ry", "fill", "stroke", "strokeWidth:stroke-width", "points", "d", "transform",
    "opacity", "fillOpacity:fill-opacity", "strokeLinecap:stroke-linecap",
    "strokeLinejoin:stroke-linejoin", "strokeOpacity:stroke-opacity",
    "strokeDasharray:stroke-dasharray", "fontSize:font-size", "textAnchor:text-anchor",
    "fontFamily:font-family", "xlinkHref:xlink:href", "dominantBaseline:dominant-baseline"
  };

  public static Map<String, Object> builtins() {
    return BUILTINS;
  }

  /**
   * The Elm names of the bound Html element functions (the {@code elmName} of each {@link #HTML_TAGS}
   * entry, e.g. {@code "div"}, {@code "dl"}, {@code "main_"}). The JS backend's element registry and
   * the type-checker's element schemes must cover exactly this set — see {@code HtmlElementParityTest}.
   */
  public static java.util.List<String> htmlElementNames() {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String spec : HTML_TAGS) {
      int colon = spec.indexOf(':');
      names.add(colon < 0 ? spec : spec.substring(0, colon));
    }
    return names;
  }

  /** The Elm names of the bound Svg element functions (the {@code elmName} of each {@link #SVG_TAGS}
   * entry). Kept in lock-step with the JS runtime and the type-checker by {@code SvgElementParityTest}. */
  public static java.util.List<String> svgElementNames() {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String spec : SVG_TAGS) {
      int colon = spec.indexOf(':');
      names.add(colon < 0 ? spec : spec.substring(0, colon));
    }
    return names;
  }

  public static Map<String, String> defaultUnqualified() {
    return new HashMap<>(UNQUALIFIED);
  }

  public static Map<String, Integer> defaultCtorArity() {
    return new HashMap<>(CTOR_ARITY);
  }

  // --- helpers -----------------------------------------------------------

  // Package-private: shared by the per-module register classes (e.g. PreludeCollections).
  static void fn(String name, int arity, Function<Object[], Object> impl) {
    BUILTINS.put(name, new Builtin(name, arity, impl));
  }

  /** Registers a builtin and also exposes its short name unqualified (as Basics does). */
  static void basics(String shortName, int arity, Function<Object[], Object> impl) {
    String canonical = "Basics." + shortName;
    BUILTINS.put(canonical, new Builtin(canonical, arity, impl));
    UNQUALIFIED.put(shortName, canonical);
  }

  static Object just(Object x) {
    return new ElmData("Just", new Object[] {x});
  }

  static final ElmData NOTHING = new ElmData("Nothing", new Object[0]);

  static Object ordering(int c) {
    return new ElmData(c < 0 ? "LT" : c == 0 ? "EQ" : "GT", new Object[0]);
  }

  static List<Object> javaList(Object v) {
    return ((ElmList) v).toJava();
  }

  static {
    PreludeCore.registerBasics();
    PreludeCore.registerList();
    PreludeCore.registerString();
    PreludeCore.registerChar();
    PreludeCore.registerBitwise();
    registerMaybe();
    registerResult();
    registerTuple();
    registerDebug();
    registerHtml();
    registerSvg();
    registerBrowser();
    registerEffects();
    PreludeCollections.registerDict();
    PreludeCollections.registerSet();
    PreludeCollections.registerArray();
    registerConstructors();
  }

  // --- Cmd / Sub / Random / Time / Task ----------------------------------

  // Package-private: shared by the per-module register classes (e.g. PreludeJson).
  static ElmData d(String ctor, Object... args) {
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
    // getZoneName: headlessly we can't read the host zone name, so report a UTC offset of 0.
    BUILTINS.put("Time.getZoneName", d("$Task_Const", d("Offset", 0L)));
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
    fn("Task.map2", 3, a -> d("$Task_Map2", a[0], a[1], a[2]));
    fn("Task.map3", 4, a -> d("$Task_Map3", a[0], a[1], a[2], a[3]));
    fn("Task.map4", 5, a -> d("$Task_Map4", a[0], a[1], a[2], a[3], a[4]));
    fn("Task.map5", 6, a -> d("$Task_Map5", a[0], a[1], a[2], a[3], a[4], a[5]));
    fn("Task.andThen", 2, a -> d("$Task_AndThen", a[0], a[1]));
    fn("Task.mapError", 2, a -> d("$Task_MapError", a[0], a[1]));
    fn("Task.onError", 2, a -> d("$Task_OnError", a[0], a[1]));
    // Process.sleep: headlessly there is no real delay; it succeeds immediately with ().
    fn("Process.sleep", 1, a -> d("$Task_Const", pl.matsuo.elm.runtime.ElmUnit.INSTANCE));
    // Process.spawn: no real concurrency headlessly, so Tea runs the task synchronously and hands back
    // an opaque process id. Process.kill is then a no-op (the process has already finished).
    fn("Process.spawn", 1, a -> d("$Task_Spawn", a[0]));
    fn("Process.kill", 1, a -> d("$Task_Const", pl.matsuo.elm.runtime.ElmUnit.INSTANCE));
    // Platform.sendToApp / sendToSelf are effect-manager primitives (they need a Router the runtime
    // only hands to effect managers, which the interpreter does not model). Bound for conformance as
    // tasks that succeed with (); without an effect manager there is nowhere to route the message.
    fn("Platform.sendToApp", 2, a -> d("$Task_Const", pl.matsuo.elm.runtime.ElmUnit.INSTANCE));
    fn("Platform.sendToSelf", 2, a -> d("$Task_Const", pl.matsuo.elm.runtime.ElmUnit.INSTANCE));

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
    // Browser.Dom.getViewportOf id / setViewportOf id x y: headless stubs (a fixed viewport / unit).
    fn("Browser.Dom.getViewportOf", 1, a -> d("$Task_Const", viewport()));
    fn(
        "Browser.Dom.setViewportOf",
        3,
        a -> d("$Task_Const", pl.matsuo.elm.runtime.ElmUnit.INSTANCE));

    registerHttp();
    PreludeJson.registerJson();
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
    // Basics polar conversions on (Float, Float) tuples (the JS backend has these in kernel.js).
    fn("toPolar", 1, a -> {
      ElmTuple p = (ElmTuple) a[0];
      double x = Operators.toDouble(p.get(0));
      double y = Operators.toDouble(p.get(1));
      return new ElmTuple(new Object[] {Math.sqrt(x * x + y * y), Math.atan2(y, x)});
    });
    fn("fromPolar", 1, a -> {
      ElmTuple p = (ElmTuple) a[0];
      double r = Operators.toDouble(p.get(0));
      double t = Operators.toDouble(p.get(1));
      return new ElmTuple(new Object[] {r * Math.cos(t), r * Math.sin(t)});
    });
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
    fn("Math.Matrix4.makeScale", 1, a -> mat4(GL.makeScale(v3(a[0], 0), v3(a[0], 1), v3(a[0], 2))));
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
    fn("WebGL.preserveDrawingBuffer", 1, a -> d("$Option"));
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
    BUILTINS.put("WebGL.Texture.defaultOptions", d("$TexOptions"));
    // WebGL.Settings enums/values used by some examples (opaque; ignored headlessly).
    BUILTINS.put("WebGL.Settings.DepthTest.default", d("$Setting"));
  }

  private static void registerFile() {
    // A File is represented as $File[name, mime, url]; toUrl yields the (stub) data URL.
    BUILTINS.put("File.decoder", d("$Dec_File"));
    fn("File.name", 1, a -> ((ElmData) a[0]).arg(0));
    fn("File.mime", 1, a -> ((ElmData) a[0]).arg(1));
    fn("File.size", 1, a -> 0L);
    fn("File.lastModified", 1, a -> d("$Posix", 0L)); // headless: no real timestamp, epoch 0
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

  static String[] split(String pair) {
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
    fn("Html.Lazy.lazy4", 5, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4]));
    fn("Html.Lazy.lazy5", 6, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5]));
    fn("Html.Lazy.lazy6", 7, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5], a[6]));
    fn("Html.Lazy.lazy7", 8, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]));
    fn(
        "Html.Lazy.lazy8",
        9,
        a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]));
    fn("Svg.Lazy.lazy", 2, a -> Apply.apply(a[0], a[1]));
    fn("Svg.Lazy.lazy2", 3, a -> Apply.applyAll(a[0], a[1], a[2]));
    fn("Svg.Lazy.lazy3", 4, a -> Apply.applyAll(a[0], a[1], a[2], a[3]));
    fn("Svg.Lazy.lazy4", 5, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4]));
    fn("Svg.Lazy.lazy5", 6, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5]));
    fn("Svg.Lazy.lazy6", 7, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5], a[6]));
    fn("Svg.Lazy.lazy7", 8, a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]));
    fn(
        "Svg.Lazy.lazy8",
        9,
        a -> Apply.applyAll(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]));
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
    // The generic escape hatches: any attribute / any DOM property.
    fn("Html.Attributes.attribute", 2, a -> new ElmData("$Att", new Object[] {a[0], a[1]}));
    fn("Html.Attributes.property", 2, a -> new ElmData("$Prop", new Object[] {a[0], a[1]}));
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
    fn("Svg.node", 3, a -> node((String) a[0], a[1], a[2])); // generic SVG element builder
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

  static boolean isJust(Object o) {
    return o instanceof ElmData d && d.ctor().equals("Just");
  }

  static Object justValue(Object o) {
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

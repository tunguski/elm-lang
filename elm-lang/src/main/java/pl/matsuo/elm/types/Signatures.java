package pl.matsuo.elm.types;

import java.util.HashMap;
import java.util.Map;

/** Built-in type schemes: operators, core constructors and a curated set of prelude functions. */
public final class Signatures {

  private Signatures() {}

  private static final Map<String, Scheme> GLOBALS = new HashMap<>();
  private static final Map<String, Scheme> OPERATORS = new HashMap<>();

  private static final String[] HTML_ELEMENTS = {
    "div", "span", "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "a", "img", "button",
    "input", "label", "form", "section", "header", "footer", "nav", "br", "hr", "table", "thead",
    "tbody", "tr", "td", "th", "pre", "code", "strong", "em", "i", "b", "small", "select", "option",
    "textarea", "canvas", "blockquote", "cite", "figure", "figcaption", "main_", "article", "audio",
    "video", "u", "s", "sup", "sub", "kbd", "samp", "dl", "dt", "dd"
  };

  private static final String[] HTML_STRING_ATTRS = {
    "class", "id", "href", "src", "alt", "title", "placeholder", "value", "name", "type_", "for_",
    "rel", "target", "action", "method", "accept", "autocomplete", "min", "max", "step", "cols",
    "rows", "tabindex"
  };

  private static final String[] HTML_BOOL_ATTRS = {
    "disabled", "checked", "selected", "required", "autofocus", "hidden", "multiple"
  };

  public static Map<String, Scheme> globals() {
    return GLOBALS;
  }

  public static Scheme operator(String op) {
    return OPERATORS.get(op);
  }

  private static void g(String name, String sig) {
    GLOBALS.put(name, SchemeParser.scheme(sig));
  }

  private static void op(String symbol, String sig) {
    OPERATORS.put(symbol, SchemeParser.scheme(sig));
  }

  static {
    // Operators.
    op("+", "number -> number -> number");
    op("-", "number -> number -> number");
    op("*", "number -> number -> number");
    op("/", "Float -> Float -> Float");
    op("//", "Int -> Int -> Int");
    op("^", "number -> number -> number");
    op("==", "a -> a -> Bool");
    op("/=", "a -> a -> Bool");
    op("<", "comparable -> comparable -> Bool");
    op(">", "comparable -> comparable -> Bool");
    op("<=", "comparable -> comparable -> Bool");
    op(">=", "comparable -> comparable -> Bool");
    op("&&", "Bool -> Bool -> Bool");
    op("||", "Bool -> Bool -> Bool");
    op("++", "appendable -> appendable -> appendable");
    op("::", "a -> List a -> List a");
    op("|>", "a -> (a -> b) -> b");
    op("<|", "(a -> b) -> a -> b");
    op(">>", "(a -> b) -> (b -> c) -> a -> c");
    op("<<", "(b -> c) -> (a -> b) -> a -> c");

    // Constructors.
    g("True", "Bool");
    g("False", "Bool");
    g("Just", "a -> Maybe a");
    g("Nothing", "Maybe a");
    g("Ok", "a -> Result e a");
    g("Err", "e -> Result e a");
    g("LT", "Order");
    g("EQ", "Order");
    g("GT", "Order");

    // Basics (unqualified).
    g("identity", "a -> a");
    g("always", "a -> b -> a");
    g("not", "Bool -> Bool");
    g("negate", "number -> number");
    g("abs", "number -> number");
    g("clamp", "number -> number -> number -> number");
    g("min", "comparable -> comparable -> comparable");
    g("max", "comparable -> comparable -> comparable");
    g("compare", "comparable -> comparable -> Order");
    g("toFloat", "Int -> Float");
    g("round", "Float -> Int");
    g("floor", "Float -> Int");
    g("ceiling", "Float -> Int");
    g("truncate", "Float -> Int");
    g("sqrt", "Float -> Float");
    g("modBy", "Int -> Int -> Int");
    g("remainderBy", "Int -> Int -> Int");
    g("pi", "Float");
    g("e", "Float");
    g("sin", "Float -> Float");
    g("cos", "Float -> Float");
    g("tan", "Float -> Float");
    g("logBase", "Float -> Float -> Float");

    // List.
    g("List.map", "(a -> b) -> List a -> List b");
    g("List.indexedMap", "(Int -> a -> b) -> List a -> List b");
    g("List.filter", "(a -> Bool) -> List a -> List a");
    g("List.foldl", "(a -> b -> b) -> b -> List a -> b");
    g("List.foldr", "(a -> b -> b) -> b -> List a -> b");
    g("List.range", "Int -> Int -> List Int");
    g("List.length", "List a -> Int");
    g("List.reverse", "List a -> List a");
    g("List.member", "a -> List a -> Bool");
    g("List.append", "List a -> List a -> List a");
    g("List.concat", "List (List a) -> List a");
    g("List.sum", "List number -> number");
    g("List.product", "List number -> number");
    g("List.head", "List a -> Maybe a");
    g("List.tail", "List a -> Maybe (List a)");
    g("List.isEmpty", "List a -> Bool");
    g("List.map2", "(a -> b -> c) -> List a -> List b -> List c");
    g("List.singleton", "a -> List a");

    // String.
    g("String.length", "String -> Int");
    g("String.reverse", "String -> String");
    g("String.toUpper", "String -> String");
    g("String.toLower", "String -> String");
    g("String.trim", "String -> String");
    g("String.append", "String -> String -> String");
    g("String.fromInt", "Int -> String");
    g("String.fromFloat", "Float -> String");
    g("String.fromChar", "Char -> String");
    g("String.toInt", "String -> Maybe Int");
    g("String.join", "String -> List String -> String");
    g("String.split", "String -> String -> List String");
    g("String.contains", "String -> String -> Bool");
    g("String.isEmpty", "String -> Bool");

    // Maybe / Result.
    g("Maybe.withDefault", "a -> Maybe a -> a");
    g("Maybe.map", "(a -> b) -> Maybe a -> Maybe b");
    g("Maybe.andThen", "(a -> Maybe b) -> Maybe a -> Maybe b");
    g("Result.withDefault", "a -> Result e a -> a");
    g("Result.map", "(a -> b) -> Result e a -> Result e b");
    g("Result.toMaybe", "Result e a -> Maybe a");

    // Tuple.
    g("Tuple.pair", "a -> b -> ( a, b )");
    g("Tuple.first", "( a, b ) -> a");
    g("Tuple.second", "( a, b ) -> b");
    g("Tuple.mapFirst", "(a -> x) -> ( a, b ) -> ( x, b )");
    g("Tuple.mapSecond", "(b -> x) -> ( a, b ) -> ( a, x )");

    // Char.
    g("Char.toCode", "Char -> Int");
    g("Char.fromCode", "Int -> Char");
    g("Char.toUpper", "Char -> Char");
    g("Char.toLower", "Char -> Char");
    g("Char.isDigit", "Char -> Bool");
    g("Char.isAlpha", "Char -> Bool");

    // Debug.
    g("Debug.toString", "a -> String");
    g("Debug.log", "String -> a -> a");

    registerHtml();
    registerSvg();
    registerBrowserAndEffects();
    registerCollections();
    registerMathWebGL();
  }

  private static void registerHtml() {
    String elem = "List (Attribute msg) -> List (Html msg) -> Html msg";
    for (String tag : HTML_ELEMENTS) {
      g("Html." + tag, elem);
    }
    g("Html.text", "String -> Html msg");
    g("Html.node", "String -> " + elem);
    g("Html.map", "(a -> b) -> Html a -> Html b");
    for (String attr : HTML_STRING_ATTRS) {
      g("Html.Attributes." + attr, "String -> Attribute msg");
    }
    g("Html.Attributes.width", "Int -> Attribute msg");
    g("Html.Attributes.height", "Int -> Attribute msg");
    for (String attr : HTML_BOOL_ATTRS) {
      g("Html.Attributes." + attr, "Bool -> Attribute msg");
    }
    g("Html.Attributes.style", "String -> String -> Attribute msg");
    g("Html.Attributes.classList", "List ( String, Bool ) -> Attribute msg");
    g("Html.Events.onClick", "msg -> Attribute msg");
    g("Html.Events.onInput", "(String -> msg) -> Attribute msg");
    g("Html.Events.onCheck", "(Bool -> msg) -> Attribute msg");
    g("Html.Events.onSubmit", "msg -> Attribute msg");
    g("Html.Events.onMouseDown", "msg -> Attribute msg");
    g("Html.Events.onMouseUp", "msg -> Attribute msg");
    g("Html.Events.on", "String -> Decoder msg -> Attribute msg");
    g("Html.Events.preventDefaultOn", "String -> Decoder ( msg, Bool ) -> Attribute msg");
    g("Html.Events.stopPropagationOn", "String -> Decoder ( msg, Bool ) -> Attribute msg");
  }

  private static void registerSvg() {
    String elem = "List (Attribute msg) -> List (Svg msg) -> Svg msg";
    for (String tag :
        new String[] {
          "svg", "circle", "rect", "line", "polygon", "polyline", "ellipse", "g", "path", "image",
          "text_"
        }) {
      g("Svg." + tag, elem);
    }
    g("Svg.text", "String -> Svg msg");
    for (String attr :
        new String[] {
          "width", "height", "viewBox", "cx", "cy", "r", "x", "y", "x1", "y1", "x2", "y2", "rx",
          "ry", "fill", "stroke", "strokeWidth", "points", "d", "transform", "opacity",
          "fillOpacity", "strokeLinecap", "fontSize", "textAnchor", "fontFamily", "xlinkHref",
          "dominantBaseline"
        }) {
      g("Svg.Attributes." + attr, "String -> Attribute msg");
    }
  }

  private static void registerBrowserAndEffects() {
    g("Browser.sandbox", "{ init : model, update : msg -> model -> model, view : model -> Html msg } -> Program () model msg");
    g("Browser.element", "{ init : flags -> ( model, Cmd msg ), update : msg -> model -> ( model, Cmd msg ), subscriptions : model -> Sub msg, view : model -> Html msg } -> Program flags model msg");
    g("Browser.document", "{ init : flags -> ( model, Cmd msg ), update : msg -> model -> ( model, Cmd msg ), subscriptions : model -> Sub msg, view : model -> { title : String, body : List (Html msg) } } -> Program flags model msg");
    g("Cmd.none", "Cmd msg");
    g("Cmd.batch", "List (Cmd msg) -> Cmd msg");
    g("Cmd.map", "(a -> b) -> Cmd a -> Cmd b");
    g("Sub.none", "Sub msg");
    g("Sub.batch", "List (Sub msg) -> Sub msg");
    g("Sub.map", "(a -> b) -> Sub a -> Sub b");

    g("Random.generate", "(a -> msg) -> Generator a -> Cmd msg");
    g("Random.int", "Int -> Int -> Generator Int");
    g("Random.float", "Float -> Float -> Generator Float");
    g("Random.uniform", "a -> List a -> Generator a");
    g("Random.list", "Int -> Generator a -> Generator (List a)");
    g("Random.pair", "Generator a -> Generator b -> Generator ( a, b )");
    g("Random.map", "(a -> b) -> Generator a -> Generator b");
    g("Random.map2", "(a -> b -> c) -> Generator a -> Generator b -> Generator c");
    g("Random.constant", "a -> Generator a");
    g("Random.andThen", "(a -> Generator b) -> Generator a -> Generator b");

    g("Time.every", "Float -> (Posix -> msg) -> Sub msg");
    g("Time.millisToPosix", "Int -> Posix");
    g("Time.posixToMillis", "Posix -> Int");
    g("Time.toHour", "Zone -> Posix -> Int");
    g("Time.toMinute", "Zone -> Posix -> Int");
    g("Time.toSecond", "Zone -> Posix -> Int");
    g("Time.utc", "Zone");
    g("Time.here", "Task x Zone");
    g("Time.now", "Task x Posix");

    g("Task.perform", "(a -> msg) -> Task x a -> Cmd msg");
    g("Task.attempt", "(Result x a -> msg) -> Task x a -> Cmd msg");
    g("Task.succeed", "a -> Task x a");
    g("Task.sequence", "List (Task x a) -> Task x (List a)");

    g("Http.get", "{ url : String, expect : Expect msg } -> Cmd msg");
    g("Http.expectString", "(Result Error String -> msg) -> Expect msg");
    g("Http.expectJson", "(Result Error a -> msg) -> Decoder a -> Expect msg");

    g("Json.Decode.string", "Decoder String");
    g("Json.Decode.int", "Decoder Int");
    g("Json.Decode.float", "Decoder Float");
    g("Json.Decode.bool", "Decoder Bool");
    g("Json.Decode.field", "String -> Decoder a -> Decoder a");
    g("Json.Decode.at", "List String -> Decoder a -> Decoder a");
    g("Json.Decode.list", "Decoder a -> Decoder (List a)");
    g("Json.Decode.map", "(a -> b) -> Decoder a -> Decoder b");
    g("Json.Decode.map2", "(a -> b -> v) -> Decoder a -> Decoder b -> Decoder v");
    g("Json.Decode.map3", "(a -> b -> c -> v) -> Decoder a -> Decoder b -> Decoder c -> Decoder v");
    g("Json.Decode.map4", "(a -> b -> c -> d -> v) -> Decoder a -> Decoder b -> Decoder c -> Decoder d -> Decoder v");
    g("Json.Decode.succeed", "a -> Decoder a");
    g("Json.Decode.andThen", "(a -> Decoder b) -> Decoder a -> Decoder b");
    g("Json.Decode.oneOrMore", "(a -> List a -> v) -> Decoder a -> Decoder v");

    g("File.decoder", "Decoder File");
    g("File.toUrl", "File -> Task x String");
    g("File.name", "File -> String");
    g("File.mime", "File -> String");
    g("File.Select.file", "List String -> (File -> msg) -> Cmd msg");
    g("File.Select.files", "List String -> (File -> List File -> msg) -> Cmd msg");
  }

  private static void registerCollections() {
    g("Dict.empty", "Dict k v");
    g("Dict.singleton", "comparable -> v -> Dict comparable v");
    g("Dict.insert", "comparable -> v -> Dict comparable v -> Dict comparable v");
    g("Dict.remove", "comparable -> Dict comparable v -> Dict comparable v");
    g("Dict.get", "comparable -> Dict comparable v -> Maybe v");
    g("Dict.member", "comparable -> Dict comparable v -> Bool");
    g("Dict.size", "Dict k v -> Int");
    g("Dict.keys", "Dict k v -> List k");
    g("Dict.values", "Dict k v -> List v");
    g("Dict.toList", "Dict k v -> List ( k, v )");
    g("Dict.fromList", "List ( comparable, v ) -> Dict comparable v");
    g("Dict.map", "(k -> a -> b) -> Dict k a -> Dict k b");
    g("Dict.foldl", "(k -> v -> b -> b) -> b -> Dict k v -> b");

    g("Set.empty", "Set a");
    g("Set.singleton", "comparable -> Set comparable");
    g("Set.insert", "comparable -> Set comparable -> Set comparable");
    g("Set.remove", "comparable -> Set comparable -> Set comparable");
    g("Set.member", "comparable -> Set comparable -> Bool");
    g("Set.size", "Set a -> Int");
    g("Set.toList", "Set a -> List a");
    g("Set.fromList", "List comparable -> Set comparable");

    g("Array.empty", "Array a");
    g("Array.fromList", "List a -> Array a");
    g("Array.toList", "Array a -> List a");
    g("Array.length", "Array a -> Int");
    g("Array.get", "Int -> Array a -> Maybe a");
    g("Array.set", "Int -> a -> Array a -> Array a");
    g("Array.push", "a -> Array a -> Array a");
    g("Array.map", "(a -> b) -> Array a -> Array b");
  }

  private static void registerMathWebGL() {
    g("Math.Vector2.vec2", "Float -> Float -> Vec2");
    g("Math.Vector3.vec3", "Float -> Float -> Float -> Vec3");
    g("Math.Vector3.getX", "Vec3 -> Float");
    g("Math.Vector3.getY", "Vec3 -> Float");
    g("Math.Vector3.getZ", "Vec3 -> Float");
    g("Math.Vector3.add", "Vec3 -> Vec3 -> Vec3");
    g("Math.Vector3.scale", "Float -> Vec3 -> Vec3");
    g("Math.Matrix4.identity", "Mat4");
    g("Math.Matrix4.mul", "Mat4 -> Mat4 -> Mat4");
    g("Math.Matrix4.makePerspective", "Float -> Float -> Float -> Float -> Mat4");
    g("Math.Matrix4.makeLookAt", "Vec3 -> Vec3 -> Vec3 -> Mat4");
    g("Math.Matrix4.makeRotate", "Float -> Vec3 -> Mat4");
    g("Math.Matrix4.makeTranslate", "Vec3 -> Mat4");
    g("WebGL.toHtml", "List (Attribute msg) -> List Entity -> Html msg");
    g("WebGL.entity", "a -> b -> Mesh c -> d -> Entity");
    g("WebGL.triangles", "List ( v, v, v ) -> Mesh v");
  }
}

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
    g("asin", "Float -> Float");
    g("acos", "Float -> Float");
    g("atan", "Float -> Float");
    g("atan2", "Float -> Float -> Float");
    g("logBase", "Float -> Float -> Float");
    g("degrees", "Float -> Float");
    g("radians", "Float -> Float");
    g("turns", "Float -> Float");
    g("toPolar", "( Float, Float ) -> ( Float, Float )");
    g("fromPolar", "( Float, Float ) -> ( Float, Float )");
    g("isNaN", "Float -> Bool");
    g("isInfinite", "Float -> Bool");

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
    g("List.partition", "(a -> Bool) -> List a -> ( List a, List a )");
    g("List.unzip", "List ( a, b ) -> ( List a, List b )");
    g("List.intersperse", "a -> List a -> List a");
    g("List.concat", "List (List a) -> List a");
    g("List.concatMap", "(a -> List b) -> List a -> List b");
    g("List.filterMap", "(a -> Maybe b) -> List a -> List b");
    g("List.map3", "(a -> b -> c -> d) -> List a -> List b -> List c -> List d");
    g("List.map4", "(a -> b -> c -> d -> e) -> List a -> List b -> List c -> List d -> List e");
    g(
        "List.map5",
        "(a -> b -> c -> d -> e -> f) -> List a -> List b -> List c -> List d -> List e -> List f");
    g("List.repeat", "Int -> a -> List a");
    g("List.sort", "List comparable -> List comparable");
    g("List.sortBy", "(a -> comparable) -> List a -> List a");
    g("List.sortWith", "(a -> a -> Order) -> List a -> List a");
    g("List.take", "Int -> List a -> List a");
    g("List.drop", "Int -> List a -> List a");
    g("List.all", "(a -> Bool) -> List a -> Bool");
    g("List.any", "(a -> Bool) -> List a -> Bool");
    g("List.maximum", "List comparable -> Maybe comparable");
    g("List.minimum", "List comparable -> Maybe comparable");
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
    g("String.padLeft", "Int -> Char -> String -> String");
    g("String.padRight", "Int -> Char -> String -> String");
    g("String.pad", "Int -> Char -> String -> String");
    g("String.indexes", "String -> String -> List Int");
    g("String.indices", "String -> String -> List Int");
    g("String.foldl", "(Char -> b -> b) -> b -> String -> b");
    g("String.foldr", "(Char -> b -> b) -> b -> String -> b");
    g("String.any", "(Char -> Bool) -> String -> Bool");
    g("String.all", "(Char -> Bool) -> String -> Bool");

    // Bitwise (32-bit).
    g("Bitwise.and", "Int -> Int -> Int");
    g("Bitwise.or", "Int -> Int -> Int");
    g("Bitwise.xor", "Int -> Int -> Int");
    g("Bitwise.complement", "Int -> Int");
    g("Bitwise.shiftLeftBy", "Int -> Int -> Int");
    g("Bitwise.shiftRightBy", "Int -> Int -> Int");
    g("Bitwise.shiftRightZfBy", "Int -> Int -> Int");

    // Maybe / Result.
    g("Maybe.withDefault", "a -> Maybe a -> a");
    g("Maybe.map", "(a -> b) -> Maybe a -> Maybe b");
    g("Maybe.map2", "(a -> b -> v) -> Maybe a -> Maybe b -> Maybe v");
    g("Maybe.map3", "(a -> b -> c -> v) -> Maybe a -> Maybe b -> Maybe c -> Maybe v");
    g("Maybe.map4", "(a -> b -> c -> d -> v) -> Maybe a -> Maybe b -> Maybe c -> Maybe d -> Maybe v");
    g("Maybe.andThen", "(a -> Maybe b) -> Maybe a -> Maybe b");
    g("Result.withDefault", "a -> Result e a -> a");
    g("Result.map", "(a -> b) -> Result e a -> Result e b");
    g("Result.map2", "(a -> b -> v) -> Result e a -> Result e b -> Result e v");
    g("Result.map3", "(a -> b -> c -> v) -> Result e a -> Result e b -> Result e c -> Result e v");
    g("Result.andThen", "(a -> Result e b) -> Result e a -> Result e b");
    g("Result.mapError", "(e -> f) -> Result e a -> Result f a");
    g("Result.toMaybe", "Result e a -> Maybe a");
    g("Result.fromMaybe", "e -> Maybe a -> Result e a");

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
    g("Char.isHexDigit", "Char -> Bool");
    g("Char.isOctDigit", "Char -> Bool");

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
    // In Elm `type alias Svg msg = Html msg`, so Svg nodes are Html nodes; emitting Html lets the
    // two mix freely (e.g. a Browser.element `view : model -> Html msg` returning an `svg [] [...]`).
    String elem = "List (Attribute msg) -> List (Html msg) -> Html msg";
    for (String tag :
        new String[] {
          "svg", "circle", "rect", "line", "polygon", "polyline", "ellipse", "g", "path", "image",
          "text_"
        }) {
      g("Svg." + tag, elem);
    }
    g("Svg.text", "String -> Html msg");
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

    g("Browser.Events.onAnimationFrame", "(Posix -> msg) -> Sub msg");
    g("Browser.Events.onAnimationFrameDelta", "(Float -> msg) -> Sub msg");
    g("Browser.Events.onResize", "(Int -> Int -> msg) -> Sub msg");
    g("Browser.Events.onKeyDown", "Decoder msg -> Sub msg");
    g("Browser.Events.onKeyUp", "Decoder msg -> Sub msg");
    g("Browser.Events.onKeyPress", "Decoder msg -> Sub msg");
    g("Browser.Events.onClick", "Decoder msg -> Sub msg");
    g("Browser.Events.onMouseMove", "Decoder msg -> Sub msg");
    g("Browser.Events.onMouseDown", "Decoder msg -> Sub msg");
    g("Browser.Events.onMouseUp", "Decoder msg -> Sub msg");
    g("Browser.Events.onVisibilityChange", "(Visibility -> msg) -> Sub msg");
    g("Browser.Events.Visible", "Visibility");
    g("Browser.Events.Hidden", "Visibility");

    // Browser.Dom.Viewport is a record alias (see Infer's builtin aliases); the inline record keeps
    // the SchemeParser-built type identical to the alias-expanded annotation.
    String viewport =
        "{ scene : { width : Float, height : Float },"
            + " viewport : { x : Float, y : Float, width : Float, height : Float } }";
    g("Browser.Dom.getViewport", "Task x " + viewport);
    g("Browser.Dom.setViewport", "Float -> Float -> Task x ()");
    g("Browser.Dom.focus", "String -> Task Error ()");
    g("Browser.Dom.blur", "String -> Task Error ()");

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
    g("Json.Decode.index", "Int -> Decoder a -> Decoder a");
    g("Json.Decode.oneOf", "List (Decoder a) -> Decoder a");
    g("Json.Decode.value", "Decoder Value");
    g("Json.Decode.maybe", "Decoder a -> Decoder (Maybe a)");
    g("Json.Decode.nullable", "Decoder a -> Decoder (Maybe a)");
    g("Json.Decode.null", "a -> Decoder a");
    g("Json.Decode.fail", "String -> Decoder a");
    g("Json.Decode.lazy", "(() -> Decoder a) -> Decoder a");
    g("Json.Decode.dict", "Decoder a -> Decoder (Dict String a)");
    g("Json.Decode.keyValuePairs", "Decoder a -> Decoder (List ( String, a ))");
    g("Json.Decode.decodeValue", "Decoder a -> Value -> Result String a");

    g("Json.Encode.int", "Int -> Value");
    g("Json.Encode.float", "Float -> Value");
    g("Json.Encode.string", "String -> Value");
    g("Json.Encode.bool", "Bool -> Value");
    g("Json.Encode.null", "Value");
    g("Json.Encode.list", "(a -> Value) -> List a -> Value");
    g("Json.Encode.object", "List ( String, Value ) -> Value");
    g("Json.Encode.dict", "(k -> String) -> (v -> Value) -> Dict k v -> Value");
    g("Json.Encode.set", "(a -> Value) -> Set a -> Value");
    g("Json.Encode.encode", "Int -> Value -> String");

    g("Url.fromString", "String -> Maybe Url");
    g("Url.toString", "Url -> String");
    g("Browser.Navigation.load", "String -> Cmd msg");
    g("Browser.Navigation.pushUrl", "Key -> String -> Cmd msg");
    g("Browser.Navigation.replaceUrl", "Key -> String -> Cmd msg");
    g("Browser.Navigation.back", "Key -> Int -> Cmd msg");
    g("Browser.Navigation.forward", "Key -> Int -> Cmd msg");
    g("Browser.Navigation.getHash", "(String -> msg) -> Cmd msg");
    g("Browser.Navigation.setHash", "String -> Cmd msg");

    g("File.decoder", "Decoder File");
    g("File.toUrl", "File -> Task x String");
    g("File.name", "File -> String");
    g("File.mime", "File -> String");
    g("File.Select.file", "List String -> (File -> msg) -> Cmd msg");
    g("File.Select.files", "List String -> (File -> List File -> msg) -> Cmd msg");
    // A kernel command used by the in-browser editor: open a file picker and hand the chosen file's
    // name and text content to the message constructor.
    g("File.openPicker", "(String -> String -> msg) -> Cmd msg");

    // Regex (elm/regex). `Match` is a record alias inlined here so `.match`/`.index`/… resolve.
    String match = "{ match : String, index : Int, number : Int, submatches : List (Maybe String) }";
    g("Regex.never", "Regex");
    g("Regex.fromString", "String -> Maybe Regex");
    g("Regex.contains", "Regex -> String -> Bool");
    g("Regex.split", "Regex -> String -> List String");
    g("Regex.find", "Regex -> String -> List " + match);
    g("Regex.replace", "Regex -> (" + match + " -> String) -> String -> String");
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
    g("Dict.foldr", "(k -> v -> b -> b) -> b -> Dict k v -> b");
    g("Dict.partition", "(comparable -> v -> Bool) -> Dict comparable v -> ( Dict comparable v, Dict comparable v )");
    g("Dict.merge", "(comparable -> a -> r -> r) -> (comparable -> a -> b -> r -> r) -> (comparable -> b -> r -> r) -> Dict comparable a -> Dict comparable b -> r -> r");

    g("Set.empty", "Set a");
    g("Set.singleton", "comparable -> Set comparable");
    g("Set.insert", "comparable -> Set comparable -> Set comparable");
    g("Set.remove", "comparable -> Set comparable -> Set comparable");
    g("Set.member", "comparable -> Set comparable -> Bool");
    g("Set.size", "Set a -> Int");
    g("Set.toList", "Set a -> List a");
    g("Set.fromList", "List comparable -> Set comparable");
    g("Set.partition", "(comparable -> Bool) -> Set comparable -> ( Set comparable, Set comparable )");
    g("Set.foldl", "(a -> b -> b) -> b -> Set a -> b");
    g("Set.foldr", "(a -> b -> b) -> b -> Set a -> b");

    g("Array.empty", "Array a");
    g("Array.fromList", "List a -> Array a");
    g("Array.toList", "Array a -> List a");
    g("Array.length", "Array a -> Int");
    g("Array.get", "Int -> Array a -> Maybe a");
    g("Array.set", "Int -> a -> Array a -> Array a");
    g("Array.push", "a -> Array a -> Array a");
    g("Array.map", "(a -> b) -> Array a -> Array b");
    g("Array.filter", "(a -> Bool) -> Array a -> Array a");
    g("Array.toIndexedList", "Array a -> List ( Int, a )");
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
    g("Math.Vector3.normalize", "Vec3 -> Vec3");
    g("Math.Vector3.sub", "Vec3 -> Vec3 -> Vec3");
    g("Math.Vector3.cross", "Vec3 -> Vec3 -> Vec3");
    g("Math.Vector3.dot", "Vec3 -> Vec3 -> Float");
    g("Math.Vector3.setX", "Float -> Vec3 -> Vec3");
    g("Math.Vector3.setY", "Float -> Vec3 -> Vec3");
    g("Math.Vector3.setZ", "Float -> Vec3 -> Vec3");
    g("Math.Vector3.length", "Vec3 -> Float");
    g("Math.Vector3.i", "Vec3");
    g("Math.Vector3.j", "Vec3");
    g("Math.Vector3.k", "Vec3");
    g("Math.Vector3.toRecord", "Vec3 -> { x : Float, y : Float, z : Float }");
    g("Math.Vector3.fromRecord", "{ x : Float, y : Float, z : Float } -> Vec3");
    g("Math.Vector2.getX", "Vec2 -> Float");
    g("Math.Vector2.getY", "Vec2 -> Float");
    g("Math.Matrix4.makeOrtho", "Float -> Float -> Float -> Float -> Float -> Float -> Mat4");
    g("Math.Matrix4.transform", "Mat4 -> Vec3 -> Vec3");
    g("WebGL.toHtml", "List (Attribute msg) -> List Entity -> Html msg");
    // A kernel bridge used by the in-browser editor: turn the interpreter's entity values into a
    // `$GL` canvas attribute the JS WebGL runtime draws. (`a` is the editor's dynamic value type.)
    g("WebGL.glAttr", "a -> Attribute msg");
    g("WebGL.toHtmlWith", "List Option -> List (Attribute msg) -> List Entity -> Html msg");
    g("WebGL.entity", "a -> b -> Mesh c -> d -> Entity");
    g("WebGL.triangles", "List ( v, v, v ) -> Mesh v");
    g("WebGL.indexedTriangles", "List v -> List ( Int, Int, Int ) -> Mesh v");
    g("WebGL.depth", "Float -> Option");
    g("WebGL.clearColor", "Float -> Float -> Float -> Float -> Option");

    // elm-explorations/webgl WebGL.Texture. `Options` is a record alias (see Infer's builtin
    // aliases), so the inline record here unifies with an `options : Texture.Options` annotation.
    String texOptions =
        "{ magnify : Resize, minify : Resize, horizontalWrap : Wrap, verticalWrap : Wrap,"
            + " flipY : Bool }";
    g("WebGL.Texture.load", "String -> Task Error Texture");
    g("WebGL.Texture.loadWith", texOptions + " -> String -> Task Error Texture");
    g("WebGL.Texture.size", "Texture -> ( Int, Int )");
    g("WebGL.Texture.nearest", "Resize");
    g("WebGL.Texture.linear", "Resize");
    g("WebGL.Texture.repeat", "Wrap");
    g("WebGL.Texture.clampToEdge", "Wrap");
    g("WebGL.Texture.mirroredRepeat", "Wrap");
  }
}

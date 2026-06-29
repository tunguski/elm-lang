package pl.matsuo.elm.interp;

import static pl.matsuo.elm.interp.Prelude.BUILTINS;
import static pl.matsuo.elm.interp.Prelude.d;
import static pl.matsuo.elm.interp.Prelude.fn;

import java.util.Map;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmDict;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmSet;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * The {@code Json.Decode}/{@code Json.Encode}, {@code Url}, {@code Browser.Navigation} and
 * {@code Storage} builtins of the interpreter prelude. The JSON decoder and its error renderer
 * ({@link #decodeErrorToString}) are a bound pair, and the Url helpers ({@link #parseUrl} et al.) are
 * only used here, so the whole group lives in its own file and writes into {@link Prelude}'s shared
 * registration maps via the package-private {@link Prelude#fn}/{@link Prelude#d}/{@code BUILTINS}.
 * {@link Prelude}'s static initializer calls {@link #registerJson()}.
 */
final class PreludeJson {

  private PreludeJson() {}

  static void registerJson() {
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
    // array = map Array.fromList over the list decoder (reuses the existing decoder combinators).
    fn(
        "Json.Decode.array",
        1,
        a -> d("$Dec_MapN", BUILTINS.get("Array.fromList"), d("$Dec_List", a[0])));
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
    // array f arr = list f (Array.toList arr) — reuses the list encoder.
    fn(
        "Json.Encode.array",
        2,
        a ->
            Apply.applyAll(
                BUILTINS.get("Json.Encode.list"), a[0], Apply.apply(BUILTINS.get("Array.toList"), a[1])));
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
    fn("Url.percentEncode", 1, a -> percentEncode((String) Thunk.resolve(a[0])));
    fn("Url.percentDecode", 1, a -> {
      String r = percentDecode((String) Thunk.resolve(a[0]));
      return r == null ? d("Nothing") : d("Just", r);
    });
    fn("Browser.Navigation.load", 1, a -> d("$CmdNone"));
    // reload / reloadAndSkipCache: headlessly there is no page to reload, so a no-op command.
    BUILTINS.put("Browser.Navigation.reload", d("$CmdNone"));
    BUILTINS.put("Browser.Navigation.reloadAndSkipCache", d("$CmdNone"));
    fn("Browser.Navigation.pushUrl", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.replaceUrl", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.back", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.forward", 2, a -> d("$CmdNone"));
    fn("Browser.Navigation.backOr", 1, a -> d("$CmdNone"));
    // The permalink bridge is browser-only; headlessly there is no URL fragment, so both are no-ops.
    fn("Browser.Navigation.getHash", 1, a -> d("$CmdNone"));
    fn("Browser.Navigation.setHash", 1, a -> d("$CmdNone"));
    // localStorage is browser-only too; headlessly there is nothing to save to or load from.
    fn("Storage.save", 2, a -> d("$CmdNone"));
    fn("Storage.load", 2, a -> d("$CmdNone"));
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

  /** Percent-encodes a string for use in a URL: the characters JavaScript's {@code encodeURIComponent}
   *  leaves alone — {@code A-Za-z0-9} and {@code - _ . ! ~ * ' ( )} — pass through, everything else
   *  becomes %XX over its UTF-8 bytes. elm/url's {@code Url.percentEncode} IS {@code encodeURIComponent},
   *  so this must match it exactly: the JS backend uses the real {@code encodeURIComponent}, and a
   *  stricter RFC-3986 set here (encoding {@code ! * ' ( )}) made `Url.percentEncode` disagree between
   *  the JVM test runtime and the browser. */
  private static String percentEncode(String s) {
    StringBuilder b = new StringBuilder();
    for (byte by : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
      int c = by & 0xFF;
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_' || c == '.' || c == '!' || c == '~'
          || c == '*' || c == '\'' || c == '(' || c == ')') {
        b.append((char) c);
      } else {
        b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
      }
    }
    return b.toString();
  }

  /** Decodes a percent-encoded string, or {@code null} on a malformed escape (-> {@code Nothing}). */
  private static String percentDecode(String s) {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '%') {
        if (i + 2 >= s.length()) {
          return null;
        }
        int hi = Character.digit(s.charAt(i + 1), 16);
        int lo = Character.digit(s.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) {
          return null;
        }
        bytes.write((hi << 4) | lo);
        i += 2;
      } else {
        for (byte by : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
          bytes.write(by & 0xFF);
        }
      }
    }
    return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
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

  // Cast helpers for the Json.Encode dict/set builtins (the Dict/Set builtins live in
  // PreludeCollections; these one-liners are only used here).
  private static ElmDict asDict(Object o) {
    return (ElmDict) o;
  }

  private static ElmSet asSet(Object o) {
    return (ElmSet) o;
  }
}

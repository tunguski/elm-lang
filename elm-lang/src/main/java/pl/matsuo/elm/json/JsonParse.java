package pl.matsuo.elm.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.error.ElmRuntimeError;

/**
 * A minimal JSON parser producing a neutral Java value tree consumed by {@link DecoderRunner}:
 * objects are {@code Map<String,Object>}, arrays are {@code List<Object>}, numbers are {@code Long}
 * or {@code Double}, plus {@code String}, {@code Boolean} and {@link #NULL}.
 */
public final class JsonParse {

  /** Sentinel for JSON {@code null} (distinct from a missing field / Java null). */
  public static final Object NULL = new Object();

  private final String src;
  private int pos = 0;

  private JsonParse(String src) {
    this.src = src;
  }

  public static Object parse(String json) {
    JsonParse p = new JsonParse(json);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.pos != json.length()) {
      throw new ElmRuntimeError("Trailing JSON content at " + p.pos);
    }
    return v;
  }

  private ElmRuntimeError err(String m) {
    return new ElmRuntimeError("JSON parse error: " + m + " at " + pos);
  }

  private void ws() {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
      pos++;
    }
  }

  private char cur() {
    return src.charAt(pos);
  }

  private Object value() {
    if (pos >= src.length()) {
      throw err("unexpected end");
    }
    char c = cur();
    return switch (c) {
      case '{' -> object();
      case '[' -> array();
      case '"' -> string();
      case 't', 'f' -> bool();
      case 'n' -> nullValue();
      default -> number();
    };
  }

  private Map<String, Object> object() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++; // {
    ws();
    if (cur() == '}') {
      pos++;
      return map;
    }
    while (true) {
      ws();
      String key = string();
      ws();
      if (cur() != ':') {
        throw err("expected ':'");
      }
      pos++;
      ws();
      map.put(key, value());
      ws();
      char c = cur();
      pos++;
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw err("expected ',' or '}'");
      }
    }
  }

  private List<Object> array() {
    List<Object> list = new ArrayList<>();
    pos++; // [
    ws();
    if (cur() == ']') {
      pos++;
      return list;
    }
    while (true) {
      ws();
      list.add(value());
      ws();
      char c = cur();
      pos++;
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw err("expected ',' or ']'");
      }
    }
  }

  private String string() {
    if (cur() != '"') {
      throw err("expected string");
    }
    pos++;
    StringBuilder sb = new StringBuilder();
    while (cur() != '"') {
      char c = src.charAt(pos++);
      if (c == '\\') {
        char e = src.charAt(pos++);
        switch (e) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case '/' -> sb.append('/');
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case 'u' -> {
            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw err("bad escape \\" + e);
        }
      } else {
        sb.append(c);
      }
    }
    pos++; // closing quote
    return sb.toString();
  }

  private Object number() {
    int start = pos;
    boolean isDouble = false;
    while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
      char c = src.charAt(pos);
      if (c == '.' || c == 'e' || c == 'E') {
        isDouble = true;
      }
      pos++;
    }
    String text = src.substring(start, pos);
    if (text.isEmpty()) {
      throw err("expected number");
    }
    return isDouble ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
  }

  private Boolean bool() {
    if (src.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (src.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    throw err("expected boolean");
  }

  private Object nullValue() {
    if (src.startsWith("null", pos)) {
      pos += 4;
      return NULL;
    }
    throw err("expected null");
  }
}

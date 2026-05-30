package pl.matsuo.elm.json;

import java.util.List;
import java.util.Map;

/**
 * Serializer for the {@code Json.Encode} builtins. An encoded {@code Value} is represented as a
 * plain Java tree (Long/Double/String/Boolean/{@link #NULL}/List/Map); {@link #serialize} renders
 * it as JSON text (optionally pretty-printed), matching {@code Json.Encode.encode}.
 */
public final class JsonEncode {

  /** Sentinel for a JSON {@code null} (Java {@code null} can't be stored in an ElmData arg array). */
  public static final Object NULL = new Object();

  private JsonEncode() {}

  public static String serialize(Object tree, int indent) {
    StringBuilder sb = new StringBuilder();
    write(sb, tree, indent, 0);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void write(StringBuilder sb, Object v, int indent, int depth) {
    if (v == NULL || v == null) {
      sb.append("null");
    } else if (v instanceof String s) {
      quote(sb, s);
    } else if (v instanceof Boolean b) {
      sb.append(b.booleanValue());
    } else if (v instanceof Long l) {
      sb.append(l.longValue());
    } else if (v instanceof Double d) {
      sb.append(d == Math.floor(d) && !d.isInfinite() ? Long.toString(d.longValue()) : d.toString());
    } else if (v instanceof List<?> list) {
      writeSeq(sb, list, indent, depth, '[', ']', null);
    } else if (v instanceof Map<?, ?> map) {
      writeSeq(sb, ((Map<String, Object>) map).entrySet(), indent, depth, '{', '}', null);
    } else {
      sb.append(v); // fallback
    }
  }

  private static void writeSeq(
      StringBuilder sb, Iterable<?> items, int indent, int depth, char open, char close, Void v) {
    sb.append(open);
    boolean first = true;
    for (Object item : items) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      newline(sb, indent, depth + 1);
      if (item instanceof Map.Entry<?, ?> e) {
        quote(sb, (String) e.getKey());
        sb.append(indent > 0 ? ": " : ":");
        write(sb, e.getValue(), indent, depth + 1);
      } else {
        write(sb, item, indent, depth + 1);
      }
    }
    if (!first) {
      newline(sb, indent, depth);
    }
    sb.append(close);
  }

  private static void newline(StringBuilder sb, int indent, int depth) {
    if (indent > 0) {
      sb.append('\n').append(" ".repeat(indent * depth));
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    sb.append('"');
  }
}

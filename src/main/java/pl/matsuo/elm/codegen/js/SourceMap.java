package pl.matsuo.elm.codegen.js;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.json.JsonEncode;

/**
 * Builds a <a href="https://sourcemaps.info/spec.html">Source Map v3</a> for generated JavaScript.
 * Our codegen emits one statement per line, so the map is line-level: each generated declaration
 * line maps to the Elm source line of the definition it came from.
 */
public final class SourceMap {

  private static final String B64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  private SourceMap() {}

  /** Base64 VLQ encoding of a single signed integer. */
  static String vlq(int value) {
    int v = value < 0 ? ((-value) << 1) | 1 : value << 1;
    StringBuilder sb = new StringBuilder();
    do {
      int digit = v & 0x1f;
      v >>>= 5;
      if (v > 0) {
        digit |= 0x20;
      }
      sb.append(B64.charAt(digit));
    } while (v > 0);
    return sb.toString();
  }

  /**
   * Mappings string: {@code prefixLines} unmapped lines, then the segments for each generated line.
   * Generated-column deltas reset at the start of every line; source line/column deltas are
   * cumulative across the whole file (Source Map v3 semantics).
   */
  static String mappings(int prefixLines, List<List<Seg>> lines) {
    StringBuilder sb = new StringBuilder();
    sb.append(";".repeat(Math.max(0, prefixLines)));
    int prevSrcLine = 0;
    int prevSrcCol = 0;
    for (List<Seg> segs : lines) {
      int prevGenCol = 0;
      for (int i = 0; i < segs.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        Seg s = segs.get(i);
        sb.append(vlq(s.genColumn() - prevGenCol)) // generated column delta (resets each line)
            .append(vlq(0)) // source index delta (always source 0)
            .append(vlq(s.sourceLine() - prevSrcLine)) // source line delta
            .append(vlq(s.sourceColumn() - prevSrcCol)); // source column delta
        prevGenCol = s.genColumn();
        prevSrcLine = s.sourceLine();
        prevSrcCol = s.sourceColumn();
      }
      sb.append(';');
    }
    return sb.toString();
  }

  /** A mapping segment: a 0-based generated column to a 0-based source line/column (source 0). */
  public record Seg(int genColumn, int sourceLine, int sourceColumn) {}

  /** A complete Source Map v3 JSON document. */
  public static String json(
      String sourceName, String sourceContent, int prefixLines, List<List<Seg>> lines) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("version", 3L);
    m.put("file", "out.js");
    m.put("sources", List.of(sourceName));
    m.put("sourcesContent", List.of(sourceContent));
    m.put("names", List.of());
    m.put("mappings", mappings(prefixLines, lines));
    return JsonEncode.serialize(m, 0);
  }
}

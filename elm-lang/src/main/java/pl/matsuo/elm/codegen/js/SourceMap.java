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
   * Mappings string: {@code prefixLines} unmapped lines, then one segment per declaration line
   * mapping generated column 0 to source 0 at the given 1-based source line.
   */
  static String mappings(int prefixLines, List<Integer> srcLines1Based) {
    StringBuilder sb = new StringBuilder();
    sb.append(";".repeat(Math.max(0, prefixLines)));
    int prevSrcLine = 0;
    for (int i = 0; i < srcLines1Based.size(); i++) {
      int srcLine0 = srcLines1Based.get(i) - 1;
      sb.append(vlq(0)) // generated column 0
          .append(vlq(0)) // source index delta (always source 0)
          .append(vlq(srcLine0 - prevSrcLine)) // source line delta
          .append(vlq(0)); // source column 0
      prevSrcLine = srcLine0;
      sb.append(';');
    }
    return sb.toString();
  }

  /** A complete Source Map v3 JSON document. */
  public static String json(
      String sourceName, String sourceContent, int prefixLines, List<Integer> srcLines1Based) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("version", 3L);
    m.put("file", "out.js");
    m.put("sources", List.of(sourceName));
    m.put("sourcesContent", List.of(sourceContent));
    m.put("names", List.of());
    m.put("mappings", mappings(prefixLines, srcLines1Based));
    return JsonEncode.serialize(m, 0);
  }
}

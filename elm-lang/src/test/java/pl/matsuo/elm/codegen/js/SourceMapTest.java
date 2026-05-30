package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.json.JsonParse;

class SourceMapTest {

  @Test
  void mainDeclarationMapsToItsElmSourceLine() {
    String src = "a = 1\nb = 2\nmain = a + b\n"; // main is on Elm line 3
    JsCompiler.Mapped m = JsCompiler.moduleProgramWithSourceMap(src, "Main.elm");

    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) JsonParse.parse(m.map());
    assertEquals(3L, map.get("version"));
    assertEquals(List.of("Main.elm"), map.get("sources"));
    assertTrue(m.code().contains("sourceMappingURL=data:application/json;base64,"), "inline map");

    // The generated line of `_$main = ...` must map (via the decoded mappings) to Elm line 3.
    String[] lines = m.code().split("\n", -1);
    int genLine = -1;
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].startsWith("var _$main = ")) {
        genLine = i;
      }
    }
    assertTrue(genLine >= 0, "generated main declaration found");
    List<int[]> decoded = decode((String) map.get("mappings"));
    int mappedSrcLine = -1;
    for (int[] seg : decoded) {
      if (seg[0] == genLine) {
        mappedSrcLine = seg[1];
      }
    }
    assertEquals(2, mappedSrcLine, "0-based source line for `main` (Elm line 3)");
  }

  @Test
  void everyDeclarationRoundTripsToItsElmSourceLine() {
    // Each generated `var _$NAME = ...` line must map back, through the source map, to the Elm line
    // where `NAME` is defined — the round-trip a debugger performs to relocate a stack frame.
    String src = "first = 1\nsecond = first + 1\nthird = second * 2\nmain = third\n";
    JsCompiler.Mapped m = JsCompiler.moduleProgramWithSourceMap(src, "Main.elm");
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) JsonParse.parse(m.map());
    List<int[]> decoded = decode((String) map.get("mappings"));

    Map<String, Integer> elmLineOf =
        Map.of("first", 0, "second", 1, "third", 2, "main", 3); // 0-based Elm lines
    String[] gen = m.code().split("\n", -1);
    for (var e : elmLineOf.entrySet()) {
      int genLine = lineStartingWith(gen, "var _$" + e.getKey() + " = ");
      assertTrue(genLine >= 0, "generated decl for " + e.getKey());
      assertEquals(
          e.getValue().intValue(),
          resolveFrame(decoded, genLine),
          "generated line " + genLine + " -> Elm source line for " + e.getKey());
    }
  }

  @Test
  void stackTraceFrameResolvesToElmFileAndLine() {
    // Simulate a browser stack frame `at out.js:<genLine>:0` and resolve it via the map, as
    // devtools would, to a human-readable Elm location.
    String src = "a = 1\nb = 2\nmain = a + b\n";
    JsCompiler.Mapped m = JsCompiler.moduleProgramWithSourceMap(src, "Main.elm");
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) JsonParse.parse(m.map());
    List<int[]> decoded = decode((String) map.get("mappings"));

    int frameLine = lineStartingWith(m.code().split("\n", -1), "var _$main = ");
    int srcLine0 = resolveFrame(decoded, frameLine);
    String source = ((List<?>) map.get("sources")).get(0).toString();
    assertEquals("Main.elm:3", source + ":" + (srcLine0 + 1));
  }

  private static int lineStartingWith(String[] lines, String prefix) {
    int found = -1;
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].startsWith(prefix)) {
        found = i;
      }
    }
    return found;
  }

  /** Resolves a generated line to its mapped 0-based source line (-1 if unmapped). */
  private static int resolveFrame(List<int[]> decoded, int genLine) {
    for (int[] seg : decoded) {
      if (seg[0] == genLine) {
        return seg[1];
      }
    }
    return -1;
  }

  /** Decodes a line-level mappings string into {generatedLine, sourceLine0} pairs. */
  private static List<int[]> decode(String mappings) {
    List<int[]> out = new ArrayList<>();
    String[] groups = mappings.split(";", -1);
    int srcLine = 0;
    for (int gl = 0; gl < groups.length; gl++) {
      if (groups[gl].isEmpty()) {
        continue;
      }
      int[] fields = vlqDecode(groups[gl]);
      srcLine += fields[2]; // field[2] = source line delta
      out.add(new int[] {gl, srcLine});
    }
    return out;
  }

  private static int[] vlqDecode(String s) {
    List<Integer> values = new ArrayList<>();
    int shift = 0;
    int value = 0;
    String b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (int i = 0; i < s.length(); i++) {
      int digit = b64.indexOf(s.charAt(i));
      boolean cont = (digit & 0x20) != 0;
      value += (digit & 0x1f) << shift;
      if (cont) {
        shift += 5;
      } else {
        int v = (value & 1) != 0 ? -(value >> 1) : (value >> 1);
        values.add(v);
        value = 0;
        shift = 0;
      }
    }
    int[] arr = new int[values.size()];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = values.get(i);
    }
    return arr;
  }
}

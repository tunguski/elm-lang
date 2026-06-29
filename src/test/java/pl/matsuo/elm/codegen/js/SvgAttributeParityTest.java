package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Prelude;
import pl.matsuo.elm.types.Signatures;

/**
 * The {@code Svg.Attributes} set must agree across the interpreter ({@link Prelude#svgAttributeNames()}),
 * the JS runtime ({@code dom.js} {@code svgAttrs}) and the type-checker ({@link Signatures}). When the
 * three drifted, {@code Svg.Attributes.id}/{@code offset}/{@code stopColor}/{@code stopOpacity} (the
 * gradient set) were bound in one place but not another: an attribute the type-checker knew but a
 * backend didn't threw {@code Unbound:} at runtime; one a backend bound but the checker didn't failed
 * {@code --check} — forcing apps onto the generic {@code Html.Attributes.attribute} escape hatch.
 */
class SvgAttributeParityTest {

  @Test
  void jsBackendRegistersExactlyTheInterpreterSvgAttributes() throws IOException {
    Set<String> canonical = new TreeSet<>(Prelude.svgAttributeNames());
    assertEquals(
        canonical,
        jsSvgAttributes(),
        "dom.js svgAttrs must match Prelude.SVG_ATTRS exactly, or a Svg.Attributes call binds in one"
            + " runtime but throws Unbound in the other");
  }

  @Test
  void typeCheckerHasASchemeForEverySvgAttribute() {
    Set<String> schemes = Signatures.globals().keySet();
    for (String name : Prelude.svgAttributeNames()) {
      assertTrue(
          schemes.contains("Svg.Attributes." + name),
          "the type-checker has no scheme for Svg.Attributes." + name);
    }
  }

  /** The Elm names in dom.js's {@code svgAttrs} list (the part before the first ':' for mapped names). */
  private static Set<String> jsSvgAttributes() throws IOException {
    String dom;
    try (InputStream in = SvgAttributeParityTest.class.getResourceAsStream("/elm/js/dom.js")) {
      dom = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher array = Pattern.compile("var svgAttrs\\s*=\\s*\\[(.*?)\\];", Pattern.DOTALL).matcher(dom);
    assertTrue(array.find(), "dom.js has a `var svgAttrs = [...]` attribute list");
    Set<String> names = new TreeSet<>();
    Matcher token = Pattern.compile("'([^']+)'").matcher(array.group(1));
    while (token.find()) {
      String spec = token.group(1);
      int colon = spec.indexOf(':');
      names.add(colon < 0 ? spec : spec.substring(0, colon));
    }
    return names;
  }
}

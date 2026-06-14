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
 * The Svg element set must agree across the interpreter ({@link Prelude#svgElementNames()}), the JS
 * runtime ({@code dom.js} {@code svgEls} — which also drives the {@code createElementNS} namespace
 * choice) and the type-checker ({@link Signatures}). A tag the interpreter binds but the JS backend
 * doesn't know would render in the wrong (HTML) namespace; one without a scheme wouldn't type-check.
 */
class SvgElementParityTest {

  @Test
  void jsBackendRegistersExactlyTheInterpreterSvgElements() throws IOException {
    Set<String> canonical = new TreeSet<>(Prelude.svgElementNames());
    assertEquals(
        canonical,
        jsSvgElements(),
        "dom.js svgEls must match Prelude.SVG_TAGS exactly, or SVG renders in the wrong namespace");
  }

  @Test
  void typeCheckerHasASchemeForEverySvgElement() {
    Set<String> schemes = Signatures.globals().keySet();
    for (String name : Prelude.svgElementNames()) {
      assertTrue(schemes.contains("Svg." + name), "the type-checker has no scheme for Svg." + name);
    }
  }

  /** The Elm names in dom.js's {@code svgEls} list (the part before ':' for aliased tags). */
  private static Set<String> jsSvgElements() throws IOException {
    String dom;
    try (InputStream in = SvgElementParityTest.class.getResourceAsStream("/elm/js/dom.js")) {
      dom = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher array = Pattern.compile("var svgEls = \\[(.*?)\\];", Pattern.DOTALL).matcher(dom);
    assertTrue(array.find(), "dom.js has a `var svgEls = [...]` element list");
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

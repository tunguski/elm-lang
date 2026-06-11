package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Prelude;
import pl.matsuo.elm.types.Signatures;

/**
 * The set of supported {@code Html} elements is hard-coded in three places that must agree: the
 * interpreter ({@link Prelude#htmlElementNames()} — the source of truth), the JS backend's runtime
 * registry ({@code elm/js/dom.js}) and the type-checker ({@link Signatures}). Drift is a latent
 * crash: an app using, say, {@code Html.dl} type-checks and runs interpreted, then throws {@code
 * Unbound: Html.dl} the moment it is compiled to JS — which is exactly how a missing {@code dl}/{@code
 * dt}/{@code dd} blanked a real page. This test fails the build if the lists ever diverge again.
 */
class HtmlElementParityTest {

  @Test
  void jsBackendRegistersExactlyTheInterpreterHtmlElements() throws IOException {
    Set<String> canonical = new TreeSet<>(Prelude.htmlElementNames());
    assertEquals(
        canonical,
        jsRegisteredHtmlElements(),
        "dom.js's Html element registry must match Prelude.HTML_TAGS exactly; an element the "
            + "interpreter binds but the JS backend doesn't crashes at runtime with Unbound: Html.<tag>");
  }

  @Test
  void typeCheckerHasASchemeForEveryHtmlElement() {
    Set<String> schemes = Signatures.globals().keySet();
    for (String name : Prelude.htmlElementNames()) {
      assertTrue(
          schemes.contains("Html." + name),
          "the type-checker has no scheme for Html." + name + " (add it to Signatures.HTML_ELEMENTS)");
    }
  }

  /** The Elm names registered as {@code $rt['Html.<name>']} by dom.js's {@code elements} list. */
  private static Set<String> jsRegisteredHtmlElements() throws IOException {
    String dom;
    try (InputStream in = HtmlElementParityTest.class.getResourceAsStream("/elm/js/dom.js")) {
      dom = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher array = Pattern.compile("var elements = \\[(.*?)\\];", Pattern.DOTALL).matcher(dom);
    assertTrue(array.find(), "dom.js has a `var elements = [...]` Html element list");
    Set<String> names = new LinkedHashSet<>();
    Matcher token = Pattern.compile("'([^']+)'").matcher(array.group(1));
    while (token.find()) {
      String spec = token.group(1); // 'elmName' or 'elmName:htmlTag'
      int colon = spec.indexOf(':');
      names.add(colon < 0 ? spec : spec.substring(0, colon));
    }
    return new TreeSet<>(names);
  }
}

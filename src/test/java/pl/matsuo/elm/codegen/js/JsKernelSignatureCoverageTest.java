package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.types.Signatures;

/**
 * The JS-backend half of the runtime-resolvability contract (the interpreter half lives in {@code
 * BuiltinSignatureCoverageTest}): every function type scheme the type-checker knows ({@link
 * Signatures#globals()}) must actually be bound in the JS runtime ({@code kernel.js} + {@code
 * dom.js}). A scheme the JS runtime can't bind type-checks and compiles, then throws {@code Unbound:
 * …} and blanks the page the moment its branch renders — exactly how {@code spellcheck},
 * {@code stopPropagationOn} and {@code Math.Matrix4.makeScale} slipped through.
 *
 * <p>JS-bound names are enumerated by actually loading the runtime under Node and reading {@code
 * Object.keys($rt)}, so the test reflects what really resolves, not a hand-maintained list. Names
 * the codegen synthesizes inline (operators) or resolves without a {@code $rt} entry are listed in
 * {@link #CODEGEN_SYNTHESIZED} with a reason, so the gap can't grow silently.
 */
class JsKernelSignatureCoverageTest {

  @Test
  void everyFunctionSchemeIsBoundInTheJsKernel() {
    Set<String> jsBound = jsRuntimeKeys();
    List<String> missing =
        Signatures.globals().keySet().stream()
            .filter(JsKernelSignatureCoverageTest::isLowercaseValue) // skip constructors (uppercase)
            .filter(name -> !boundInJs(name, jsBound))
            .filter(name -> !ALLOWED_UNBOUND.contains(name))
            .sorted()
            .toList();
    assertTrue(
        missing.isEmpty(),
        "These type schemes have no JS-runtime binding (bind them in kernel.js/dom.js, or — if"
            + " genuinely deferred — add to ALLOWED_UNBOUND with a reason):\n  "
            + String.join("\n  ", missing));
  }

  /**
   * Schemes that type-check but have no JS-runtime binding, by design — the WebGL subset the JS GL
   * renderer ({@code dom.js} {@code drawGL}) does not yet implement: non-triangle draw modes, the
   * depth/stencil/preserveDrawingBuffer settings, and the texture-option records. They render under
   * the interpreter but a compiled program using them would throw {@code Unbound} — a known,
   * documented gap so it can't grow silently. Removing one from here is the to-do when it's wired up.
   */
  private static final Set<String> ALLOWED_UNBOUND =
      Set.of(
          "WebGL.lines",
          "WebGL.lineStrip",
          "WebGL.lineLoop",
          "WebGL.points",
          "WebGL.triangleStrip",
          "WebGL.triangleFan",
          "WebGL.stencil",
          "WebGL.preserveDrawingBuffer",
          "WebGL.Settings.DepthTest.default",
          "WebGL.Texture.defaultOptions",
          "WebGL.Texture.linearMipmapNearest",
          "WebGL.Texture.nearestMipmapLinear",
          "WebGL.Texture.nonPowerOfTwoOptions");

  /** Loads the JS runtime (kernel + DOM) under Node and returns every {@code $rt} key. */
  private static Set<String> jsRuntimeKeys() {
    String harness =
        "globalThis.window = globalThis;\n"
            + JsCompiler.declarationsScriptWithDomProject("module M exposing (x)\nx = 0\n")
            + "\nprocess.stdout.write(Object.keys($rt).join('\\n'));\n";
    return new HashSet<>(Arrays.asList(runNode(harness).split("\\r?\\n")));
  }

  /** Whether {@code name} resolves in the JS runtime: bound directly, or — for a bare type-checker
   *  name (no module) — under its canonical {@code Basics.} key, the way the codegen resolves it. */
  private static boolean boundInJs(String name, Set<String> jsBound) {
    return jsBound.contains(name)
        || (name.indexOf('.') < 0 && jsBound.contains("Basics." + name));
  }

  private static boolean isLowercaseValue(String name) {
    String last = name.substring(name.lastIndexOf('.') + 1);
    return !last.isEmpty() && Character.isLowerCase(last.charAt(0));
  }

  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-js-", ".js");
      Files.writeString(file, program, StandardCharsets.UTF_8);
      Process p = new ProcessBuilder("node", file.toString()).redirectErrorStream(false).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("node timed out");
      }
      Files.deleteIfExists(file);
      if (p.exitValue() != 0) {
        throw new IllegalStateException("node failed: " + err);
      }
      return out;
    } catch (IOException | InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }
}

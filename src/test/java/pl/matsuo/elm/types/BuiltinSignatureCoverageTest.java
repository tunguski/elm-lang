package pl.matsuo.elm.types;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Prelude;

/**
 * Guards the interpreter↔compiler contract: every builtin the interpreter knows ({@link
 * Prelude#builtins()}) must also have a type scheme ({@link Signatures#globals()}). Without a scheme
 * a function runs under {@code elm run}/repl but the type checker rejects it ("Unknown name: …"), so
 * it can't be compiled by {@code elm make}/{@code js}/{@code wasm}. Anything deliberately left
 * interpreter-only must be listed in {@link #ALLOWLIST} with a reason, so the gap can't grow silently.
 */
class BuiltinSignatureCoverageTest {

  /** Builtins intentionally without a public type scheme. */
  private static final Set<String> ALLOWLIST =
      Set.of(
          // Internal effect plumbing the runtime constructs/consumes itself (not user-callable).
          "Platform.worker");

  @Test
  void everyInterpreterBuiltinHasATypeSignature() {
    Set<String> schemes = Signatures.globals().keySet();
    List<String> missing =
        Prelude.builtins().keySet().stream()
            .filter(name -> !schemes.contains(name))
            .filter(name -> !ALLOWLIST.contains(name))
            .sorted()
            .toList();
    assertTrue(
        missing.isEmpty(),
        "These interpreter builtins have no type scheme (add one to Signatures, or allowlist it):\n  "
            + String.join("\n  ", missing));
  }

  /**
   * The reverse contract: every <em>function</em> type scheme (a name whose final segment is
   * lowercase — constructors are uppercase and resolved by the runtime's tag machinery) must be
   * resolvable by the runtime. A scheme the runtime can't bind type-checks but fails at run time with
   * "Unbound variable", so the gap is caught here. Resolution is checked through the real interpreter
   * (covering Prelude builtins and the math/Basics functions it lowers specially); browser-only
   * effects that exist solely in the JS backend are allowlisted with the backend that implements them.
   */
  @Test
  void everyFunctionSchemeIsRuntimeResolvable() {
    List<String> missing =
        Signatures.globals().keySet().stream()
            .filter(BuiltinSignatureCoverageTest::isLowercaseValue) // skip constructors (uppercase)
            .filter(name -> !JS_BACKEND_ONLY.contains(name))
            .filter(name -> !resolvableByInterpreter(name))
            .sorted()
            .toList();
    assertTrue(
        missing.isEmpty(),
        "These type schemes are not runtime-resolvable (implement them, or allowlist with a backend):\n  "
            + String.join("\n  ", missing));
  }

  /** Whether the interpreter can bind {@code name} to a value (i.e. referencing it does not throw an
   *  "Unbound variable" error). */
  private static boolean resolvableByInterpreter(String name) {
    try {
      pl.matsuo.elm.interp.Interpreter.eval(name);
      return true;
    } catch (RuntimeException e) {
      String msg = String.valueOf(e.getMessage());
      // Only an unbound-name failure counts as "not resolvable"; any other error means it resolved
      // to a value that simply can't be evaluated bare (e.g. needs arguments).
      return !msg.contains("Unbound") && !msg.contains("Unknown name");
    }
  }

  /** Whether a global's value name (its final dotted segment) begins lowercase — i.e. it is a
   *  function/value rather than a constructor. */
  private static boolean isLowercaseValue(String name) {
    String last = name.substring(name.lastIndexOf('.') + 1);
    return !last.isEmpty() && Character.isLowerCase(last.charAt(0));
  }

  /** Schemes implemented only by the JS backend (browser DOM/WebGL/File effects), so they have no
   *  binding in the headless interpreter — see kernel.js / dom.js. */
  private static final Set<String> JS_BACKEND_ONLY =
      Set.of(
          "Browser.Dom.blur",
          "Browser.Dom.focus",
          "Browser.Dom.setViewport",
          "Browser.Events.onKeyPress",
          "File.openPicker",
          "Math.Matrix4.makeOrtho",
          "Math.Vector3.fromRecord",
          "Math.Vector3.setX",
          "Math.Vector3.setY",
          "Math.Vector3.setZ",
          "Math.Vector3.toRecord",
          "WebGL.glAttr");
}

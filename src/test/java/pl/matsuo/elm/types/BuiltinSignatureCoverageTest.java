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
}

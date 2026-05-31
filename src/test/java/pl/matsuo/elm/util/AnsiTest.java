package pl.matsuo.elm.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Colouring is disabled under tests (no real terminal), so output stays plain — which is exactly
 *  what CI logs, pipes and the test assertions rely on. */
class AnsiTest {

  @Test
  void colouringIsDisabledWithoutATerminalSoOutputIsPlain() {
    assertFalse(Ansi.enabled(), "no TTY under tests");
    String msg = "Type mismatch.\n2 | main = foo\n        ^\n\nHint: try bar";
    // With colouring off, error() must be exactly "header message" and carry no ANSI escape byte.
    String rendered = Ansi.error("Type error:", msg);
    assertEquals("Type error: " + msg, rendered);
    assertFalse(rendered.indexOf(27) >= 0, "no ANSI escape (ESC) byte when disabled");
  }
}

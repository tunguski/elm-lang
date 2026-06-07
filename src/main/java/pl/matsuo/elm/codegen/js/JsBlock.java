package pl.matsuo.elm.codegen.js;

/**
 * A growable sequence of JavaScript statements.
 *
 * <p>Replaces the ad-hoc {@code StringBuilder} + {@code append("var ")…append(";")} threading the
 * codegen used for statement bodies (function bodies, {@code let} blocks, the tail-call loop) with
 * typed statement methods, so the emitter reads as {@code block.varDecl(t, expr).continueTo("$tco")}.
 * Each method appends one complete statement (already terminated by {@code ;} or {@code }}), and
 * {@link #toString} renders them concatenated — ready to drop inside a {@code { … }}.
 */
public final class JsBlock {

  private final StringBuilder sb = new StringBuilder();

  /** Appends an already-rendered statement (or several) verbatim. */
  public JsBlock add(String statement) {
    sb.append(statement);
    return this;
  }

  /** {@code var name=value;}. */
  public JsBlock varDecl(String name, String value) {
    return add(Js.varDecl(name, value));
  }

  /** {@code lhs=rhs;} — a reassignment (no {@code var}). */
  public JsBlock assign(String lhs, String rhs) {
    return add(Js.assign(lhs, rhs));
  }

  /** {@code return expr;}. */
  public JsBlock ret(String expr) {
    return add(Js.ret(expr));
  }

  /** {@code continue label;}. */
  public JsBlock continueTo(String label) {
    return add(Js.continueTo(label));
  }

  /** {@code throw new Error("message");}. */
  public JsBlock throwError(String message) {
    return add(Js.throwError(message));
  }

  public boolean isEmpty() {
    return sb.length() == 0;
  }

  @Override
  public String toString() {
    return sb.toString();
  }
}

package pl.matsuo.elm.error;

/** A type error detected during inference, optionally carrying the source location it occurred at. */
public class ElmTypeError extends RuntimeException {

  /** Where the error occurred, or {@code null} if not yet located. */
  public final Position position;

  /** An optional one-line hint shown to the user. */
  public final String hint;

  public ElmTypeError(String message) {
    this(message, null, null);
  }

  public ElmTypeError(String message, Position position) {
    this(message, position, null);
  }

  public ElmTypeError(String message, Position position, String hint) {
    super(message);
    this.position = position;
    this.hint = hint;
  }

  /** The bare message without any location/hint decoration. */
  public String rawMessage() {
    return super.getMessage();
  }

  /**
   * Returns this error located at {@code p} (with an optional hint). If it already has a location it
   * is returned unchanged, so the innermost/most-specific position wins as it bubbles up.
   */
  public ElmTypeError at(Position p, String hint) {
    if (position != null || p == null) {
      return this;
    }
    return new ElmTypeError(rawMessage(), p, hint);
  }
}

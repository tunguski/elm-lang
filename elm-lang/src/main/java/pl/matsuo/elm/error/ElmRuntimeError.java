package pl.matsuo.elm.error;

/** An error raised while evaluating Elm code (unbound name, non-exhaustive pattern, etc.). */
public class ElmRuntimeError extends RuntimeException {

  /** The source location the error occurred at, or {@code null} if unknown. */
  public final Position position;

  public ElmRuntimeError(String message) {
    super(message);
    this.position = null;
  }

  public ElmRuntimeError(String message, Position position) {
    super(position == null ? message : message + " (at " + position + ")");
    this.position = position;
  }

  /** Returns this error located at {@code p} if it has no location yet, else unchanged. */
  public ElmRuntimeError at(Position p) {
    if (position != null || p == null) {
      return this;
    }
    return new ElmRuntimeError(getMessage(), p);
  }
}

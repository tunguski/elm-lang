package pl.matsuo.elm.error;

/** A type error detected during inference, optionally carrying the source location it occurred at. */
public class ElmTypeError extends RuntimeException {

  /** Where the error occurred, or {@code null} if not yet located. */
  public final Position position;

  /** An optional one-line hint shown to the user. */
  public final String hint;

  /** The module the error occurred in, or {@code null} (single-module / not yet tagged). Set as the
   * error bubbles out of a module during project inference so the report can render the excerpt
   * against THAT module's source and name it — across a multi-module project a bare {@code line:col}
   * located against the entry file is unlocatable. */
  public final String module;

  public ElmTypeError(String message) {
    this(message, null, null, null);
  }

  public ElmTypeError(String message, Position position) {
    this(message, position, null, null);
  }

  public ElmTypeError(String message, Position position, String hint) {
    this(message, position, hint, null);
  }

  public ElmTypeError(String message, Position position, String hint, String module) {
    super(message);
    this.position = position;
    this.hint = hint;
    this.module = module;
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
    return new ElmTypeError(rawMessage(), p, hint, module);
  }

  /** Returns this error tagged with the module it occurred in (the first tag wins, so the innermost
   * module that re-throws keeps its name). */
  public ElmTypeError inModule(String m) {
    if (module != null || m == null) {
      return this;
    }
    return new ElmTypeError(rawMessage(), position, hint, m);
  }
}

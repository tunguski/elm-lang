package pl.matsuo.elm.error;

/** An error raised while evaluating Elm code (unbound name, non-exhaustive pattern, etc.). */
public class ElmRuntimeError extends RuntimeException {

  public ElmRuntimeError(String message) {
    super(message);
  }
}

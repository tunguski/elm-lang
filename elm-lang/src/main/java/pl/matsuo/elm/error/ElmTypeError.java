package pl.matsuo.elm.error;

/** A type error detected during inference. */
public class ElmTypeError extends RuntimeException {

  public ElmTypeError(String message) {
    super(message);
  }

  public ElmTypeError(String message, Position position) {
    super(message + " (at " + position + ")");
  }
}

package pl.matsuo.elm.error;

/** A lexical or syntactic error, carrying the source position where it was detected. */
public class ElmSyntaxError extends RuntimeException {

  private final Position position;

  public ElmSyntaxError(String message, Position position) {
    super(message + " (at " + position + ")");
    this.position = position;
  }

  public Position position() {
    return position;
  }
}

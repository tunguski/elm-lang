package pl.matsuo.elm.error;

/**
 * A source location. {@code line} and {@code col} are 1-based; {@code offset} is the 0-based index
 * into the source string. Used for tokens, AST nodes and error reporting.
 */
public record Position(int line, int col, int offset) {

  @Override
  public String toString() {
    return line + ":" + col;
  }
}

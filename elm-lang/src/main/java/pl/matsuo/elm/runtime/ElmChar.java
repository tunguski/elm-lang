package pl.matsuo.elm.runtime;

/** An Elm {@code Char}, stored as a Unicode code point (distinct from {@code Int}). */
public record ElmChar(int codePoint) {

  @Override
  public String toString() {
    return "'" + new String(Character.toChars(codePoint)) + "'";
  }
}

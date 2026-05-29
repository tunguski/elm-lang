package pl.matsuo.elm.lexer;

import pl.matsuo.elm.error.Position;

/**
 * A single lexical token.
 *
 * @param type the token kind
 * @param text the raw source text of the token (for names/operators this is the identifier/symbol)
 * @param value decoded literal value for {@code INT} (Long), {@code FLOAT} (Double), {@code STRING}
 *     (String) and {@code CHAR} (Integer code point); {@code null} otherwise
 * @param start the position of the first character of the token
 * @param spaceBefore whether at least one whitespace/comment character preceded this token; used by
 *     the parser to distinguish record access ({@code r.x}) from the accessor function ({@code .x})
 *     and operator sections
 */
public record Token(TokenType type, String text, Object value, Position start, boolean spaceBefore) {

  public int line() {
    return start.line();
  }

  public int col() {
    return start.col();
  }

  public boolean is(TokenType t) {
    return type == t;
  }

  @Override
  public String toString() {
    return switch (type) {
      case EOF -> "<eof>";
      case STRING -> "\"" + text + "\"";
      default -> type + "(" + text + ")";
    };
  }
}

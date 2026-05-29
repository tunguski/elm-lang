package pl.matsuo.elm.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.error.ElmSyntaxError;
import pl.matsuo.elm.error.Position;

/**
 * Hand-written lexer for Elm source. Produces a flat list of {@link Token}s with accurate line/col
 * positions; layout (the offside rule) is resolved later by the parser using those positions, so no
 * virtual layout tokens are inserted here.
 *
 * <p>Known simplifications (sufficient for the elm-lang.org examples): operator characters do not
 * include {@code .}, so dot-containing user operators such as {@code |.} from elm/parser are not
 * supported; identifiers may contain underscores but a lone {@code _} is always the wildcard.
 */
public final class Lexer {

  /** Characters that combine into operator tokens via maximal munch. */
  private static final String OP_CHARS = "+-*/<>=!&|^%:~";

  private static final Map<String, TokenType> KEYWORDS =
      Map.ofEntries(
          Map.entry("if", TokenType.KW_IF),
          Map.entry("then", TokenType.KW_THEN),
          Map.entry("else", TokenType.KW_ELSE),
          Map.entry("case", TokenType.KW_CASE),
          Map.entry("of", TokenType.KW_OF),
          Map.entry("let", TokenType.KW_LET),
          Map.entry("in", TokenType.KW_IN),
          Map.entry("type", TokenType.KW_TYPE),
          Map.entry("module", TokenType.KW_MODULE),
          Map.entry("where", TokenType.KW_WHERE),
          Map.entry("import", TokenType.KW_IMPORT),
          Map.entry("exposing", TokenType.KW_EXPOSING),
          Map.entry("as", TokenType.KW_AS),
          Map.entry("port", TokenType.KW_PORT));

  private final String src;
  private int pos = 0;
  private int line = 1;
  private int col = 1;
  private boolean spaceBefore = false;
  private final List<Token> out = new ArrayList<>();

  private Lexer(String src) {
    this.src = src;
  }

  /** Tokenizes the whole source, ending with an {@code EOF} token. */
  public static List<Token> tokenize(String source) {
    return new Lexer(source).run();
  }

  private List<Token> run() {
    while (true) {
      skipTrivia();
      if (atEnd()) {
        break;
      }
      lexToken();
    }
    out.add(new Token(TokenType.EOF, "", null, here(), true));
    return out;
  }

  // --- character helpers -------------------------------------------------

  private boolean atEnd() {
    return pos >= src.length();
  }

  private char peek(int ahead) {
    int i = pos + ahead;
    return i < src.length() ? src.charAt(i) : '\0';
  }

  private char cur() {
    return peek(0);
  }

  private char advance() {
    char c = src.charAt(pos++);
    if (c == '\n') {
      line++;
      col = 1;
    } else {
      col++;
    }
    return c;
  }

  private Position here() {
    return new Position(line, col, pos);
  }

  private ElmSyntaxError error(String message) {
    return new ElmSyntaxError(message, here());
  }

  // --- trivia (whitespace + comments) ------------------------------------

  private void skipTrivia() {
    spaceBefore = false;
    while (!atEnd()) {
      char c = cur();
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        advance();
        spaceBefore = true;
      } else if (c == '-' && peek(1) == '-') {
        // Line comment to end of line (newline left for the next iteration).
        while (!atEnd() && cur() != '\n') {
          advance();
        }
        spaceBefore = true;
      } else if (c == '{' && peek(1) == '-') {
        skipBlockComment();
        spaceBefore = true;
      } else {
        break;
      }
    }
  }

  private void skipBlockComment() {
    Position open = here();
    advance(); // {
    advance(); // -
    int depth = 1;
    while (depth > 0) {
      if (atEnd()) {
        throw new ElmSyntaxError("Unterminated block comment", open);
      }
      if (cur() == '{' && peek(1) == '-') {
        advance();
        advance();
        depth++;
      } else if (cur() == '-' && peek(1) == '}') {
        advance();
        advance();
        depth--;
      } else {
        advance();
      }
    }
  }

  // --- token dispatch ----------------------------------------------------

  private void lexToken() {
    Position start = here();
    char c = cur();

    if (c == '[' && src.startsWith("[glsl|", pos)) {
      lexShader(start);
    } else if (Character.isDigit(c)) {
      lexNumber(start);
    } else if (c == '"') {
      lexString(start);
    } else if (c == '\'') {
      lexChar(start);
    } else if (isIdentStart(c)) {
      lexIdentifier(start);
    } else {
      lexSymbol(start);
    }
  }

  private void emit(TokenType type, String text, Object value, Position start) {
    out.add(new Token(type, text, value, start, spaceBefore));
    spaceBefore = false;
  }

  // --- identifiers & keywords --------------------------------------------

  private static boolean isIdentStart(char c) {
    return Character.isLetter(c);
  }

  private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private void lexIdentifier(Position start) {
    int begin = pos;
    char first = cur();
    while (!atEnd() && isIdentPart(cur())) {
      advance();
    }
    String text = src.substring(begin, pos);
    TokenType kw = KEYWORDS.get(text);
    if (kw != null) {
      emit(kw, text, null, start);
    } else {
      emit(Character.isUpperCase(first) ? TokenType.UPPER : TokenType.LOWER, text, null, start);
    }
  }

  // --- numbers -----------------------------------------------------------

  private void lexNumber(Position start) {
    int begin = pos;
    if (cur() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
      advance();
      advance();
      int hexBegin = pos;
      while (!atEnd() && isHex(cur())) {
        advance();
      }
      if (pos == hexBegin) {
        throw error("Hexadecimal literal needs at least one digit");
      }
      String text = src.substring(begin, pos);
      long value = Long.parseLong(src.substring(hexBegin, pos), 16);
      emit(TokenType.INT, text, value, start);
      return;
    }

    while (!atEnd() && Character.isDigit(cur())) {
      advance();
    }
    boolean isFloat = false;
    if (cur() == '.' && Character.isDigit(peek(1))) {
      isFloat = true;
      advance(); // .
      while (!atEnd() && Character.isDigit(cur())) {
        advance();
      }
    }
    if (cur() == 'e' || cur() == 'E') {
      char sign = peek(1);
      int expDigit = (sign == '+' || sign == '-') ? 2 : 1;
      if (Character.isDigit(peek(expDigit))) {
        isFloat = true;
        advance(); // e
        if (sign == '+' || sign == '-') {
          advance();
        }
        while (!atEnd() && Character.isDigit(cur())) {
          advance();
        }
      }
    }
    String text = src.substring(begin, pos);
    if (isFloat) {
      emit(TokenType.FLOAT, text, Double.parseDouble(text), start);
    } else {
      emit(TokenType.INT, text, Long.parseLong(text), start);
    }
  }

  private static boolean isHex(char c) {
    return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  // --- strings & chars ---------------------------------------------------

  private void lexString(Position start) {
    if (peek(1) == '"' && peek(2) == '"') {
      lexMultilineString(start);
      return;
    }
    advance(); // opening quote
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (atEnd() || cur() == '\n') {
        throw new ElmSyntaxError("Unterminated string literal", start);
      }
      char c = cur();
      if (c == '"') {
        advance();
        break;
      }
      if (c == '\\') {
        sb.appendCodePoint(readEscape());
      } else {
        sb.append(advance());
      }
    }
    emit(TokenType.STRING, sb.toString(), sb.toString(), start);
  }

  /** Lexes a GLSL shader literal {@code [glsl| ... |]}, capturing the source between the bars. */
  private void lexShader(Position start) {
    for (int i = 0; i < "[glsl|".length(); i++) {
      advance();
    }
    int begin = pos;
    while (!(cur() == '|' && peek(1) == ']')) {
      if (atEnd()) {
        throw new ElmSyntaxError("Unterminated GLSL shader literal", start);
      }
      advance();
    }
    String source = src.substring(begin, pos);
    advance(); // |
    advance(); // ]
    emit(TokenType.GLSL, source, source, start);
  }

  private void lexMultilineString(Position start) {
    advance();
    advance();
    advance(); // """
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (atEnd()) {
        throw new ElmSyntaxError("Unterminated multi-line string literal", start);
      }
      if (cur() == '"' && peek(1) == '"' && peek(2) == '"') {
        advance();
        advance();
        advance();
        break;
      }
      if (cur() == '\\') {
        sb.appendCodePoint(readEscape());
      } else {
        sb.append(advance());
      }
    }
    emit(TokenType.STRING, sb.toString(), sb.toString(), start);
  }

  private void lexChar(Position start) {
    advance(); // opening '
    int codePoint;
    if (atEnd()) {
      throw new ElmSyntaxError("Unterminated character literal", start);
    }
    if (cur() == '\\') {
      codePoint = readEscape();
    } else if (cur() == '\'') {
      throw new ElmSyntaxError("Empty character literal", start);
    } else {
      codePoint = src.codePointAt(pos);
      for (int i = 0; i < Character.charCount(codePoint); i++) {
        advance();
      }
    }
    if (cur() != '\'') {
      throw new ElmSyntaxError("Character literal must contain exactly one character", start);
    }
    advance(); // closing '
    emit(TokenType.CHAR, new String(Character.toChars(codePoint)), codePoint, start);
  }

  /** Reads an escape sequence; assumes the current character is the backslash. */
  private int readEscape() {
    advance(); // backslash
    if (atEnd()) {
      throw error("Unterminated escape sequence");
    }
    char c = advance();
    return switch (c) {
      case 'n' -> '\n';
      case 't' -> '\t';
      case 'r' -> '\r';
      case '\\' -> '\\';
      case '"' -> '"';
      case '\'' -> '\'';
      case '0' -> '\0';
      case 'u' -> readUnicodeEscape();
      default -> throw error("Unknown escape sequence \\" + c);
    };
  }

  /** Reads the unicode-escape brace form (the backslash and {@code u} are already consumed). */
  private int readUnicodeEscape() {
    if (cur() != '{') {
      throw error("Unicode escape must look like \\u{1F600}");
    }
    advance(); // {
    int begin = pos;
    while (!atEnd() && isHex(cur())) {
      advance();
    }
    String digits = src.substring(begin, pos);
    if (digits.isEmpty() || cur() != '}') {
      throw error("Malformed unicode escape");
    }
    advance(); // }
    return Integer.parseInt(digits, 16);
  }

  // --- symbols & operators -----------------------------------------------

  private void lexSymbol(Position start) {
    char c = cur();
    switch (c) {
      case '(' -> single(TokenType.LPAREN, start);
      case ')' -> single(TokenType.RPAREN, start);
      case '[' -> single(TokenType.LBRACKET, start);
      case ']' -> single(TokenType.RBRACKET, start);
      case '{' -> single(TokenType.LBRACE, start);
      case '}' -> single(TokenType.RBRACE, start);
      case ',' -> single(TokenType.COMMA, start);
      case '\\' -> single(TokenType.BACKSLASH, start);
      case '_' -> single(TokenType.UNDERSCORE, start);
      case '.' -> {
        if (peek(1) == '.') {
          advance();
          advance();
          emit(TokenType.DOTDOT, "..", null, start);
        } else {
          advance();
          emit(TokenType.DOT, ".", null, start);
        }
      }
      default -> {
        if (OP_CHARS.indexOf(c) >= 0) {
          lexOperatorRun(start);
        } else {
          throw error("Unexpected character '" + c + "'");
        }
      }
    }
  }

  private void single(TokenType type, Position start) {
    String text = String.valueOf(advance());
    emit(type, text, null, start);
  }

  private void lexOperatorRun(Position start) {
    int begin = pos;
    while (!atEnd() && OP_CHARS.indexOf(cur()) >= 0) {
      advance();
    }
    String text = src.substring(begin, pos);
    TokenType type =
        switch (text) {
          case "=" -> TokenType.EQUALS;
          case "->" -> TokenType.ARROW;
          case ":" -> TokenType.COLON;
          case "|" -> TokenType.PIPE;
          default -> TokenType.OPERATOR;
        };
    emit(type, text, null, start);
  }
}

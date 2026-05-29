package pl.matsuo.elm.lexer;

/** The kinds of tokens produced by the {@link Lexer}. */
public enum TokenType {
  // Literals
  INT, // integer literal; value is a Long
  FLOAT, // floating literal; value is a Double
  STRING, // string literal; value is the decoded String
  CHAR, // character literal; value is an Integer code point

  // Names
  LOWER, // identifier beginning with a lowercase letter (variables, fields)
  UPPER, // identifier beginning with an uppercase letter (types, constructors, modules)

  // Reserved keywords
  KW_IF,
  KW_THEN,
  KW_ELSE,
  KW_CASE,
  KW_OF,
  KW_LET,
  KW_IN,
  KW_TYPE,
  KW_MODULE,
  KW_WHERE,
  KW_IMPORT,
  KW_EXPOSING,
  KW_AS,
  KW_PORT,

  // Structural symbols
  LPAREN, // (
  RPAREN, // )
  LBRACKET, // [
  RBRACKET, // ]
  LBRACE, // {
  RBRACE, // }
  COMMA, // ,
  EQUALS, // =
  ARROW, // ->
  COLON, // :
  PIPE, // |
  BACKSLASH, // \
  DOT, // .
  DOTDOT, // ..
  UNDERSCORE, // _

  // Any other run of operator characters (+, -, *, |>, ::, ==, ...)
  OPERATOR,

  // End of input
  EOF
}

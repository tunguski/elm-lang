package pl.matsuo.elm.lexer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.error.ElmSyntaxError;

class LexerTest {

  private List<Token> lex(String src) {
    return Lexer.tokenize(src);
  }

  /** Token types excluding the trailing EOF. */
  private List<TokenType> types(String src) {
    List<Token> tokens = lex(src);
    return tokens.subList(0, tokens.size() - 1).stream().map(Token::type).toList();
  }

  @Test
  void emptyInputIsJustEof() {
    List<Token> tokens = lex("   \n  ");
    assertEquals(1, tokens.size());
    assertEquals(TokenType.EOF, tokens.get(0).type());
  }

  @Test
  void integersAndFloats() {
    List<Token> t = lex("0 42 3.14 6.022e23 1e3 0xFF");
    assertEquals(TokenType.INT, t.get(0).type());
    assertEquals(0L, t.get(0).value());
    assertEquals(42L, t.get(1).value());
    assertEquals(TokenType.FLOAT, t.get(2).type());
    assertEquals(3.14, t.get(2).value());
    assertEquals(6.022e23, t.get(3).value());
    assertEquals(TokenType.FLOAT, t.get(4).type());
    assertEquals(1000.0, t.get(4).value());
    assertEquals(TokenType.INT, t.get(5).type());
    assertEquals(255L, t.get(5).value());
  }

  @Test
  void floatDotFollowedByNonDigitIsIntThenDot() {
    // "4.x" is not a float; it is INT(4) DOT LOWER(x).
    assertEquals(List.of(TokenType.INT, TokenType.DOT, TokenType.LOWER), types("4.x"));
  }

  @Test
  void namesVersusKeywords() {
    List<Token> t = lex("map List.map ifx if then case");
    assertEquals(TokenType.LOWER, t.get(0).type());
    assertEquals("map", t.get(0).text());
    assertEquals(TokenType.UPPER, t.get(1).type());
    assertEquals(TokenType.DOT, t.get(2).type());
    assertEquals(TokenType.LOWER, t.get(3).type());
    assertEquals(TokenType.LOWER, t.get(4).type(), "ifx is an identifier, not a keyword");
    assertEquals(TokenType.KW_IF, t.get(5).type());
    assertEquals(TokenType.KW_THEN, t.get(6).type());
    assertEquals(TokenType.KW_CASE, t.get(7).type());
  }

  @Test
  void structuralSymbolsAndOperators() {
    assertEquals(
        List.of(
            TokenType.EQUALS,
            TokenType.ARROW,
            TokenType.COLON,
            TokenType.PIPE,
            TokenType.OPERATOR, // ==
            TokenType.OPERATOR, // ::
            TokenType.OPERATOR, // |>
            TokenType.OPERATOR, // ++
            TokenType.OPERATOR), // <|
        types("= -> : | == :: |> ++ <|"));
  }

  @Test
  void operatorTextIsPreserved() {
    List<Token> t = lex("a |> b ++ c");
    assertEquals("|>", t.get(1).text());
    assertEquals("++", t.get(3).text());
  }

  @Test
  void dotVersusDotDot() {
    assertEquals(List.of(TokenType.DOT, TokenType.DOTDOT, TokenType.DOT), types(". .. ."));
  }

  @Test
  void parensBracketsBraces() {
    assertEquals(
        List.of(
            TokenType.LPAREN,
            TokenType.RPAREN,
            TokenType.LBRACKET,
            TokenType.RBRACKET,
            TokenType.LBRACE,
            TokenType.RBRACE,
            TokenType.COMMA,
            TokenType.UNDERSCORE,
            TokenType.BACKSLASH),
        types("()[]{}, _ \\"));
  }

  @Test
  void lineComments() {
    List<Token> t = lex("a -- this is ignored\nb");
    assertEquals(TokenType.LOWER, t.get(0).type());
    assertEquals("a", t.get(0).text());
    assertEquals("b", t.get(1).text());
    assertEquals(3, t.size()); // a, b, EOF
  }

  @Test
  void nestedBlockComments() {
    List<Token> t = lex("a {- outer {- inner -} still -} b");
    assertEquals("a", t.get(0).text());
    assertEquals("b", t.get(1).text());
    assertEquals(3, t.size());
  }

  @Test
  void unterminatedBlockCommentThrows() {
    assertThrows(ElmSyntaxError.class, () -> lex("{- never closed"));
  }

  @Test
  void stringEscapes() {
    List<Token> t = lex("\"line\\nbreak\\t\\\"q\\\"\"");
    assertEquals(TokenType.STRING, t.get(0).type());
    assertEquals("line\nbreak\t\"q\"", t.get(0).value());
  }

  @Test
  void unicodeEscape() {
    List<Token> t = lex("\"\\u{1F600}\"");
    assertEquals(new String(Character.toChars(0x1F600)), t.get(0).value());
  }

  @Test
  void multilineString() {
    List<Token> t = lex("\"\"\"a\nb \"not end\" c\"\"\"");
    assertEquals("a\nb \"not end\" c", t.get(0).value());
  }

  @Test
  void unterminatedStringThrows() {
    assertThrows(ElmSyntaxError.class, () -> lex("\"no end"));
    assertThrows(ElmSyntaxError.class, () -> lex("\"newline\nhere\""));
  }

  @Test
  void charLiterals() {
    List<Token> t = lex("'a' '\\n' '\\u{41}'");
    assertEquals(TokenType.CHAR, t.get(0).type());
    assertEquals((int) 'a', t.get(0).value());
    assertEquals((int) '\n', t.get(1).value());
    assertEquals(0x41, t.get(2).value());
  }

  @Test
  void positionsAreTracked() {
    List<Token> t = lex("a\n  b");
    assertEquals(1, t.get(0).line());
    assertEquals(1, t.get(0).col());
    assertEquals(2, t.get(1).line());
    assertEquals(3, t.get(1).col());
  }

  @Test
  void spaceBeforeFlagDistinguishesAccessFromAccessor() {
    // "r.x" : x has no space before -> record access; ".x" alone -> accessor.
    List<Token> access = lex("r.x");
    assertFalse(access.get(1).spaceBefore(), "dot directly follows r");
    assertFalse(access.get(2).spaceBefore(), "field directly follows dot");

    List<Token> spaced = lex("f .x");
    assertTrue(spaced.get(1).spaceBefore(), "dot is preceded by a space");
  }

  @Test
  void realSnippetTokenizes() {
    String src =
        """
        module Main exposing (main)

        import Html exposing (text)

        main =
            text "Hello!"
        """;
    List<TokenType> ts = types(src);
    assertEquals(TokenType.KW_MODULE, ts.get(0));
    assertTrue(ts.contains(TokenType.KW_IMPORT));
    assertTrue(ts.contains(TokenType.EQUALS));
    assertTrue(ts.contains(TokenType.STRING));
  }
}

package pl.matsuo.elm.parser;

import java.util.HashMap;
import java.util.Map;
import pl.matsuo.elm.lexer.Lexer;
import pl.matsuo.elm.lexer.Token;
import pl.matsuo.elm.lexer.TokenType;

/**
 * Operator-precedence data for the {@link Parser}: the built-in (elm/core) fixity table, the default
 * for an undeclared operator, and the pre-scan that gathers a module's (or a project's) {@code infix}
 * declarations. This is pure precedence data, independent of the recursive-descent cursor, so it
 * lives apart from the parser's parsing logic.
 */
public final class OperatorFixities {

  private OperatorFixities() {}

  public enum Assoc {
    LEFT,
    RIGHT,
    NON
  }

  public record Fixity(int prec, Assoc assoc) {}

  /** Built-in (elm/core) and a few widely-used package fixities; a module's own {@code infix}
   * declarations are layered on top per-parser. */
  private static final Map<String, Fixity> DEFAULTS =
      Map.ofEntries(
          Map.entry("<|", new Fixity(0, Assoc.RIGHT)),
          Map.entry("|>", new Fixity(0, Assoc.LEFT)),
          Map.entry("||", new Fixity(2, Assoc.RIGHT)),
          Map.entry("&&", new Fixity(3, Assoc.RIGHT)),
          Map.entry("==", new Fixity(4, Assoc.NON)),
          Map.entry("/=", new Fixity(4, Assoc.NON)),
          Map.entry("<", new Fixity(4, Assoc.NON)),
          Map.entry(">", new Fixity(4, Assoc.NON)),
          Map.entry("<=", new Fixity(4, Assoc.NON)),
          Map.entry(">=", new Fixity(4, Assoc.NON)),
          Map.entry("++", new Fixity(5, Assoc.RIGHT)),
          Map.entry("::", new Fixity(5, Assoc.RIGHT)),
          Map.entry("+", new Fixity(6, Assoc.LEFT)),
          Map.entry("-", new Fixity(6, Assoc.LEFT)),
          Map.entry("*", new Fixity(7, Assoc.LEFT)),
          Map.entry("/", new Fixity(7, Assoc.LEFT)),
          Map.entry("//", new Fixity(7, Assoc.LEFT)),
          Map.entry("^", new Fixity(8, Assoc.RIGHT)),
          Map.entry("<<", new Fixity(9, Assoc.RIGHT)),
          Map.entry(">>", new Fixity(9, Assoc.LEFT)),
          // Widely-used published-package operators, with their declared fixities, so programs
          // using them parse without bundling the package (elm/parser, elm/url).
          Map.entry("|=", new Fixity(5, Assoc.LEFT)), // elm/parser keeper
          Map.entry("|.", new Fixity(6, Assoc.LEFT)), // elm/parser ignorer
          Map.entry("</>", new Fixity(7, Assoc.RIGHT)), // elm/url slash
          Map.entry("<?>", new Fixity(8, Assoc.LEFT))); // elm/url questionMark

  /**
   * Fixity for an operator not in the table. Elm operators are package-declared, so we can't know an
   * undeclared one's precedence; we default to left-associative at the same precedence as
   * {@code <|}/{@code |>} (the lowest), which gives sensible chaining for pipeline-style operators
   * rather than failing to parse.
   */
  public static final Fixity DEFAULT = new Fixity(0, Assoc.LEFT);

  /** A fresh fixity map: the built-in defaults plus a {@code seed} of fixities declared in *other*
   * modules of the same project (op -> {prec, assoc} where assoc is 0=left, 1=right, 2=non). The
   * parser layers the module's own {@code infix} declarations on top. */
  public static Map<String, Fixity> initial(Map<String, int[]> seed) {
    Map<String, Fixity> fixities = new HashMap<>(DEFAULTS);
    seed.forEach((op, pa) -> fixities.put(op, fromCodes(pa[0], pa[1])));
    return fixities;
  }

  /** A {@link Fixity} from the {prec, assoc-code} pair used by {@link #scanFixities} (assoc 0=left,
   * 1=right, 2=non). */
  public static Fixity fromCodes(int prec, int assocCode) {
    return new Fixity(prec, assocCode == 1 ? Assoc.RIGHT : assocCode == 2 ? Assoc.NON : Assoc.LEFT);
  }

  /** Scans a source for its {@code infix} declarations, returning op -> {precedence, assoc} (assoc
   * 0=left, 1=right, 2=non). Used to gather a project's operator fixities before parsing each module. */
  public static Map<String, int[]> scanFixities(String source) {
    return scanTokens(Lexer.tokenize(source));
  }

  /** As {@link #scanFixities(String)} but over an already-tokenised stream. */
  public static Map<String, int[]> scanTokens(java.util.List<Token> toks) {
    Map<String, int[]> out = new HashMap<>();
    for (int i = 0; i + 5 < toks.size(); i++) {
      if (toks.get(i).type() == TokenType.LOWER
          && toks.get(i).text().equals("infix")
          && toks.get(i + 1).type() == TokenType.LOWER
          && toks.get(i + 2).type() == TokenType.INT
          && toks.get(i + 3).type() == TokenType.LPAREN
          && toks.get(i + 4).type() == TokenType.OPERATOR
          && toks.get(i + 5).type() == TokenType.RPAREN) {
        int assoc =
            switch (toks.get(i + 1).text()) {
              case "right" -> 1;
              case "non" -> 2;
              default -> 0;
            };
        out.put(toks.get(i + 4).text(), new int[] {((Long) toks.get(i + 2).value()).intValue(), assoc});
      }
    }
    return out;
  }
}

package pl.matsuo.elm.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.error.ElmSyntaxError;
import pl.matsuo.elm.error.ElmSyntaxErrors;
import pl.matsuo.elm.error.Position;
import pl.matsuo.elm.lexer.Lexer;
import pl.matsuo.elm.lexer.Token;
import pl.matsuo.elm.lexer.TokenType;

/**
 * Recursive-descent parser for Elm, with operator-precedence (Pratt-style) expression parsing and
 * an indentation/offside-rule layout handled via token columns. Built directly on the {@link Lexer}
 * token stream; no separate layout pass is used.
 */
public final class Parser {

  // --- operator fixities (elm/core defaults) -----------------------------

  private enum Assoc {
    LEFT,
    RIGHT,
    NON
  }

  private record Fixity(int prec, Assoc assoc) {}

  /** Built-in (elm/core) and a few widely-used package fixities; a module's own {@code infix}
   * declarations are layered on top per-parser (see {@link #fixities}). */
  private static final Map<String, Fixity> FIXITY =
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
   * Fixity for an operator not in {@link #FIXITY}. Elm operators are package-declared, so we can't
   * know an undeclared one's precedence; we default to left-associative at the same precedence as
   * {@code <|}/{@code |>} (the lowest), which gives sensible chaining for pipeline-style operators
   * rather than failing to parse.
   */
  private static final Fixity DEFAULT_FIXITY = new Fixity(0, Assoc.LEFT);

  // --- token stream ------------------------------------------------------

  private final List<Token> tokens;
  private int p = 0;

  /** Columns strictly less-or-equal to {@code indent} on a fresh line end the current construct. */
  private int indent = 0;

  /** Operator fixities in effect for this parse: the built-in defaults plus any the module declares
   * with {@code infix} (pre-scanned up front so a use before its declaration still parses right). */
  private final Map<String, Fixity> fixities;

  private Parser(List<Token> tokens) {
    this(tokens, Map.of());
  }

  /** {@code seed} carries fixities declared in *other* modules of the same project (op -> {prec,
   * assoc} where assoc is 0=left, 1=right, 2=non), so cross-module custom operators parse with their
   * declared precedence. The module's own {@code infix} declarations are layered on top. */
  private Parser(List<Token> tokens, Map<String, int[]> seed) {
    this.tokens = tokens;
    this.fixities = new java.util.HashMap<>(FIXITY);
    seed.forEach(
        (op, pa) ->
            fixities.put(
                op, new Fixity(pa[0], pa[1] == 1 ? Assoc.RIGHT : pa[1] == 2 ? Assoc.NON : Assoc.LEFT)));
    scanInfixDeclarations();
  }

  /** Scans a source for its {@code infix} declarations, returning op -> {precedence, assoc} (assoc
   * 0=left, 1=right, 2=non). Used to gather a project's operator fixities before parsing each module. */
  public static Map<String, int[]> scanFixities(String source) {
    List<Token> toks = Lexer.tokenize(source);
    Map<String, int[]> out = new java.util.HashMap<>();
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

  /** Pre-scans the token stream for {@code infix <assoc> <prec> (<op>)} declarations and records
   * their fixities, so operator-precedence parsing of expressions uses the declared precedence even
   * when the {@code infix} line comes after (or is interleaved with) the expressions that use it. */
  private void scanInfixDeclarations() {
    for (int i = 0; i + 5 < tokens.size(); i++) {
      if (tokens.get(i).type() == TokenType.LOWER
          && tokens.get(i).text().equals("infix")
          && tokens.get(i + 1).type() == TokenType.LOWER
          && tokens.get(i + 2).type() == TokenType.INT
          && tokens.get(i + 3).type() == TokenType.LPAREN
          && tokens.get(i + 4).type() == TokenType.OPERATOR
          && tokens.get(i + 5).type() == TokenType.RPAREN) {
        Assoc assoc =
            switch (tokens.get(i + 1).text()) {
              case "right" -> Assoc.RIGHT;
              case "non" -> Assoc.NON;
              default -> Assoc.LEFT;
            };
        int prec = ((Long) tokens.get(i + 2).value()).intValue();
        fixities.put(tokens.get(i + 4).text(), new Fixity(prec, assoc));
      }
    }
  }

  // --- public entry points ----------------------------------------------

  public static Module parseModule(String source) {
    return new Parser(Lexer.tokenize(source)).parseModuleInternal();
  }

  /** Parses a module given the fixities declared elsewhere in the project (see {@link #scanFixities}),
   * so an operator defined in one module parses with its declared precedence in another. */
  public static Module parseModule(String source, Map<String, int[]> projectFixities) {
    return new Parser(Lexer.tokenize(source), projectFixities).parseModuleInternal();
  }

  /** Parses a single expression (used by tests and the REPL). */
  public static Expr parseExpression(String source) {
    Parser parser = new Parser(Lexer.tokenize(source));
    Expr e = parser.parseExpr();
    parser.expect(TokenType.EOF, "end of input");
    return e;
  }

  /** Parses a type signature string (used by the type checker's prelude signatures). */
  public static Type parseTypeSignature(String source) {
    Parser parser = new Parser(Lexer.tokenize(source));
    Type t = parser.parseType();
    parser.expect(TokenType.EOF, "end of input");
    return t;
  }

  // --- token helpers -----------------------------------------------------

  private Token peek() {
    return tokens.get(p);
  }

  private Token peek(int ahead) {
    int i = Math.min(p + ahead, tokens.size() - 1);
    return tokens.get(i);
  }

  private boolean check(TokenType type) {
    return peek().type() == type;
  }

  private boolean checkOp(String op) {
    return peek().type() == TokenType.OPERATOR && peek().text().equals(op);
  }

  private Token advance() {
    Token t = tokens.get(p);
    if (t.type() != TokenType.EOF) {
      p++;
    }
    return t;
  }

  private boolean match(TokenType type) {
    if (check(type)) {
      advance();
      return true;
    }
    return false;
  }

  private Token expect(TokenType type, String what) {
    if (check(type)) {
      return advance();
    }
    throw new ElmSyntaxError("Expected " + what + " but found " + peek(), peek().start());
  }

  private ElmSyntaxError error(String message) {
    return new ElmSyntaxError(message + " but found " + peek(), peek().start());
  }

  // --- layout ------------------------------------------------------------

  private boolean atNewLine() {
    if (p == 0) {
      return true;
    }
    return peek().line() > tokens.get(p - 1).line();
  }

  /** Whether the next token continues the current construct under the offside rule. */
  private boolean continues() {
    return !atNewLine() || peek().col() > indent;
  }

  private <T> T withIndent(int newIndent, Supplier<T> body) {
    int saved = indent;
    indent = newIndent;
    try {
      return body.get();
    } finally {
      indent = saved;
    }
  }

  // --- module ------------------------------------------------------------

  private record Ann(String name, Type type) {}

  private Module parseModuleInternal() {
    Position pos = peek().start();

    // Optional module header: [port] module Name exposing (...)
    String moduleName = "Main";
    Module.Exposing exposing = Module.Exposing.ALL;
    if (check(TokenType.KW_PORT) && peek(1).type() == TokenType.KW_MODULE) {
      advance();
    }
    if (check(TokenType.KW_MODULE)) {
      advance();
      moduleName = parseModuleName();
      if (match(TokenType.KW_EXPOSING)) {
        exposing = parseExposing();
      }
    }

    List<Module.Import> imports = new ArrayList<>();
    while (check(TokenType.KW_IMPORT)) {
      imports.add(parseImport());
    }

    List<Decl> decls = check(TokenType.EOF) ? List.of() : parseTopLevelDecls(peek().col());
    expect(TokenType.EOF, "end of input");
    return new Module(moduleName, exposing, imports, decls, pos);
  }

  /**
   * Parses the module's top-level declarations with error recovery: a declaration that fails to
   * parse is recorded and the parser skips to the next top-level declaration and continues, so one
   * parse reports every independent syntax error. Throws the single error unchanged when there is
   * exactly one (preserving existing messages), or an {@link ElmSyntaxErrors} when there are several.
   */
  private List<Decl> parseTopLevelDecls(int col) {
    List<Decl> decls = new ArrayList<>();
    List<ElmSyntaxError> errors = new ArrayList<>();
    Ann pending = null;
    while (!check(TokenType.EOF)) {
      try {
        Object item = withIndent(col, () -> parseOneDecl(false));
        if (item instanceof Ann a) {
          pending = a;
        } else {
          Decl d = (Decl) item;
          if (d instanceof Decl.Value v && pending != null && pending.name().equals(v.name())) {
            d = new Decl.Value(v.name(), v.params(), v.body(), Optional.of(pending.type()), v.pos());
          }
          pending = null;
          decls.add(d);
        }
        if (check(TokenType.EOF) || (atNewLine() && peek().col() == col)) {
          continue; // cleanly at the next top-level declaration (or end)
        }
        // Anything else after a declaration is unexpected; record it and resynchronise.
        errors.add(error("Unexpected token after declaration"));
        recoverToNextTopLevel(col);
        pending = null;
      } catch (ElmSyntaxError e) {
        errors.add(e);
        pending = null;
        recoverToNextTopLevel(col);
      }
    }
    if (errors.size() == 1) {
      throw errors.get(0);
    }
    if (!errors.isEmpty()) {
      StringBuilder b = new StringBuilder(errors.size() + " syntax errors:");
      for (ElmSyntaxError e : errors) {
        b.append("\n  - ").append(e.getMessage());
      }
      throw new ElmSyntaxErrors(b.toString(), errors);
    }
    return decls;
  }

  /** Skips tokens until the start of the next top-level declaration (a new line at column {@code
   * col} or shallower) or end of input. Always consumes at least one token, guaranteeing progress. */
  private void recoverToNextTopLevel(int col) {
    if (!check(TokenType.EOF)) {
      advance();
    }
    while (!check(TokenType.EOF) && !(atNewLine() && peek().col() <= col)) {
      advance();
    }
  }

  private String parseModuleName() {
    StringBuilder sb = new StringBuilder(expect(TokenType.UPPER, "module name").text());
    while (check(TokenType.DOT) && !peek().spaceBefore() && peek(1).type() == TokenType.UPPER) {
      advance();
      sb.append('.').append(advance().text());
    }
    return sb.toString();
  }

  private Module.Import parseImport() {
    Position pos = peek().start();
    expect(TokenType.KW_IMPORT, "import");
    String module = parseModuleName();
    Optional<String> alias = Optional.empty();
    if (match(TokenType.KW_AS)) {
      alias = Optional.of(expect(TokenType.UPPER, "import alias").text());
    }
    Module.Exposing exposing = new Module.Exposing(false, List.of());
    if (match(TokenType.KW_EXPOSING)) {
      exposing = parseExposing();
    }
    return new Module.Import(module, alias, exposing, pos);
  }

  private Module.Exposing parseExposing() {
    expect(TokenType.LPAREN, "'('");
    if (match(TokenType.DOTDOT)) {
      expect(TokenType.RPAREN, "')'");
      return Module.Exposing.ALL;
    }
    List<String> names = new ArrayList<>();
    do {
      if (check(TokenType.LOWER) || check(TokenType.UPPER)) {
        names.add(advance().text());
        // Optional constructor exposing list after a type name: Type(..) or Type(A, B)
        if (check(TokenType.LPAREN)) {
          skipBalancedParens();
        }
      } else if (check(TokenType.LPAREN)) {
        // Exposed operator: (++)
        advance();
        names.add(expect(TokenType.OPERATOR, "operator").text());
        expect(TokenType.RPAREN, "')'");
      } else {
        throw error("Expected an exposed name");
      }
    } while (match(TokenType.COMMA));
    expect(TokenType.RPAREN, "')'");
    return new Module.Exposing(false, names);
  }

  private void skipBalancedParens() {
    expect(TokenType.LPAREN, "'('");
    int depth = 1;
    while (depth > 0 && !check(TokenType.EOF)) {
      if (check(TokenType.LPAREN)) {
        depth++;
      } else if (check(TokenType.RPAREN)) {
        depth--;
      }
      advance();
    }
  }

  // --- declarations ------------------------------------------------------

  private List<Decl> parseDeclList(int col, boolean allowDestructure) {
    List<Decl> decls = new ArrayList<>();
    Ann pending = null;
    while (!atDeclTerminator(col)) {
      Object item = withIndent(col, () -> parseOneDecl(allowDestructure));
      if (item instanceof Ann a) {
        pending = a;
      } else {
        Decl d = (Decl) item;
        if (d instanceof Decl.Value v && pending != null && pending.name().equals(v.name())) {
          d = new Decl.Value(v.name(), v.params(), v.body(), Optional.of(pending.type()), v.pos());
        }
        pending = null;
        decls.add(d);
      }
      if (atDeclTerminator(col)) {
        break;
      }
      if (atNewLine() && peek().col() == col) {
        continue;
      }
      if (atNewLine() && peek().col() < col) {
        break;
      }
      throw error("Unexpected token after declaration");
    }
    return decls;
  }

  private boolean atDeclTerminator(int col) {
    if (check(TokenType.EOF) || check(TokenType.KW_IN)) {
      return true;
    }
    return atNewLine() && peek().col() < col;
  }

  /** Returns either a {@link Decl} or an {@link Ann} (a standalone type annotation). */
  private Object parseOneDecl(boolean allowDestructure) {
    Position pos = peek().start();
    return switch (peek().type()) {
      case KW_TYPE -> parseTypeDecl(pos);
      case KW_PORT -> parsePortDecl(pos);
      case LOWER ->
          // `infix <assoc> <prec> (<op>) = <function>` declares a custom operator (fixity already
          // recorded by the pre-scan); bind the operator name to its implementing function.
          (peek().text().equals("infix")
                  && peek(1).type() == TokenType.LOWER
                  && peek(2).type() == TokenType.INT)
              ? parseInfixDecl(pos)
              : parseValueOrAnnotation(pos);
      default -> {
        // A user/package-defined infix operator definition: `(op) a b = …` or `(op) : Type`.
        if (check(TokenType.LPAREN)
            && peek(1).type() == TokenType.OPERATOR
            && peek(2).type() == TokenType.RPAREN) {
          yield parseOperatorDef(pos);
        }
        if (allowDestructure) {
          Pattern pattern = parsePattern();
          expect(TokenType.EQUALS, "'='");
          Expr body = parseExpr();
          yield new Decl.Destructure(pattern, body, pos);
        }
        throw error("Expected a declaration");
      }
    };
  }

  /** An `infix` fixity declaration: `infix left 6 (+++) = addDouble`. Records nothing further about
   * the fixity (the pre-scan did that) and binds the operator name to its implementing function so
   * `a +++ b` resolves like a normal call across every backend. */
  private Object parseInfixDecl(Position pos) {
    expect(TokenType.LOWER, "infix"); // 'infix'
    advance(); // associativity (left/right/non)
    advance(); // precedence (int)
    expect(TokenType.LPAREN, "'('");
    String op = expect(TokenType.OPERATOR, "operator").text();
    expect(TokenType.RPAREN, "')'");
    expect(TokenType.EQUALS, "'='");
    String fn = expect(TokenType.LOWER, "implementing function name").text();
    return new Decl.Value(op, List.of(), new Expr.Var(null, fn, pos), Optional.empty(), pos);
  }

  /** An operator definition `(op) a b = body` (or its annotation `(op) : Type`). */
  private Object parseOperatorDef(Position pos) {
    expect(TokenType.LPAREN, "'('");
    String op = expect(TokenType.OPERATOR, "operator").text();
    expect(TokenType.RPAREN, "')'");
    if (match(TokenType.COLON)) {
      return new Ann(op, parseType());
    }
    List<Pattern> params = new ArrayList<>();
    while (!check(TokenType.EQUALS)) {
      params.add(parsePatternAtom());
    }
    expect(TokenType.EQUALS, "'='");
    return new Decl.Value(op, params, parseExpr(), Optional.empty(), pos);
  }

  private Object parseValueOrAnnotation(Position pos) {
    String name = expect(TokenType.LOWER, "name").text();
    if (match(TokenType.COLON)) {
      return new Ann(name, parseType());
    }
    List<Pattern> params = new ArrayList<>();
    while (!check(TokenType.EQUALS)) {
      params.add(parsePatternAtom());
    }
    expect(TokenType.EQUALS, "'='");
    Expr body = parseExpr();
    return new Decl.Value(name, params, body, Optional.empty(), pos);
  }

  private Decl parseTypeDecl(Position pos) {
    expect(TokenType.KW_TYPE, "type");
    if (check(TokenType.LOWER) && peek().text().equals("alias")) {
      advance();
      String name = expect(TokenType.UPPER, "type name").text();
      List<String> params = new ArrayList<>();
      while (check(TokenType.LOWER)) {
        params.add(advance().text());
      }
      expect(TokenType.EQUALS, "'='");
      return new Decl.TypeAlias(name, params, parseType(), pos);
    }
    String name = expect(TokenType.UPPER, "type name").text();
    List<String> params = new ArrayList<>();
    while (check(TokenType.LOWER)) {
      params.add(advance().text());
    }
    expect(TokenType.EQUALS, "'='");
    List<Decl.Union.Variant> variants = new ArrayList<>();
    variants.add(parseVariant());
    while (check(TokenType.PIPE)) {
      advance();
      variants.add(parseVariant());
    }
    return new Decl.Union(name, params, variants, pos);
  }

  private Decl.Union.Variant parseVariant() {
    String name = expect(TokenType.UPPER, "constructor name").text();
    List<Type> args = new ArrayList<>();
    while (continues() && startsTypeAtom()) {
      args.add(parseTypeAtom());
    }
    return new Decl.Union.Variant(name, args);
  }

  private Decl parsePortDecl(Position pos) {
    expect(TokenType.KW_PORT, "port");
    String name = expect(TokenType.LOWER, "port name").text();
    expect(TokenType.COLON, "':'");
    return new Decl.Port(name, parseType(), pos);
  }

  // --- expressions -------------------------------------------------------

  private Expr parseExpr() {
    return parseBinary(0);
  }

  private Expr parseBinary(int minPrec) {
    Expr left = parseOperand();
    while (continues() && check(TokenType.OPERATOR)) {
      String op = peek().text();
      Fixity fx = fixities.getOrDefault(op, DEFAULT_FIXITY);
      if (fx.prec() < minPrec) {
        break;
      }
      advance();
      int nextMin = fx.assoc() == Assoc.RIGHT ? fx.prec() : fx.prec() + 1;
      Expr right = parseBinary(nextMin);
      left = new Expr.BinOp(op, left, right, left.pos());
    }
    return left;
  }

  private Expr parseOperand() {
    if (checkOp("-")) {
      Position pos = peek().start();
      advance();
      return new Expr.Negate(parseOperand(), pos);
    }
    return parseApplication();
  }

  private Expr parseApplication() {
    Expr fn = parseAtom();
    while (continues()) {
      // Elm negation as an argument: `f -x` (space before `-`, none after) means `f (-x)`.
      if (checkOp("-") && peek().spaceBefore() && !peek(1).spaceBefore() && startsAtom(peek(1))) {
        Position pos = peek().start();
        advance();
        fn = new Expr.App(fn, new Expr.Negate(parseAtom(), pos), fn.pos());
        continue;
      }
      if (startsAtomArg()) {
        fn = new Expr.App(fn, parseAtom(), fn.pos());
        continue;
      }
      break;
    }
    return fn;
  }

  private boolean startsAtomArg() {
    return switch (peek().type()) {
      case INT, FLOAT, STRING, CHAR, LOWER, UPPER, LPAREN, LBRACKET, LBRACE -> true;
      case DOT -> peek(1).type() == TokenType.LOWER && !peek(1).spaceBefore();
      default -> false;
    };
  }

  private static boolean startsAtom(Token t) {
    return switch (t.type()) {
      case INT, FLOAT, STRING, CHAR, LOWER, UPPER, LPAREN, LBRACKET, LBRACE -> true;
      default -> false;
    };
  }

  private Expr parseAtom() {
    Token t = peek();
    Position pos = t.start();
    Expr atom =
        switch (t.type()) {
          case INT -> {
            advance();
            yield new Expr.IntLit((Long) t.value(), pos);
          }
          case FLOAT -> {
            advance();
            yield new Expr.FloatLit((Double) t.value(), pos);
          }
          case STRING -> {
            advance();
            yield new Expr.StrLit((String) t.value(), pos);
          }
          case CHAR -> {
            advance();
            yield new Expr.CharLit((Integer) t.value(), pos);
          }
          case GLSL -> {
            advance();
            yield new Expr.Shader((String) t.value(), pos);
          }
          case LOWER -> {
            advance();
            yield new Expr.Var(null, t.text(), pos);
          }
          case UPPER -> parseQualified(pos);
          case DOT -> {
            advance();
            yield new Expr.Accessor(expect(TokenType.LOWER, "field name").text(), pos);
          }
          case LPAREN -> parseParen(pos);
          case LBRACKET -> parseListLiteral(pos);
          case LBRACE -> parseRecordLiteral(pos);
          case KW_IF -> parseIf(pos);
          case KW_CASE -> parseCase(pos);
          case KW_LET -> parseLet(pos);
          case BACKSLASH -> parseLambda(pos);
          default -> throw error("Expected an expression");
        };
    return parsePostfixAccess(atom);
  }

  /** Parses a qualified name beginning with an uppercase segment (a {@code Var} or {@code Ctor}). */
  private Expr parseQualified(Position pos) {
    List<String> segments = new ArrayList<>();
    segments.add(advance().text());
    while (noSpaceDot() && peek(1).type() == TokenType.UPPER && !peek(1).spaceBefore()) {
      advance();
      segments.add(advance().text());
    }
    if (noSpaceDot() && peek(1).type() == TokenType.LOWER && !peek(1).spaceBefore()) {
      advance();
      String name = advance().text();
      return new Expr.Var(String.join(".", segments), name, pos);
    }
    String name = segments.get(segments.size() - 1);
    String module =
        segments.size() > 1 ? String.join(".", segments.subList(0, segments.size() - 1)) : null;
    return new Expr.Ctor(module, name, pos);
  }

  private boolean noSpaceDot() {
    return check(TokenType.DOT) && !peek().spaceBefore();
  }

  private Expr parsePostfixAccess(Expr e) {
    while (noSpaceDot() && peek(1).type() == TokenType.LOWER && !peek(1).spaceBefore()) {
      advance();
      String field = advance().text();
      e = new Expr.RecordAccess(e, field, e.pos());
    }
    return e;
  }

  private Expr parseParen(Position pos) {
    advance(); // (
    if (match(TokenType.RPAREN)) {
      return new Expr.Unit(pos);
    }
    if (check(TokenType.OPERATOR) && peek(1).type() == TokenType.RPAREN) {
      String op = advance().text();
      advance(); // )
      return new Expr.OpFunc(op, pos);
    }
    return withIndent(
        0,
        () -> {
          Expr first = parseExpr();
          if (check(TokenType.COMMA)) {
            List<Expr> items = new ArrayList<>();
            items.add(first);
            while (match(TokenType.COMMA)) {
              items.add(parseExpr());
            }
            expect(TokenType.RPAREN, "')'");
            return new Expr.Tuple(items, pos);
          }
          expect(TokenType.RPAREN, "')'");
          return first;
        });
  }

  private Expr parseListLiteral(Position pos) {
    advance(); // [
    List<Expr> items = new ArrayList<>();
    if (!check(TokenType.RBRACKET)) {
      withIndent(
          0,
          () -> {
            items.add(parseExpr());
            while (match(TokenType.COMMA)) {
              items.add(parseExpr());
            }
            return null;
          });
    }
    expect(TokenType.RBRACKET, "']'");
    return new Expr.ListLit(items, pos);
  }

  private Expr parseRecordLiteral(Position pos) {
    advance(); // {
    if (match(TokenType.RBRACE)) {
      return new Expr.Record(List.of(), pos);
    }
    return withIndent(
        0,
        () -> {
          // Record update: { base | field = value, ... }
          if (check(TokenType.LOWER) && peek(1).type() == TokenType.PIPE) {
            String base = advance().text();
            advance(); // |
            List<Expr.Record.Field> fields = parseRecordFields();
            expect(TokenType.RBRACE, "'}'");
            return new Expr.RecordUpdate(base, fields, pos);
          }
          List<Expr.Record.Field> fields = parseRecordFields();
          expect(TokenType.RBRACE, "'}'");
          return new Expr.Record(fields, pos);
        });
  }

  private List<Expr.Record.Field> parseRecordFields() {
    List<Expr.Record.Field> fields = new ArrayList<>();
    do {
      String name = expect(TokenType.LOWER, "field name").text();
      expect(TokenType.EQUALS, "'='");
      fields.add(new Expr.Record.Field(name, parseExpr()));
    } while (match(TokenType.COMMA));
    return fields;
  }

  private Expr parseIf(Position pos) {
    expect(TokenType.KW_IF, "if");
    Expr cond = parseExpr();
    expect(TokenType.KW_THEN, "then");
    Expr thenBranch = parseExpr();
    expect(TokenType.KW_ELSE, "else");
    Expr elseBranch = parseExpr();
    return new Expr.If(cond, thenBranch, elseBranch, pos);
  }

  private Expr parseLambda(Position pos) {
    expect(TokenType.BACKSLASH, "'\\'");
    List<Pattern> params = new ArrayList<>();
    while (!check(TokenType.ARROW)) {
      params.add(parsePatternAtom());
    }
    expect(TokenType.ARROW, "'->'");
    return new Expr.Lambda(params, parseExpr(), pos);
  }

  private Expr parseLet(Position pos) {
    expect(TokenType.KW_LET, "let");
    int defCol = peek().col();
    List<Decl> defs = parseDeclList(defCol, true);
    expect(TokenType.KW_IN, "in");
    return new Expr.Let(defs, parseExpr(), pos);
  }

  private Expr parseCase(Position pos) {
    expect(TokenType.KW_CASE, "case");
    Expr scrutinee = withIndent(0, this::parseExpr);
    expect(TokenType.KW_OF, "of");
    int branchCol = peek().col();
    List<Expr.Case.Branch> branches = new ArrayList<>();
    while (true) {
      Pattern pattern = withIndent(branchCol, this::parsePattern);
      expect(TokenType.ARROW, "'->'");
      Expr body = withIndent(branchCol, this::parseExpr);
      branches.add(new Expr.Case.Branch(pattern, body));
      if (atNewLine() && peek().col() == branchCol && startsPatternAtom()) {
        continue;
      }
      break;
    }
    return new Expr.Case(scrutinee, branches, pos);
  }

  // --- patterns ----------------------------------------------------------

  private Pattern parsePattern() {
    Pattern p = parsePatternCons();
    if (match(TokenType.KW_AS)) {
      return new Pattern.Alias(p, expect(TokenType.LOWER, "alias name").text());
    }
    return p;
  }

  private Pattern parsePatternCons() {
    Pattern head = parsePatternApp();
    if (checkOp("::")) {
      advance();
      return new Pattern.Cons(head, parsePatternCons());
    }
    return head;
  }

  private Pattern parsePatternApp() {
    if (check(TokenType.UPPER)) {
      String[] qn = parseQualifiedCtorName();
      List<Pattern> args = new ArrayList<>();
      while (startsPatternAtom()) {
        args.add(parsePatternAtom());
      }
      return new Pattern.Ctor(qn[0], qn[1], args);
    }
    return parsePatternAtom();
  }

  private String[] parseQualifiedCtorName() {
    List<String> segments = new ArrayList<>();
    segments.add(advance().text()); // first UPPER
    while (noSpaceDot() && peek(1).type() == TokenType.UPPER && !peek(1).spaceBefore()) {
      advance();
      segments.add(advance().text());
    }
    String name = segments.get(segments.size() - 1);
    String module =
        segments.size() > 1 ? String.join(".", segments.subList(0, segments.size() - 1)) : null;
    return new String[] {module, name};
  }

  private boolean startsPatternAtom() {
    return switch (peek().type()) {
      case UNDERSCORE, LOWER, INT, STRING, CHAR, UPPER, LPAREN, LBRACKET, LBRACE -> true;
      default -> false;
    };
  }

  private Pattern parsePatternAtom() {
    Token t = peek();
    switch (t.type()) {
      case UNDERSCORE -> {
        advance();
        return new Pattern.Wildcard();
      }
      case LOWER -> {
        advance();
        return new Pattern.Var(t.text());
      }
      case INT -> {
        advance();
        return new Pattern.IntLit((Long) t.value());
      }
      case STRING -> {
        advance();
        return new Pattern.StrLit((String) t.value());
      }
      case CHAR -> {
        advance();
        return new Pattern.CharLit((Integer) t.value());
      }
      case UPPER -> {
        String[] qn = parseQualifiedCtorName();
        return new Pattern.Ctor(qn[0], qn[1], List.of());
      }
      case LPAREN -> {
        return parseParenPattern();
      }
      case LBRACKET -> {
        return parseListPattern();
      }
      case LBRACE -> {
        return parseRecordPattern();
      }
      default -> throw error("Expected a pattern");
    }
  }

  private Pattern parseParenPattern() {
    advance(); // (
    if (match(TokenType.RPAREN)) {
      return new Pattern.Unit();
    }
    Pattern first = parsePattern();
    if (check(TokenType.COMMA)) {
      List<Pattern> items = new ArrayList<>();
      items.add(first);
      while (match(TokenType.COMMA)) {
        items.add(parsePattern());
      }
      expect(TokenType.RPAREN, "')'");
      return new Pattern.Tuple(items);
    }
    expect(TokenType.RPAREN, "')'");
    return first;
  }

  private Pattern parseListPattern() {
    advance(); // [
    List<Pattern> items = new ArrayList<>();
    if (!check(TokenType.RBRACKET)) {
      items.add(parsePattern());
      while (match(TokenType.COMMA)) {
        items.add(parsePattern());
      }
    }
    expect(TokenType.RBRACKET, "']'");
    return new Pattern.ListPat(items);
  }

  private Pattern parseRecordPattern() {
    advance(); // {
    List<String> fields = new ArrayList<>();
    if (!check(TokenType.RBRACE)) {
      fields.add(expect(TokenType.LOWER, "field name").text());
      while (match(TokenType.COMMA)) {
        fields.add(expect(TokenType.LOWER, "field name").text());
      }
    }
    expect(TokenType.RBRACE, "'}'");
    return new Pattern.RecordPat(fields);
  }

  // --- types -------------------------------------------------------------

  private Type parseType() {
    Type left = parseTypeApp();
    if (match(TokenType.ARROW)) {
      return new Type.Arrow(left, parseType());
    }
    return left;
  }

  private Type parseTypeApp() {
    Type head = parseTypeAtom();
    if (head instanceof Type.Con con && con.args().isEmpty()) {
      List<Type> args = new ArrayList<>();
      while (continues() && startsTypeAtom()) {
        args.add(parseTypeAtom());
      }
      if (!args.isEmpty()) {
        return new Type.Con(con.module(), con.name(), args);
      }
    }
    return head;
  }

  private boolean startsTypeAtom() {
    return switch (peek().type()) {
      case LOWER, UPPER, LPAREN, LBRACE -> true;
      default -> false;
    };
  }

  private Type parseTypeAtom() {
    switch (peek().type()) {
      case LOWER -> {
        return new Type.Var(advance().text());
      }
      case UPPER -> {
        String[] qn = parseQualifiedCtorName();
        return new Type.Con(qn[0], qn[1], List.of());
      }
      case LPAREN -> {
        return parseParenType();
      }
      case LBRACE -> {
        return parseRecordType();
      }
      default -> throw error("Expected a type");
    }
  }

  private Type parseParenType() {
    advance(); // (
    if (match(TokenType.RPAREN)) {
      return new Type.Unit();
    }
    return withIndent(
        0,
        () -> {
          Type first = parseType();
          if (check(TokenType.COMMA)) {
            List<Type> items = new ArrayList<>();
            items.add(first);
            while (match(TokenType.COMMA)) {
              items.add(parseType());
            }
            expect(TokenType.RPAREN, "')'");
            return new Type.Tuple(items);
          }
          expect(TokenType.RPAREN, "')'");
          return first;
        });
  }

  private Type parseRecordType() {
    advance(); // {
    if (match(TokenType.RBRACE)) {
      return new Type.Record(Optional.empty(), List.of());
    }
    return withIndent(
        0,
        () -> {
          Optional<String> base = Optional.empty();
          if (check(TokenType.LOWER) && peek(1).type() == TokenType.PIPE) {
            base = Optional.of(advance().text());
            advance(); // |
          }
          List<Type.Record.Field> fields = new ArrayList<>();
          do {
            String name = expect(TokenType.LOWER, "field name").text();
            expect(TokenType.COLON, "':'");
            fields.add(new Type.Record.Field(name, parseType()));
          } while (match(TokenType.COMMA));
          expect(TokenType.RBRACE, "'}'");
          return new Type.Record(base, fields);
        });
  }
}

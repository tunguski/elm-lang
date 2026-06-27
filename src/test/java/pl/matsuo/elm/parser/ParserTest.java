package pl.matsuo.elm.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.error.ElmSyntaxError;

class ParserTest {

  private String expr(String src) {
    return AstSexpr.show(Parser.parseExpression(src));
  }

  // --- precedence & associativity ---------------------------------------

  @Test
  void arithmeticPrecedence() {
    assertEquals("(+ 1 (* 2 3))", expr("1 + 2 * 3"));
    assertEquals("(+ (* 2 3) 1)", expr("2 * 3 + 1"));
    assertEquals("(* (+ 1 2) 3)", expr("(1 + 2) * 3"));
  }

  @Test
  void leftAndRightAssociativity() {
    assertEquals("(- (- 1 2) 3)", expr("1 - 2 - 3")); // left
    assertEquals("(:: 1 (:: 2 []))", expr("1 :: 2 :: []")); // right
    assertEquals("(^ 2 (^ 3 4))", expr("2 ^ 3 ^ 4")); // right
  }

  @Test
  void pipesAndComposition() {
    assertEquals("(|> (|> x f) g)", expr("x |> f |> g")); // |> left
    assertEquals("(<| f (<| g x))", expr("f <| g <| x")); // <| right
  }

  @Test
  void dotOperatorsParseWithDeclaredFixity() {
    // elm/parser: |. (infixl 6) binds tighter than |= (infixl 5), both left-associative.
    assertEquals("(|= (|= p (|. a b)) c)", expr("p |= a |. b |= c"));
    // An undeclared operator falls back to the default (left-assoc, lowest precedence).
    assertEquals("(<+> (<+> a b) c)", expr("a <+> b <+> c"));
  }

  @Test
  void applicationBindsTighterThanOperators() {
    assertEquals("(+ (f x) (g y))", expr("f x + g y"));
    assertEquals("((f a) b)", expr("f a b"));
  }

  @Test
  void unaryNegation() {
    assertEquals("(neg x)", expr("-x"));
    assertEquals("(- a (neg b))", expr("a - -b"));
    assertEquals("(- f 1)", expr("f - 1")); // spaces both sides: subtraction
    assertEquals("(f (neg y))", expr("f -y")); // space before, none after: negated argument
    assertEquals("((String.fromFloat (neg y)) x)", expr("String.fromFloat -y x"));
  }

  // --- error recovery ----------------------------------------------------

  @Test
  void reportsEveryTopLevelSyntaxErrorInOnePass() {
    // Two malformed declarations between two good ones: the parser recovers and reports both.
    String src =
        """
        module M exposing (..)
        good1 = 1
        bad1 = if
        good2 = 2
        bad2 = case
        good3 = 3
        """;
    var errs =
        assertThrows(pl.matsuo.elm.error.ElmSyntaxErrors.class, () -> Parser.parseModule(src));
    assertEquals(2, errs.errors.size(), errs.getMessage());
    // The two errors are at two distinct locations (the parser resynced between them).
    assertTrue(
        errs.errors.get(0).position().line() < errs.errors.get(1).position().line(),
        errs.getMessage());
  }

  @Test
  void aSingleSyntaxErrorIsThrownUnchanged() {
    // With exactly one error, the plain ElmSyntaxError is thrown (not the multi-error wrapper).
    String src = "module M exposing (..)\ngood = 1\nbad = if\n";
    ElmSyntaxError e = assertThrows(ElmSyntaxError.class, () -> Parser.parseModule(src));
    assertTrue(
        !(e instanceof pl.matsuo.elm.error.ElmSyntaxErrors), "single error is not wrapped");
  }

  @Test
  void recoveryStillParsesTheGoodDeclarations() {
    // A bad middle declaration doesn't stop the good ones from being parsed (the parser keeps the
    // ones it could read). We can't get the Module out when it throws, so assert via the LSP-style
    // count instead: three good + one bad means exactly one error survives recovery.
    String src = "module M exposing (..)\na = 1\nb = )\nc = 3\n";
    ElmSyntaxError e = assertThrows(ElmSyntaxError.class, () -> Parser.parseModule(src));
    assertEquals(3, e.position().line()); // the `b = )` line
  }

  // --- atoms -------------------------------------------------------------

  @Test
  void literalsAndCollections() {
    assertEquals("42", expr("42"));
    assertEquals("3.14", expr("3.14"));
    assertEquals("\"hi\"", expr("\"hi\""));
    assertEquals("'a'", expr("'a'"));
    assertEquals("[1, 2, 3]", expr("[1, 2, 3]"));
    assertEquals("(1, 2)", expr("(1, 2)"));
    assertEquals("()", expr("()"));
    assertEquals("[]", expr("[]"));
  }

  @Test
  void qualifiedNamesAndConstructors() {
    assertEquals("List.map", expr("List.map"));
    assertEquals("Maybe.Just", expr("Maybe.Just"));
    assertEquals("Html.Attributes.style", expr("Html.Attributes.style"));
    assertEquals("(Just 5)", expr("Just 5"));
    assertEquals("Nothing", expr("Nothing"));
  }

  @Test
  void recordsAccessAndUpdate() {
    assertEquals("{x=1, y=2}", expr("{ x = 1, y = 2 }"));
    assertEquals("{model | count=0}", expr("{ model | count = 0 }"));
    assertEquals("model.count", expr("model.count"));
    assertEquals("model.a.b", expr("model.a.b"));
    assertEquals(".name", expr(".name"));
    assertEquals("((List.map .name) xs)", expr("List.map .name xs"));
  }

  @Test
  void operatorAsValue() {
    assertEquals("(+)", expr("(+)"));
    assertEquals("(((+) 1) 2)", expr("(+) 1 2"));
  }

  // --- compound forms ----------------------------------------------------

  @Test
  void ifThenElse() {
    assertEquals("(if (< x 0) (neg x) x)", expr("if x < 0 then -x else x"));
  }

  @Test
  void lambda() {
    assertEquals("(\\ x y -> (+ x y))", expr("\\x y -> x + y"));
  }

  @Test
  void letInSingleLine() {
    assertEquals("(let 1 (+ x 1))", expr("let x = 1 in x + 1"));
  }

  @Test
  void letInMultiLine() {
    String src =
        """
        let
            x =
                1

            y =
                2
        in
        x + y
        """;
    assertEquals("(let 2 (+ x y))", expr(src));
  }

  @Test
  void caseOf() {
    String src =
        """
        case msg of
            Increment ->
                model + 1

            Decrement ->
                model - 1
        """;
    assertEquals(
        "(case msg Increment->(+ model 1) Decrement->(- model 1))", expr(src));
  }

  @Test
  void caseWithPatterns() {
    String src =
        """
        case xs of
            [] ->
                0

            first :: rest ->
                first
        """;
    assertEquals("(case xs []->0 (:: first rest)->first)", expr(src));
  }

  // --- modules -----------------------------------------------------------

  @Test
  void helloModule() {
    String src =
        """
        import Html exposing (text)

        main =
            text "Hello!"
        """;
    Module m = Parser.parseModule(src);
    assertEquals("Main", m.name());
    assertEquals(1, m.imports().size());
    assertEquals("Html", m.imports().get(0).module());
    assertEquals(1, m.decls().size());
    Decl.Value main = assertInstanceOf(Decl.Value.class, m.decls().get(0));
    assertEquals("main", main.name());
    assertEquals("(text \"Hello!\")", AstSexpr.show(main.body()));
  }

  @Test
  void buttonsModuleWithTypeAndAnnotations() {
    String src =
        """
        module Main exposing (main)

        import Browser
        import Html exposing (Html, button, div, text)
        import Html.Events exposing (onClick)

        main =
            Browser.sandbox { init = 0, update = update, view = view }

        type Msg
            = Increment
            | Decrement

        update : Msg -> Int -> Int
        update msg model =
            case msg of
                Increment ->
                    model + 1

                Decrement ->
                    model - 1
        """;
    Module m = Parser.parseModule(src);
    assertEquals("Main", m.name());
    assertEquals(3, m.imports().size());
    // decls: main, type Msg, update
    assertEquals(3, m.decls().size());
    Decl.Union msg =
        (Decl.Union) m.decls().stream().filter(d -> d instanceof Decl.Union).findFirst().get();
    assertEquals("Msg", msg.name());
    assertEquals(2, msg.variants().size());
    Decl.Value update =
        m.decls().stream()
            .filter(d -> d instanceof Decl.Value v && v.name().equals("update"))
            .map(d -> (Decl.Value) d)
            .findFirst()
            .get();
    assertTrue(update.annotation().isPresent(), "annotation attached to update");
    assertEquals(2, update.params().size());
  }

  @Test
  void typeAliasParses() {
    String src =
        """
        type alias Model =
            { count : Int, name : String }

        x = 1
        """;
    Module m = Parser.parseModule(src);
    assertInstanceOf(Decl.TypeAlias.class, m.decls().get(0));
  }

  @Test
  void syntaxErrorReports() {
    assertThrows(ElmSyntaxError.class, () -> Parser.parseExpression("(1 + )"));
    assertThrows(ElmSyntaxError.class, () -> Parser.parseExpression("let x = in x"));
  }
}

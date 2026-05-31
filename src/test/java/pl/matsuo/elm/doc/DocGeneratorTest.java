package pl.matsuo.elm.doc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests Markdown documentation generation from doc comments + inferred types. */
class DocGeneratorTest {

  @Test
  void documentsExposedValuesWithTypesAndComments() {
    String src =
        """
        module Math exposing (double, triple)

        {-| Small math helpers. -}


        {-| Doubles a number. -}
        double n =
            n * 2


        {-| Triples a number. -}
        triple n =
            n * 3


        secret =
            42
        """;
    String md = DocGenerator.markdown(src);
    assertTrue(md.startsWith("# Math"), md);
    assertTrue(md.contains("Small math helpers."), "module doc");
    assertTrue(md.contains("### `double : number -> number`"), md); // inferred signature
    assertTrue(md.contains("Doubles a number."), md);
    assertTrue(md.contains("### `triple : number -> number`"), md);
    assertTrue(!md.contains("secret"), "non-exposed values are omitted"); // not in exposing
  }

  @Test
  void documentsTypesAndAliases() {
    String src =
        """
        module Shapes exposing (Shape, Point)

        {-| A 2-D point. -}
        type alias Point =
            { x : Float, y : Float }

        {-| A geometric shape. -}
        type Shape
            = Circle Float
            | Rect Float Float
        """;
    String md = DocGenerator.markdown(src);
    assertTrue(md.contains("### `type alias Point`"), md);
    assertTrue(md.contains("A 2-D point."), md);
    assertTrue(md.contains("### `type Shape`"), md);
    assertTrue(md.contains("Constructors: Circle, Rect"), md);
  }

  @Test
  void htmlRendersASearchableApiPage() {
    String src =
        """
        module Math exposing (double, Op)

        {-| Small math helpers. -}


        {-| A binary operation. -}
        type Op = Add | Mul

        {-| Doubles a number. -}
        double n =
            n * 2
        """;
    String html = DocGenerator.html(src);
    assertTrue(html.startsWith("<!doctype html>"), html);
    assertTrue(html.contains("<title>Math - docs</title>"), html);
    assertTrue(html.contains("Small math helpers."), "module comment");
    assertTrue(html.contains("double") && html.contains("number -&gt; number"), html); // value + type (escaped)
    assertTrue(html.contains("type Op") && html.contains("Add, Mul"), html); // union + constructors
    assertTrue(html.contains("id=\"q\""), "has a search box"); // live name filter
    assertTrue(html.contains("data-name=\"double\""), "entries are filterable by name");
  }

  @Test
  void docsJsonIsAModuleArrayCarryingDocComments() {
    String src =
        """
        module Math exposing (double)

        {-| Small math helpers. -}


        {-| Doubles a number. -}
        double n =
            n * 2
        """;
    String json = ApiDocs.of(src).toJson();
    assertTrue(json.stripLeading().startsWith("["), "docs.json is an array of modules: " + json);
    assertTrue(json.contains("\"Math\""), json);
    assertTrue(json.contains("\"comment\""), "entries carry a comment field: " + json);
    assertTrue(json.contains("Small math helpers."), "module doc comment included: " + json);
    assertTrue(json.contains("Doubles a number."), "value doc comment included: " + json);
    assertTrue(json.contains("\"binops\""), json);
  }
}

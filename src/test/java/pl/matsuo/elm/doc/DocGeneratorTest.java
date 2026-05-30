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
}

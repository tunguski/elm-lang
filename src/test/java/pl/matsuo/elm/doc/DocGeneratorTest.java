package pl.matsuo.elm.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  @Test
  void packageJsonDocumentsEveryModuleSortedByName() {
    String mathSrc = "module Math exposing (double)\ndouble n = n * 2\n";
    String strSrc = "module Strings exposing (shout)\nshout s = s ++ \"!\"\n";
    // Whole-package docs.json: a single array with both modules, sorted by name (Math before
    // Strings) — the artifact `elm publish` uploads.
    String json = ApiDocs.packageJson(java.util.List.of(strSrc, mathSrc));
    assertTrue(json.stripLeading().startsWith("["), json);
    assertTrue(json.contains("\"Math\"") && json.contains("\"Strings\""), json);
    assertTrue(json.indexOf("\"Math\"") < json.indexOf("\"Strings\""), "modules sorted by name: " + json);
    assertTrue(json.contains("double") && json.contains("shout"), json);
  }

  @Test
  void rendersMarkdownInDocHtml() {
    String src =
        """
        module M exposing (f)

        {-| Doubles with **emphasis** and a `code` span.

        ```
        f 2
        ```

        - first
        - second
        -}
        f n =
            n * 2
        """;
    String html = DocGenerator.html(src);
    assertTrue(html.contains("<strong>emphasis</strong>"), html);
    assertTrue(html.contains("<code>code</code>"), html);
    assertTrue(html.contains("<pre><code>") && html.contains("f 2"), html); // fenced code block
    assertTrue(html.contains("<li>first</li>") && html.contains("<li>second</li>"), html);
  }

  @Test
  void docMarkdownEscapesHtmlAndLinks() {
    String html = DocMarkdown.toHtml("see [docs](https://example.com) and <b>raw</b>");
    assertTrue(html.contains("<a href=\"https://example.com\">docs</a>"), html);
    assertTrue(html.contains("&lt;b&gt;raw&lt;/b&gt;"), html); // raw HTML is escaped, not injected
  }

  @Test
  void doctestVerifiesDocCommentExamples() {
    String src =
        """
        module M exposing (double, triple)

        {-| Doubles a number.

            double 21
            --> 42
        -}
        double n =
            n * 2

        {-| Triples a number.

            triple 2
            --> 7
        -}
        triple n =
            n * 3
        """;
    DocTest.Result r = DocTest.run(src);
    assertEquals(1, r.passed(), r.failures().toString()); // double 21 --> 42 holds
    assertEquals(1, r.failed(), r.failures().toString()); // triple 2 --> 7 is wrong (it's 6)
    assertTrue(r.failures().get(0).contains("triple 2"), r.failures().toString());
  }
}

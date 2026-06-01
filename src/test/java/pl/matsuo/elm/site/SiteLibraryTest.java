package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Project;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.util.Resources;

/** Exercises the bundled {@code Site} library's index and RSS-feed helpers through the interpreter. */
class SiteLibraryTest {

  private static final String LIB = Resources.read("/elm/lib/Site.elm");

  private static final String SRC =
      """
      module Main exposing (indexHtml, feedXml)

      import Site exposing (..)

      pages : List Page
      pages =
          [ page "a.html" "First" []
          , page "b.html" "Second" []
          ]

      indexHtml : String
      indexHtml = Site.render (Site.index "index.html" "All" pages)

      feedXml : String
      feedXml = Site.feed "My blog" "https://example.com/" pages

      inlineHtml : String
      inlineHtml =
          Site.render
              (Site.page "p.html" "T"
                  (Site.markdown "Some **bold**, *italic*, `code` and a [link](https://e.com/?a=1). Also 2 < 3.")
              )
      """;

  private static String value(String name) {
    return Show.plain(Project.load(SRC, LIB).value("Main", name));
  }

  @Test
  void indexLinksEveryPageByTitle() {
    String html = value("indexHtml");
    assertTrue(html.contains("<a href=\"a.html\">First</a>"), html);
    assertTrue(html.contains("<a href=\"b.html\">Second</a>"), html);
  }

  @Test
  void feedEmitsRssItemsWithAbsoluteLinks() {
    String xml = value("feedXml");
    assertTrue(xml.contains("<rss version=\"2.0\">"), xml);
    assertTrue(xml.contains("<title>My blog</title>"), xml);
    assertTrue(xml.contains("<item><title>First</title><link>https://example.com/a.html</link></item>"), xml);
    assertTrue(xml.contains("<item><title>Second</title><link>https://example.com/b.html</link></item>"), xml);
  }

  @Test
  void markdownParagraphsSupportInlineFormatting() {
    String html = value("inlineHtml");
    assertTrue(html.contains("<strong>bold</strong>"), html);
    assertTrue(html.contains("<em>italic</em>"), html);
    assertTrue(html.contains("<code>code</code>"), html);
    assertTrue(html.contains("<a href=\"https://e.com/?a=1\">link</a>"), html);
    assertTrue(html.contains("2 &lt; 3."), html); // HTML special characters still escaped
  }
}

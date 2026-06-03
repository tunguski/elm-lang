package pl.matsuo.elm.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests the small Markdown→HTML renderer used for the documentation pages. */
class MarkdownTest {

  @Test
  void rendersHeadingsParagraphsInlineAndCode() {
    String html =
        Markdown.toHtml(
            "# Title\n\nSome **bold** and `code` and a [link](https://x).\n\n```\nlet x = 1\n```\n");
    assertTrue(html.contains("<h1>Title</h1>"), html);
    assertTrue(html.contains("<strong>bold</strong>"), html);
    assertTrue(html.contains("<code>code</code>"), html);
    assertTrue(html.contains("<a href=\"https://x\">link</a>"), html);
    assertTrue(html.contains("<pre><code>let x = 1\n</code></pre>"), html);
  }

  @Test
  void rendersListsAndTables() {
    String html = Markdown.toHtml("- one\n- two\n\n| A | B |\n|---|---|\n| 1 | 2 |\n");
    assertTrue(html.contains("<ul>") && html.contains("<li>one</li>"), html);
    assertTrue(html.contains("<th>A</th>") && html.contains("<td>1</td>"), html);
  }

  @Test
  void fencedCodeCarriesAHighlightLanguageClass() {
    // The fence info string becomes a highlight.js class so the doc pages colour Elm and Bash.
    assertTrue(
        Markdown.toHtml("```elm\nmain = text \"hi\"\n```\n")
            .contains("<pre><code class=\"language-elm\">"),
        "elm fence");
    assertTrue(
        Markdown.toHtml("```bash\nls -la\n```\n").contains("<pre><code class=\"language-bash\">"),
        "bash fence");
    assertTrue(
        Markdown.toHtml("```sh\necho hi\n```\n").contains("language-bash"), "sh maps to bash");
    // An unlabelled fence stays a plain code block.
    assertTrue(Markdown.toHtml("```\nplain\n```\n").contains("<pre><code>plain"), "no label");
  }

  @Test
  void escapesHtmlInText() {
    assertTrue(Markdown.toHtml("a < b && c\n").contains("a &lt; b &amp;&amp; c"));
  }

  @Test
  void rendersOrderedLists() {
    String html = Markdown.toHtml("1. first\n2. second\n3. third\n");
    assertTrue(html.contains("<ol>") && html.contains("</ol>"), html);
    assertTrue(html.contains("<li>first</li>"), html);
    assertTrue(html.contains("<li>third</li>"), html);
    assertTrue(!html.contains("<p>1. first"), "not rendered as a paragraph: " + html);
  }

  @Test
  void coalescesConsecutiveBlockquoteLinesIntoOneBox() {
    // A multi-line "> Note:" must render as a single <blockquote>, not one box per line.
    String html = Markdown.toHtml("> **Note:** line one\n> line two\n> line three\n");
    assertEquals(1, countOccurrences(html, "<blockquote>"), html);
    assertTrue(html.contains("line one line two line three"), html);
  }

  @Test
  void listItemHoldsContinuationAndNestedCode() {
    // A multi-line item with a blank line and an indented fenced code block: the code must render
    // inside the <li> (not leak as literal ``` text), and the continuation stays in the same item.
    String md =
        "- `index` — a page linking every page by\n"
            + "  title. Add it to your `site`:\n\n"
            + "  ```elm\n"
            + "  site = Site.index articles\n"
            + "  ```\n";
    String html = Markdown.toHtml(md);
    assertTrue(html.contains("<li>"), html);
    assertTrue(html.contains("<pre><code class=\"language-elm\">site = Site.index articles\n"), html);
    assertTrue(!html.contains("```"), "no literal fence leaked: " + html);
    // The code block lives inside the list item, not after the closing </ul>.
    assertTrue(html.indexOf("<pre>") < html.indexOf("</ul>"), "code is inside the list: " + html);
  }

  private static int countOccurrences(String haystack, String needle) {
    int n = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
      n++;
    }
    return n;
  }
}

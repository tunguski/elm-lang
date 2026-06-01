package pl.matsuo.elm.site;

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
}

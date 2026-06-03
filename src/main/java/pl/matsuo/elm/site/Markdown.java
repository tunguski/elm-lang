package pl.matsuo.elm.site;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, dependency-free Markdown→HTML renderer for the documentation pages: ATX headings, fenced
 * code blocks, ordered and unordered lists (with multi-line / nested-code items), GitHub-style
 * tables, blockquotes, paragraphs, and inline `code`, **bold**, and [links](url). Sufficient for
 * this project's docs; not a full CommonMark engine.
 */
public final class Markdown {

  private Markdown() {}

  private static final Pattern CODE = Pattern.compile("`([^`]+)`");
  private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
  // A list item: optional indent, then a `-`/`*` bullet or an `N.` ordered marker, then the content.
  private static final Pattern ITEM = Pattern.compile("^(\\s*)([-*]|\\d+\\.)\\s+(.*)$");

  /** Renders Markdown {@code md} to an HTML fragment (no <html>/<body> wrapper). */
  public static String toHtml(String md) {
    return renderBlocks(md.split("\n", -1));
  }

  /** Renders a sequence of lines as block-level HTML. Re-entered for the contents of loose list
   *  items, so an item can itself hold paragraphs and fenced code blocks. */
  private static String renderBlocks(String[] lines) {
    StringBuilder out = new StringBuilder();
    StringBuilder para = new StringBuilder();
    int i = 0;
    while (i < lines.length) {
      String line = lines[i];
      String t = line.strip();
      if (t.startsWith("```")) {
        flushPara(out, para);
        i = codeBlock(out, lines, i);
        continue;
      }
      if (t.isEmpty()) {
        flushPara(out, para);
        i++;
        continue;
      }
      Matcher h = HEADING.matcher(t);
      if (h.matches()) {
        flushPara(out, para);
        int level = h.group(1).length();
        out.append("<h").append(level).append(">").append(inline(h.group(2))).append("</h").append(level).append(">\n");
        i++;
        continue;
      }
      if (t.startsWith("|") && i + 1 < lines.length && lines[i + 1].strip().matches("\\|?[\\s:|-]+\\|?")) {
        flushPara(out, para);
        i = table(out, lines, i) + 1; // consumes the table rows
        continue;
      }
      if (t.startsWith(">")) {
        flushPara(out, para);
        i = blockquote(out, lines, i);
        continue;
      }
      if (ITEM.matcher(line).matches()) {
        flushPara(out, para);
        i = list(out, lines, i);
        continue;
      }
      if (para.length() > 0) {
        para.append(" ");
      }
      para.append(t);
      i++;
    }
    flushPara(out, para);
    return out.toString();
  }

  /** Emits a fenced code block starting at {@code lines[start]}; returns the index past its close. */
  private static int codeBlock(StringBuilder out, String[] lines, int start) {
    // The fence's info string names the language (```elm, ```bash); emit it as a highlight.js class
    // so the doc pages' highlighter colours Elm and Bash blocks.
    String lang = lines[start].strip().substring(3).strip().toLowerCase();
    out.append(langClass(lang).isEmpty() ? "<pre><code>" : "<pre><code class=\"" + langClass(lang) + "\">");
    int i = start + 1;
    for (; i < lines.length && !lines[i].strip().startsWith("```"); i++) {
      out.append(escape(lines[i])).append("\n");
    }
    out.append("</code></pre>\n");
    return i < lines.length ? i + 1 : i; // skip the closing fence
  }

  /** Coalesces consecutive {@code >} lines into a single blockquote; returns the index past it. */
  private static int blockquote(StringBuilder out, String[] lines, int start) {
    StringBuilder q = new StringBuilder();
    int i = start;
    for (; i < lines.length; i++) {
      String s = lines[i].strip();
      if (!s.startsWith(">")) {
        break;
      }
      String content = s.substring(1).strip();
      if (q.length() > 0) {
        q.append(" ");
      }
      q.append(content);
    }
    out.append("<blockquote>").append(inline(q.toString())).append("</blockquote>\n");
    return i;
  }

  /** Renders one list (ordered or unordered) starting at {@code lines[start]}; returns the index
   *  past it. Collects each item's continuation lines (and nested fenced code) so multi-line and
   *  code-bearing items render inside their {@code <li>} rather than leaking after the list. */
  private static int list(StringBuilder out, String[] lines, int start) {
    Matcher first = ITEM.matcher(lines[start]);
    first.matches();
    int baseIndent = first.group(1).length();
    boolean ordered = first.group(2).matches("\\d+\\.");
    out.append(ordered ? "<ol>\n" : "<ul>\n");
    int i = start;
    while (i < lines.length) {
      Matcher m = ITEM.matcher(lines[i]);
      if (!m.matches() || m.group(1).length() != baseIndent || m.group(2).matches("\\d+\\.") != ordered) {
        break;
      }
      int contentIndent = m.start(3); // column where the item's content begins
      List<String> item = new ArrayList<>();
      item.add(m.group(3));
      i++;
      while (i < lines.length) {
        String ln = lines[i];
        if (ln.strip().isEmpty()) {
          item.add("");
          i++;
          continue;
        }
        Matcher mm = ITEM.matcher(ln);
        if (mm.matches() && mm.group(1).length() <= baseIndent) {
          break; // a sibling (or shallower) item ends this one
        }
        if (leading(ln) >= contentIndent) {
          item.add(ln.substring(contentIndent)); // dedent the continuation
          i++;
          continue;
        }
        break; // a non-indented, non-item line ends the list
      }
      while (!item.isEmpty() && item.get(item.size() - 1).isEmpty()) {
        item.remove(item.size() - 1); // drop trailing blank lines
      }
      out.append(renderItem(item));
    }
    out.append(ordered ? "</ol>\n" : "</ul>\n");
    return i;
  }

  /** A single {@code <li>}. Simple items (wrapped text only) render inline; items with a blank-line
   *  break or a fenced code block render their content as nested blocks. */
  private static String renderItem(List<String> item) {
    boolean nested = false;
    for (String l : item) {
      if (l.isEmpty() || l.strip().startsWith("```")) {
        nested = true;
        break;
      }
    }
    if (!nested) {
      return "<li>" + inline(String.join(" ", item).strip()) + "</li>\n";
    }
    return "<li>" + renderBlocks(item.toArray(new String[0])) + "</li>\n";
  }

  /** The number of leading space characters on a line. */
  private static int leading(String s) {
    int n = 0;
    while (n < s.length() && s.charAt(n) == ' ') {
      n++;
    }
    return n;
  }

  private static void flushPara(StringBuilder out, StringBuilder para) {
    if (para.length() > 0) {
      out.append("<p>").append(inline(para.toString())).append("</p>\n");
      para.setLength(0);
    }
  }

  /** Renders a GitHub-style table starting at {@code lines[start]}; returns the last consumed row. */
  private static int table(StringBuilder out, String[] lines, int start) {
    out.append("<table>\n");
    List<String> header = cells(lines[start]);
    out.append("<thead><tr>");
    for (String c : header) {
      out.append("<th>").append(inline(c)).append("</th>");
    }
    out.append("</tr></thead>\n<tbody>\n");
    int i = start + 2; // skip header and the |---| separator
    for (; i < lines.length && lines[i].strip().startsWith("|"); i++) {
      out.append("<tr>");
      for (String c : cells(lines[i])) {
        out.append("<td>").append(inline(c)).append("</td>");
      }
      out.append("</tr>\n");
    }
    out.append("</tbody></table>\n");
    return i - 1;
  }

  private static List<String> cells(String row) {
    String t = row.strip();
    if (t.startsWith("|")) {
      t = t.substring(1);
    }
    if (t.endsWith("|")) {
      t = t.substring(0, t.length() - 1);
    }
    return java.util.Arrays.stream(t.split("\\|", -1)).map(String::strip).toList();
  }

  /** Inline formatting: escape HTML, then apply `code`, **bold** and [links](url). */
  private static String inline(String s) {
    String e = escape(s);
    e = CODE.matcher(e).replaceAll(m -> "<code>" + Matcher.quoteReplacement(m.group(1)) + "</code>");
    e = BOLD.matcher(e).replaceAll(m -> "<strong>" + Matcher.quoteReplacement(m.group(1)) + "</strong>");
    e = LINK.matcher(e).replaceAll(m -> "<a href=\"" + Matcher.quoteReplacement(m.group(2)) + "\">"
        + Matcher.quoteReplacement(m.group(1)) + "</a>");
    return e;
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Maps a fence info string to a highlight.js language class (empty when unrecognised). */
  private static String langClass(String lang) {
    return switch (lang) {
      case "elm" -> "language-elm";
      case "bash", "sh", "shell", "console" -> "language-bash";
      default -> "";
    };
  }
}

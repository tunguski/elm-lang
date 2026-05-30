package pl.matsuo.elm.site;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, dependency-free Markdown→HTML renderer for the documentation pages: ATX headings, fenced
 * code blocks, unordered lists, GitHub-style tables, blockquotes, paragraphs, and inline `code`,
 * **bold**, and [links](url). Sufficient for this project's docs; not a full CommonMark engine.
 */
public final class Markdown {

  private Markdown() {}

  private static final Pattern CODE = Pattern.compile("`([^`]+)`");
  private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

  /** Renders Markdown {@code md} to an HTML fragment (no <html>/<body> wrapper). */
  public static String toHtml(String md) {
    String[] lines = md.split("\n", -1);
    StringBuilder out = new StringBuilder();
    boolean inCode = false;
    boolean inList = false;
    StringBuilder para = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      if (line.startsWith("```")) {
        flushPara(out, para);
        if (inList) {
          out.append("</ul>\n");
          inList = false;
        }
        if (inCode) {
          out.append("</code></pre>\n");
        } else {
          out.append("<pre><code>");
        }
        inCode = !inCode;
        continue;
      }
      if (inCode) {
        out.append(escape(line)).append("\n");
        continue;
      }
      String t = line.strip();
      if (t.isEmpty()) {
        flushPara(out, para);
        if (inList) {
          out.append("</ul>\n");
          inList = false;
        }
        continue;
      }
      Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(t);
      if (h.matches()) {
        flushPara(out, para);
        int level = h.group(1).length();
        out.append("<h").append(level).append(">").append(inline(h.group(2))).append("</h").append(level).append(">\n");
        continue;
      }
      if (t.startsWith("|") && i + 1 < lines.length && lines[i + 1].strip().matches("\\|?[\\s:|-]+\\|?")) {
        flushPara(out, para);
        i = table(out, lines, i); // consumes the table rows
        continue;
      }
      if (t.startsWith("- ") || t.startsWith("* ")) {
        flushPara(out, para);
        if (!inList) {
          out.append("<ul>\n");
          inList = true;
        }
        out.append("<li>").append(inline(t.substring(2))).append("</li>\n");
        continue;
      }
      if (t.startsWith("> ")) {
        flushPara(out, para);
        out.append("<blockquote>").append(inline(t.substring(2))).append("</blockquote>\n");
        continue;
      }
      if (para.length() > 0) {
        para.append(" ");
      }
      para.append(t);
    }
    flushPara(out, para);
    if (inCode) {
      out.append("</code></pre>\n");
    }
    if (inList) {
      out.append("</ul>\n");
    }
    return out.toString();
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
}

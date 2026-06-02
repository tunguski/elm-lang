package pl.matsuo.elm.doc;

import java.util.ArrayList;
import java.util.List;

/**
 * A compact Markdown-to-HTML renderer for the subset that appears in Elm doc comments: fenced and
 * indented code blocks, bullet lists, paragraphs, and the inline spans {@code `code`}, {@code
 * **bold**}, {@code *italic*} and {@code [text](url)}. Everything is HTML-escaped first, so doc text
 * is always safe; only the recognised markup turns into tags.
 */
public final class DocMarkdown {

  private DocMarkdown() {}

  /** A NUL sentinel that cannot occur in doc text, used to mask code spans during inline rewriting. */
  private static final String SENT = "";

  public static String toHtml(String markdown) {
    String[] lines = markdown.split("\n", -1);
    StringBuilder out = new StringBuilder();
    StringBuilder para = new StringBuilder();
    boolean inList = false;
    int i = 0;
    while (i < lines.length) {
      String line = lines[i];
      if (line.strip().startsWith("```")) {
        // A fenced code block: ``` … ```
        flushPara(out, para);
        inList = endList(out, inList);
        StringBuilder code = new StringBuilder();
        i++;
        while (i < lines.length && !lines[i].strip().startsWith("```")) {
          code.append(esc(lines[i])).append("\n");
          i++;
        }
        i++; // skip the closing fence
        out.append("<pre><code>").append(code).append("</code></pre>\n");
        continue;
      }
      if (line.startsWith("    ") && para.length() == 0 && !inList) {
        // An indented (4-space) code block.
        StringBuilder code = new StringBuilder();
        while (i < lines.length && (lines[i].startsWith("    ") || lines[i].isBlank())) {
          code.append(esc(lines[i].length() >= 4 ? lines[i].substring(4) : "")).append("\n");
          i++;
        }
        out.append("<pre><code>").append(code.toString().stripTrailing()).append("</code></pre>\n");
        continue;
      }
      String stripped = line.strip();
      if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
        flushPara(out, para);
        if (!inList) {
          out.append("<ul>");
          inList = true;
        }
        out.append("<li>").append(inline(stripped.substring(2))).append("</li>");
        i++;
        continue;
      }
      if (stripped.isEmpty()) {
        flushPara(out, para);
        inList = endList(out, inList);
        i++;
        continue;
      }
      inList = endList(out, inList);
      if (para.length() > 0) {
        para.append(" ");
      }
      para.append(stripped);
      i++;
    }
    flushPara(out, para);
    endList(out, inList);
    return out.toString();
  }

  private static void flushPara(StringBuilder out, StringBuilder para) {
    if (para.length() > 0) {
      out.append("<p>").append(inline(para.toString())).append("</p>\n");
      para.setLength(0);
    }
  }

  private static boolean endList(StringBuilder out, boolean inList) {
    if (inList) {
      out.append("</ul>\n");
    }
    return false;
  }

  /** Renders inline spans. Code spans are masked into sentinel-delimited placeholders first so their
   *  bodies aren't re-scanned for emphasis/links (and survive HTML-escaping unchanged). */
  static String inline(String text) {
    List<String> codes = new ArrayList<>();
    StringBuilder masked = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '`') {
        int end = text.indexOf('`', i + 1);
        if (end > i) {
          masked.append(SENT).append(codes.size()).append(SENT);
          codes.add(text.substring(i + 1, end));
          i = end + 1;
          continue;
        }
      }
      masked.append(c);
      i++;
    }
    String t = esc(masked.toString());
    t = t.replaceAll("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
    t = t.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
    t = t.replaceAll("(?<![*\\w])\\*([^*\\s][^*]*)\\*", "<em>$1</em>");
    t = t.replaceAll("(?<![_\\w])_([^_\\s][^_]*)_", "<em>$1</em>");
    for (int k = 0; k < codes.size(); k++) {
      t = t.replace(SENT + k + SENT, "<code>" + esc(codes.get(k)) + "</code>");
    }
    return t;
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}

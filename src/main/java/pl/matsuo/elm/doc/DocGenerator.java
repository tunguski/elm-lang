package pl.matsuo.elm.doc;

import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.lexer.Lexer;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.types.TypeChecker;

/**
 * Generates Markdown API documentation for a module from its exposed declarations: the module doc
 * comment, then each exposed value with its <em>inferred</em> type signature and doc comment, and
 * each exposed type alias / custom type. Doc comments are the {@code &#123;-| … -&#125;} blocks
 * captured by the lexer and associated with the declaration that follows them.
 */
public final class DocGenerator {

  private DocGenerator() {}

  /** Renders the module's public API as a self-contained, searchable HTML page (from {@link ApiDocs},
   * i.e. the same structured form behind {@code docs.json}). */
  public static String html(String source) {
    ApiDocs api = ApiDocs.of(source);
    StringBuilder h = new StringBuilder();
    h.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">")
        .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        .append("<title>").append(esc(api.moduleName())).append(" - docs</title>\n")
        .append("<style>")
        .append("body{font-family:system-ui,sans-serif;max-width:780px;margin:40px auto;padding:0 16px;color:#1f2933;line-height:1.5}")
        .append("h1{border-bottom:2px solid #e4e7eb;padding-bottom:8px}")
        .append("code{background:#f5f7fa;padding:1px 5px;border-radius:4px;font-size:.95em}")
        .append(".entry{margin:18px 0;padding-top:8px;border-top:1px solid #f0f0f0}")
        .append(".sig{font-size:1.05em}.sig .nm{color:#3a7bd5;font-weight:600}")
        .append(".doc{color:#52606d;white-space:pre-wrap}")
        .append("#q{width:100%;padding:8px;font-size:1em;border:1px solid #cbd2d9;border-radius:6px;margin:12px 0}")
        .append("a{color:#3a7bd5;text-decoration:none}.toc{columns:2;font-size:.95em}")
        .append("</style></head><body>\n");
    h.append("<h1>").append(esc(api.moduleName())).append("</h1>\n");
    if (!api.moduleComment().isEmpty()) {
      h.append("<p class=\"doc\">").append(esc(api.moduleComment())).append("</p>\n");
    }
    h.append("<input id=\"q\" placeholder=\"Filter by name…\" autofocus>\n");
    // Table of contents.
    h.append("<div class=\"toc\">");
    api.unions().keySet().forEach(n -> tocLink(h, n));
    api.aliases().keySet().forEach(n -> tocLink(h, n));
    api.values().keySet().forEach(n -> tocLink(h, n));
    h.append("</div>\n");
    api.unions().values().forEach(u -> {
      String head = "type " + u.name() + (u.params().isEmpty() ? "" : " " + String.join(" ", u.params()));
      String ctors = "Constructors: " + String.join(", ", u.tags().keySet());
      entry(h, u.name(), head, u.comment().isEmpty() ? ctors : ctors + "\n\n" + u.comment());
    });
    api.aliases().values().forEach(a -> {
      String head = "type alias " + a.name()
          + (a.params().isEmpty() ? "" : " " + String.join(" ", a.params())) + " = " + a.type();
      entry(h, a.name(), head, a.comment());
    });
    api.values().values().forEach(v ->
        entry(h, v.name(), v.name() + (v.type().isEmpty() ? "" : " : " + v.type()), v.comment()));
    // Live filter: hide entries (and their TOC links) whose name doesn't contain the query.
    h.append("<script>var q=document.getElementById('q');q.addEventListener('input',function(){")
        .append("var t=q.value.toLowerCase();")
        .append("document.querySelectorAll('[data-name]').forEach(function(e){")
        .append("e.style.display=e.getAttribute('data-name').indexOf(t)>=0?'':'none';});});</script>\n");
    return h.append("</body></html>\n").toString();
  }

  private static void tocLink(StringBuilder h, String name) {
    h.append("<a data-name=\"").append(esc(name.toLowerCase())).append("\" href=\"#")
        .append(esc(name)).append("\"><code>").append(esc(name)).append("</code></a><br>");
  }

  private static void entry(StringBuilder h, String name, String signature, String doc) {
    h.append("<div class=\"entry\" data-name=\"").append(esc(name.toLowerCase())).append("\">")
        .append("<div class=\"sig\" id=\"").append(esc(name)).append("\"><code>")
        .append(esc(signature)).append("</code></div>");
    if (!doc.isEmpty()) {
      h.append("<div class=\"doc\">").append(esc(doc)).append("</div>");
    }
    h.append("</div>\n");
  }

  private static String esc(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  /** Renders the module's documentation as Markdown. */
  public static String markdown(String source) {
    Module module = Parser.parseModule(source);
    List<Lexer.Comment> comments = Lexer.comments(source);
    Map<String, String> types;
    try {
      types = TypeChecker.checkModule(source);
    } catch (RuntimeException e) {
      types = Map.of();
    }

    StringBuilder md = new StringBuilder();
    md.append("# ").append(module.name()).append("\n\n");
    // The module doc is the first {-| comment after the header; it must not be reused as a decl doc.
    int moduleDocLine = -1;
    for (Lexer.Comment c : comments) {
      if (c.block() && c.text().startsWith("{-|") && c.line() > module.pos().line()) {
        if (moduleDocLine == -1 || c.line() < moduleDocLine) {
          moduleDocLine = c.line();
        }
      }
    }
    if (moduleDocLine >= 0) {
      md.append(docBefore(moduleDocLine + 1, comments, -1)).append("\n\n");
    }

    boolean all = module.exposing().open();
    var exposed = module.exposing().names();
    for (Decl d : module.decls()) {
      switch (d) {
        case Decl.Value v -> {
          if ((all || exposed.contains(v.name())) && !v.name().equals("main")) {
            String type = types.getOrDefault(v.name(), "");
            md.append("### `").append(v.name());
            if (!type.isEmpty()) {
              md.append(" : ").append(type);
            }
            md.append("`\n\n");
            appendDoc(md, v.pos().line(), comments, moduleDocLine);
          }
        }
        case Decl.TypeAlias ta -> {
          if (all || exposed.contains(ta.name())) {
            md.append("### `type alias ").append(ta.name());
            for (String p : ta.params()) {
              md.append(" ").append(p);
            }
            md.append("`\n\n");
            appendDoc(md, ta.pos().line(), comments, moduleDocLine);
          }
        }
        case Decl.Union u -> {
          if (all || exposed.contains(u.name())) {
            md.append("### `type ").append(u.name());
            for (String p : u.params()) {
              md.append(" ").append(p);
            }
            md.append("`\n\n");
            md.append("Constructors: ")
                .append(String.join(", ", u.variants().stream().map(Decl.Union.Variant::name).toList()))
                .append("\n\n");
            appendDoc(md, u.pos().line(), comments, moduleDocLine);
          }
        }
        default -> {}
      }
    }
    return md.toString();
  }

  private static void appendDoc(StringBuilder md, int line, List<Lexer.Comment> comments, int excludeLine) {
    String doc = docBefore(line, comments, excludeLine);
    if (!doc.isEmpty()) {
      md.append(doc).append("\n\n");
    }
  }

  /**
   * The text of the doc comment ({@code &#123;-| … -&#125;}) closest above {@code line}, or "";
   * a comment on {@code excludeLine} (the module doc) is never reused.
   */
  private static String docBefore(int line, List<Lexer.Comment> comments, int excludeLine) {
    Lexer.Comment best = null;
    for (Lexer.Comment c : comments) {
      if (c.block() && c.text().startsWith("{-|") && c.line() < line && c.line() != excludeLine) {
        if (best == null || c.line() > best.line()) {
          best = c;
        }
      }
    }
    if (best == null) {
      return "";
    }
    String t = best.text();
    t = t.substring(3); // drop "{-|"
    if (t.endsWith("-}")) {
      t = t.substring(0, t.length() - 2);
    }
    return t.strip();
  }
}

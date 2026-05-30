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

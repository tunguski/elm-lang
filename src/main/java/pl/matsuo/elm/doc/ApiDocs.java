package pl.matsuo.elm.doc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.json.JsonEncode;
import pl.matsuo.elm.lexer.Lexer;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.types.TypeChecker;

/**
 * The public API of a module: its exposed values (with inferred type signatures), custom types and
 * type aliases. This is the structured form behind {@code docs.json} and the input to {@code elm
 * diff}/{@code elm bump}, which compare two APIs to derive a semantic-version magnitude.
 */
public final class ApiDocs {

  /** An exposed value/function: its rendered type ({@code ""} if inference couldn't type it) and
   *  its doc comment ({@code ""} if none). */
  public record Value(String name, String type, String comment) {}

  /** An exposed custom type: its parameters, each constructor's rendered argument types, and doc. */
  public record Union(String name, List<String> params, Map<String, List<String>> tags,
      String comment) {}

  /** An exposed type alias: its parameters, the rendered aliased type, and doc comment. */
  public record Alias(String name, List<String> params, String type, String comment) {}

  private final String moduleName;
  private final String moduleComment;
  private final Map<String, Value> values; // sorted by name
  private final Map<String, Union> unions;
  private final Map<String, Alias> aliases;

  private ApiDocs(String moduleName, String moduleComment, Map<String, Value> values,
      Map<String, Union> unions, Map<String, Alias> aliases) {
    this.moduleName = moduleName;
    this.moduleComment = moduleComment;
    this.values = values;
    this.unions = unions;
    this.aliases = aliases;
  }

  public String moduleName() {
    return moduleName;
  }

  public String moduleComment() {
    return moduleComment;
  }

  public Map<String, Value> values() {
    return values;
  }

  public Map<String, Union> unions() {
    return unions;
  }

  public Map<String, Alias> aliases() {
    return aliases;
  }

  /** Extracts the public API of a module from its source (exposed declarations + inferred types). */
  public static ApiDocs of(String source) {
    Module module = Parser.parseModule(source);
    Map<String, String> types;
    try {
      types = TypeChecker.checkModule(source);
    } catch (RuntimeException e) {
      types = Map.of();
    }
    boolean all = module.exposing().open();
    List<String> exposed = module.exposing().names();

    // Doc comments: the module doc is the first {-| block after the header; each declaration's doc
    // is the nearest {-| block above it (the module doc is never reused as a declaration's).
    List<Lexer.Comment> comments = Lexer.comments(source);
    int moduleDocLine = -1;
    for (Lexer.Comment c : comments) {
      if (c.block() && c.text().startsWith("{-|") && c.line() > module.pos().line()
          && (moduleDocLine == -1 || c.line() < moduleDocLine)) {
        moduleDocLine = c.line();
      }
    }
    String moduleComment = moduleDocLine >= 0 ? docBefore(moduleDocLine + 1, comments, -1) : "";

    Map<String, Value> values = new TreeMap<>();
    Map<String, Union> unions = new TreeMap<>();
    Map<String, Alias> aliases = new TreeMap<>();
    for (Decl d : module.decls()) {
      switch (d) {
        case Decl.Value v -> {
          if ((all || exposed.contains(v.name())) && !v.name().equals("main")) {
            values.put(v.name(), new Value(v.name(), types.getOrDefault(v.name(), ""),
                docBefore(v.pos().line(), comments, moduleDocLine)));
          }
        }
        case Decl.Union u -> {
          if (all || exposed.contains(u.name())) {
            Map<String, List<String>> tags = new LinkedHashMap<>();
            for (Decl.Union.Variant variant : u.variants()) {
              tags.put(variant.name(), variant.args().stream().map(ApiDocs::render).toList());
            }
            unions.put(u.name(), new Union(u.name(), u.params(), tags,
                docBefore(u.pos().line(), comments, moduleDocLine)));
          }
        }
        case Decl.TypeAlias ta -> {
          if (all || exposed.contains(ta.name())) {
            aliases.put(ta.name(), new Alias(ta.name(), ta.params(), render(ta.type()),
                docBefore(ta.pos().line(), comments, moduleDocLine)));
          }
        }
        default -> {}
      }
    }
    return new ApiDocs(module.name(), moduleComment, values, unions, aliases);
  }

  /** The text of the {@code &#123;-| … -&#125;} doc comment closest above {@code line}, or "";
   *  the module doc (on {@code excludeLine}) is never reused for a declaration. */
  private static String docBefore(int line, List<Lexer.Comment> comments, int excludeLine) {
    Lexer.Comment best = null;
    for (Lexer.Comment c : comments) {
      if (c.block() && c.text().startsWith("{-|") && c.line() < line && c.line() != excludeLine
          && (best == null || c.line() > best.line())) {
        best = c;
      }
    }
    if (best == null) {
      return "";
    }
    String t = best.text().substring(3); // drop "{-|"
    if (t.endsWith("-}")) {
      t = t.substring(0, t.length() - 2);
    }
    return t.strip();
  }

  /**
   * Serialises the API to the standard Elm {@code docs.json} format: a JSON <em>array</em> of module
   * objects (here one), each with its doc {@code comment}, {@code unions}/{@code aliases}/
   * {@code values} (carrying their own doc comments) and an (empty) {@code binops} list — the same
   * shape package.elm-lang.org serves and {@code elm diff} consumes.
   */
  public String toJson() {
    return JsonEncode.serialize(List.<Object>of(moduleObject()), 2) + "\n";
  }

  /** The single module object inside the {@code docs.json} array. */
  private Map<String, Object> moduleObject() {
    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("name", moduleName);
    doc.put("comment", moduleComment);
    List<Object> us = new ArrayList<>();
    unions.values().forEach(u -> {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("name", u.name());
      o.put("comment", u.comment());
      o.put("args", u.params());
      List<Object> cases = new ArrayList<>();
      u.tags().forEach((tag, args) -> cases.add(List.of(tag, args)));
      o.put("cases", cases);
      us.add(o);
    });
    doc.put("unions", us);
    List<Object> as = new ArrayList<>();
    aliases.values().forEach(a -> {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("name", a.name());
      o.put("comment", a.comment());
      o.put("args", a.params());
      o.put("type", a.type());
      as.add(o);
    });
    doc.put("aliases", as);
    List<Object> vs = new ArrayList<>();
    values.values().forEach(v -> {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("name", v.name());
      o.put("comment", v.comment());
      o.put("type", v.type());
      vs.add(o);
    });
    doc.put("values", vs);
    doc.put("binops", new ArrayList<>());
    return doc;
  }

  /** Renders a surface type to a single-line string (used for constructor args and alias bodies). */
  static String render(Type t) {
    return switch (t) {
      case Type.Var v -> v.name();
      case Type.Unit ignored -> "()";
      case Type.Con c -> {
        String name = (c.module() == null ? "" : c.module() + ".") + c.name();
        if (c.args().isEmpty()) {
          yield name;
        }
        yield name + " " + String.join(" ", c.args().stream().map(ApiDocs::renderAtom).toList());
      }
      case Type.Arrow a -> renderAtom(a.from()) + " -> " + render(a.to());
      case Type.Tuple tu -> "( " + String.join(", ", tu.items().stream().map(ApiDocs::render).toList()) + " )";
      case Type.Record r -> {
        String base = r.base().map(b -> b + " | ").orElse("");
        String fields =
            String.join(", ", r.fields().stream().map(f -> f.name() + " : " + render(f.type())).toList());
        yield "{ " + base + fields + " }";
      }
    };
  }

  /** Renders a type that may need parentheses when used as an argument (arrows and applied cons). */
  private static String renderAtom(Type t) {
    boolean needsParens =
        t instanceof Type.Arrow || (t instanceof Type.Con c && !c.args().isEmpty());
    String s = render(t);
    return needsParens ? "(" + s + ")" : s;
  }
}

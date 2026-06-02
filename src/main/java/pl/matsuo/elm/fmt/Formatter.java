package pl.matsuo.elm.fmt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.lexer.Lexer;
import pl.matsuo.elm.parser.Parser;

/**
 * An elm-format-style source formatter: it parses a module and re-prints it with the standard
 * layout — a {@code module … exposing (…)} header, alphabetically sorted imports, two blank lines
 * between top-level declarations, type annotations on their own line, and 4-space indentation
 * (expression bodies via {@link Pretty}). It approximates elm-format's rules and is idempotent and
 * semantics-preserving.
 *
 * <p>Comments are preserved at the top level: a comment is emitted (verbatim) before the first
 * thing it precedes — above the module header, as the module doc comment after the header, or
 * attached to the declaration that follows it (and any trailing comments at the end). Comments that
 * sit <em>inside</em> a declaration's body or on the same line as code (trailing) are not preserved
 * in place — the parser has no node to anchor them to.
 */
public final class Formatter {

  private Formatter() {}

  /** Formats Elm module source. */
  public static String format(String source) {
    Module m = Parser.parseModule(source);
    List<Lexer.Comment> comments = new ArrayList<>(Lexer.comments(source));
    comments.sort(Comparator.comparingInt(Lexer.Comment::line));
    int[] idx = {0};

    StringBuilder sb = new StringBuilder();

    // Comments above the module header (e.g. a license banner) stay at the very top.
    for (Lexer.Comment c : take(comments, idx, m.pos().line())) {
      sb.append(c.text()).append("\n");
    }

    sb.append("module ").append(m.name()).append(" exposing (").append(exposing(m.exposing()))
        .append(")\n");

    int firstBodyLine = firstBodyLine(m);

    // The module doc comment (and anything between the header and the first import/declaration).
    List<Lexer.Comment> header = take(comments, idx, firstBodyLine);
    for (Lexer.Comment c : header) {
      sb.append("\n").append(c.text());
    }
    if (!header.isEmpty()) {
      sb.append("\n");
    }

    List<Module.Import> imports = new ArrayList<>(m.imports());
    imports.sort((a, b) -> a.module().compareTo(b.module()));
    if (!imports.isEmpty()) {
      sb.append("\n");
      for (Module.Import imp : imports) {
        sb.append("import ").append(imp.module());
        imp.alias().ifPresent(a -> sb.append(" as ").append(a));
        if (imp.exposing().open() || !imp.exposing().names().isEmpty()) {
          sb.append(" exposing (").append(exposing(imp.exposing())).append(")");
        }
        sb.append("\n");
      }
    }

    String[] srcLines = source.split("\n", -1);
    List<int[]> commentSpans = new ArrayList<>();
    for (Lexer.Comment c : Lexer.comments(source)) {
      commentSpans.add(new int[] {c.line(), c.line() + countNewlines(c.text())});
    }
    List<Decl> decls = m.decls();
    for (int i = 0; i < decls.size(); i++) {
      Decl d = decls.get(i);
      sb.append("\n\n");
      // Comments preceding this declaration (a doc comment, or a section header).
      for (Lexer.Comment c : take(comments, idx, d.pos().line())) {
        sb.append(c.text()).append("\n");
      }
      int nextStart =
          i + 1 < decls.size()
              ? regionStart(decls.get(i + 1), srcLines, commentSpans)
              : srcLines.length + 1;
      int lastCode = lastCodeLine(srcLines, commentSpans, d.pos().line(), nextStart);
      boolean hasInnerComment =
          idx[0] < comments.size() && comments.get(idx[0]).line() <= lastCode;
      if (hasInnerComment) {
        // Reflowing would drop or misplace the comments inside this declaration, so keep its body
        // verbatim (the type annotation is still normalised). Comments are never lost.
        if (d instanceof Decl.Value v) {
          // Verbatim decls (those with inner comments) keep the annotation on one line — reflowing
          // it would desync the line-based region tracking the verbatim body relies on.
          v.annotation()
              .ifPresent(t -> sb.append(Pretty.declName(v.name())).append(" : ").append(type(t, false)).append("\n"));
        }
        for (int line = d.pos().line(); line <= lastCode; line++) {
          sb.append(srcLines[line - 1]).append(line < lastCode ? "\n" : "");
        }
        sb.append("\n");
        while (idx[0] < comments.size() && comments.get(idx[0]).line() <= lastCode) {
          idx[0]++; // the in-body comments are now part of the verbatim block
        }
      } else {
        sb.append(declaration(d)).append("\n");
      }
    }

    // Any comments after the last declaration.
    List<Lexer.Comment> trailing = take(comments, idx, Integer.MAX_VALUE);
    if (!trailing.isEmpty()) {
      sb.append("\n");
      for (Lexer.Comment c : trailing) {
        sb.append(c.text()).append("\n");
      }
    }
    return sb.toString();
  }

  private static int countNewlines(String s) {
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) == '\n') {
        n++;
      }
    }
    return n;
  }

  /** The first source line of a declaration's leading region: its body line, moved up past a
   * directly-preceding type annotation and any contiguous doc/section comments and blank lines. This
   * is the boundary where the previous declaration's body ends. */
  private static int regionStart(Decl d, String[] srcLines, List<int[]> commentSpans) {
    int line = d.pos().line();
    if (d instanceof Decl.Value v && line - 1 >= 1) {
      if (isAnnotationLine(srcLines[line - 2], v.name())) {
        line--; // a single-line annotation directly above the body
      } else if (isAnnotationContinuation(srcLines[line - 2])) {
        // A wrapped (multi-line) annotation: skip its continuation lines up to the `name :` line.
        int probe = line - 1;
        while (probe - 1 >= 1 && isAnnotationContinuation(srcLines[probe - 2])) {
          probe--;
        }
        if (probe - 1 >= 1 && isAnnotationLine(srcLines[probe - 2], v.name())) {
          line = probe - 1; // include the `name :` line that starts the annotation
        }
      }
    }
    while (line - 1 >= 1
        && (srcLines[line - 2].isBlank() || isCommentLine(commentSpans, line - 1))) {
      line--; // skip the doc/section comment block (and blank lines) above
    }
    return line;
  }

  /** Whether a line is a continuation of a wrapped type annotation: an indented segment, optionally
   *  led by {@code ->} (as {@link #annotation} emits). */
  private static boolean isAnnotationContinuation(String text) {
    if (text.isEmpty() || !Character.isWhitespace(text.charAt(0))) {
      return false;
    }
    String s = text.strip();
    return !s.isEmpty()
        && (s.startsWith("->")
            || s.startsWith("(")
            || Character.isLetter(s.charAt(0)));
  }

  /** Whether {@code text} is the type annotation of {@code name} (e.g. {@code "name : Int -> Int"}). */
  private static boolean isAnnotationLine(String text, String name) {
    String t = text.strip();
    return t.startsWith(name) && t.substring(name.length()).stripLeading().startsWith(":");
  }

  private static boolean isCommentLine(List<int[]> commentSpans, int line) {
    for (int[] span : commentSpans) {
      if (line >= span[0] && line <= span[1]) {
        return true;
      }
    }
    return false;
  }

  /** The last source line that holds code (non-blank, not inside a comment) in {@code [from,
   * nextStart)} — i.e. the end of a declaration's body. Falls back to {@code from}. */
  private static int lastCodeLine(String[] srcLines, List<int[]> commentSpans, int from, int nextStart) {
    int upper = Math.min(nextStart - 1, srcLines.length);
    for (int line = upper; line >= from; line--) {
      if (isCodeLine(srcLines, commentSpans, line)) {
        return line;
      }
    }
    return from;
  }

  /** Whether 1-based {@code line} is a code line: non-blank and not covered by any comment span. */
  private static boolean isCodeLine(String[] srcLines, List<int[]> commentSpans, int line) {
    if (line < 1 || line > srcLines.length || srcLines[line - 1].isBlank()) {
      return false;
    }
    for (int[] span : commentSpans) {
      if (line >= span[0] && line <= span[1]) {
        return false;
      }
    }
    return true;
  }

  /** Consumes and returns the leading comments whose line is before {@code beforeLine}. */
  private static List<Lexer.Comment> take(List<Lexer.Comment> comments, int[] idx, int beforeLine) {
    List<Lexer.Comment> out = new ArrayList<>();
    while (idx[0] < comments.size() && comments.get(idx[0]).line() < beforeLine) {
      out.add(comments.get(idx[0]));
      idx[0]++;
    }
    return out;
  }

  /** The source line of the first import or declaration (the end of the header region). */
  private static int firstBodyLine(Module m) {
    int min = Integer.MAX_VALUE;
    for (Module.Import imp : m.imports()) {
      min = Math.min(min, imp.pos().line());
    }
    for (Decl d : m.decls()) {
      min = Math.min(min, d.pos().line());
    }
    return min;
  }

  /** All {@code .elm} files of a project (elm.json's source-directories), for {@code format --project}. */
  public static List<java.nio.file.Path> projectFiles(java.nio.file.Path elmJsonOrDir) {
    java.nio.file.Path base =
        java.nio.file.Files.isDirectory(elmJsonOrDir)
            ? elmJsonOrDir
            : elmJsonOrDir.toAbsolutePath().getParent();
    java.nio.file.Path elmJson =
        java.nio.file.Files.isDirectory(elmJsonOrDir) ? elmJsonOrDir.resolve("elm.json") : elmJsonOrDir;
    List<String> dirs = new ArrayList<>();
    try {
      if (java.nio.file.Files.exists(elmJson)) {
        Object cfg = pl.matsuo.elm.json.JsonParse.parse(java.nio.file.Files.readString(elmJson));
        Object sd = cfg instanceof java.util.Map<?, ?> mm
            ? ((java.util.Map<?, ?>) mm).get("source-directories") : null;
        if (sd instanceof List<?> list) {
          list.forEach(x -> dirs.add(String.valueOf(x)));
        }
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    if (dirs.isEmpty()) {
      dirs.add(".");
    }
    List<java.nio.file.Path> files = new ArrayList<>();
    for (String dir : dirs) {
      java.nio.file.Path root = base.resolve(dir);
      if (!java.nio.file.Files.isDirectory(root)) {
        continue;
      }
      try (var walk = java.nio.file.Files.walk(root)) {
        walk.filter(p -> p.toString().endsWith(".elm")).sorted().forEach(files::add);
      } catch (java.io.IOException e) {
        throw new java.io.UncheckedIOException(e);
      }
    }
    return files;
  }

  private static String exposing(Module.Exposing e) {
    if (e.open()) {
      return "..";
    }
    return e.names().stream().map(Formatter::exposedName).collect(Collectors.joining(", "));
  }

  /** An exposed name, wrapping operators in parentheses: {@code (|=)}, {@code (++)}. */
  private static String exposedName(String name) {
    boolean ident = !name.isEmpty() && (Character.isLetter(name.charAt(0)) || name.charAt(0) == '_');
    return ident ? name : "(" + name + ")";
  }

  private static String declaration(Decl d) {
    return switch (d) {
      case Decl.Value v -> {
        StringBuilder sb = new StringBuilder();
        v.annotation().ifPresent(t -> sb.append(annotation(Pretty.declName(v.name()), t)).append("\n"));
        sb.append(Pretty.decl(v, 0));
        yield sb.toString();
      }
      case Decl.TypeAlias ta -> {
        String params = ta.params().isEmpty() ? "" : " " + String.join(" ", ta.params());
        yield "type alias " + ta.name() + params + " =\n    " + type(ta.type(), false);
      }
      case Decl.Union u -> {
        String params = u.params().isEmpty() ? "" : " " + String.join(" ", u.params());
        StringBuilder sb = new StringBuilder("type " + u.name() + params + "\n");
        for (int i = 0; i < u.variants().size(); i++) {
          Decl.Union.Variant variant = u.variants().get(i);
          sb.append(i == 0 ? "    = " : "    | ").append(variant.name());
          for (Type arg : variant.args()) {
            sb.append(" ").append(type(arg, true));
          }
          if (i < u.variants().size() - 1) {
            sb.append("\n");
          }
        }
        yield sb.toString();
      }
      default -> d.toString();
    };
  }

  /** Prints a type; {@code atom} parenthesizes arrows/applications so it can be a constructor arg. */
  /** The width past which a type annotation is wrapped across multiple lines. */
  private static final int MAX_WIDTH = 100;

  /** Renders a value's type annotation, wrapping a long function type one arrow-segment per line
   *  (elm-format style): the name and {@code :} alone, then each argument/return indented, with
   *  {@code ->} leading every segment after the first. */
  static String annotation(String name, Type t) {
    String oneLine = name + " : " + type(t, false);
    if (oneLine.length() <= MAX_WIDTH || !(t instanceof Type.Arrow)) {
      return oneLine;
    }
    List<Type> segments = new ArrayList<>();
    Type cur = t;
    while (cur instanceof Type.Arrow a) {
      segments.add(a.from());
      cur = a.to();
    }
    segments.add(cur); // the return type
    StringBuilder sb = new StringBuilder(name).append(" :");
    for (int i = 0; i < segments.size(); i++) {
      Type seg = segments.get(i);
      // A function-typed argument is itself an Arrow and must keep its parentheses.
      sb.append("\n    ").append(i == 0 ? "" : "-> ").append(type(seg, seg instanceof Type.Arrow));
    }
    return sb.toString();
  }

  static String type(Type t, boolean atom) {
    return switch (t) {
      case Type.Var v -> v.name();
      case Type.Unit ignored -> "()";
      case Type.Con c -> {
        String name = c.module() == null ? c.name() : c.module() + "." + c.name();
        if (c.args().isEmpty()) {
          yield name;
        }
        String s = name + " " + c.args().stream().map(a -> type(a, true)).collect(Collectors.joining(" "));
        yield atom ? "(" + s + ")" : s;
      }
      case Type.Arrow a -> {
        String s = type(a.from(), true) + " -> " + type(a.to(), false);
        yield atom ? "(" + s + ")" : s;
      }
      case Type.Tuple tup ->
          "( " + tup.items().stream().map(x -> type(x, false)).collect(Collectors.joining(", ")) + " )";
      case Type.Record r -> {
        String base = r.base().map(b -> b + " | ").orElse("");
        if (r.fields().isEmpty()) {
          yield "{}";
        }
        yield "{ " + base + r.fields().stream()
            .map(f -> f.name() + " : " + type(f.type(), false))
            .collect(Collectors.joining(", ")) + " }";
      }
    };
  }
}

package pl.matsuo.elm.fmt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.parser.Parser;

/**
 * An elm-format-style source formatter: it parses a module and re-prints it with the standard
 * layout — a {@code module … exposing (…)} header, alphabetically sorted imports, two blank lines
 * between top-level declarations, type annotations on their own line, and 4-space indentation
 * (expression bodies via {@link Pretty}). It approximates elm-format's rules and is idempotent and
 * semantics-preserving; it does not preserve comments (which the parser discards).
 */
public final class Formatter {

  private Formatter() {}

  /** Formats Elm module source. */
  public static String format(String source) {
    Module m = Parser.parseModule(source);
    StringBuilder sb = new StringBuilder();

    sb.append("module ").append(m.name()).append(" exposing (").append(exposing(m.exposing()))
        .append(")\n");

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

    for (Decl d : m.decls()) {
      sb.append("\n\n").append(declaration(d)).append("\n");
    }
    return sb.toString();
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
    return e.open() ? ".." : String.join(", ", e.names());
  }

  private static String declaration(Decl d) {
    return switch (d) {
      case Decl.Value v -> {
        StringBuilder sb = new StringBuilder();
        v.annotation().ifPresent(t -> sb.append(v.name()).append(" : ").append(type(t, false)).append("\n"));
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

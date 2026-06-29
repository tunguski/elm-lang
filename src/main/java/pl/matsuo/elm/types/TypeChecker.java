package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.error.ElmTypeErrors;
import pl.matsuo.elm.parser.Parser;

/** Entry point for type inference. */
public final class TypeChecker {

  private TypeChecker() {}

  /** Infers the type of an expression and returns its rendered form (e.g. {@code "List number"}). */
  public static String infer(String expression) {
    Expr e = Parser.parseExpression(expression);
    try {
      Ty t = new Infer().infer(TypeEnv.root(Signatures.globals()), e);
      return Types.show(t);
    } catch (ElmTypeError err) {
      throw locate(expression, err);
    }
  }

  /**
   * Type-checks a whole module, returning each top-level definition's inferred type (rendered).
   * Throws {@link ElmTypeError} (with a source excerpt, caret and hint) on a type error.
   */
  public static Map<String, String> checkModule(String source) {
    // If the module imports pure bundled libraries (List.Extra, Maybe.Extra, …), type-check them
    // together as a project so the imported names resolve. The user module stays the entry (last).
    List<String> withLibs = pl.matsuo.elm.interp.BundledLibs.resolve(List.of(source));
    if (withLibs.size() > 1) {
      return checkProject(withLibs.toArray(new String[0]));
    }
    Module module = Parser.parseModule(source);
    try {
      Map<String, Scheme> schemes = new Infer().inferModule(module, Signatures.globals());
      Map<String, String> result = new LinkedHashMap<>();
      schemes.forEach((name, scheme) -> result.put(name, Types.show(scheme.body())));
      return result;
    } catch (ElmTypeErrors errs) {
      throw combine(source, errs);
    } catch (ElmTypeError err) {
      throw locate(source, err);
    }
  }

  /**
   * Type-checks a multi-module project (e.g. an example plus the {@code Playground} source it
   * imports). The entry module is the one defining {@code main} (else the last). Returns the entry
   * module's inferred top-level types.
   */
  public static Map<String, String> checkProject(String... sources) {
    List<String> resolved = pl.matsuo.elm.interp.BundledLibs.resolve(List.of(sources));
    List<Module> modules = new ArrayList<>();
    Map<String, String> sourceByModule = new LinkedHashMap<>();
    for (String s : resolved) {
      Module m = Parser.parseModule(s);
      modules.add(m);
      sourceByModule.put(m.name(), s); // so an error from module M is rendered against M's source
    }
    String entry = sources[sources.length - 1];
    try {
      Map<String, Scheme> schemes = new Infer().inferProject(modules, Signatures.globals());
      Map<String, String> result = new LinkedHashMap<>();
      schemes.forEach((name, scheme) -> result.put(name, Types.show(scheme.body())));
      return result;
    } catch (ElmTypeErrors errs) {
      throw combine(sourceByModule, entry, errs);
    } catch (ElmTypeError err) {
      throw locate(sourceFor(sourceByModule, entry, err), err);
    }
  }

  /** The source the error should be rendered against: its tagged module's, or the entry as fallback. */
  private static String sourceFor(Map<String, String> sourceByModule, String entry, ElmTypeError err) {
    return err.module != null ? sourceByModule.getOrDefault(err.module, entry) : entry;
  }

  /** Locates every error in a multi-error result and joins them into one Elm-style report. The
   * returned exception keeps the individual (bare, located) errors in {@link ElmTypeErrors#errors}
   * so callers like the LSP can place one diagnostic per error. */
  static ElmTypeErrors combine(String source, ElmTypeErrors errs) {
    return combine(Map.of(), source, errs);
  }

  /** As {@link #combine(String, ElmTypeErrors)} but picks each error's source by its tagged module
   * (falling back to {@code entry}), so a multi-module report locates every excerpt in the right file. */
  static ElmTypeErrors combine(Map<String, String> sourceByModule, String entry, ElmTypeErrors errs) {
    StringBuilder b = new StringBuilder();
    b.append("Found ").append(errs.errors.size()).append(" type errors:\n");
    for (int i = 0; i < errs.errors.size(); i++) {
      b.append("\n");
      if (i > 0) {
        b.append("─".repeat(50)).append("\n\n");
      }
      ElmTypeError e = errs.errors.get(i);
      b.append(locate(sourceFor(sourceByModule, entry, e), e).getMessage()).append("\n");
    }
    return new ElmTypeErrors(b.toString(), errs.errors);
  }

  /** Rebuilds an error with an Elm-style source excerpt, a caret under the offending code and a hint. */
  static ElmTypeError locate(String source, ElmTypeError err) {
    if (err.position == null) {
      return err;
    }
    StringBuilder b = new StringBuilder(err.rawMessage());
    String[] lines = source.split("\n", -1);
    int ln = err.position.line();
    if (ln >= 1 && ln <= lines.length) {
      String gutter = ln + " | ";
      b.append("\n\n").append(gutter).append(lines[ln - 1]);
      b.append("\n")
          .append(" ".repeat(gutter.length() + Math.max(0, err.position.col() - 1)))
          .append("^");
    }
    b.append("\n\n  at ")
        .append(err.module != null ? "module " + err.module + ", " : "")
        .append("line ").append(err.position.line()).append(", column ").append(err.position.col());
    if (err.hint != null) {
      b.append("\n\nHint: ").append(err.hint);
    }
    return new ElmTypeError(b.toString(), err.position, err.hint);
  }
}

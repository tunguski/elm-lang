package pl.matsuo.elm.types;

import java.util.LinkedHashMap;
import java.util.Map;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.error.ElmTypeError;
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
    Module module = Parser.parseModule(source);
    try {
      Map<String, Scheme> schemes = new Infer().inferModule(module, Signatures.globals());
      Map<String, String> result = new LinkedHashMap<>();
      schemes.forEach((name, scheme) -> result.put(name, Types.show(scheme.body())));
      return result;
    } catch (ElmTypeError err) {
      throw locate(source, err);
    }
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
    b.append("\n\n  at line ").append(err.position.line()).append(", column ")
        .append(err.position.col());
    if (err.hint != null) {
      b.append("\n\nHint: ").append(err.hint);
    }
    return new ElmTypeError(b.toString(), err.position, err.hint);
  }
}

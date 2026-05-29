package pl.matsuo.elm.types;

import java.util.LinkedHashMap;
import java.util.Map;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.parser.Parser;

/** Entry point for type inference. */
public final class TypeChecker {

  private TypeChecker() {}

  /** Infers the type of an expression and returns its rendered form (e.g. {@code "List number"}). */
  public static String infer(String expression) {
    Expr e = Parser.parseExpression(expression);
    Ty t = new Infer().infer(TypeEnv.root(Signatures.globals()), e);
    return Types.show(t);
  }

  /**
   * Type-checks a whole module, returning each top-level definition's inferred type (rendered).
   * Throws {@link pl.matsuo.elm.error.ElmTypeError} on a type error.
   */
  public static Map<String, String> checkModule(String source) {
    Module module = Parser.parseModule(source);
    Map<String, Scheme> schemes = new Infer().inferModule(module, Signatures.globals());
    Map<String, String> result = new LinkedHashMap<>();
    schemes.forEach((name, scheme) -> result.put(name, Types.show(scheme.body())));
    return result;
  }
}

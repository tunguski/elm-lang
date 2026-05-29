package pl.matsuo.elm.types;

import pl.matsuo.elm.ast.Expr;
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
}

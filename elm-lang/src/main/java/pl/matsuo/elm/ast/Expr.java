package pl.matsuo.elm.ast;

import java.util.List;
import pl.matsuo.elm.error.Position;

/** Elm expressions. Every node carries its source {@link Position} for error reporting. */
public sealed interface Expr {

  Position pos();

  record IntLit(long value, Position pos) implements Expr {}

  record FloatLit(double value, Position pos) implements Expr {}

  record StrLit(String value, Position pos) implements Expr {}

  record CharLit(int codePoint, Position pos) implements Expr {}

  /** A (possibly qualified) lowercase reference, e.g. {@code model} or {@code List.map}. */
  record Var(String module, String name, Position pos) implements Expr {}

  /** A (possibly qualified) constructor reference, e.g. {@code Nothing} or {@code Maybe.Just}. */
  record Ctor(String module, String name, Position pos) implements Expr {}

  /** An operator used as a value, e.g. {@code (+)}. */
  record OpFunc(String op, Position pos) implements Expr {}

  /** {@code [ a, b, ... ]} */
  record ListLit(List<Expr> items, Position pos) implements Expr {}

  /** {@code ( a, b, ... )} with two or more items. */
  record Tuple(List<Expr> items, Position pos) implements Expr {}

  /** {@code ()} */
  record Unit(Position pos) implements Expr {}

  /** {@code { f1 = e1, ... }} */
  record Record(List<Field> fields, Position pos) implements Expr {
    public record Field(String name, Expr value) {}
  }

  /** {@code { base | f1 = e1, ... }} */
  record RecordUpdate(String base, List<Record.Field> fields, Position pos) implements Expr {}

  /** {@code target.field} */
  record RecordAccess(Expr target, String field, Position pos) implements Expr {}

  /** {@code .field} as a function. */
  record Accessor(String field, Position pos) implements Expr {}

  /** Curried function application {@code fn arg}. */
  record App(Expr fn, Expr arg, Position pos) implements Expr {}

  /** A binary operator application {@code left op right}. */
  record BinOp(String op, Expr left, Expr right, Position pos) implements Expr {}

  /** Unary negation {@code -e}. */
  record Negate(Expr operand, Position pos) implements Expr {}

  /** {@code if cond then thenBranch else elseBranch} */
  record If(Expr cond, Expr thenBranch, Expr elseBranch, Position pos) implements Expr {}

  /** {@code \p1 p2 -> body} */
  record Lambda(List<Pattern> params, Expr body, Position pos) implements Expr {}

  /** {@code let defs in body} */
  record Let(List<Decl> defs, Expr body, Position pos) implements Expr {}

  /** {@code case scrutinee of branches} */
  record Case(Expr scrutinee, List<Branch> branches, Position pos) implements Expr {
    public record Branch(Pattern pattern, Expr body) {}
  }
}

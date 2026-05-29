package pl.matsuo.elm.ast;

import java.util.List;
import java.util.Optional;
import pl.matsuo.elm.error.Position;

/** Top-level and {@code let} declarations. */
public sealed interface Decl {

  Position pos();

  /**
   * A value or function definition: {@code name p1 p2 = body}. A value has no parameters. The
   * optional {@code annotation} is the type from a preceding {@code name : Type} line, if any.
   */
  record Value(
      String name, List<Pattern> params, Expr body, Optional<Type> annotation, Position pos)
      implements Decl {}

  /**
   * A destructuring binding allowed in {@code let}, e.g. {@code ( x, y ) = point}. The pattern is
   * matched against the value of {@code body}.
   */
  record Destructure(Pattern pattern, Expr body, Position pos) implements Decl {}

  /** {@code type alias Name a b = Type} */
  record TypeAlias(String name, List<String> params, Type type, Position pos) implements Decl {}

  /** {@code type Name a b = Ctor1 T.. | Ctor2 ..} */
  record Union(String name, List<String> params, List<Variant> variants, Position pos)
      implements Decl {
    public record Variant(String name, List<Type> args) {}
  }

  /** {@code port name : Type} */
  record Port(String name, Type type, Position pos) implements Decl {}
}

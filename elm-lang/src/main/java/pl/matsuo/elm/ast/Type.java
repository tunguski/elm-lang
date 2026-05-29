package pl.matsuo.elm.ast;

import java.util.List;
import java.util.Optional;

/**
 * Elm type expressions (used in annotations, {@code type alias} and custom-type constructor
 * arguments). The interpreter is dynamically typed and largely ignores these, but they must be
 * parsed to consume the source correctly.
 */
public sealed interface Type {

  /** A lowercase type variable, e.g. {@code a}. */
  record Var(String name) implements Type {}

  /** A (possibly qualified) type constructor applied to arguments, e.g. {@code Maybe Int}. */
  record Con(String module, String name, List<Type> args) implements Type {}

  /** A function type {@code from -> to}. */
  record Arrow(Type from, Type to) implements Type {}

  /** A tuple type {@code ( a, b, ... )}. */
  record Tuple(List<Type> items) implements Type {}

  /** The unit type {@code ()}. */
  record Unit() implements Type {}

  /** A record type, optionally extending a base variable: {@code { base | field : T, ... }}. */
  record Record(Optional<String> base, List<Field> fields) implements Type {
    public record Field(String name, Type type) {}
  }
}

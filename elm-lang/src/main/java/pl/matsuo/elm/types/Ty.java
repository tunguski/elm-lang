package pl.matsuo.elm.types;

import java.util.List;
import java.util.Map;

/**
 * Inference-time types (distinct from the surface {@link pl.matsuo.elm.ast.Type}). Type variables
 * are mutable union-find cells with a binding {@code link}, a {@code level} for let-generalization,
 * and an optional {@link Constraint} modelling Elm's built-in constrained type variables.
 */
public sealed interface Ty permits Ty.Var, Ty.Arrow, Ty.Con, Ty.Tuple, Ty.Record, Ty.Unit {

  /** Elm's special constrained type variables. */
  enum Constraint {
    NONE,
    NUMBER, // Int | Float
    COMPARABLE, // Int, Float, Char, String, lists/tuples thereof
    APPENDABLE, // String | List a
    COMPAPPEND // comparable AND appendable (String, List comparable)
  }

  /** A unification variable. Mutable: {@code link} is set when the variable is bound. */
  final class Var implements Ty {
    private static int counter = 0;

    public final int id = counter++;
    public int level;
    public Constraint constraint;
    public Ty link; // null while unbound

    public Var(int level, Constraint constraint) {
      this.level = level;
      this.constraint = constraint;
    }
  }

  record Arrow(Ty from, Ty to) implements Ty {}

  /** A type constructor application, e.g. {@code Int}, {@code List a}, {@code Maybe Int}. */
  record Con(String name, List<Ty> args) implements Ty {
    public Con(String name) {
      this(name, List.of());
    }
  }

  record Tuple(List<Ty> items) implements Ty {}

  /**
   * A record type. {@code tail} is {@code null} for a closed record, or a {@link Var} for an open
   * one ({@code { tail | fields }}), enabling row-polymorphic access/update.
   */
  record Record(Map<String, Ty> fields, Ty tail) implements Ty {}

  record Unit() implements Ty {}

  // --- convenience constructors -----------------------------------------

  Ty INT = new Con("Int");
  Ty FLOAT = new Con("Float");
  Ty BOOL = new Con("Bool");
  Ty STRING = new Con("String");
  Ty CHAR = new Con("Char");

  static Ty list(Ty elem) {
    return new Con("List", List.of(elem));
  }

  static Ty maybe(Ty a) {
    return new Con("Maybe", List.of(a));
  }
}

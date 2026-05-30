package pl.matsuo.elm.types;

import static pl.matsuo.elm.types.Types.prune;

import java.util.LinkedHashMap;
import java.util.Map;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.types.Ty.Constraint;

/** Unification with occurs check, level adjustment, constrained variables and row-polymorphic
 * records. Mutates variables in place. */
public final class Unify {

  private Unify() {}

  private static int depth = 0;

  /** Unifies two types, guarding against pathological deep recursion (reported as a type error
   * rather than a {@link StackOverflowError}). */
  public static void unify(Ty a0, Ty b0) {
    if (++depth > 400) {
      depth--;
      throw new ElmTypeError("Type is too complex to unify");
    }
    try {
      unify0(a0, b0);
    } finally {
      depth--;
    }
  }

  private static void unify0(Ty a0, Ty b0) {
    Ty a = prune(a0);
    Ty b = prune(b0);
    if (a == b) {
      return;
    }
    if (a instanceof Ty.Var va) {
      bind(va, b);
      return;
    }
    if (b instanceof Ty.Var vb) {
      bind(vb, a);
      return;
    }
    if (a instanceof Ty.Arrow aa && b instanceof Ty.Arrow bb) {
      unify(aa.from(), bb.from());
      unify(aa.to(), bb.to());
      return;
    }
    if (a instanceof Ty.Con ca && b instanceof Ty.Con cb) {
      if (!ca.name().equals(cb.name()) || ca.args().size() != cb.args().size()) {
        throw mismatch(a, b);
      }
      for (int i = 0; i < ca.args().size(); i++) {
        unify(ca.args().get(i), cb.args().get(i));
      }
      return;
    }
    if (a instanceof Ty.Tuple ta && b instanceof Ty.Tuple tb) {
      if (ta.items().size() != tb.items().size()) {
        throw mismatch(a, b);
      }
      for (int i = 0; i < ta.items().size(); i++) {
        unify(ta.items().get(i), tb.items().get(i));
      }
      return;
    }
    if (a instanceof Ty.Unit && b instanceof Ty.Unit) {
      return;
    }
    if (a instanceof Ty.Record ra && b instanceof Ty.Record rb) {
      unifyRecords(ra, rb);
      return;
    }
    throw mismatch(a, b);
  }

  private static void bind(Ty.Var v, Ty t) {
    if (t instanceof Ty.Var other) {
      if (other == v) {
        return;
      }
      // Propagate the combined constraint onto the surviving variable.
      other.constraint = combine(v.constraint, other.constraint, v, t);
      other.level = Math.min(other.level, v.level);
      v.link = other;
      return;
    }
    if (occurs(v, t)) {
      throw new ElmTypeError("Infinite type: " + Types.show(v) + " occurs in " + Types.show(t));
    }
    adjustLevels(t, v.level);
    if (v.constraint != Constraint.NONE) {
      checkConstraint(v.constraint, t);
    }
    v.link = t;
  }

  // --- constraints -------------------------------------------------------

  private static Constraint combine(Constraint x, Constraint y, Ty a, Ty b) {
    if (x == y || y == Constraint.NONE) {
      return x;
    }
    if (x == Constraint.NONE) {
      return y;
    }
    // number is the most specific of {number, comparable}; comparable+appendable = compappend.
    if (isOneOf(x, y, Constraint.NUMBER, Constraint.COMPARABLE)) {
      return Constraint.NUMBER;
    }
    if (isOneOf(x, y, Constraint.COMPARABLE, Constraint.APPENDABLE)) {
      return Constraint.COMPAPPEND;
    }
    if (isOneOf(x, y, Constraint.COMPARABLE, Constraint.COMPAPPEND)
        || isOneOf(x, y, Constraint.APPENDABLE, Constraint.COMPAPPEND)) {
      return Constraint.COMPAPPEND;
    }
    throw new ElmTypeError(
        "Cannot unify constrained variables " + Types.show(a) + " and " + Types.show(b));
  }

  private static boolean isOneOf(Constraint x, Constraint y, Constraint p, Constraint q) {
    return (x == p && y == q) || (x == q && y == p);
  }

  private static void checkConstraint(Constraint c, Ty t) {
    String name = t instanceof Ty.Con con ? con.name() : null;
    boolean ok =
        switch (c) {
          case NONE -> true;
          case NUMBER -> "Int".equals(name) || "Float".equals(name);
          case COMPARABLE ->
              "Int".equals(name) || "Float".equals(name) || "Char".equals(name)
                  || "String".equals(name) || "List".equals(name) || t instanceof Ty.Tuple;
          case APPENDABLE -> "String".equals(name) || "List".equals(name);
          case COMPAPPEND -> "String".equals(name) || "List".equals(name);
        };
    if (!ok) {
      throw new ElmTypeError(
          Types.show(t) + " does not satisfy the '" + c.name().toLowerCase() + "' constraint");
    }
  }

  // --- records -----------------------------------------------------------

  private static void unifyRecords(Ty.Record a, Ty.Record b) {
    Map<String, Ty> onlyA = new LinkedHashMap<>();
    Map<String, Ty> onlyB = new LinkedHashMap<>();
    for (var e : a.fields().entrySet()) {
      Ty other = b.fields().get(e.getKey());
      if (other != null) {
        unify(e.getValue(), other);
      } else {
        onlyA.put(e.getKey(), e.getValue());
      }
    }
    for (var e : b.fields().entrySet()) {
      if (!a.fields().containsKey(e.getKey())) {
        onlyB.put(e.getKey(), e.getValue());
      }
    }
    boolean aClosed = a.tail() == null;
    boolean bClosed = b.tail() == null;

    if (aClosed && bClosed) {
      if (!onlyA.isEmpty() || !onlyB.isEmpty()) {
        throw mismatch(a, b);
      }
      return;
    }
    if (aClosed) {
      // b is open; it cannot require fields a (closed) lacks, and its tail is exactly a's remainder.
      if (!onlyB.isEmpty()) {
        throw mismatch(a, b);
      }
      unify(b.tail(), new Ty.Record(onlyA, null));
      return;
    }
    if (bClosed) {
      if (!onlyA.isEmpty()) {
        throw mismatch(a, b);
      }
      unify(a.tail(), new Ty.Record(onlyB, null));
      return;
    }
    // Both open: if they share the same row variable, the surplus fields are irreconcilable.
    if (prune(a.tail()) == prune(b.tail())) {
      if (onlyA.isEmpty() && onlyB.isEmpty()) {
        return;
      }
      throw mismatch(a, b);
    }
    // Route each record's surplus through a shared fresh row variable. When a side has no surplus,
    // bind its row variable straight to the shared one rather than to an empty record `{ | rest }`:
    // wrapping a bare row variable in an empty open record produces records that re-unify and
    // re-route without end (an infinite loop), since `{ | r }` is a record, not a plain variable.
    Ty rest = new Ty.Var(0, Constraint.NONE);
    unify(a.tail(), onlyB.isEmpty() ? rest : new Ty.Record(onlyB, rest));
    unify(b.tail(), onlyA.isEmpty() ? rest : new Ty.Record(onlyA, rest));
  }

  // --- helpers -----------------------------------------------------------

  private static boolean occurs(Ty.Var v, Ty t0) {
    Ty t = prune(t0);
    return switch (t) {
      case Ty.Var v2 -> v2 == v;
      case Ty.Arrow a -> occurs(v, a.from()) || occurs(v, a.to());
      case Ty.Con c -> c.args().stream().anyMatch(x -> occurs(v, x));
      case Ty.Tuple tup -> tup.items().stream().anyMatch(x -> occurs(v, x));
      case Ty.Record r ->
          r.fields().values().stream().anyMatch(x -> occurs(v, x))
              || (r.tail() != null && occurs(v, r.tail()));
      case Ty.Unit ignored -> false;
    };
  }

  private static void adjustLevels(Ty t0, int level) {
    Ty t = prune(t0);
    switch (t) {
      case Ty.Var v -> v.level = Math.min(v.level, level);
      case Ty.Arrow a -> {
        adjustLevels(a.from(), level);
        adjustLevels(a.to(), level);
      }
      case Ty.Con c -> c.args().forEach(x -> adjustLevels(x, level));
      case Ty.Tuple tup -> tup.items().forEach(x -> adjustLevels(x, level));
      case Ty.Record r -> {
        r.fields().values().forEach(x -> adjustLevels(x, level));
        if (r.tail() != null) {
          adjustLevels(r.tail(), level);
        }
      }
      case Ty.Unit ignored -> {}
    }
  }

  private static ElmTypeError mismatch(Ty a, Ty b) {
    return new ElmTypeError("Type mismatch: " + Types.show(a) + " vs " + Types.show(b));
  }
}

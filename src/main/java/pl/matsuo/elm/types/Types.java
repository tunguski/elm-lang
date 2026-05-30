package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Core operations on {@link Ty}: pruning, generalization, instantiation and pretty-printing. */
public final class Types {

  private Types() {}

  /** Follows bound variables to the representative type. */
  public static Ty prune(Ty t) {
    if (t instanceof Ty.Var v && v.link != null) {
      v.link = prune(v.link);
      return v.link;
    }
    return t;
  }

  /** Prunes recursively, resolving variables inside arrows, constructors, tuples and records, so the
   *  returned type is fully substituted (used by consumers that inspect a type's structure). */
  public static Ty deepPrune(Ty t) {
    Ty p = prune(t);
    return switch (p) {
      case Ty.Arrow a -> new Ty.Arrow(deepPrune(a.from()), deepPrune(a.to()));
      case Ty.Con c -> new Ty.Con(c.name(), c.args().stream().map(Types::deepPrune).toList());
      case Ty.Tuple tu -> new Ty.Tuple(tu.items().stream().map(Types::deepPrune).toList());
      case Ty.Record r -> {
        // Flatten the row: a record closed by unification carries a tail Var linked to an empty
        // row rather than a literal null, so follow the chain, gathering every field, and report
        // the record as closed (tail == null) unless the chain ends in an unbound (open) Var.
        Map<String, Ty> fields = new LinkedHashMap<>();
        Ty tail = collectRow(r, fields);
        yield new Ty.Record(fields, tail);
      }
      default -> p;
    };
  }

  /** Accumulates a record's fields (deep-pruned), following its tail; returns the open tail Var, or
   *  null if the row is closed. */
  private static Ty collectRow(Ty.Record r, Map<String, Ty> out) {
    r.fields().forEach((k, v) -> out.putIfAbsent(k, deepPrune(v)));
    if (r.tail() == null) {
      return null;
    }
    Ty t = prune(r.tail());
    if (t instanceof Ty.Record rec) {
      return collectRow(rec, out);
    }
    return t instanceof Ty.Var ? t : null; // an unbound tail is open; anything else closes the row
  }

  // --- generalization / instantiation -----------------------------------

  /** Quantifies every unbound variable whose level is deeper than {@code level}. */
  public static Scheme generalize(Ty type, int level) {
    Set<Ty.Var> vars = new LinkedHashSet<>();
    collectGeneralizable(type, level, vars);
    return new Scheme(new ArrayList<>(vars), type);
  }

  private static void collectGeneralizable(Ty type, int level, Set<Ty.Var> acc) {
    Ty t = prune(type);
    switch (t) {
      case Ty.Var v -> {
        if (v.level > level) {
          acc.add(v);
        }
      }
      case Ty.Arrow a -> {
        collectGeneralizable(a.from(), level, acc);
        collectGeneralizable(a.to(), level, acc);
      }
      case Ty.Con c -> c.args().forEach(arg -> collectGeneralizable(arg, level, acc));
      case Ty.Tuple tup -> tup.items().forEach(i -> collectGeneralizable(i, level, acc));
      case Ty.Record r -> {
        r.fields().values().forEach(f -> collectGeneralizable(f, level, acc));
        if (r.tail() != null) {
          collectGeneralizable(r.tail(), level, acc);
        }
      }
      case Ty.Unit ignored -> {}
    }
  }

  /** Instantiates a scheme with fresh variables at {@code level}. */
  public static Ty instantiate(Scheme scheme, int level) {
    Map<Ty.Var, Ty.Var> fresh = new IdentityHashMap<>();
    for (Ty.Var v : scheme.vars()) {
      fresh.put(v, new Ty.Var(level, v.constraint));
    }
    return copy(scheme.body(), fresh);
  }

  private static Ty copy(Ty type, Map<Ty.Var, Ty.Var> fresh) {
    Ty t = prune(type);
    return switch (t) {
      case Ty.Var v -> fresh.getOrDefault(v, v);
      case Ty.Arrow a -> new Ty.Arrow(copy(a.from(), fresh), copy(a.to(), fresh));
      case Ty.Con c -> new Ty.Con(c.name(), c.args().stream().map(x -> copy(x, fresh)).toList());
      case Ty.Tuple tup ->
          new Ty.Tuple(tup.items().stream().map(x -> copy(x, fresh)).toList());
      case Ty.Record r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        r.fields().forEach((k, val) -> fields.put(k, copy(val, fresh)));
        yield new Ty.Record(fields, r.tail() == null ? null : copy(r.tail(), fresh));
      }
      case Ty.Unit u -> u;
    };
  }

  // --- pretty-printing ---------------------------------------------------

  public static String show(Ty type) {
    return show(type, new IdentityHashMap<>(), new int[] {0});
  }

  private static String show(Ty type, Map<Ty.Var, String> names, int[] next) {
    Ty t = prune(type);
    return switch (t) {
      case Ty.Var v -> {
        String name = names.computeIfAbsent(v, k -> varName(next[0]++));
        yield switch (v.constraint) {
          case NUMBER -> "number" + name.substring(1);
          case COMPARABLE -> "comparable" + name.substring(1);
          case APPENDABLE -> "appendable" + name.substring(1);
          case COMPAPPEND -> "compappend" + name.substring(1);
          case NONE -> name;
        };
      }
      case Ty.Arrow a -> {
        String from = show(a.from(), names, next);
        if (prune(a.from()) instanceof Ty.Arrow) {
          from = "(" + from + ")";
        }
        yield from + " -> " + show(a.to(), names, next);
      }
      case Ty.Con c -> {
        if (c.args().isEmpty()) {
          yield c.name();
        }
        yield c.name() + " "
            + c.args().stream().map(x -> showArg(x, names, next)).collect(Collectors.joining(" "));
      }
      case Ty.Tuple tup ->
          tup.items().stream()
              .map(x -> show(x, names, next))
              .collect(Collectors.joining(", ", "(", ")"));
      case Ty.Record r -> {
        String body =
            r.fields().entrySet().stream()
                .map(e -> e.getKey() + " : " + show(e.getValue(), names, next))
                .collect(Collectors.joining(", "));
        yield "{ " + body + " }";
      }
      case Ty.Unit ignored -> "()";
    };
  }

  private static String showArg(Ty type, Map<Ty.Var, String> names, int[] next) {
    Ty t = prune(type);
    String s = show(t, names, next);
    if ((t instanceof Ty.Con c && !c.args().isEmpty()) || t instanceof Ty.Arrow) {
      return "(" + s + ")";
    }
    return s;
  }

  private static String varName(int i) {
    return "" + (char) ('a' + (i % 26)) + (i < 26 ? "" : Integer.toString(i / 26));
  }
}

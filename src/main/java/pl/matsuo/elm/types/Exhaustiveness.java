package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmTypeError;

/**
 * Checks {@code case} expressions for missing and unreachable branches.
 *
 * <p>Exhaustiveness uses Maranget's usefulness algorithm (<i>Warnings for pattern matching</i>): a
 * match is non-exhaustive iff a fresh wildcard row is still <em>useful</em> against the matrix of
 * branch patterns, and the algorithm yields a witness value that escapes every branch. The full
 * constructor signature of a column is taken from the union registry built during inference (plus
 * the builtin unions {@code Bool}, {@code Maybe}, {@code Result} and {@code Order}) and from the
 * structural types {@code List}, tuples and {@code ()}; literal columns ({@code Int}/{@code String}
 * /{@code Char}) are treated as an infinite signature, so only a wildcard makes them exhaustive.
 *
 * <p>If a column's head constructor belongs to a type the registry doesn't know (e.g. a union
 * imported from a module that wasn't part of this check), the analysis aborts silently rather than
 * risk a false "missing patterns" report — soundness here means never rejecting a valid match.
 *
 * <p>Redundancy is reported for branches made unreachable by an earlier branch that always matches
 * (an irrefutable wildcard/variable/record/tuple pattern); a defensive trailing {@code _} after
 * complete constructor coverage is intentionally left alone.
 */
final class Exhaustiveness {

  /** Thrown internally to abort the analysis when a column's signature can't be determined. */
  private static final class Unknown extends RuntimeException {
    Unknown() {
      super(null, null, false, false);
    }
  }

  /** A normalized pattern: either a wildcard or a constructor applied to sub-patterns. */
  private sealed interface NPat {
    record Wild() implements NPat {}

    record Ctor(String key, String display, List<NPat> args) implements NPat {}
  }

  /** A constructor in a type's signature: its arity and how to render it in a witness. */
  private record Sig(String key, String display, int arity) {}

  /** Whether a set of head constructors forms a finite, fully-known signature. */
  private enum Kind {
    FINITE,
    INFINITE,
    UNKNOWN
  }

  private final Map<String, List<String>> unionCtors;
  private final Map<String, String> ctorUnion;
  private final Map<String, Integer> ctorArity;

  Exhaustiveness(
      Map<String, List<String>> unionCtors,
      Map<String, String> ctorUnion,
      Map<String, Integer> ctorArity) {
    this.unionCtors = unionCtors;
    this.ctorUnion = ctorUnion;
    this.ctorArity = ctorArity;
  }

  /** Reports missing/unreachable branches in {@code c}, throwing {@link ElmTypeError} if any. */
  void check(Expr.Case c) {
    List<Pattern> tops = c.branches().stream().map(Expr.Case.Branch::pattern).toList();

    // Redundancy: anything after an always-matching branch is unreachable.
    for (int i = 0; i < tops.size() - 1; i++) {
      if (irrefutable(tops.get(i))) {
        throw new ElmTypeError(
                "This pattern is unreachable: branch "
                    + (i + 2)
                    + " can never match, because branch "
                    + (i + 1)
                    + " already matches every value.")
            .at(c.pos(), "Remove the unreachable branch, or move it before the catch-all.");
      }
    }

    // Exhaustiveness via usefulness of a fresh wildcard row.
    List<NPat[]> matrix = new ArrayList<>();
    for (Pattern p : tops) {
      matrix.add(new NPat[] {normalize(p)});
    }
    String[] witness;
    try {
      witness = missing(matrix, 1);
    } catch (Unknown ignored) {
      return; // couldn't determine a column's signature — stay sound, report nothing
    }
    if (witness != null) {
      throw new ElmTypeError("This `case` does not handle all possible inputs.")
          .at(
              c.pos(),
              "Missing a branch for: " + witness[0] + ". Add it, or a wildcard `_ ->` branch.");
    }
  }

  // --- Maranget's algorithm I (witness-producing usefulness) -------------

  /**
   * Returns a witness row (one rendered pattern per column) describing a value matched by no row of
   * {@code matrix}, or {@code null} if the matrix is exhaustive over {@code width} columns.
   */
  private String[] missing(List<NPat[]> matrix, int width) {
    if (width == 0) {
      return matrix.isEmpty() ? new String[0] : null;
    }
    // Head constructors present in column 0.
    Map<String, Sig> present = new LinkedHashMap<>();
    for (NPat[] row : matrix) {
      if (row[0] instanceof NPat.Ctor ct) {
        present.put(ct.key(), new Sig(ct.key(), ct.display(), ct.args().size()));
      }
    }
    if (present.isEmpty()) {
      // All wildcards in this column: descend on the default matrix.
      String[] rest = missing(defaultMatrix(matrix), width - 1);
      return rest == null ? null : prepend("_", rest);
    }
    Kind kind = classify(present.values().iterator().next());
    if (kind == Kind.UNKNOWN) {
      throw new Unknown();
    }
    Map<String, Sig> full = kind == Kind.FINITE ? fullSignature(present.values().iterator().next()) : null;

    if (kind == Kind.FINITE && present.keySet().containsAll(full.keySet())) {
      // Complete signature: exhaustive iff every constructor's specialization is.
      for (Sig c : full.values()) {
        String[] sub = missing(specialize(matrix, c), c.arity() + width - 1);
        if (sub != null) {
          return prepend(render(c, sub), java.util.Arrays.copyOfRange(sub, c.arity(), sub.length));
        }
      }
      return null;
    }

    // Incomplete (or infinite) signature: a missing constructor escapes.
    String[] rest = missing(defaultMatrix(matrix), width - 1);
    if (rest == null) {
      return null;
    }
    String head;
    if (kind == Kind.FINITE) {
      Sig miss =
          full.values().stream().filter(s -> !present.containsKey(s.key())).findFirst().orElseThrow();
      head = render(miss, fill("_", miss.arity()));
    } else {
      head = "_"; // infinite domain (Int/String/Char): a wildcard branch is required
    }
    return prepend(head, rest);
  }

  /** Specialize the matrix to rows compatible with constructor {@code c}. */
  private List<NPat[]> specialize(List<NPat[]> matrix, Sig c) {
    List<NPat[]> out = new ArrayList<>();
    for (NPat[] row : matrix) {
      NPat head = row[0];
      NPat[] tail = java.util.Arrays.copyOfRange(row, 1, row.length);
      if (head instanceof NPat.Wild) {
        NPat[] expanded = new NPat[c.arity() + tail.length];
        java.util.Arrays.fill(expanded, 0, c.arity(), new NPat.Wild());
        System.arraycopy(tail, 0, expanded, c.arity(), tail.length);
        out.add(expanded);
      } else if (head instanceof NPat.Ctor ct && ct.key().equals(c.key())) {
        NPat[] expanded = new NPat[ct.args().size() + tail.length];
        for (int i = 0; i < ct.args().size(); i++) {
          expanded[i] = ct.args().get(i);
        }
        System.arraycopy(tail, 0, expanded, ct.args().size(), tail.length);
        out.add(expanded);
      }
    }
    return out;
  }

  /** The default matrix: rows whose first column is a wildcard, with that column dropped. */
  private List<NPat[]> defaultMatrix(List<NPat[]> matrix) {
    List<NPat[]> out = new ArrayList<>();
    for (NPat[] row : matrix) {
      if (row[0] instanceof NPat.Wild) {
        out.add(java.util.Arrays.copyOfRange(row, 1, row.length));
      }
    }
    return out;
  }

  // --- signatures --------------------------------------------------------

  private Kind classify(Sig head) {
    String key = head.key();
    if (key.equals("Cons") || key.equals("Nil") || key.startsWith("Tuple/") || key.equals("Unit")) {
      return Kind.FINITE;
    }
    if (key.startsWith("lit:")) {
      return Kind.INFINITE;
    }
    // A data constructor: known iff its union is in the registry.
    String ctor = key.substring(2); // strip "C:"
    return ctorUnion.containsKey(ctor) ? Kind.FINITE : Kind.UNKNOWN;
  }

  private Map<String, Sig> fullSignature(Sig head) {
    String key = head.key();
    Map<String, Sig> out = new LinkedHashMap<>();
    if (key.equals("Cons") || key.equals("Nil")) {
      out.put("Nil", new Sig("Nil", "[]", 0));
      out.put("Cons", new Sig("Cons", "_ :: _", 2));
    } else if (key.startsWith("Tuple/")) {
      out.put(key, head);
    } else if (key.equals("Unit")) {
      out.put("Unit", new Sig("Unit", "()", 0));
    } else {
      String union = ctorUnion.get(key.substring(2));
      for (String ctor : unionCtors.get(union)) {
        out.put("C:" + ctor, new Sig("C:" + ctor, ctor, ctorArity.getOrDefault(ctor, 0)));
      }
    }
    return out;
  }

  // --- normalization & rendering -----------------------------------------

  private NPat normalize(Pattern p) {
    return switch (p) {
      case Pattern.Wildcard ignored -> new NPat.Wild();
      case Pattern.Var ignored -> new NPat.Wild();
      case Pattern.RecordPat ignored -> new NPat.Wild(); // record destructure always matches
      case Pattern.Alias a -> normalize(a.pattern());
      case Pattern.Unit ignored -> new NPat.Ctor("Unit", "()", List.of());
      case Pattern.IntLit l -> new NPat.Ctor("lit:i" + l.value(), String.valueOf(l.value()), List.of());
      case Pattern.StrLit l -> new NPat.Ctor("lit:s" + l.value(), '"' + l.value() + '"', List.of());
      case Pattern.CharLit l ->
          new NPat.Ctor("lit:c" + l.codePoint(), "'" + (char) l.codePoint() + "'", List.of());
      case Pattern.Tuple t -> {
        List<NPat> args = t.items().stream().map(this::normalize).toList();
        yield new NPat.Ctor("Tuple/" + args.size(), "tuple", args);
      }
      case Pattern.Cons c -> new NPat.Ctor("Cons", "_ :: _", List.of(normalize(c.head()), normalize(c.tail())));
      case Pattern.ListPat l -> desugarList(l.items(), 0);
      case Pattern.Ctor c -> new NPat.Ctor("C:" + c.name(), c.name(), c.args().stream().map(this::normalize).toList());
    };
  }

  /** {@code [a, b, c]} is {@code a :: b :: c :: []} for the purpose of coverage. */
  private NPat desugarList(List<Pattern> items, int i) {
    if (i == items.size()) {
      return new NPat.Ctor("Nil", "[]", List.of());
    }
    return new NPat.Ctor("Cons", "_ :: _", List.of(normalize(items.get(i)), desugarList(items, i + 1)));
  }

  /** Renders constructor {@code c} applied to the first {@code arity} rendered sub-patterns. */
  private String render(Sig c, String[] subWitness) {
    int a = c.arity();
    if (c.key().equals("Nil")) {
      return "[]";
    }
    if (c.key().equals("Cons")) {
      return "(" + subWitness[0] + " :: " + subWitness[1] + ")";
    }
    if (c.key().startsWith("Tuple/")) {
      return "( " + String.join(", ", java.util.Arrays.copyOf(subWitness, a)) + " )";
    }
    if (c.key().equals("Unit")) {
      return "()";
    }
    if (a == 0) {
      return c.display();
    }
    StringBuilder sb = new StringBuilder("(").append(c.display());
    for (int i = 0; i < a; i++) {
      sb.append(' ').append(subWitness[i]);
    }
    return sb.append(')').toString();
  }

  private static String[] prepend(String head, String[] rest) {
    String[] out = new String[rest.length + 1];
    out[0] = head;
    System.arraycopy(rest, 0, out, 1, rest.length);
    return out;
  }

  private static String[] fill(String v, int n) {
    String[] out = new String[n];
    java.util.Arrays.fill(out, v);
    return out;
  }

  private static boolean irrefutable(Pattern p) {
    return switch (p) {
      case Pattern.Wildcard ignored -> true;
      case Pattern.Var ignored -> true;
      case Pattern.RecordPat ignored -> true;
      case Pattern.Unit ignored -> true;
      case Pattern.Alias a -> irrefutable(a.pattern());
      case Pattern.Tuple t -> t.items().stream().allMatch(Exhaustiveness::irrefutable);
      default -> false;
    };
  }
}

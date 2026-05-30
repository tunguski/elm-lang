package pl.matsuo.elm.interp;

import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.Builtin;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmList;

/** Implements Elm's built-in binary/unary operators and structural comparison/equality. */
public final class Operators {

  private Operators() {}

  /** Evaluates a binary operator eagerly. {@code &&}/{@code ||} short-circuiting is done in the
   * BinOp node; here they evaluate both operands (used only when an operator is taken as a value). */
  public static Object binary(String op, Object l, Object r) {
    return switch (op) {
      case "+" -> arith(op, l, r);
      case "-" -> arith(op, l, r);
      case "*" -> arith(op, l, r);
      case "^" -> arith(op, l, r);
      case "/" -> toDouble(l) / toDouble(r);
      case "//" -> asLong(l) / asLong(r);
      case "==" -> equals(l, r);
      case "/=" -> !equals(l, r);
      case "<" -> compareValues(l, r) < 0;
      case ">" -> compareValues(l, r) > 0;
      case "<=" -> compareValues(l, r) <= 0;
      case ">=" -> compareValues(l, r) >= 0;
      case "&&" -> (Boolean) l && (Boolean) r;
      case "||" -> (Boolean) l || (Boolean) r;
      case "++" -> append(l, r);
      case "::" -> ElmList.cons(l, (ElmList) r);
      case "|>" -> Apply.apply(r, l);
      case "<|" -> Apply.apply(l, r);
      case "<<" -> compose(l, r);
      case ">>" -> compose(r, l);
      default -> throw new ElmRuntimeError("Unknown operator " + op);
    };
  }

  /** Whether {@code op} is a built-in operator (else it's a user/package-defined infix function). */
  public static boolean isBuiltin(String op) {
    return switch (op) {
      case "+", "-", "*", "^", "/", "//", "==", "/=", "<", ">", "<=", ">=", "&&", "||", "++", "::",
              "|>", "<|", "<<", ">>" ->
          true;
      default -> false;
    };
  }

  /** Returns a callable value for an operator used as a function, e.g. {@code (+)}. */
  public static Builtin asFunction(String op) {
    return new Builtin("(" + op + ")", 2, args -> binary(op, args[0], args[1]));
  }

  private static Object compose(Object f, Object g) {
    // (f << g) x = f (g x)
    return new Builtin("<<", 1, args -> Apply.apply(f, Apply.apply(g, args[0])));
  }

  private static Object arith(String op, Object l, Object r) {
    if (l instanceof Long a && r instanceof Long b) {
      return switch (op) {
        case "+" -> a + b;
        case "-" -> a - b;
        case "*" -> a * b;
        case "^" -> (long) Math.pow(a, b);
        default -> throw new ElmRuntimeError("bad arith " + op);
      };
    }
    double a = toDouble(l);
    double b = toDouble(r);
    return switch (op) {
      case "+" -> a + b;
      case "-" -> a - b;
      case "*" -> a * b;
      case "^" -> Math.pow(a, b);
      default -> throw new ElmRuntimeError("bad arith " + op);
    };
  }

  public static Object negate(Object v) {
    if (v instanceof Long l) {
      return -l;
    }
    if (v instanceof Double d) {
      return -d;
    }
    throw new ElmRuntimeError("Cannot negate " + v);
  }

  private static Object append(Object l, Object r) {
    if (l instanceof String a && r instanceof String b) {
      return a + b;
    }
    if (l instanceof ElmList a && r instanceof ElmList b) {
      return concat(a, b);
    }
    throw new ElmRuntimeError("(++) expects two Strings or two Lists");
  }

  private static ElmList concat(ElmList a, ElmList b) {
    if (a instanceof ElmList.Nil) {
      return b;
    }
    ElmList.Cons c = (ElmList.Cons) a;
    return ElmList.cons(c.head(), concat(c.tail(), b));
  }

  // --- comparison & equality --------------------------------------------

  public static boolean equals(Object l, Object r) {
    if (isNumber(l) && isNumber(r)) {
      if (l instanceof Long a && r instanceof Long b) {
        return a.longValue() == b.longValue();
      }
      return toDouble(l) == toDouble(r);
    }
    return l.equals(r);
  }

  public static int compareValues(Object l, Object r) {
    if (isNumber(l) && isNumber(r)) {
      if (l instanceof Long a && r instanceof Long b) {
        return Long.compare(a, b);
      }
      return Double.compare(toDouble(l), toDouble(r));
    }
    if (l instanceof ElmChar a && r instanceof ElmChar b) {
      return Integer.compare(a.codePoint(), b.codePoint());
    }
    if (l instanceof String a && r instanceof String b) {
      return a.compareTo(b);
    }
    if (l instanceof ElmList a && r instanceof ElmList b) {
      return compareLists(a, b);
    }
    if (l instanceof pl.matsuo.elm.runtime.ElmTuple a
        && r instanceof pl.matsuo.elm.runtime.ElmTuple b) {
      int n = Math.min(a.size(), b.size());
      for (int i = 0; i < n; i++) {
        int c = compareValues(a.get(i), b.get(i));
        if (c != 0) {
          return c;
        }
      }
      return Integer.compare(a.size(), b.size());
    }
    throw new ElmRuntimeError("Cannot compare values: " + l + " and " + r);
  }

  private static int compareLists(ElmList a, ElmList b) {
    while (a instanceof ElmList.Cons ca && b instanceof ElmList.Cons cb) {
      int c = compareValues(ca.head(), cb.head());
      if (c != 0) {
        return c;
      }
      a = ca.tail();
      b = cb.tail();
    }
    boolean aEmpty = a instanceof ElmList.Nil;
    boolean bEmpty = b instanceof ElmList.Nil;
    return Boolean.compare(!aEmpty, !bEmpty);
  }

  private static boolean isNumber(Object o) {
    return o instanceof Long || o instanceof Double;
  }

  public static double toDouble(Object o) {
    if (o instanceof Long l) {
      return l;
    }
    if (o instanceof Double d) {
      return d;
    }
    throw new ElmRuntimeError("Expected a number but got " + o);
  }

  public static long asLong(Object o) {
    if (o instanceof Long l) {
      return l;
    }
    throw new ElmRuntimeError("Expected an Int but got " + o);
  }
}

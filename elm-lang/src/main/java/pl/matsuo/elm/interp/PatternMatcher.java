package pl.matsuo.elm.interp;

import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmRecord;
import pl.matsuo.elm.runtime.ElmTuple;

/** Matches a {@link Pattern} against a runtime value, binding variables into a {@link Scope}. */
public final class PatternMatcher {

  private PatternMatcher() {}

  /** Returns whether the value matches; on success, side-effects bindings into {@code scope}. */
  public static boolean match(Pattern pattern, Object value, Scope scope) {
    return switch (pattern) {
      case Pattern.Wildcard ignored -> true;
      case Pattern.Var v -> {
        scope.bind(v.name(), value);
        yield true;
      }
      case Pattern.Unit ignored -> true;
      case Pattern.IntLit i -> value instanceof Long l && l == i.value();
      case Pattern.StrLit s -> s.value().equals(value);
      case Pattern.CharLit c -> value instanceof ElmChar ch && ch.codePoint() == c.codePoint();
      case Pattern.Alias a -> {
        if (!match(a.pattern(), value, scope)) {
          yield false;
        }
        scope.bind(a.name(), value);
        yield true;
      }
      case Pattern.Tuple t -> {
        if (!(value instanceof ElmTuple tup) || tup.size() != t.items().size()) {
          yield false;
        }
        for (int i = 0; i < t.items().size(); i++) {
          if (!match(t.items().get(i), tup.get(i), scope)) {
            yield false;
          }
        }
        yield true;
      }
      case Pattern.Ctor c -> matchCtor(c, value, scope);
      case Pattern.ListPat l -> matchList(l, value, scope);
      case Pattern.Cons cons -> matchCons(cons, value, scope);
      case Pattern.RecordPat r -> {
        if (!(value instanceof ElmRecord rec)) {
          yield false;
        }
        for (String field : r.fields()) {
          scope.bind(field, rec.get(field));
        }
        yield true;
      }
    };
  }

  private static boolean matchCtor(Pattern.Ctor c, Object value, Scope scope) {
    // Bool constructors match Java booleans.
    if (c.name().equals("True")) {
      return Boolean.TRUE.equals(value);
    }
    if (c.name().equals("False")) {
      return Boolean.FALSE.equals(value);
    }
    if (!(value instanceof ElmData data)
        || !data.ctor().equals(c.name())
        || data.args().length != c.args().size()) {
      return false;
    }
    for (int i = 0; i < c.args().size(); i++) {
      if (!match(c.args().get(i), data.arg(i), scope)) {
        return false;
      }
    }
    return true;
  }

  private static boolean matchList(Pattern.ListPat l, Object value, Scope scope) {
    ElmList cur = value instanceof ElmList list ? list : null;
    if (cur == null) {
      return false;
    }
    for (Pattern item : l.items()) {
      if (!(cur instanceof ElmList.Cons cell)) {
        return false;
      }
      if (!match(item, cell.head(), scope)) {
        return false;
      }
      cur = cell.tail();
    }
    return cur instanceof ElmList.Nil;
  }

  private static boolean matchCons(Pattern.Cons cons, Object value, Scope scope) {
    if (!(value instanceof ElmList.Cons cell)) {
      return false;
    }
    return match(cons.head(), cell.head(), scope) && match(cons.tail(), cell.tail(), scope);
  }
}

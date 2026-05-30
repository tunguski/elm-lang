package pl.matsuo.elm.runtime;

import java.util.ArrayList;
import java.util.List;

/** An immutable Elm list, represented faithfully as nil/cons cells. */
public sealed interface ElmList permits ElmList.Nil, ElmList.Cons {

  record Nil() implements ElmList {}

  record Cons(Object head, ElmList tail) implements ElmList {}

  ElmList NIL = new Nil();

  static ElmList cons(Object head, ElmList tail) {
    return new Cons(head, tail);
  }

  /** Builds a list from a Java list, preserving order. */
  static ElmList fromJava(List<?> items) {
    ElmList result = NIL;
    for (int i = items.size() - 1; i >= 0; i--) {
      result = new Cons(items.get(i), result);
    }
    return result;
  }

  default boolean isEmpty() {
    return this instanceof Nil;
  }

  /** Converts to a Java list (eagerly walking the spine). */
  default List<Object> toJava() {
    List<Object> out = new ArrayList<>();
    ElmList cur = this;
    while (cur instanceof Cons c) {
      out.add(c.head());
      cur = c.tail();
    }
    return out;
  }
}

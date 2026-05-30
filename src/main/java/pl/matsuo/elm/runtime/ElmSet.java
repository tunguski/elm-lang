package pl.matsuo.elm.runtime;

import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** An immutable Elm {@code Set}, ordered by Elm's structural comparison (comparator from prelude). */
public final class ElmSet {

  private final TreeSet<Object> elements;
  private final Comparator<Object> comparator;

  private ElmSet(TreeSet<Object> elements, Comparator<Object> comparator) {
    this.elements = elements;
    this.comparator = comparator;
  }

  public static ElmSet empty(Comparator<Object> comparator) {
    return new ElmSet(new TreeSet<>(comparator), comparator);
  }

  private TreeSet<Object> copy() {
    TreeSet<Object> c = new TreeSet<>(comparator);
    c.addAll(elements);
    return c;
  }

  public ElmSet insert(Object value) {
    TreeSet<Object> c = copy();
    c.add(value);
    return new ElmSet(c, comparator);
  }

  public ElmSet remove(Object value) {
    if (!elements.contains(value)) {
      return this;
    }
    TreeSet<Object> c = copy();
    c.remove(value);
    return new ElmSet(c, comparator);
  }

  public boolean member(Object value) {
    return elements.contains(value);
  }

  public int size() {
    return elements.size();
  }

  public Set<Object> elements() {
    return elements;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ElmSet s && elements.equals(s.elements);
  }

  @Override
  public int hashCode() {
    return elements.hashCode();
  }

  @Override
  public String toString() {
    return elements.stream().map(String::valueOf).collect(Collectors.joining(",", "Set.fromList [", "]"));
  }
}

package pl.matsuo.elm.runtime;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * An immutable Elm {@code Dict}, ordered by key using Elm's structural comparison. The comparator
 * is supplied by the prelude (so this low-level type stays free of interpreter dependencies);
 * functional updates copy the backing {@link TreeMap}.
 */
public final class ElmDict {

  private final TreeMap<Object, Object> entries;
  private final Comparator<Object> comparator;

  private ElmDict(TreeMap<Object, Object> entries, Comparator<Object> comparator) {
    this.entries = entries;
    this.comparator = comparator;
  }

  public static ElmDict empty(Comparator<Object> comparator) {
    return new ElmDict(new TreeMap<>(comparator), comparator);
  }

  private TreeMap<Object, Object> copy() {
    TreeMap<Object, Object> c = new TreeMap<>(comparator);
    c.putAll(entries);
    return c;
  }

  public ElmDict insert(Object key, Object value) {
    TreeMap<Object, Object> c = copy();
    c.put(key, value);
    return new ElmDict(c, comparator);
  }

  public ElmDict remove(Object key) {
    if (!entries.containsKey(key)) {
      return this;
    }
    TreeMap<Object, Object> c = copy();
    c.remove(key);
    return new ElmDict(c, comparator);
  }

  public boolean member(Object key) {
    return entries.containsKey(key);
  }

  /** Returns the value for {@code key}, or {@code null} if absent (callers wrap as Maybe). */
  public Object getOrNull(Object key) {
    return entries.get(key);
  }

  public int size() {
    return entries.size();
  }

  public Map<Object, Object> entries() {
    return entries;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ElmDict d && entries.equals(d.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return entries.entrySet().stream()
        .map(e -> "(" + e.getKey() + "," + e.getValue() + ")")
        .collect(Collectors.joining(",", "Dict.fromList [", "]"));
  }
}

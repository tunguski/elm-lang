package pl.matsuo.elm.runtime;

import java.util.Arrays;
import java.util.stream.Collectors;

/** An immutable Elm {@code Array}, backed by a copy-on-write object array. */
public record ElmArray(Object[] items) {

  public static final ElmArray EMPTY = new ElmArray(new Object[0]);

  public int length() {
    return items.length;
  }

  public Object get(int i) {
    return items[i];
  }

  public ElmArray set(int i, Object value) {
    if (i < 0 || i >= items.length) {
      return this;
    }
    Object[] copy = items.clone();
    copy[i] = value;
    return new ElmArray(copy);
  }

  public ElmArray push(Object value) {
    Object[] copy = Arrays.copyOf(items, items.length + 1);
    copy[items.length] = value;
    return new ElmArray(copy);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ElmArray a && Arrays.deepEquals(items, a.items);
  }

  @Override
  public int hashCode() {
    return Arrays.deepHashCode(items);
  }

  @Override
  public String toString() {
    return Arrays.stream(items)
        .map(String::valueOf)
        .collect(Collectors.joining(",", "Array.fromList [", "]"));
  }
}

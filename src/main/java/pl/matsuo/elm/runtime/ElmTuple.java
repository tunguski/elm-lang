package pl.matsuo.elm.runtime;

import java.util.Arrays;
import java.util.stream.Collectors;

/** An Elm tuple ({@code (a, b)} or {@code (a, b, c)}). */
public record ElmTuple(Object[] values) {

  public int size() {
    return values.length;
  }

  public Object get(int i) {
    return values[i];
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ElmTuple t && Arrays.deepEquals(values, t.values);
  }

  @Override
  public int hashCode() {
    return Arrays.deepHashCode(values);
  }

  @Override
  public String toString() {
    return Arrays.stream(values).map(String::valueOf).collect(Collectors.joining(", ", "(", ")"));
  }
}

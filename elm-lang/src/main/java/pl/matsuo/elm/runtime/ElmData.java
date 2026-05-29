package pl.matsuo.elm.runtime;

import java.util.Arrays;

/**
 * A custom-type (tagged union) value: a constructor name plus its arguments. For example {@code
 * Just 5} is {@code new ElmData("Just", [5L])} and {@code Nothing} is {@code new ElmData("Nothing",
 * [])}. {@code Bool} is represented as a Java {@code Boolean}, not as {@code ElmData}.
 */
public record ElmData(String ctor, Object[] args) {

  public Object arg(int i) {
    return args[i];
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ElmData d && ctor.equals(d.ctor) && Arrays.deepEquals(args, d.args);
  }

  @Override
  public int hashCode() {
    return 31 * ctor.hashCode() + Arrays.deepHashCode(args);
  }

  @Override
  public String toString() {
    if (args.length == 0) {
      return ctor;
    }
    StringBuilder sb = new StringBuilder(ctor);
    for (Object a : args) {
      sb.append(' ').append(a);
    }
    return sb.toString();
  }
}

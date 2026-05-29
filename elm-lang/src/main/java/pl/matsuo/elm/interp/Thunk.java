package pl.matsuo.elm.interp;

import java.util.function.Supplier;

/**
 * A lazily-evaluated, memoized top-level value. Top-level definitions without parameters are stored
 * as thunks so they may reference one another regardless of source order (Elm definitions are
 * order-independent).
 */
public final class Thunk {

  private Supplier<Object> compute;
  private Object value;
  private boolean forced;

  public Thunk(Supplier<Object> compute) {
    this.compute = compute;
  }

  public Object force() {
    if (!forced) {
      value = compute.get();
      forced = true;
      compute = null;
    }
    return value;
  }

  /** Forces {@code o} if it is a thunk; otherwise returns it unchanged. */
  public static Object resolve(Object o) {
    return o instanceof Thunk t ? t.force() : o;
  }
}

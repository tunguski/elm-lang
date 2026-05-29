package pl.matsuo.elm.interp;

import java.util.Arrays;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmCallable;
import pl.matsuo.elm.runtime.PartialApp;

/**
 * Implements Elm's curried function application one argument at a time, collecting arguments into a
 * {@link PartialApp} until a callable's arity is reached, then invoking it.
 */
public final class Apply {

  private Apply() {}

  public static Object apply(Object fn, Object arg) {
    if (fn instanceof PartialApp pa) {
      Object[] next = Arrays.copyOf(pa.collected(), pa.collected().length + 1);
      next[next.length - 1] = arg;
      if (next.length == pa.base().arity()) {
        return pa.base().invoke(next);
      }
      return new PartialApp(pa.base(), next);
    }
    if (fn instanceof ElmCallable c) {
      if (c.arity() <= 1) {
        return c.invoke(new Object[] {arg});
      }
      return new PartialApp(c, new Object[] {arg});
    }
    throw new ElmRuntimeError("Tried to call a non-function value: " + fn);
  }

  /** Applies {@code fn} to several arguments left-to-right. */
  public static Object applyAll(Object fn, Object... args) {
    Object result = fn;
    for (Object a : args) {
      result = apply(result, a);
    }
    return result;
  }
}

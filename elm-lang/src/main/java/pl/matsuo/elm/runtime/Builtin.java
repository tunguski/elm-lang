package pl.matsuo.elm.runtime;

import java.util.function.Function;

/** A primitive function implemented in Java. */
public final class Builtin implements ElmCallable {

  private final String name;
  private final int arity;
  private final Function<Object[], Object> impl;

  public Builtin(String name, int arity, Function<Object[], Object> impl) {
    this.name = name;
    this.arity = arity;
    this.impl = impl;
  }

  @Override
  public int arity() {
    return arity;
  }

  @Override
  public Object invoke(Object[] args) {
    return impl.apply(args);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return "<builtin " + name + ">";
  }
}

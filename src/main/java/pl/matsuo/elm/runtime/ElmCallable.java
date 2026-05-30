package pl.matsuo.elm.runtime;

/** A callable Elm value: a {@link Closure} or a {@link Builtin}. */
public interface ElmCallable {

  /** The number of arguments this callable consumes before producing a result. */
  int arity();

  /** Invokes with exactly {@link #arity()} arguments. */
  Object invoke(Object[] args);

  /** A human-readable name for diagnostics. */
  String name();
}

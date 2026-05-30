package pl.matsuo.elm.bytecode;

import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.PatternMatcher;
import pl.matsuo.elm.interp.RuntimeEnv;
import pl.matsuo.elm.interp.Scope;
import pl.matsuo.elm.runtime.ElmCallable;

/** A function value for the bytecode VM: a {@link Chunk} plus the lexical scope captured at the
 * point of definition. */
public final class BytecodeClosure implements ElmCallable {

  private final Chunk chunk;
  private final Scope captured;
  private final RuntimeEnv env;

  public BytecodeClosure(Chunk chunk, Scope captured, RuntimeEnv env) {
    this.chunk = chunk;
    this.captured = captured;
    this.env = env;
  }

  @Override
  public int arity() {
    return chunk.params().size();
  }

  @Override
  public Object invoke(Object[] args) {
    Scope local = captured.child();
    for (int i = 0; i < args.length; i++) {
      if (!PatternMatcher.match(chunk.params().get(i), args[i], local)) {
        throw new ElmRuntimeError("Argument pattern did not match in " + chunk.name());
      }
    }
    return VM.run(chunk, local, env);
  }

  @Override
  public String name() {
    return chunk.name();
  }

  @Override
  public String toString() {
    return "<bytecode " + chunk.name() + "/" + arity() + ">";
  }
}

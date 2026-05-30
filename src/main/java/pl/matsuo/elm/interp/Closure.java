package pl.matsuo.elm.interp;

import com.oracle.truffle.api.RootCallTarget;
import java.util.List;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.runtime.ElmCallable;

/**
 * A user-defined function value: the compiled body (as a Truffle {@link RootCallTarget}), the
 * parameter patterns, and the lexical scope captured at the definition site.
 */
public final class Closure implements ElmCallable {

  private final String name;
  private final RootCallTarget target;
  private final Scope captured;
  private final List<Pattern> params;

  public Closure(String name, RootCallTarget target, Scope captured, List<Pattern> params) {
    this.name = name;
    this.target = target;
    this.captured = captured;
    this.params = params;
  }

  @Override
  public int arity() {
    return params.size();
  }

  @Override
  public Object invoke(Object[] args) {
    Scope local = captured.child();
    for (int i = 0; i < params.size(); i++) {
      if (!PatternMatcher.match(params.get(i), args[i], local)) {
        throw new ElmRuntimeError("Argument does not match parameter pattern in " + name);
      }
    }
    return target.call(local);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return "<function " + name + "/" + arity() + ">";
  }
}

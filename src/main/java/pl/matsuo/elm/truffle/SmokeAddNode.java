package pl.matsuo.elm.truffle;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * Minimal Truffle DSL node used only to verify that the Truffle annotation processor and runtime
 * are wired up correctly. The real language nodes live elsewhere.
 */
public abstract class SmokeAddNode extends Node {

  public abstract Object execute(Object left, Object right);

  @Specialization
  protected long addLongs(long left, long right) {
    return left + right;
  }

  @Specialization
  protected double addDoubles(double left, double right) {
    return left + right;
  }
}

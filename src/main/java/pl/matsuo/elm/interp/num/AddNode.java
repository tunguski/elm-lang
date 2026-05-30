package pl.matsuo.elm.interp.num;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import pl.matsuo.elm.interp.Operators;

/** Specializing {@code +}: fast paths for two longs and two doubles. */
public abstract class AddNode extends BinaryOp {

  @Specialization
  protected long longs(long left, long right) {
    return left + right;
  }

  @Specialization
  protected double doubles(double left, double right) {
    return left + right;
  }

  @Fallback
  protected Object generic(Object left, Object right) {
    return Operators.binary("+", left, right);
  }
}

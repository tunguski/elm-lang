package pl.matsuo.elm.interp.num;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import pl.matsuo.elm.interp.Operators;

/** Specializing {@code ==}. {@code /=} is the negation of this, handled by the BinOp node. */
public abstract class EqualsNode extends BinaryOp {

  @Specialization
  protected boolean longs(long left, long right) {
    return left == right;
  }

  @Specialization
  protected boolean doubles(double left, double right) {
    return left == right;
  }

  @Specialization
  protected boolean booleans(boolean left, boolean right) {
    return left == right;
  }

  @Fallback
  protected Object generic(Object left, Object right) {
    return Operators.equals(left, right);
  }
}

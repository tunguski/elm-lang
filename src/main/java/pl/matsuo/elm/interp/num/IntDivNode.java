package pl.matsuo.elm.interp.num;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import pl.matsuo.elm.interp.Operators;

/** Specializing {@code //} (integer division): a fast path for two longs (the common hot-loop case),
 *  with the general coercing path as a fallback. */
public abstract class IntDivNode extends BinaryOp {

  @Specialization
  protected long longs(long left, long right) {
    return left / right;
  }

  @Fallback
  protected Object generic(Object left, Object right) {
    return Operators.binary("//", left, right);
  }
}

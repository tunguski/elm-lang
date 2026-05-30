package pl.matsuo.elm.interp.num;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import pl.matsuo.elm.interp.Operators;

/**
 * Specializing {@code <}. Numbers compare directly; everything else (String, Char, List, Tuple)
 * falls back to structural comparison. {@code >}, {@code <=}, {@code >=} are derived by the BinOp
 * node via operand swapping/negation.
 */
public abstract class LessNode extends BinaryOp {

  @Specialization
  protected boolean longs(long left, long right) {
    return left < right;
  }

  @Specialization
  protected boolean doubles(double left, double right) {
    return left < right;
  }

  @Fallback
  protected Object generic(Object left, Object right) {
    return Operators.compareValues(left, right) < 0;
  }
}

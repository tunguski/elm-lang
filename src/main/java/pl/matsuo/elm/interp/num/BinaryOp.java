package pl.matsuo.elm.interp.num;

import com.oracle.truffle.api.nodes.Node;

/**
 * Base class for self-specializing binary operator nodes built with the Truffle DSL. Operands are
 * already-evaluated values; specializations (e.g. {@code long,long}) let the Graal compiler emit
 * monomorphic primitive arithmetic for hot code, with an {@code @Fallback} for the general case.
 */
public abstract class BinaryOp extends Node {

  public abstract Object execute(Object left, Object right);
}

package pl.matsuo.elm.truffle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;

/** Verifies the Truffle DSL processor generates node code and a CallTarget can execute it. */
class TruffleSmokeTest {

  /** A trivial root node that adds its two arguments via the generated {@code SmokeAddNodeGen}. */
  static final class AddRoot extends RootNode {
    @Child private SmokeAddNode add = SmokeAddNodeGen.create();

    AddRoot() {
      super(null);
    }

    @Override
    public Object execute(VirtualFrame frame) {
      Object[] args = frame.getArguments();
      return add.execute(args[0], args[1]);
    }
  }

  @Test
  void addsLongs() {
    Object result = new AddRoot().getCallTarget().call(2L, 3L);
    assertEquals(5L, result);
  }

  @Test
  void addsDoubles() {
    Object result = new AddRoot().getCallTarget().call(2.5, 3.0);
    assertEquals(5.5, result);
  }
}

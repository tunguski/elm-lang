package pl.matsuo.elm.interp;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The root of a compiled function (or lambda) body. The invoking {@link Closure} passes the
 * already-extended {@link Scope} as the single call argument; this root evaluates the body in it.
 */
public final class ElmRootNode extends RootNode {

  @Child private ElmNode body;

  public ElmRootNode(ElmNode body) {
    super(null);
    this.body = body;
  }

  @Override
  public Object execute(VirtualFrame frame) {
    Scope scope = (Scope) frame.getArguments()[0];
    return body.execute(scope);
  }
}

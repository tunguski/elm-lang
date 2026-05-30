package pl.matsuo.elm.interp;

import com.oracle.truffle.api.nodes.Node;

/**
 * Base class for all Elm expression nodes. Each node evaluates against a {@link Scope} (threaded as
 * a plain argument rather than via frame slots). Nodes form a Truffle tree rooted at an {@link
 * ElmRootNode}, so on GraalVM the Graal compiler partially evaluates hot trees into machine code.
 */
public abstract class ElmNode extends Node {

  public abstract Object execute(Scope scope);
}

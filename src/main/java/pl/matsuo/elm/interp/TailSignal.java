package pl.matsuo.elm.interp;

/**
 * Internal sentinel returned by a {@link Nodes.SelfTailCall} node: instead of recursing, it carries
 * the arguments for the next iteration up to the enclosing {@link Nodes.TailLoop}, which rebinds
 * the parameters and loops. This gives constant-stack self-recursion (tail-call optimization).
 */
public record TailSignal(Object[] args) {}

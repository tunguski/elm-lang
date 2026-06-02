package pl.matsuo.elm.bytecode;

import java.util.List;
import java.util.Map;

/**
 * A fully-compiled Elm program in a portable, serializable form: the name-resolution tables the
 * {@link VM} needs plus each top-level definition's {@link Chunk} bytecode. The runtime builtins
 * (which are Java code, available on every platform — including Android's ART) are <em>not</em>
 * stored; they are re-bound from {@link pl.matsuo.elm.interp.Prelude} when the program is reloaded.
 *
 * <p>This is what {@link BytecodeWriter} emits and {@link BytecodeReader} reads, so a module can be
 * compiled once and the bytecode shipped and run anywhere the pure-Java VM runs.
 */
public record BytecodeProgram(
    String moduleName,
    Map<String, Integer> ctorArity,
    Map<String, List<String>> recordCtors,
    Map<String, String> unqualified,
    Map<String, String> aliases,
    List<Def> defs) {

  /** One top-level definition: its name and compiled body. A chunk with no params is a (lazy) value;
   *  one with params is a function. */
  public record Def(String name, Chunk chunk) {}
}

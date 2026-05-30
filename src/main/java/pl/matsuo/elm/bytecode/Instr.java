package pl.matsuo.elm.bytecode;

/** A single bytecode instruction. {@code arg} is an integer operand (count or jump target); {@code
 * operand} is an object operand (constant, name, pattern, chunk, ...). */
public record Instr(Op op, int arg, Object operand) {

  public static Instr of(Op op) {
    return new Instr(op, 0, null);
  }

  public static Instr of(Op op, Object operand) {
    return new Instr(op, 0, operand);
  }

  public static Instr ofArg(Op op, int arg) {
    return new Instr(op, arg, null);
  }
}

package pl.matsuo.elm.bytecode;

import java.util.List;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmUnit;

/**
 * Renders a {@link BytecodeProgram} (or a single {@link Chunk}) as a human-readable instruction
 * listing — the counterpart of {@code objdump} for the {@code .elmbc} format. Each definition prints
 * its name and parameters followed by its numbered instructions, so a shipped artifact can be
 * inspected without re-compiling the source.
 */
public final class BytecodeDisassembler {

  private BytecodeDisassembler() {}

  /** A full listing of every top-level definition in the program. */
  public static String disassemble(BytecodeProgram program) {
    StringBuilder b = new StringBuilder();
    b.append("; module ").append(program.moduleName()).append('\n');
    b.append("; ").append(program.defs().size()).append(" definitions\n");
    for (BytecodeProgram.Def def : program.defs()) {
      b.append('\n');
      disassembleChunk(b, def.name(), def.chunk(), 0);
    }
    return b.toString();
  }

  private static void disassembleChunk(StringBuilder b, String label, Chunk chunk, int depth) {
    String indent = "  ".repeat(depth);
    b.append(indent).append("== ").append(label);
    if (!chunk.params().isEmpty()) {
      b.append(" (");
      for (int i = 0; i < chunk.params().size(); i++) {
        if (i > 0) {
          b.append(", ");
        }
        b.append(pattern(chunk.params().get(i)));
      }
      b.append(')');
    }
    b.append(" ==\n");
    List<Instr> code = chunk.code();
    for (int i = 0; i < code.size(); i++) {
      Instr instr = code.get(i);
      b.append(indent).append(String.format("%4d  %-14s", i, instr.op()));
      if (hasArg(instr.op())) {
        b.append(' ').append(instr.arg());
      }
      if (instr.operand() != null && !(instr.operand() instanceof Chunk)) {
        b.append(' ').append(operand(instr.operand()));
      }
      b.append('\n');
      if (instr.operand() instanceof Chunk nested) {
        disassembleChunk(b, "closure " + nested.name(), nested, depth + 1);
      }
    }
  }

  /** Whether the opcode's integer {@code arg} field is meaningful (a count or a jump target). */
  private static boolean hasArg(Op op) {
    return switch (op) {
      case MAKE_LIST, MAKE_TUPLE, JUMP, JUMP_IF_FALSE, MATCH, TAIL_CALL, APPLY -> true;
      default -> false;
    };
  }

  private static String operand(Object operand) {
    return switch (operand) {
      case null -> "";
      case String s -> '"' + s + '"';
      case Long l -> l.toString();
      case Double d -> d.toString();
      case Boolean bool -> bool.toString();
      case ElmChar c -> "'" + new String(Character.toChars(c.codePoint())) + "'";
      case ElmUnit ignored -> "()";
      case ElmData data -> data.ctor() + "/" + data.args().length;
      case String[] ss -> "[" + String.join(", ", ss) + "]";
      case Pattern p -> pattern(p);
      default -> operand.toString();
    };
  }

  private static String pattern(Pattern p) {
    return switch (p) {
      case Pattern.Wildcard ignored -> "_";
      case Pattern.Var v -> v.name();
      case Pattern.Unit ignored -> "()";
      case Pattern.IntLit i -> Long.toString(i.value());
      case Pattern.StrLit s -> '"' + s.value() + '"';
      case Pattern.CharLit c -> "'" + new String(Character.toChars(c.codePoint())) + "'";
      case Pattern.Ctor c -> {
        StringBuilder b = new StringBuilder(c.name());
        for (Pattern a : c.args()) {
          b.append(' ').append(pattern(a));
        }
        yield c.args().isEmpty() ? c.name() : "(" + b + ")";
      }
      case Pattern.Tuple t -> {
        java.util.List<String> items = t.items().stream().map(BytecodeDisassembler::pattern).toList();
        yield "(" + String.join(", ", items) + ")";
      }
      case Pattern.ListPat l -> {
        java.util.List<String> items = l.items().stream().map(BytecodeDisassembler::pattern).toList();
        yield "[" + String.join(", ", items) + "]";
      }
      case Pattern.Cons c -> "(" + pattern(c.head()) + " :: " + pattern(c.tail()) + ")";
      case Pattern.RecordPat r -> "{ " + String.join(", ", r.fields()) + " }";
      case Pattern.Alias a -> "(" + pattern(a.pattern()) + " as " + a.name() + ")";
    };
  }
}

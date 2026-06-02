package pl.matsuo.elm.bytecode;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmUnit;

/** Reads the portable {@code .elmbc} format written by {@link BytecodeWriter} back into a runnable
 *  {@link BytecodeProgram}. */
public final class BytecodeReader {

  private static final Op[] OPS = Op.values();

  private BytecodeReader() {}

  /** Deserializes a program from a byte array. */
  public static BytecodeProgram fromBytes(byte[] bytes) {
    return read(new ByteArrayInputStream(bytes));
  }

  /** Deserializes a program from {@code in}. */
  public static BytecodeProgram read(InputStream in) {
    try (DataInputStream d = new DataInputStream(in)) {
      byte[] magic = new byte[BytecodeWriter.MAGIC.length];
      d.readFully(magic);
      if (!java.util.Arrays.equals(magic, BytecodeWriter.MAGIC)) {
        throw new IllegalArgumentException("not an .elmbc bytecode file (bad magic)");
      }
      int version = d.readUnsignedByte();
      if (version != BytecodeWriter.VERSION) {
        throw new IllegalArgumentException(
            "unsupported .elmbc version " + version + " (expected " + BytecodeWriter.VERSION + ")");
      }
      String moduleName = d.readUTF();
      Map<String, Integer> ctorArity = readIntMap(d);
      Map<String, List<String>> recordCtors = readStringsMap(d);
      Map<String, String> unqualified = readStringMap(d);
      Map<String, String> aliases = readStringMap(d);
      int defCount = d.readInt();
      List<BytecodeProgram.Def> defs = new ArrayList<>(defCount);
      for (int i = 0; i < defCount; i++) {
        String name = d.readUTF();
        defs.add(new BytecodeProgram.Def(name, readChunk(d)));
      }
      return new BytecodeProgram(moduleName, ctorArity, recordCtors, unqualified, aliases, defs);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, Integer> readIntMap(DataInputStream d) throws IOException {
    int n = d.readInt();
    Map<String, Integer> m = new LinkedHashMap<>(Math.max(16, n * 2));
    for (int i = 0; i < n; i++) {
      m.put(d.readUTF(), d.readInt());
    }
    return m;
  }

  private static Map<String, String> readStringMap(DataInputStream d) throws IOException {
    int n = d.readInt();
    Map<String, String> m = new LinkedHashMap<>(Math.max(16, n * 2));
    for (int i = 0; i < n; i++) {
      m.put(d.readUTF(), d.readUTF());
    }
    return m;
  }

  private static Map<String, List<String>> readStringsMap(DataInputStream d) throws IOException {
    int n = d.readInt();
    Map<String, List<String>> m = new LinkedHashMap<>(Math.max(16, n * 2));
    for (int i = 0; i < n; i++) {
      String key = d.readUTF();
      int len = d.readInt();
      List<String> vals = new ArrayList<>(len);
      for (int j = 0; j < len; j++) {
        vals.add(d.readUTF());
      }
      m.put(key, vals);
    }
    return m;
  }

  private static Chunk readChunk(DataInputStream d) throws IOException {
    String name = d.readUTF();
    int paramCount = d.readInt();
    List<Pattern> params = new ArrayList<>(paramCount);
    for (int i = 0; i < paramCount; i++) {
      params.add(readPattern(d));
    }
    int codeCount = d.readInt();
    List<Instr> code = new ArrayList<>(codeCount);
    for (int i = 0; i < codeCount; i++) {
      Op op = OPS[d.readUnsignedByte()];
      int arg = d.readInt();
      Object operand = readOperand(d);
      code.add(new Instr(op, arg, operand));
    }
    return new Chunk(code, params, name);
  }

  private static Object readOperand(DataInputStream d) throws IOException {
    int tag = d.readUnsignedByte();
    return switch (tag) {
      case BytecodeWriter.OP_NULL -> null;
      case BytecodeWriter.OP_STRING -> d.readUTF();
      case BytecodeWriter.OP_LONG -> d.readLong();
      case BytecodeWriter.OP_DOUBLE -> d.readDouble();
      case BytecodeWriter.OP_BOOL -> d.readBoolean();
      case BytecodeWriter.OP_CHAR -> new ElmChar(d.readInt());
      case BytecodeWriter.OP_UNIT -> ElmUnit.INSTANCE;
      case BytecodeWriter.OP_DATA -> {
        String ctor = d.readUTF();
        int argc = d.readInt();
        Object[] args = new Object[argc];
        for (int i = 0; i < argc; i++) {
          args[i] = readOperand(d);
        }
        yield new ElmData(ctor, args);
      }
      case BytecodeWriter.OP_STRINGS -> {
        int len = d.readInt();
        String[] ss = new String[len];
        for (int i = 0; i < len; i++) {
          ss[i] = d.readUTF();
        }
        yield ss;
      }
      case BytecodeWriter.OP_CHUNK -> readChunk(d);
      case BytecodeWriter.OP_PATTERN -> readPattern(d);
      default -> throw new IllegalArgumentException("unknown bytecode operand tag " + tag);
    };
  }

  private static Pattern readPattern(DataInputStream d) throws IOException {
    int tag = d.readUnsignedByte();
    return switch (tag) {
      case BytecodeWriter.P_WILDCARD -> new Pattern.Wildcard();
      case BytecodeWriter.P_VAR -> new Pattern.Var(d.readUTF());
      case BytecodeWriter.P_UNIT -> new Pattern.Unit();
      case BytecodeWriter.P_INT -> new Pattern.IntLit(d.readLong());
      case BytecodeWriter.P_STR -> new Pattern.StrLit(d.readUTF());
      case BytecodeWriter.P_CHAR -> new Pattern.CharLit(d.readInt());
      case BytecodeWriter.P_CTOR -> {
        String module = readNullableUTF(d);
        String name = d.readUTF();
        yield new Pattern.Ctor(module, name, readPatterns(d));
      }
      case BytecodeWriter.P_TUPLE -> new Pattern.Tuple(readPatterns(d));
      case BytecodeWriter.P_LIST -> new Pattern.ListPat(readPatterns(d));
      case BytecodeWriter.P_CONS -> new Pattern.Cons(readPattern(d), readPattern(d));
      case BytecodeWriter.P_RECORD -> {
        int n = d.readInt();
        List<String> fields = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
          fields.add(d.readUTF());
        }
        yield new Pattern.RecordPat(fields);
      }
      case BytecodeWriter.P_ALIAS -> {
        Pattern inner = readPattern(d);
        yield new Pattern.Alias(inner, d.readUTF());
      }
      default -> throw new IllegalArgumentException("unknown bytecode pattern tag " + tag);
    };
  }

  private static List<Pattern> readPatterns(DataInputStream d) throws IOException {
    int n = d.readInt();
    List<Pattern> ps = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      ps.add(readPattern(d));
    }
    return ps;
  }

  private static String readNullableUTF(DataInputStream d) throws IOException {
    return d.readBoolean() ? d.readUTF() : null;
  }
}

package pl.matsuo.elm.bytecode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.runtime.ElmChar;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmUnit;

/**
 * Serializes a {@link BytecodeProgram} to the portable {@code .elmbc} binary format read back by
 * {@link BytecodeReader}. The encoding is platform-independent (big-endian {@link DataOutputStream}),
 * so a module compiled on the desktop can be shipped and run by the pure-Java VM on Android.
 */
public final class BytecodeWriter {

  static final byte[] MAGIC = {'E', 'L', 'M', 'B', 'C'};
  static final int VERSION = 2; // v2 adds a CRC32 of the body after the version byte

  // Operand type tags.
  static final int OP_NULL = 0,
      OP_STRING = 1,
      OP_LONG = 2,
      OP_DOUBLE = 3,
      OP_BOOL = 4,
      OP_CHAR = 5,
      OP_UNIT = 6,
      OP_DATA = 7,
      OP_STRINGS = 8,
      OP_CHUNK = 9,
      OP_PATTERN = 10;

  // Pattern type tags.
  static final int P_WILDCARD = 0,
      P_VAR = 1,
      P_UNIT = 2,
      P_INT = 3,
      P_STR = 4,
      P_CHAR = 5,
      P_CTOR = 6,
      P_TUPLE = 7,
      P_LIST = 8,
      P_CONS = 9,
      P_RECORD = 10,
      P_ALIAS = 11;

  private BytecodeWriter() {}

  /** Serializes {@code program} to a fresh byte array: {@code MAGIC, version, crc32(body), body}. */
  public static byte[] toBytes(BytecodeProgram program) {
    byte[] body = bodyBytes(program);
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(body);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream d = new DataOutputStream(bytes)) {
      d.write(MAGIC);
      d.writeByte(VERSION);
      d.writeInt((int) crc.getValue()); // integrity check over the body
      d.write(body);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** Serializes {@code program} to {@code out}. */
  public static void write(BytecodeProgram program, OutputStream out) {
    try {
      out.write(toBytes(program));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The serialized body (everything the CRC covers): module name, the resolution tables, and defs. */
  private static byte[] bodyBytes(BytecodeProgram program) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream d = new DataOutputStream(bytes)) {
      d.writeUTF(program.moduleName());
      writeIntMap(d, program.ctorArity());
      writeStringsMap(d, program.recordCtors());
      writeStringMap(d, program.unqualified());
      writeStringMap(d, program.aliases());
      d.writeInt(program.defs().size());
      for (BytecodeProgram.Def def : program.defs()) {
        d.writeUTF(def.name());
        writeChunk(d, def.chunk());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static void writeIntMap(DataOutputStream d, Map<String, Integer> m) throws IOException {
    d.writeInt(m.size());
    for (Map.Entry<String, Integer> e : m.entrySet()) {
      d.writeUTF(e.getKey());
      d.writeInt(e.getValue());
    }
  }

  private static void writeStringMap(DataOutputStream d, Map<String, String> m) throws IOException {
    d.writeInt(m.size());
    for (Map.Entry<String, String> e : m.entrySet()) {
      d.writeUTF(e.getKey());
      d.writeUTF(e.getValue());
    }
  }

  private static void writeStringsMap(DataOutputStream d, Map<String, List<String>> m)
      throws IOException {
    d.writeInt(m.size());
    for (Map.Entry<String, List<String>> e : m.entrySet()) {
      d.writeUTF(e.getKey());
      d.writeInt(e.getValue().size());
      for (String s : e.getValue()) {
        d.writeUTF(s);
      }
    }
  }

  private static void writeChunk(DataOutputStream d, Chunk chunk) throws IOException {
    d.writeUTF(chunk.name());
    d.writeInt(chunk.params().size());
    for (Pattern p : chunk.params()) {
      writePattern(d, p);
    }
    d.writeInt(chunk.code().size());
    for (Instr instr : chunk.code()) {
      d.writeByte(instr.op().ordinal());
      d.writeInt(instr.arg());
      writeOperand(d, instr.operand());
    }
  }

  private static void writeOperand(DataOutputStream d, Object operand) throws IOException {
    switch (operand) {
      case null -> d.writeByte(OP_NULL);
      case String s -> {
        d.writeByte(OP_STRING);
        d.writeUTF(s);
      }
      case Long l -> {
        d.writeByte(OP_LONG);
        d.writeLong(l);
      }
      case Double db -> {
        d.writeByte(OP_DOUBLE);
        d.writeDouble(db);
      }
      case Boolean b -> {
        d.writeByte(OP_BOOL);
        d.writeBoolean(b);
      }
      case ElmChar c -> {
        d.writeByte(OP_CHAR);
        d.writeInt(c.codePoint());
      }
      case ElmUnit ignored -> d.writeByte(OP_UNIT);
      case ElmData data -> {
        d.writeByte(OP_DATA);
        d.writeUTF(data.ctor());
        d.writeInt(data.args().length);
        for (Object arg : data.args()) {
          writeOperand(d, arg);
        }
      }
      case String[] ss -> {
        d.writeByte(OP_STRINGS);
        d.writeInt(ss.length);
        for (String s : ss) {
          d.writeUTF(s);
        }
      }
      case Chunk chunk -> {
        d.writeByte(OP_CHUNK);
        writeChunk(d, chunk);
      }
      case Pattern p -> {
        d.writeByte(OP_PATTERN);
        writePattern(d, p);
      }
      default ->
          throw new IllegalArgumentException(
              "cannot serialize bytecode operand of type " + operand.getClass());
    }
  }

  private static void writePattern(DataOutputStream d, Pattern p) throws IOException {
    switch (p) {
      case Pattern.Wildcard ignored -> d.writeByte(P_WILDCARD);
      case Pattern.Var v -> {
        d.writeByte(P_VAR);
        d.writeUTF(v.name());
      }
      case Pattern.Unit ignored -> d.writeByte(P_UNIT);
      case Pattern.IntLit i -> {
        d.writeByte(P_INT);
        d.writeLong(i.value());
      }
      case Pattern.StrLit s -> {
        d.writeByte(P_STR);
        d.writeUTF(s.value());
      }
      case Pattern.CharLit c -> {
        d.writeByte(P_CHAR);
        d.writeInt(c.codePoint());
      }
      case Pattern.Ctor c -> {
        d.writeByte(P_CTOR);
        writeNullableUTF(d, c.module());
        d.writeUTF(c.name());
        writePatterns(d, c.args());
      }
      case Pattern.Tuple t -> {
        d.writeByte(P_TUPLE);
        writePatterns(d, t.items());
      }
      case Pattern.ListPat l -> {
        d.writeByte(P_LIST);
        writePatterns(d, l.items());
      }
      case Pattern.Cons c -> {
        d.writeByte(P_CONS);
        writePattern(d, c.head());
        writePattern(d, c.tail());
      }
      case Pattern.RecordPat r -> {
        d.writeByte(P_RECORD);
        d.writeInt(r.fields().size());
        for (String f : r.fields()) {
          d.writeUTF(f);
        }
      }
      case Pattern.Alias a -> {
        d.writeByte(P_ALIAS);
        writePattern(d, a.pattern());
        d.writeUTF(a.name());
      }
    }
  }

  private static void writePatterns(DataOutputStream d, List<Pattern> ps) throws IOException {
    d.writeInt(ps.size());
    for (Pattern p : ps) {
      writePattern(d, p);
    }
  }

  private static void writeNullableUTF(DataOutputStream d, String s) throws IOException {
    d.writeBoolean(s != null);
    if (s != null) {
      d.writeUTF(s);
    }
  }
}

package pl.matsuo.elm.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Low-level WebAssembly binary-format plumbing shared by both WASM backends ({@link WasmCompiler},
 * the linear-memory backend, and {@link WasmGc}, the GC backend): LEB128 integer encoding, the
 * generic section framer, length-prefixed UTF-8 names, and the "name" custom section.
 *
 * <p>This used to be copied, byte for byte, into each backend; pulling it out gives a single source
 * of truth (a fix to LEB128 encoding now lives in one place) and keeps the codegen files focused on
 * compiling Elm rather than on byte framing.
 */
final class WasmEncoding {

  private WasmEncoding() {}

  /** Frames {@code content} as a section: id byte, ULEB128 length, then the bytes. */
  static void section(ByteArrayOutputStream out, int id, ByteArrayOutputStream content) {
    out.write(id);
    leb(out, content.size());
    out.writeBytes(content.toByteArray());
  }

  /** A length-prefixed (ULEB128) UTF-8 string, as used for names throughout the format. */
  static void name(ByteArrayOutputStream out, String s) {
    byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    leb(out, bytes.length);
    out.writeBytes(bytes);
  }

  /** Unsigned LEB128. */
  static void leb(ByteArrayOutputStream out, long value) {
    long v = value;
    do {
      int b = (int) (v & 0x7F);
      v >>>= 7;
      if (v != 0) {
        b |= 0x80;
      }
      out.write(b);
    } while (v != 0);
  }

  /** Signed LEB128. */
  static void sleb(ByteArrayOutputStream out, long value) {
    long v = value;
    boolean more = true;
    while (more) {
      int b = (int) (v & 0x7F);
      v >>= 7;
      if ((v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)) {
        more = false;
      } else {
        b |= 0x80;
      }
      out.write(b);
    }
  }

  static void nameSection(ByteArrayOutputStream out, List<String> funcNames) {
    nameSection(out, funcNames, java.util.List.of());
  }

  /**
   * Emits the WebAssembly "name" custom section: a module name, a function-name map, and (when
   * {@code localNames} is given) a local-name map carrying each function's parameter names. Param
   * names make wasm stack traces / disassembly show {@code (local $n)} instead of {@code (local 0)};
   * a release strip step ({@code wasm-opt --strip}) removes the whole section if size matters.
   *
   * @param localNames per-function parameter names, parallel to {@code funcNames} (an entry may be
   *     empty for a function with no named parameters, e.g. the native runtime helpers).
   */
  static void nameSection(
      ByteArrayOutputStream out, List<String> funcNames, List<List<String>> localNames) {
    nameSection(out, funcNames, localNames, List.of(), List.of());
  }

  /**
   * As {@link #nameSection(ByteArrayOutputStream, List, List)}, plus the GC type-name (subsection 4)
   * and field-name (subsection 10) maps so a disassembler shows {@code (type $tuple2 (struct (field
   * $item0 …)))} rather than numeric indices. {@code typeNames}/{@code fieldNames} are parallel to
   * the module's struct type indices (an empty name / list is skipped).
   */
  static void nameSection(
      ByteArrayOutputStream out,
      List<String> funcNames,
      List<List<String>> localNames,
      List<String> typeNames,
      List<List<String>> fieldNames) {
    ByteArrayOutputStream content = new ByteArrayOutputStream();
    name(content, "name"); // custom section name

    // Subsection 0: module name.
    ByteArrayOutputStream moduleName = new ByteArrayOutputStream();
    name(moduleName, "elm");
    content.write(0x00);
    leb(content, moduleName.size());
    content.writeBytes(moduleName.toByteArray());

    // Subsection 1: function names (idx -> name).
    ByteArrayOutputStream funcs = new ByteArrayOutputStream();
    leb(funcs, funcNames.size());
    for (int i = 0; i < funcNames.size(); i++) {
      leb(funcs, i);
      name(funcs, funcNames.get(i));
    }
    content.write(0x01);
    leb(content, funcs.size());
    content.writeBytes(funcs.toByteArray());

    // Subsection 2: local names — an indirect name map (funcIdx -> (localIdx -> name)), listing only
    // the functions that have named locals (their parameters).
    int withLocals = (int) localNames.stream().filter(ns -> !ns.isEmpty()).count();
    if (withLocals > 0) {
      ByteArrayOutputStream locals = new ByteArrayOutputStream();
      leb(locals, withLocals);
      for (int i = 0; i < localNames.size(); i++) {
        List<String> ns = localNames.get(i);
        if (ns.isEmpty()) {
          continue;
        }
        leb(locals, i); // function index
        leb(locals, ns.size()); // number of named locals
        for (int j = 0; j < ns.size(); j++) {
          leb(locals, j); // local index (parameters are locals 0..arity-1)
          name(locals, ns.get(j));
        }
      }
      content.write(0x02);
      leb(content, locals.size());
      content.writeBytes(locals.toByteArray());
    }

    // Subsection 4: type names (idx -> name), for the GC struct/functype types.
    int namedTypes = (int) typeNames.stream().filter(n -> n != null && !n.isEmpty()).count();
    if (namedTypes > 0) {
      ByteArrayOutputStream types = new ByteArrayOutputStream();
      leb(types, namedTypes);
      for (int i = 0; i < typeNames.size(); i++) {
        String n = typeNames.get(i);
        if (n != null && !n.isEmpty()) {
          leb(types, i);
          name(types, n);
        }
      }
      content.write(0x04);
      leb(content, types.size());
      content.writeBytes(types.toByteArray());
    }

    // Subsection 10: field names — an indirect map (typeIdx -> (fieldIdx -> name)).
    int typesWithFields = (int) fieldNames.stream().filter(fs -> fs != null && !fs.isEmpty()).count();
    if (typesWithFields > 0) {
      ByteArrayOutputStream fields = new ByteArrayOutputStream();
      leb(fields, typesWithFields);
      for (int i = 0; i < fieldNames.size(); i++) {
        List<String> fs = fieldNames.get(i);
        if (fs == null || fs.isEmpty()) {
          continue;
        }
        leb(fields, i); // type index
        leb(fields, fs.size());
        for (int j = 0; j < fs.size(); j++) {
          leb(fields, j);
          name(fields, fs.get(j));
        }
      }
      content.write(0x0A);
      leb(content, fields.size());
      content.writeBytes(fields.toByteArray());
    }

    out.write(0x00); // custom section id
    leb(out, content.size());
    out.writeBytes(content.toByteArray());
  }
}

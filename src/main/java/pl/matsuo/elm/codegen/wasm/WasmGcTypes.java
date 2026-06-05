package pl.matsuo.elm.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.matsuo.elm.types.Ty;
import pl.matsuo.elm.types.Types;

// The WasmGC backend's type model + struct/type registry, extracted from WasmGc so the codegen
// engine (WasmGc.Gen) can stay focused. Package-private top-level types referenced by simple name
// within the codegen.wasm package; `Tuples` assigns stable type indices to every cons/tuple/record/
// closure/ADT shape. (Multiple top-level types in one file: no public type, so the filename is free.)

sealed interface W permits Sca, Ref {}

record Sca(int valtype) implements W {} // WasmGc.I64 or F64

record Ref(int typeIndex) implements W {} // (ref null typeIndex)

/** A GC type definition: a cons cell {@code {head : E, tail : (ref null self)}} for {@code List
 * E}, a plain struct (a tuple/record's fields), or the {@code array i8} backing a {@code String}. */
sealed interface StructDef
    permits ConsDef, PlainDef, StrArrayDef, AdtBaseDef, AdtVariantDef, FuncDef,
        ClosBaseDef, ClosVariantDef {}

record ConsDef(W head) implements StructDef {}

record PlainDef(List<W> fields) implements StructDef {}

record StrArrayDef() implements StructDef {} // array i8 (a UTF-8 string's bytes)

/** The shared supertype of every argument-carrying custom-type value: {@code sub (struct {tag:i32})}. */
record AdtBaseDef() implements StructDef {}

/** A constructor's subtype of {@link AdtBaseDef}: {@code {tag : i32, args…}}. */
record AdtVariantDef(int baseIndex, List<W> argFields) implements StructDef {}

/** A function type {@code (params…) -> result}. Shares the type-index space with the structs (one
 * rec group), so {@code WasmGc.wOf(Ty.Arrow)} can refer to one during the pre-pass — the basis for the
 * closure-calling convention and {@code call_ref}. */
record FuncDef(List<W> params, W result) implements StructDef {}

/** A closure base for one arrow signature: {@code sub (struct { fn : (ref null cc) })}, where
 * {@code cc} is the calling-convention functype {@code (ref base, arg) -> result}. Subtypable so a
 * capturing lambda can add capture fields, and instantiable for capture-free function values. */
record ClosBaseDef(int ccIndex) implements StructDef {}

/** A closure subtype for a lambda that captures locals: {@code sub base (struct { fn, caps… })}. */
record ClosVariantDef(int baseIndex, int ccIndex, List<W> captures) implements StructDef {}

final class Tuples {
  private final java.util.LinkedHashMap<String, Integer> indexByKey = new java.util.LinkedHashMap<>();
  private final List<StructDef> shapes = new ArrayList<>();
  // Nullary ("enum") custom types: each is represented as an i64 tag (the variant's index).
  private final java.util.Set<String> enumTypes = new java.util.HashSet<>();
  private final Map<String, Long> ctorTag = new HashMap<>();
  // Argument-carrying ("boxed") custom types: a shared base struct {tag:i32} with a subtype per
  // constructor. `ctorVariant` maps a constructor to {subtypeIndex, tag, arity}.
  private final java.util.Set<String> boxedUnions = new java.util.HashSet<>();
  private final Map<String, int[]> ctorVariant = new HashMap<>();
  private final Map<String, List<W>> ctorFields = new HashMap<>();
  private int adtBase = -1;

  /** Registers an all-nullary union as an enum whose constructors are i64 tags 0,1,2,…. */
  void registerEnum(String typeName, List<String> ctorsInOrder) {
    enumTypes.add(typeName);
    for (int i = 0; i < ctorsInOrder.size(); i++) {
      ctorTag.put(ctorsInOrder.get(i), (long) i);
    }
  }

  boolean isEnum(String typeName) {
    return enumTypes.contains(typeName);
  }

  /** The i64 tag of a nullary constructor, or {@code null} if it isn't an enum constructor. */
  Long tagOf(String ctor) {
    return ctorTag.get(ctor);
  }

  boolean isBoxed(String typeName) {
    return boxedUnions.contains(typeName);
  }

  /** Marks an argument-carrying union as boxed (so recursive references resolve) and ensures the
   * shared base struct exists. */
  void markBoxed(String typeName) {
    boxedUnions.add(typeName);
    if (adtBase < 0) {
      adtBase = register("ADTBASE$", new AdtBaseDef());
    }
  }

  int adtBaseIndex() {
    return adtBase;
  }

  /** Registers a boxed constructor's subtype (fields = its argument wasm types) and records its
   * {subtypeIndex, tag, arity}. */
  void registerVariant(String ctor, int tag, List<W> argFields) {
    int sub = register("ADTV$" + ctor + "$" + tag, new AdtVariantDef(adtBase, argFields));
    ctorVariant.put(ctor, new int[] {sub, tag, argFields.size()});
    ctorFields.put(ctor, argFields);
  }

  /** A boxed constructor's {subtypeIndex, tag, arity}, or {@code null}. */
  int[] variantOf(String ctor) {
    return ctorVariant.get(ctor);
  }

  /** A boxed constructor's argument wasm types. */
  List<W> variantFields(String ctor) {
    return ctorFields.get(ctor);
  }

  /** Recursively registers every list, tuple and record shape inside a type (innermost first). */
  void registerAll(Ty t) {
    Ty p = Types.prune(t);
    switch (p) {
      case Ty.Con c when c.name().equals("List") -> {
        registerAll(WasmGc.listElem(c));
        consIndexOf(WasmGc.wOf(WasmGc.listElem(c), this));
      }
      case Ty.Con c when c.name().equals("String") -> strIndex();
      case Ty.Tuple tup -> {
        tup.items().forEach(this::registerAll);
        indexOf(tup);
      }
      case Ty.Record rec -> {
        rec.fields().values().forEach(this::registerAll);
        recordIndexOf(rec);
      }
      case Ty.Arrow a -> {
        registerAll(a.from());
        registerAll(a.to());
      }
      case Ty.Con c -> c.args().forEach(this::registerAll);
      default -> {}
    }
  }

  /** The struct type index for a {@code List E} cons cell with the given element type. */
  int consIndexOf(W head) {
    return register("L" + keyOf(List.of(head)), new ConsDef(head));
  }

  /** The (singleton) {@code array i8} type backing every {@code String}. */
  int strIndex() {
    return register("STR$", new StrArrayDef());
  }

  /** The struct type index for a tuple shape (registering it if new — nested tuples must already
   * be registered, which the {@link #registerAll} pre-pass guarantees). */
  int indexOf(Ty.Tuple tup) {
    List<W> fields = new ArrayList<>();
    for (Ty it : tup.items()) {
      fields.add(WasmGc.wOf(it, this));
    }
    return register("T" + keyOf(fields), new PlainDef(fields));
  }

  /** The struct type index for a record shape (fields in sorted-name order, like the linear-memory
   * backend), registering it if new. Lists/records/tuples share the struct space but never collide
   * (their keys are prefixed), so field-by-index access stays well defined. */
  int recordIndexOf(Ty.Record rec) {
    List<W> fields = new ArrayList<>();
    StringBuilder names = new StringBuilder("R");
    for (String name : WasmGc.sortedFields(rec)) {
      names.append(name).append(':');
      fields.add(WasmGc.wOf(rec.fields().get(name), this));
    }
    return register(names + keyOf(fields), new PlainDef(fields));
  }

  /** The type index of a function type {@code (params…) -> result}, registering it if new. Always
   * called after the structs are registered, so functypes occupy the indices above them. */
  int funcTypeIndex(List<W> params, W result) {
    return register("FN" + keyOf(params) + ">" + keyOf(List.of(result)), new FuncDef(params, result));
  }

  /** Whether the type at {@code index} is a function type (so a {@code (ref index)} is a funcref). */
  boolean isFuncType(int index) {
    return index >= 0 && index < shapes.size() && shapes.get(index) instanceof FuncDef;
  }

  /** The closure base struct for an arrow {@code arg -> result}, registering it (and its
   * calling-convention functype {@code (ref base, arg) -> result}) if new. {@code WasmGc.wOf(Ty.Arrow)}
   * returns a {@code (ref base)}; {@link #closureCc} gives the functype for {@code call_ref}. */
  int closureBaseIndex(W arg, W result) {
    String key = "CB" + keyOf(List.of(arg, result));
    Integer existing = indexByKey.get(key);
    if (existing != null) {
      return existing;
    }
    int baseIdx = shapes.size();
    indexByKey.put(key, baseIdx);
    shapes.add(new ClosBaseDef(-1)); // placeholder; patched once the cc functype is registered
    int cc = funcTypeIndex(List.of(new Ref(baseIdx), arg), result); // canonical, so wrappers share it
    shapes.set(baseIdx, new ClosBaseDef(cc));
    return baseIdx;
  }

  boolean isClosureBase(int index) {
    return index >= 0 && index < shapes.size() && shapes.get(index) instanceof ClosBaseDef;
  }

  /** The calling-convention functype index of a closure base. */
  int closureCc(int baseIndex) {
    return ((ClosBaseDef) shapes.get(baseIndex)).ccIndex();
  }

  /** The result type a closure base returns when applied (the cc functype's result). For a curried
   * value this is itself a {@code (ref closureBase)} until the last argument is supplied. */
  W closureResult(int baseIndex) {
    return ((FuncDef) shapes.get(closureCc(baseIndex))).result();
  }

  /** A subtype of {@code baseIndex} carrying a lambda's {@code captures}, registering it if new. */
  int closureVariantIndex(int baseIndex, List<W> captures) {
    return register(
        "CV" + baseIndex + "/" + keyOf(captures),
        new ClosVariantDef(baseIndex, closureCc(baseIndex), captures));
  }

  /** The capture field types of a closure variant (for binding them from {@code env}). */
  List<W> closureVariantCaptures(int variantIndex) {
    return ((ClosVariantDef) shapes.get(variantIndex)).captures();
  }

  private int register(String key, StructDef def) {
    Integer existing = indexByKey.get(key);
    if (existing != null) {
      return existing;
    }
    int idx = indexByKey.size();
    indexByKey.put(key, idx);
    shapes.add(def);
    return idx;
  }

  List<StructDef> shapes() {
    return shapes;
  }

  int count() {
    return shapes.size();
  }

  /** Human-readable type names parallel to the struct type indices (for the wasm "name" section, so
   * a disassembler shows {@code (type $tuple2 …)} instead of {@code (type (;5;) …)}). */
  List<String> typeNames() {
    String[] names = new String[shapes.size()];
    for (var e : indexByKey.entrySet()) {
      names[e.getValue()] = typeNameOf(e.getKey(), shapes.get(e.getValue()), e.getValue());
    }
    return java.util.Arrays.asList(names);
  }

  /** Field names per struct type index (empty where the type has no named fields), for the wasm
   * "name" section's field-name subsection. */
  List<List<String>> fieldNames() {
    @SuppressWarnings("unchecked")
    List<String>[] fields = new List[shapes.size()];
    for (var e : indexByKey.entrySet()) {
      fields[e.getValue()] = fieldNamesOf(e.getKey(), shapes.get(e.getValue()));
    }
    return java.util.Arrays.asList(fields);
  }

  private static String typeNameOf(String key, StructDef def, int index) {
    return switch (def) {
      case AdtBaseDef ignored -> "adtBase";
      case AdtVariantDef ignored -> "adt." + key.split("\\$")[1]; // ADTV$Ctor$tag -> Ctor
      case ConsDef ignored -> "cons$" + index;
      case StrArrayDef ignored -> "string";
      case PlainDef ignored -> (key.startsWith("R") ? "record$" : "tuple$") + index;
      case FuncDef ignored -> "fn$" + index;
      case ClosBaseDef ignored -> "closure$" + index;
      case ClosVariantDef ignored -> "closureCap$" + index;
    };
  }

  private static List<String> fieldNamesOf(String key, StructDef def) {
    return switch (def) {
      case ConsDef ignored -> List.of("head", "tail");
      case AdtBaseDef ignored -> List.of("tag");
      case AdtVariantDef v -> tagPlusArgs(v.argFields().size());
      case ClosBaseDef ignored -> List.of("fn");
      case ClosVariantDef v -> fnPlusCaps(v.captures().size());
      case PlainDef p -> p == null ? List.of() : plainFieldNames(key, p.fields().size());
      default -> List.of();
    };
  }

  private static List<String> tagPlusArgs(int n) {
    List<String> out = new ArrayList<>(n + 1);
    out.add("tag");
    for (int i = 0; i < n; i++) {
      out.add("arg" + i);
    }
    return out;
  }

  private static List<String> fnPlusCaps(int n) {
    List<String> out = new ArrayList<>(n + 1);
    out.add("fn");
    for (int i = 0; i < n; i++) {
      out.add("cap" + i);
    }
    return out;
  }

  /** A tuple uses positional names; a record key ("R" + "name:"*N + types) carries its field names. */
  private static List<String> plainFieldNames(String key, int count) {
    if (key.startsWith("R")) {
      String[] parts = key.substring(1).split(":", count + 1); // first N tokens are the field names
      List<String> names = new ArrayList<>(count);
      for (int i = 0; i < count && i < parts.length; i++) {
        names.add(parts[i]);
      }
      if (names.size() == count) {
        return names;
      }
    }
    List<String> items = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      items.add("item" + i);
    }
    return items;
  }

  private static String keyOf(List<W> fields) {
    StringBuilder b = new StringBuilder();
    for (W w : fields) {
      if (w instanceof Sca s) {
        b.append(s.valtype() == WasmGc.I64 ? 'i' : 'f');
      } else if (w instanceof Ref r) {
        b.append('r').append(r.typeIndex());
      }
      b.append(',');
    }
    return b.toString();
  }
}

// --- per-function code generation --------------------------------------


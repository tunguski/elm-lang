package pl.matsuo.elm.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.types.Infer;
import pl.matsuo.elm.types.Scheme;
import pl.matsuo.elm.types.Signatures;
import pl.matsuo.elm.types.Ty;
import pl.matsuo.elm.types.Types;

/**
 * A second WebAssembly backend that uses the <b>WasmGC</b> proposal — host-garbage-collected
 * {@code struct} references — instead of the linear-memory bump allocator of {@link WasmCompiler}.
 * A cons-list is a GC struct {@code {head : i64, tail : (ref null $cons)}}, so building and
 * discarding lists is reclaimed by the engine's collector with no manual memory management.
 *
 * <p>The supported subset is monomorphic {@code Int}/{@code Bool}/{@code Float}, {@code List Int},
 * <b>tuples</b>, <b>closed records</b> and <b>nullary custom types</b>: integer and floating-point
 * arithmetic and comparisons ({@code Float} is an {@code f64}, with {@code /} and {@code f64}
 * compares), {@code if}, {@code let} (including {@code let (a, b) = …}), top-level functions and
 * recursion, list literals and {@code ::}, tuple construction with {@code Tuple.first}/{@code
 * Tuple.second} and tuple-pattern {@code case}, record literals / {@code .field} access / {@code
 * { r | f = v }} update, and a {@code case} over {@code []} / {@code head :: tail} or a nullary
 * union. Each function's parameter and result wasm types come from Hindley–Milner inference: an
 * {@code Int} (and a nullary custom type, as its variant tag) is an {@code i64}, a {@code Float} an
 * {@code f64}, a {@code List Int} the cons struct, and each tuple/record shape its own GC struct
 * (record fields laid out in sorted-name order). <b>Row-polymorphic</b> (open) record parameters
 * have no fixed struct layout, and argument-carrying custom types, closures and strings remain on
 * the linear-memory backend — extending this one to them (boxing / struct subtyping, and host-opaque
 * GC arrays for strings) is future work.
 */
public final class WasmGc {

  private static final int I64 = 0x7E;
  private static final int F64 = 0x7C;
  // The cons struct is type index 0; a list value has type (ref null 0): bytes 0x63 0x00.
  private static final int[] LISTREF = {0x63, 0x00};

  private WasmGc() {}

  /** A compiled function: name, parameter (name,type) pairs, result type and body. */
  private record Func(String name, List<String> params, List<W> paramTypes, W result, Expr body) {}

  /** A wasm value type in the supported subset: a scalar (i64/f64) or a reference to a GC struct
   * type (the cons list is struct 0; each distinct tuple shape gets its own struct type). */
  private sealed interface W permits Sca, Ref {}

  private record Sca(int valtype) implements W {} // I64 or F64

  private record Ref(int typeIndex) implements W {} // (ref null typeIndex)

  private static final W INT = new Sca(I64);
  private static final W FLOAT = new Sca(F64);
  private static final W LIST = new Ref(0); // the cons struct is always type 0

  /** Compiles a module's monomorphic Int/List-Int functions to a WasmGC binary. */
  public static byte[] module(String source) {
    Module module = Parser.parseModule(source);
    Infer infer = new Infer();
    Map<String, Scheme> schemes = infer.inferModule(module, Signatures.globals());
    Map<Expr, Ty> nodeTypes = infer.nodeTypes();

    // Pre-pass: register every tuple shape (innermost first) so it has a stable struct type index
    // before any function body is compiled.
    Tuples tuples = new Tuples();
    // Register all-nullary unions as i64-tagged enums.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u && u.variants().stream().allMatch(v -> v.args().isEmpty())) {
        tuples.registerEnum(u.name(), u.variants().stream().map(Decl.Union.Variant::name).toList());
      }
    }
    for (Scheme s : schemes.values()) {
      tuples.registerAll(s.body());
    }
    for (Ty t : nodeTypes.values()) {
      tuples.registerAll(t);
    }

    List<Func> funcs = new ArrayList<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && schemes.containsKey(v.name())) {
        Ty t = Types.prune(schemes.get(v.name()).body());
        List<W> paramTypes = new ArrayList<>();
        List<String> params = new ArrayList<>();
        for (Pattern p : v.params()) {
          if (!(p instanceof Pattern.Var pv)) {
            throw unsupported("non-variable parameter");
          }
          params.add(pv.name());
          paramTypes.add(wOf(arrowParam(t, paramTypes.size()), tuples));
        }
        funcs.add(
            new Func(v.name(), params, paramTypes, wOf(resultType(t, params.size()), tuples), v.body()));
      }
    }
    return assemble(funcs, nodeTypes, tuples);
  }

  /** The i-th parameter type of an arrow chain. */
  private static Ty arrowParam(Ty t, int i) {
    Ty cur = Types.prune(t);
    for (int k = 0; k < i; k++) {
      cur = Types.prune(((Ty.Arrow) cur).to());
    }
    return Types.prune(((Ty.Arrow) cur).from());
  }

  /** The result type after applying {@code n} parameters of an arrow chain. */
  private static Ty resultType(Ty t, int n) {
    Ty cur = Types.prune(t);
    for (int k = 0; k < n; k++) {
      cur = Types.prune(((Ty.Arrow) cur).to());
    }
    return cur;
  }

  private static W wOf(Ty t, Tuples tuples) {
    Ty p = Types.prune(t);
    if (p instanceof Ty.Con c && c.name().equals("List")) {
      return LIST;
    }
    if (p instanceof Ty.Con c && c.name().equals("Float")) {
      return FLOAT;
    }
    if (p instanceof Ty.Con c && (c.name().equals("Int") || c.name().equals("Bool"))) {
      return INT;
    }
    if (p instanceof Ty.Con c && tuples.isEnum(c.name())) {
      return INT; // a nullary custom type is an i64 tag
    }
    if (p instanceof Ty.Tuple tup) {
      return new Ref(tuples.indexOf(tup));
    }
    if (p instanceof Ty.Record rec && rec.tail() == null) {
      return new Ref(tuples.recordIndexOf(rec));
    }
    // An unresolved type variable — a `number` (defaults to Int) or a polymorphic element — is
    // represented as an i64 in this monomorphic subset.
    if (p instanceof Ty.Var) {
      return INT;
    }
    throw unsupported(
        "type " + Types.show(p) + " (WasmGC backend handles Int/Bool/Float, tuples, records and List Int)");
  }

  /** A record's field names in canonical (sorted) order, matching the struct's field layout. */
  private static List<String> sortedFields(Ty.Record rec) {
    List<String> names = new ArrayList<>(rec.fields().keySet());
    java.util.Collections.sort(names);
    return names;
  }

  static ElmRuntimeError unsupported(String what) {
    return new ElmRuntimeError("WasmGC backend does not support " + what);
  }

  /**
   * The distinct tuple shapes a module uses, each assigned a GC struct type index (the cons list is
   * type 0, tuples are 1..T, function signatures come after). A pre-pass registers every tuple that
   * appears in a signature or inferred sub-expression type, innermost first, so that nested tuples
   * always have a lower index than the tuples that contain them.
   */
  private static final class Tuples {
    private final java.util.LinkedHashMap<String, Integer> indexByKey = new java.util.LinkedHashMap<>();
    private final List<List<W>> shapes = new ArrayList<>();
    // Nullary ("enum") custom types: each is represented as an i64 tag (the variant's index).
    private final java.util.Set<String> enumTypes = new java.util.HashSet<>();
    private final Map<String, Long> ctorTag = new HashMap<>();

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

    /** Recursively registers every tuple and record shape inside a type. */
    void registerAll(Ty t) {
      Ty p = Types.prune(t);
      switch (p) {
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

    /** The struct type index for a tuple shape (registering it if new — nested tuples must already
     * be registered, which the {@link #registerAll} pre-pass guarantees). */
    int indexOf(Ty.Tuple tup) {
      List<W> fields = new ArrayList<>();
      for (Ty it : tup.items()) {
        fields.add(wOf(it, this));
      }
      return register("T" + keyOf(fields), fields);
    }

    /** The struct type index for a record shape (fields in sorted-name order, like the linear-memory
     * backend), registering it if new. Records and tuples share the struct space but never collide
     * (their keys are prefixed), so a record's field-by-index access stays well defined. */
    int recordIndexOf(Ty.Record rec) {
      List<W> fields = new ArrayList<>();
      StringBuilder names = new StringBuilder("R");
      for (String name : sortedFields(rec)) {
        names.append(name).append(':');
        fields.add(wOf(rec.fields().get(name), this));
      }
      return register(names + keyOf(fields), fields);
    }

    private int register(String key, List<W> fields) {
      Integer existing = indexByKey.get(key);
      if (existing != null) {
        return existing;
      }
      int idx = 1 + indexByKey.size(); // cons is type 0
      indexByKey.put(key, idx);
      shapes.add(fields);
      return idx;
    }

    List<List<W>> shapes() {
      return shapes;
    }

    int count() {
      return shapes.size();
    }

    private static String keyOf(List<W> fields) {
      StringBuilder b = new StringBuilder();
      for (W w : fields) {
        if (w instanceof Sca s) {
          b.append(s.valtype() == I64 ? 'i' : 'f');
        } else if (w instanceof Ref r) {
          b.append('r').append(r.typeIndex());
        }
        b.append(',');
      }
      return b.toString();
    }
  }

  // --- per-function code generation --------------------------------------

  private static final class Gen {
    private final Map<String, int[]> funcs; // name -> {index, arity}
    private final Map<String, W> funcResult; // name -> result type
    private final Map<Expr, Ty> nodeTypes;
    private final Tuples tuples;
    private final Map<String, Integer> locals = new HashMap<>();
    private final Map<String, W> localTypes = new HashMap<>();
    private final List<W> extraLocals = new ArrayList<>(); // beyond params
    private final int numParams;
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    Gen(Map<String, int[]> funcs, Map<String, W> funcResult, Map<Expr, Ty> nodeTypes, Tuples tuples,
        List<String> params, List<W> paramTypes) {
      this.funcs = funcs;
      this.funcResult = funcResult;
      this.nodeTypes = nodeTypes;
      this.tuples = tuples;
      this.numParams = params.size();
      for (int i = 0; i < params.size(); i++) {
        locals.put(params.get(i), i);
        localTypes.put(params.get(i), paramTypes.get(i));
      }
    }

    byte[] compile(Expr body) {
      tailGen(body); // tail position: a direct call becomes return_call (deep recursion is safe)
      // Emit each extra local as its own group (so mixed i64/ref types are declared correctly).
      ByteArrayOutputStream locs = new ByteArrayOutputStream();
      leb(locs, extraLocals.size());
      for (W w : extraLocals) {
        leb(locs, 1);
        writeType(locs, w);
      }
      ByteArrayOutputStream body2 = new ByteArrayOutputStream();
      body2.writeBytes(locs.toByteArray());
      body2.writeBytes(code.toByteArray());
      body2.write(0x0B);
      ByteArrayOutputStream entry = new ByteArrayOutputStream();
      leb(entry, body2.size());
      entry.writeBytes(body2.toByteArray());
      return entry.toByteArray();
    }

    private int freshLocal(String name, W w) {
      int idx = numParams + extraLocals.size();
      extraLocals.add(w);
      locals.put(name, idx);
      localTypes.put(name, w);
      return idx;
    }

    /** Emits code leaving the expression's value on the stack (i64, or a list ref). */
    private void gen(Expr e) {
      switch (e) {
        case Expr.IntLit lit -> {
          code.write(0x42);
          sleb(code, lit.value());
        }
        case Expr.Ctor c when c.name().equals("True") -> {
          code.write(0x42);
          sleb(code, 1); // Bool is an i64 (1/0) in value position
        }
        case Expr.Ctor c when c.name().equals("False") -> {
          code.write(0x42);
          sleb(code, 0);
        }
        case Expr.Ctor c when tuples.tagOf(c.name()) != null -> {
          code.write(0x42);
          sleb(code, tuples.tagOf(c.name())); // a nullary custom-type constructor is its i64 tag
        }
        case Expr.FloatLit lit -> emitF64(lit.value());
        case Expr.Negate n -> {
          if (isFloat(n)) {
            gen(n.operand());
            code.write(0x9A); // f64.neg
          } else {
            code.write(0x42);
            sleb(code, 0);
            gen(n.operand());
            code.write(0x7D); // i64.sub
          }
        }
        case Expr.BinOp b -> binOp(b);
        case Expr.If iff -> {
          boolGen(iff.cond());
          code.write(0x04); // if
          writeType(code, wOf(nodeType(iff), tuples)); // result type (i64 or list ref)
          gen(iff.thenBranch());
          code.write(0x05);
          gen(iff.elseBranch());
          code.write(0x0B);
        }
        case Expr.Let let -> {
          for (Decl d : let.defs()) {
            if (d instanceof Decl.Value v && v.params().isEmpty()) {
              W w = wOf(nodeType(v.body()), tuples);
              int idx = freshLocal(v.name(), w);
              gen(v.body());
              code.write(0x21);
              leb(code, idx);
            } else if (d instanceof Decl.Destructure de && de.pattern() instanceof Pattern.Tuple) {
              bindTuplePattern((Pattern.Tuple) de.pattern(), de.body());
            } else {
              throw unsupported("let with parameters");
            }
          }
          gen(let.body());
        }
        case Expr.Var v -> {
          Integer idx = locals.get(v.name());
          if (idx != null) {
            code.write(0x20);
            leb(code, idx);
          } else if (funcs.containsKey(v.name()) && funcs.get(v.name())[1] == 0) {
            code.write(0x10);
            leb(code, funcs.get(v.name())[0]);
          } else {
            throw unsupported("variable " + v.name());
          }
        }
        case Expr.ListLit l -> emitList(l.items(), 0);
        case Expr.Tuple tup -> {
          for (Expr item : tup.items()) {
            gen(item);
          }
          code.write(0xFB);
          code.write(0x00);
          leb(code, tupleIndex(nodeType(tup))); // struct.new $tupleN
        }
        case Expr.Record rec -> emitRecord(rec);
        case Expr.RecordAccess acc -> {
          Ty.Record rt = recordTypeOf(acc.target());
          gen(acc.target());
          code.write(0xFB);
          code.write(0x02); // struct.get
          leb(code, tupleIndex(nodeType(acc.target())));
          leb(code, sortedFields(rt).indexOf(acc.field()));
        }
        case Expr.RecordUpdate up -> emitRecordUpdate(up);
        case Expr.App app -> app(app, false);
        case Expr.Case c -> caseExpr(c, this::gen);
        default -> throw unsupported(e.getClass().getSimpleName());
      }
    }

    /** The struct type index of a tuple-/record-typed expression. */
    private int tupleIndex(Ty t) {
      W w = wOf(t, tuples);
      if (w instanceof Ref r) {
        return r.typeIndex();
      }
      throw unsupported("a tuple/record operation on a non-struct");
    }

    private Ty.Record recordTypeOf(Expr e) {
      Ty t = Types.prune(nodeType(e));
      if (t instanceof Ty.Record rec) {
        return rec;
      }
      throw unsupported("a record operation on a non-record");
    }

    /** A record literal: struct.new with the field values in canonical (sorted-name) order. */
    private void emitRecord(Expr.Record rec) {
      Ty.Record rt = recordTypeOf(rec);
      for (String name : sortedFields(rt)) {
        gen(fieldValue(rec, name));
      }
      code.write(0xFB);
      code.write(0x00);
      leb(code, tupleIndex(nodeType(rec))); // struct.new $recordN
    }

    /** A record update `{ base | f = v, … }`: struct.new copying base's fields, overriding the
     * updated ones. */
    private void emitRecordUpdate(Expr.RecordUpdate up) {
      Ty.Record rt = recordTypeOf(up);
      int ti = tupleIndex(nodeType(up));
      // Evaluate the base record once into a fresh local, then copy unchanged fields from it.
      int baseLocal = freshLocal("$rec" + code.size(), new Ref(ti));
      emitVarRef(up.base());
      code.write(0x21);
      leb(code, baseLocal);
      List<String> names = sortedFields(rt);
      for (String name : names) {
        Expr updated = updateValue(up, name);
        if (updated != null) {
          gen(updated);
        } else {
          code.write(0x20);
          leb(code, baseLocal);
          code.write(0xFB);
          code.write(0x02); // struct.get
          leb(code, ti);
          leb(code, names.indexOf(name));
        }
      }
      code.write(0xFB);
      code.write(0x00);
      leb(code, ti); // struct.new
    }

    /** Pushes the value of a variable: a local, or a no-arg top-level function (called). */
    private void emitVarRef(String name) {
      Integer li = locals.get(name);
      if (li != null) {
        code.write(0x20);
        leb(code, li);
      } else if (funcs.containsKey(name) && funcs.get(name)[1] == 0) {
        code.write(0x10);
        leb(code, funcs.get(name)[0]);
      } else {
        throw unsupported("record update base " + name);
      }
    }

    private static Expr fieldValue(Expr.Record rec, String name) {
      for (Expr.Record.Field f : rec.fields()) {
        if (f.name().equals(name)) {
          return f.value();
        }
      }
      throw unsupported("record literal missing field " + name);
    }

    private static Expr updateValue(Expr.RecordUpdate up, String name) {
      for (Expr.Record.Field f : up.fields()) {
        if (f.name().equals(name)) {
          return f.value();
        }
      }
      return null;
    }

    private void binOp(Expr.BinOp b) {
      if (b.op().equals("::")) {
        // struct.new $cons (head:i64, tail:list)
        gen(b.left());
        gen(b.right());
        code.write(0xFB);
        code.write(0x00);
        leb(code, 0); // type 0 = $cons
        return;
      }
      switch (b.op()) {
        case "+", "-", "*" -> {
          gen(b.left());
          gen(b.right());
          if (isFloat(b)) {
            code.write(switch (b.op()) {
              case "+" -> 0xA0; // f64.add
              case "-" -> 0xA1; // f64.sub
              default -> 0xA2; // f64.mul
            });
          } else {
            code.write(switch (b.op()) {
              case "+" -> 0x7C;
              case "-" -> 0x7D;
              default -> 0x7E;
            });
          }
        }
        case "//" -> {
          gen(b.left());
          gen(b.right());
          code.write(0x7F); // i64.div_s
        }
        case "/" -> {
          gen(b.left());
          gen(b.right());
          code.write(0xA3); // f64.div
        }
        case "<", ">", "<=", ">=", "==", "/=", "&&", "||" -> {
          // A comparison/boolean in value position: i32 result widened to i64 (0/1).
          boolGen(b);
          code.write(0xAD); // i64.extend_i32_u
        }
        default -> throw unsupported("operator " + b.op());
      }
    }

    /** Emits an i32 (0/1) for a Bool-valued expression (an `if` condition). */
    private void boolGen(Expr e) {
      if (e instanceof Expr.BinOp b) {
        switch (b.op()) {
          case "&&" -> {
            boolGen(b.left());
            boolGen(b.right());
            code.write(0x71);
            return;
          }
          case "||" -> {
            boolGen(b.left());
            boolGen(b.right());
            code.write(0x72);
            return;
          }
          case "<", ">", "<=", ">=", "==", "/=" -> {
            gen(b.left());
            gen(b.right());
            if (isFloat(b.left())) {
              code.write(switch (b.op()) {
                case "<" -> 0x63; // f64.lt
                case ">" -> 0x64; // f64.gt
                case "<=" -> 0x65; // f64.le
                case ">=" -> 0x66; // f64.ge
                case "==" -> 0x61; // f64.eq
                default -> 0x62; // f64.ne
              });
            } else {
              code.write(switch (b.op()) {
                case "<" -> 0x53;
                case ">" -> 0x55;
                case "<=" -> 0x57;
                case ">=" -> 0x59;
                case "==" -> 0x51;
                default -> 0x52;
              });
            }
            return;
          }
          default -> {}
        }
      }
      if (e instanceof Expr.Ctor c && c.name().equals("True")) {
        code.write(0x41);
        sleb(code, 1);
        return;
      }
      if (e instanceof Expr.Ctor c && c.name().equals("False")) {
        code.write(0x41);
        sleb(code, 0);
        return;
      }
      // Fallback: an i64 0/1 narrowed to i32.
      gen(e);
      code.write(0xA7);
    }

    private void emitList(List<Expr> items, int i) {
      if (i >= items.size()) {
        code.write(0xD0); // ref.null
        leb(code, 0); // heaptype $cons
        return;
      }
      gen(items.get(i));
      emitList(items, i + 1);
      code.write(0xFB);
      code.write(0x00);
      leb(code, 0); // struct.new $cons
    }

    /** Tail-position compilation: a direct call becomes {@code return_call} (deep recursion is
     *  stack-safe); tail position flows through {@code if}/{@code let}/{@code case}. */
    private void tailGen(Expr e) {
      switch (e) {
        case Expr.If iff -> {
          boolGen(iff.cond());
          code.write(0x04);
          writeType(code, wOf(nodeType(iff), tuples));
          tailGen(iff.thenBranch());
          code.write(0x05);
          tailGen(iff.elseBranch());
          code.write(0x0B);
        }
        case Expr.Let let -> {
          for (Decl d : let.defs()) {
            if (d instanceof Decl.Value v && v.params().isEmpty()) {
              W w = wOf(nodeType(v.body()), tuples);
              int idx = freshLocal(v.name(), w);
              gen(v.body());
              code.write(0x21);
              leb(code, idx);
            } else if (d instanceof Decl.Destructure de && de.pattern() instanceof Pattern.Tuple) {
              bindTuplePattern((Pattern.Tuple) de.pattern(), de.body());
            } else {
              throw unsupported("let with parameters");
            }
          }
          tailGen(let.body());
        }
        case Expr.Case c -> caseExpr(c, this::tailGen);
        case Expr.App app -> app(app, true);
        default -> gen(e);
      }
    }

    private void app(Expr.App app, boolean tail) {
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      // Tuple.first / Tuple.second on a pair: struct.get on the tuple's struct type.
      if (head instanceof Expr.Var v
          && "Tuple".equals(v.module())
          && (v.name().equals("first") || v.name().equals("second"))
          && args.size() == 1) {
        gen(args.get(0));
        code.write(0xFB);
        code.write(0x02); // struct.get
        leb(code, tupleIndex(nodeType(args.get(0))));
        leb(code, v.name().equals("first") ? 0 : 1);
        return;
      }
      // A record accessor used as a function: `.field record`.
      if (head instanceof Expr.Accessor acc && args.size() == 1) {
        Ty.Record rt = recordTypeOf(args.get(0));
        gen(args.get(0));
        code.write(0xFB);
        code.write(0x02); // struct.get
        leb(code, tupleIndex(nodeType(args.get(0))));
        leb(code, sortedFields(rt).indexOf(acc.field()));
        return;
      }
      if (head instanceof Expr.Var v && funcs.containsKey(v.name()) && funcs.get(v.name())[1] == args.size()) {
        for (Expr arg : args) {
          gen(arg);
        }
        code.write(tail ? 0x12 : 0x10); // return_call in tail position, else call
        leb(code, funcs.get(v.name())[0]);
        return;
      }
      throw unsupported("application of " + (head instanceof Expr.Var v ? v.name() : "expression"));
    }

    /** Dispatches a {@code case} to the tuple, enum or list compiler based on its branch patterns. */
    private void caseExpr(Expr.Case c, java.util.function.Consumer<Expr> body) {
      if (c.branches().stream().anyMatch(br -> br.pattern() instanceof Pattern.Tuple)) {
        tupleCase(c, body);
      } else if (c.branches().stream()
          .anyMatch(br -> br.pattern() instanceof Pattern.Ctor ct && tuples.tagOf(ct.name()) != null)) {
        enumCase(c, body);
      } else {
        listCase(c, body);
      }
    }

    /** A {@code case} over a nullary custom type: a chain of {@code i64.eq} tests on the tag, with a
     *  trailing wildcard/last branch as the default. */
    private void enumCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      int s = freshLocal("$tag" + code.size(), INT);
      gen(c.scrutinee());
      code.write(0x21);
      leb(code, s);
      W result = wOf(nodeType(c), tuples);
      List<Expr.Case.Branch> branches = c.branches();
      emitEnumChain(branches, 0, s, result, body);
    }

    private void emitEnumChain(
        List<Expr.Case.Branch> branches, int i, int s, W result,
        java.util.function.Consumer<Expr> body) {
      Expr.Case.Branch br = branches.get(i);
      boolean last = i == branches.size() - 1;
      // A wildcard, a var, or the final branch acts as the default (no test needed).
      boolean isDefault =
          last
              || br.pattern() instanceof Pattern.Wildcard
              || br.pattern() instanceof Pattern.Var;
      if (isDefault) {
        body.accept(br.body());
        return;
      }
      Long tag = tuples.tagOf(((Pattern.Ctor) br.pattern()).name());
      if (tag == null) {
        throw unsupported("a non-nullary constructor pattern in a case");
      }
      code.write(0x20);
      leb(code, s);
      code.write(0x42);
      sleb(code, tag);
      code.write(0x51); // i64.eq
      code.write(0x04); // if
      writeType(code, result);
      body.accept(br.body());
      code.write(0x05); // else
      emitEnumChain(branches, i + 1, s, result, body);
      code.write(0x0B);
    }

    /** A {@code case} whose single branch destructures a tuple: bind each field, then the body. */
    private void tupleCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      if (c.branches().size() != 1 || !(c.branches().get(0).pattern() instanceof Pattern.Tuple tp)) {
        throw unsupported("a tuple case with more than one branch");
      }
      bindTupleInto(tp, nodeType(c.scrutinee()), c.scrutinee());
      body.accept(c.branches().get(0).body());
    }

    /** Evaluates a tuple expression and binds each component of a tuple pattern to a fresh local. */
    private void bindTuplePattern(Pattern.Tuple tp, Expr value) {
      bindTupleInto(tp, nodeType(value), value);
    }

    private void bindTupleInto(Pattern.Tuple tp, Ty tupleTy, Expr value) {
      int ti = tupleIndex(tupleTy);
      int scrut = freshLocal("$tup" + code.size(), new Ref(ti));
      gen(value);
      code.write(0x21);
      leb(code, scrut);
      List<Ty> items = ((Ty.Tuple) Types.prune(tupleTy)).items();
      for (int i = 0; i < tp.items().size(); i++) {
        if (tp.items().get(i) instanceof Pattern.Var pv) {
          int idx = freshLocal(pv.name(), wOf(items.get(i), tuples));
          code.write(0x20);
          leb(code, scrut);
          code.write(0xFB);
          code.write(0x02); // struct.get
          leb(code, ti);
          leb(code, i);
          code.write(0x21);
          leb(code, idx);
        } else if (!(tp.items().get(i) instanceof Pattern.Wildcard)) {
          throw unsupported("a nested pattern in a tuple destructure");
        }
      }
    }

    /** Compiles a {@code case} over a list: branches for {@code []} and {@code head :: tail}. */
    private void listCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      Expr nilBody = null;
      Pattern consHead = null;
      Pattern consTail = null;
      Expr consBody = null;
      for (Expr.Case.Branch br : c.branches()) {
        switch (br.pattern()) {
          case Pattern.ListPat lp when lp.items().isEmpty() -> nilBody = br.body();
          case Pattern.Cons cons -> {
            consHead = cons.head();
            consTail = cons.tail();
            consBody = br.body();
          }
          case Pattern.Wildcard ignored -> nilBody = nilBody == null ? br.body() : nilBody;
          default -> throw unsupported("case pattern (only [] and head :: tail)");
        }
      }
      if (nilBody == null || consBody == null) {
        throw unsupported("case without both [] and :: branches");
      }
      int s = freshLocal("$scrut" + code.size(), LIST);
      gen(c.scrutinee());
      code.write(0x21);
      leb(code, s);
      // if ref.is_null then nil else { bind head/tail; cons }
      code.write(0x20);
      leb(code, s);
      code.write(0xD1); // ref.is_null
      code.write(0x04);
      writeType(code, wOf(nodeType(c), tuples)); // result type
      body.accept(nilBody);
      code.write(0x05);
      if (consHead instanceof Pattern.Var hv) {
        int h = freshLocal(hv.name(), INT);
        code.write(0x20);
        leb(code, s);
        code.write(0xFB);
        code.write(0x02);
        leb(code, 0);
        leb(code, 0); // struct.get $cons 0 (head)
        code.write(0x21);
        leb(code, h);
      }
      if (consTail instanceof Pattern.Var tv) {
        int t = freshLocal(tv.name(), LIST);
        code.write(0x20);
        leb(code, s);
        code.write(0xFB);
        code.write(0x02);
        leb(code, 0);
        leb(code, 1); // struct.get $cons 1 (tail)
        code.write(0x21);
        leb(code, t);
      }
      body.accept(consBody);
      code.write(0x0B);
    }

    private Ty nodeType(Expr e) {
      Ty t = nodeTypes.get(e);
      if (t == null) {
        throw unsupported("an expression whose type inference did not record");
      }
      return t;
    }

    /** Whether the expression's inferred type is {@code Float} (so it uses f64 ops). */
    private boolean isFloat(Expr e) {
      return wOf(nodeType(e), tuples).equals(FLOAT);
    }

    /** Emits {@code f64.const} followed by the 8-byte little-endian IEEE-754 encoding. */
    private void emitF64(double value) {
      code.write(0x44);
      long bits = Double.doubleToLongBits(value);
      for (int i = 0; i < 8; i++) {
        code.write((int) ((bits >>> (8 * i)) & 0xFF));
      }
    }
  }

  private static void writeType(ByteArrayOutputStream out, W w) {
    if (w instanceof Sca s) {
      out.write(s.valtype());
    } else if (w instanceof Ref r) {
      out.write(0x63); // (ref null <typeIndex>)
      sleb(out, r.typeIndex());
    }
  }

  // --- module assembly ----------------------------------------------------

  private static byte[] assemble(List<Func> funcList, Map<Expr, Ty> nodeTypes, Tuples tuples) {
    Map<String, int[]> table = new HashMap<>();
    Map<String, W> funcResult = new HashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      table.put(funcList.get(i).name(), new int[] {i, funcList.get(i).params().size()});
      funcResult.put(funcList.get(i).name(), funcList.get(i).result());
    }

    // Struct types occupy indices 0..(structCount-1): the cons list is 0, tuple shapes are 1..T.
    // Function signature types come after them.
    int structCount = 1 + tuples.count();
    List<Func> sigOrder = new ArrayList<>();
    Map<String, Integer> sigIndex = new LinkedHashMap<>();
    for (Func f : funcList) {
      String sig = sigKey(f);
      if (!sigIndex.containsKey(sig)) {
        sigIndex.put(sig, structCount + sigIndex.size());
        sigOrder.add(f);
      }
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00});

    // Type section: one rec group holding the cons struct (type 0) and every tuple struct
    // (types 1..T), then one functype per distinct signature.
    ByteArrayOutputStream types = new ByteArrayOutputStream();
    leb(types, 1 + sigOrder.size()); // total type definitions (the rec group counts as one)
    types.write(0x4E);
    leb(types, structCount); // rec group: cons + tuple structs
    types.write(0x5F); // struct (cons)
    leb(types, 2); // 2 fields
    types.write(I64);
    types.write(0x00); // head : i64 (const)
    types.write(LISTREF[0]);
    types.write(LISTREF[1]);
    types.write(0x00); // tail : (ref null $cons) (const)
    for (List<W> shape : tuples.shapes()) {
      types.write(0x5F); // struct (tuple)
      leb(types, shape.size());
      for (W w : shape) {
        writeType(types, w);
        types.write(0x00); // immutable field
      }
    }
    for (Func f : sigOrder) {
      types.write(0x60);
      leb(types, f.paramTypes().size());
      for (W w : f.paramTypes()) {
        writeType(types, w);
      }
      leb(types, 1);
      writeType(types, f.result());
    }
    section(out, 1, types);

    // Function section.
    ByteArrayOutputStream funcs = new ByteArrayOutputStream();
    leb(funcs, funcList.size());
    for (Func f : funcList) {
      leb(funcs, sigIndex.get(sigKey(f)));
    }
    section(out, 3, funcs);

    // Export section: each function by name, plus "main".
    ByteArrayOutputStream exports = new ByteArrayOutputStream();
    java.util.LinkedHashMap<String, Integer> names = new java.util.LinkedHashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      names.putIfAbsent(funcList.get(i).name(), i);
    }
    leb(exports, names.size());
    names.forEach((name, idx) -> {
      name(exports, name);
      exports.write(0x00);
      leb(exports, idx);
    });
    section(out, 7, exports);

    // Code section.
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    leb(code, funcList.size());
    for (Func f : funcList) {
      code.writeBytes(
          new Gen(table, funcResult, nodeTypes, tuples, f.params(), f.paramTypes())
              .compile(f.body()));
    }
    section(out, 10, code);
    return out.toByteArray();
  }

  private static String sigKey(Func f) {
    StringBuilder b = new StringBuilder();
    for (W w : f.paramTypes()) {
      b.append(w).append(',');
    }
    return b.append("->").append(f.result()).toString();
  }

  private static void section(ByteArrayOutputStream out, int id, ByteArrayOutputStream content) {
    out.write(id);
    leb(out, content.size());
    out.writeBytes(content.toByteArray());
  }

  private static void name(ByteArrayOutputStream out, String s) {
    byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    leb(out, bytes.length);
    out.writeBytes(bytes);
  }

  private static void leb(ByteArrayOutputStream out, long value) {
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

  private static void sleb(ByteArrayOutputStream out, long value) {
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
}

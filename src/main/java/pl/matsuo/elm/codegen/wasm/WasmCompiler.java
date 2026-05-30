package pl.matsuo.elm.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.parser.Parser;

/**
 * A from-scratch compiler from a numeric/boolean subset of Elm directly to a <b>WebAssembly</b>
 * binary module — no external assembler. It is a fourth backend alongside the Truffle interpreter,
 * the bytecode VM and the JavaScript compiler, and is differential-tested against them.
 *
 * <p>The supported subset is the closed, total {@code Int}/{@code Bool} fragment: integer literals,
 * {@code + - * //}, {@code negate}, {@code modBy}/{@code abs}, {@code let}, an inlined lambda
 * application {@code (\x -> body) arg}, {@code if}, comparisons ({@code < > <= >= == /=}) and the
 * boolean operators ({@code && || not}, {@code True}/{@code False}). Integers are 64-bit ({@code
 * i64}), matching the interpreter, so a host reads results as {@code BigInt}.
 *
 * <p>It also has a <b>heap</b>: a linear-memory bump allocator (which grows memory on demand, so
 * long lists and deep recursion don't trap on the first 64 KiB page) backs cons-lists (built with
 * list literals and {@code ::}, consumed by a {@code case} over {@code []} / {@code head :: tail}),
 * tuples, and <b>tagged custom types</b> — a value {@code Ctor a b} is a cell {@code {tag, a, b}}
 * whose tag is the constructor's index in its union, and a {@code case} over constructors loads the
 * tag word and dispatches, binding fields by offset (constructor-argument patterns must be
 * variable/wildcard — no nested matching). A heap value is an {@code i64} address, so values stay
 * uniformly {@code i64} on the stack. This lets recursive list functions and custom-type matching
 * (e.g. {@code area (Rect 3 4)}) compile and run in wasm.
 *
 * <p><b>First-class top-level functions</b> work too: every function is placed in a funcref table,
 * a function used as a value compiles to its table index (carried as an i64), and applying a
 * function value held in a parameter dispatches via {@code call_indirect}. So higher-order code over
 * named functions ({@code apply f x = f x}; {@code main = apply inc 5}) runs in wasm. What is still
 * unsupported is <b>closures</b> (capturing locals) and <b>currying / partial application</b>, plus
 * strings and records, which need a richer (encoded / type-directed) representation; those remain on
 * the JS backend.
 */
public final class WasmCompiler {

  // Value types and opcodes (WebAssembly binary format).
  private static final int I32 = 0x7F, I64 = 0x7E;

  private WasmCompiler() {}

  /** A compiled function: its name, parameter names (all i64) and body. */
  private record Func(String name, List<String> params, Expr body) {}

  /** Compiles one expression into a module exporting {@code main : () -> i64}. */
  public static byte[] module(String expression) {
    return module(List.of(Parser.parseExpression(expression)));
  }

  /** Compiles several expressions into a module exporting {@code f0..fN}, each {@code () -> i64}. */
  public static byte[] module(List<Expr> expressions) {
    List<Func> funcs = new ArrayList<>();
    for (int i = 0; i < expressions.size(); i++) {
      funcs.add(new Func("f" + i, List.of(), expressions.get(i)));
    }
    return assemble(funcs, Map.of(), Map.of());
  }

  /**
   * Compiles all numeric top-level functions of a module to a wasm module, exporting each by name
   * (calls and recursion become wasm {@code call}s). This is what lets {@code fib} compile, so the
   * WASM backend can join the recursive benchmark and differential tests.
   */
  public static byte[] moduleFromSource(String source) {
    pl.matsuo.elm.ast.Module module = pl.matsuo.elm.parser.Parser.parseModule(source);
    List<Func> funcs = new ArrayList<>();
    // Custom-type constructors: each variant gets a tag (its index in the union) and an arity, so a
    // value `Ctor a b` is a heap cell {tag, a, b} and a `case` dispatches on the loaded tag word.
    Map<String, Integer> ctorTag = new HashMap<>();
    Map<String, Integer> ctorArity = new HashMap<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        for (int i = 0; i < u.variants().size(); i++) {
          Decl.Union.Variant variant = u.variants().get(i);
          ctorTag.put(variant.name(), i);
          ctorArity.put(variant.name(), variant.args().size());
        }
      }
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        List<String> params = new ArrayList<>();
        for (Pattern p : v.params()) {
          if (p instanceof Pattern.Var pv) {
            params.add(pv.name());
          } else {
            throw unsupported("non-variable parameter pattern");
          }
        }
        funcs.add(new Func(v.name(), params, v.body()));
      }
    }
    // Per-expression inferred types power the type-directed codegen for records (and strings): the
    // compiler walks the same Expr instances, so an IdentityHashMap keyed by expression resolves
    // field layouts and operator overloads. Best-effort — if inference can't type the module we just
    // proceed without it (records/strings then fall back to "unsupported").
    Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes;
    try {
      pl.matsuo.elm.types.Infer infer = new pl.matsuo.elm.types.Infer();
      infer.inferModule(module, pl.matsuo.elm.types.Signatures.globals());
      nodeTypes = infer.nodeTypes();
    } catch (RuntimeException e) {
      nodeTypes = Map.of();
    }
    return assemble(funcs, ctorTag, ctorArity, nodeTypes);
  }

  // --- per-function code generation --------------------------------------

  private static final class FunctionGen {
    private final Map<String, Integer> locals = new HashMap<>(); // name -> local index
    private final Map<String, int[]> funcs; // function name -> {index, arity}
    private final Map<String, Integer> ctorTag; // constructor name -> tag (index in its union)
    private final Map<String, Integer> ctorArity; // constructor name -> number of fields
    private final Map<Integer, Integer> arityType; // call arity -> wasm type index (for call_indirect)
    private final Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes; // inferred type per expression
    private int localCount; // all locals are i64; params occupy 0..numParams-1
    private final int numParams;
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    FunctionGen(
        Map<String, int[]> funcs,
        List<String> params,
        Map<String, Integer> ctorTag,
        Map<String, Integer> ctorArity,
        Map<Integer, Integer> arityType,
        Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes) {
      this.funcs = funcs;
      this.ctorTag = ctorTag;
      this.ctorArity = ctorArity;
      this.arityType = arityType;
      this.nodeTypes = nodeTypes;
      this.numParams = params.size();
      for (int i = 0; i < params.size(); i++) {
        locals.put(params.get(i), i);
      }
      this.localCount = params.size();
    }

    /** Compiles an Int-typed expression into a complete code entry (locals + body + end). */
    byte[] compile(Expr e) {
      intExpr(e);
      // Locals declaration covers only the non-parameter locals; params are implicit (0..n-1).
      int extra = localCount - numParams;
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (extra == 0) {
        body.write(0x00); // no local groups
      } else {
        body.write(0x01); // one group
        leb(body, extra);
        body.write(I64);
      }
      byte[] c = code.toByteArray();
      body.write(c, 0, c.length);
      body.write(0x0B); // end
      ByteArrayOutputStream entry = new ByteArrayOutputStream();
      leb(entry, body.size());
      byte[] b = body.toByteArray();
      entry.write(b, 0, b.length);
      return entry.toByteArray();
    }

    private int local(String name) {
      return locals.computeIfAbsent(name, n -> localCount++);
    }

    private int freshLocal() {
      return localCount++;
    }

    /** Emits code leaving an i64 (the value of an Int-typed expression) on the stack. */
    private void intExpr(Expr e) {
      switch (e) {
        case Expr.IntLit lit -> {
          code.write(0x42); // i64.const
          sleb(code, lit.value());
        }
        case Expr.Negate n -> {
          code.write(0x42);
          sleb(code, 0);
          intExpr(n.operand());
          code.write(0x7D); // i64.sub  (0 - x)
        }
        case Expr.BinOp b -> intBinOp(b);
        case Expr.If iff -> {
          boolExpr(iff.cond());
          code.write(0x04); // if
          code.write(I64); // -> i64
          intExpr(iff.thenBranch());
          code.write(0x05); // else
          intExpr(iff.elseBranch());
          code.write(0x0B); // end
        }
        case Expr.Let let -> {
          for (Decl d : let.defs()) {
            if (d instanceof Decl.Value v && v.params().isEmpty()) {
              int idx = local(v.name());
              intExpr(v.body());
              code.write(0x21); // local.set
              leb(code, idx);
            } else {
              throw unsupported("let definition with parameters");
            }
          }
          intExpr(let.body());
        }
        case Expr.Var v -> {
          Integer idx = locals.get(v.name());
          if (idx != null) {
            code.write(0x20); // local.get
            leb(code, idx);
          } else if (funcs.containsKey(v.name()) && funcs.get(v.name())[1] == 0) {
            code.write(0x10); // call (a zero-arg top-level value)
            leb(code, funcs.get(v.name())[0]);
          } else if (funcs.containsKey(v.name())) {
            // A top-level function used as a first-class value: its table index, carried as i64.
            code.write(0x42); // i64.const
            sleb(code, funcs.get(v.name())[0]);
          } else {
            throw unsupported("variable " + v.name());
          }
        }
        case Expr.App app -> intApp(app);
        case Expr.ListLit l -> emitList(l.items(), 0);
        case Expr.Tuple t -> emitTuple(t.items());
        case Expr.Ctor c when ctorTag.containsKey(c.name()) -> emitCtor(c.name(), List.of());
        case Expr.Case c -> intCase(c);
        case Expr.Record r -> emitRecord(r);
        case Expr.RecordAccess a -> emitRecordAccess(a);
        case Expr.RecordUpdate u -> emitRecordUpdate(u);
        default -> throw unsupported(e.getClass().getSimpleName());
      }
    }

    // --- heap: cons-lists and tuples live in linear memory via a bump allocator -------------
    // A list value is an i64: 0 means [], a non-zero value is the address of a 2-word cons cell
    // {head, tail}. A tuple of arity n is the address of n contiguous i64 words. The pointer is
    // carried as an i64 (zero-extended address) so every value stays a uniform i64 on the stack.

    /** Builds the cons chain for {@code items[i..]}, leaving its i64 pointer (0 for the empty tail). */
    private void emitList(List<Expr> items, int i) {
      if (i >= items.size()) {
        code.write(0x42); // i64.const 0  -> Nil
        sleb(code, 0);
        return;
      }
      emitCons(() -> intExpr(items.get(i)), () -> emitList(items, i + 1));
    }

    /** Allocates a 2-word cons cell {head, tail}, leaving its address (as i64) on the stack. */
    private void emitCons(Runnable head, Runnable tail) {
      int addr = freshLocal(); // i64 local holding the zero-extended cell address
      code.write(0x23);
      leb(code, 0); // global.get $hp (i32)
      code.write(0xAD); // i64.extend_i32_u
      code.write(0x21);
      leb(code, addr); // local.set addr
      bumpHeap(16);
      store(addr, 0, head);
      store(addr, 8, tail);
      code.write(0x20);
      leb(code, addr); // local.get addr -> the i64 pointer
    }

    /** Allocates {@code items.size()} contiguous words, leaving the tuple's address (as i64). */
    private void emitTuple(List<Expr> items) {
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr);
      bumpHeap(items.size() * 8);
      for (int j = 0; j < items.size(); j++) {
        int item = j;
        store(addr, j * 8, () -> intExpr(items.get(item)));
      }
      code.write(0x20);
      leb(code, addr);
    }

    /**
     * Emits {@code $hp += n} (the global heap pointer, an i32) and then grows linear memory by one
     * page if the new pointer has passed the current capacity. Each allocation is far smaller than a
     * 64 KiB page, so a single {@code memory.grow 1} always restores {@code $hp <= capacity}; this is
     * what lets heap-heavy, long-running programs (long lists, deep recursion) run without trapping.
     */
    private void bumpHeap(int n) {
      code.write(0x23);
      leb(code, 0); // global.get $hp
      code.write(0x41);
      sleb(code, n); // i32.const n
      code.write(0x6A); // i32.add
      code.write(0x24);
      leb(code, 0); // global.set $hp
      // if ($hp > memory.size * 65536) memory.grow(1)
      code.write(0x23);
      leb(code, 0); // global.get $hp
      code.write(0x3F);
      code.write(0x00); // memory.size (pages)
      code.write(0x41);
      sleb(code, 16); // i32.const 16
      code.write(0x74); // i32.shl  -> capacity in bytes
      code.write(0x4B); // i32.gt_u -> $hp > capacity ?
      code.write(0x04);
      code.write(0x40); // if (void)
      code.write(0x41);
      sleb(code, 1); // i32.const 1
      code.write(0x40);
      code.write(0x00); // memory.grow 1
      code.write(0x1A); // drop the previous-size result
      code.write(0x0B); // end
    }

    /** Stores the i64 produced by {@code value} at word offset {@code off} of cell {@code addrLocal}. */
    private void store(int addrLocal, int off, Runnable value) {
      code.write(0x20);
      leb(code, addrLocal); // local.get addr (i64)
      code.write(0xA7); // i32.wrap_i64 -> address
      value.run(); // i64 value
      code.write(0x37); // i64.store
      leb(code, 3); // align = 2^3 = 8
      leb(code, off);
    }

    /** Loads the i64 word at offset {@code off} of the cell whose i64 address is in {@code addrLocal}. */
    private void load(int addrLocal, int off) {
      code.write(0x20);
      leb(code, addrLocal);
      code.write(0xA7); // i32.wrap_i64
      code.write(0x29); // i64.load
      leb(code, 3);
      leb(code, off);
    }

    /** Allocates a custom-type value: a cell {tag, field0, field1, …}, leaving its address (i64). */
    private void emitCtor(String name, List<Expr> args) {
      if (args.size() != ctorArity.getOrDefault(name, -1)) {
        throw unsupported("partially-applied constructor " + name);
      }
      int tag = ctorTag.get(name);
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr);
      bumpHeap(8 * (1 + args.size()));
      store(
          addr,
          0,
          () -> {
            code.write(0x42); // i64.const tag
            sleb(code, tag);
          });
      for (int j = 0; j < args.size(); j++) {
        int arg = j;
        store(addr, (1 + j) * 8, () -> intExpr(args.get(arg)));
      }
      code.write(0x20);
      leb(code, addr);
    }

    // --- records: a heap block of one i64 word per field, in canonical (name-sorted) order ------
    // The sorted layout is independent of the literal's field order, so a literal and a later
    // `.field` access (whose record type is known and closed) agree on every field's offset.

    /** Allocates a record literal: its fields stored at their name-sorted offsets; leaves the i64
     *  pointer. */
    private void emitRecord(Expr.Record rec) {
      List<Expr.Record.Field> fs = new ArrayList<>(rec.fields());
      fs.sort(java.util.Comparator.comparing(Expr.Record.Field::name));
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr);
      bumpHeap(fs.size() * 8);
      for (int j = 0; j < fs.size(); j++) {
        int jj = j;
        store(addr, j * 8, () -> intExpr(fs.get(jj).value()));
      }
      code.write(0x20);
      leb(code, addr);
    }

    /** Loads {@code target.field}: the record's i64 word at the field's name-sorted offset. */
    private void emitRecordAccess(Expr.RecordAccess acc) {
      List<String> fields = closedRecordFields(acc.target());
      int idx = fields.indexOf(acc.field());
      if (idx < 0) {
        throw unsupported("record field ." + acc.field());
      }
      int addr = freshLocal();
      intExpr(acc.target()); // i64 pointer
      code.write(0x21);
      leb(code, addr);
      load(addr, idx * 8);
    }

    /** {@code { base | f = v, … }}: a fresh record, copying {@code base}'s words then overwriting the
     *  updated fields (at their name-sorted offsets). */
    private void emitRecordUpdate(Expr.RecordUpdate up) {
      Integer baseLocal = locals.get(up.base());
      if (baseLocal == null) {
        throw unsupported("record update of non-local '" + up.base() + "'");
      }
      List<String> fields = closedRecordFields(up); // the result has the base record's fields
      java.util.Map<String, Expr> updates = new java.util.HashMap<>();
      for (Expr.Record.Field f : up.fields()) {
        updates.put(f.name(), f.value());
      }
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr);
      bumpHeap(fields.size() * 8);
      for (int j = 0; j < fields.size(); j++) {
        int off = j * 8;
        Expr updated = updates.get(fields.get(j));
        if (updated != null) {
          store(addr, off, () -> intExpr(updated));
        } else {
          // copy base.word(off): store at addr+off the value loaded from base+off
          store(
              addr,
              off,
              () -> {
                code.write(0x20);
                leb(code, baseLocal);
                code.write(0xA7); // i32.wrap_i64 -> base address
                code.write(0x29); // i64.load
                leb(code, 3);
                leb(code, off);
              });
        }
      }
      code.write(0x20);
      leb(code, addr);
    }

    /** The name-sorted field list of {@code e}'s record type, requiring it to be known and closed. */
    private List<String> closedRecordFields(Expr e) {
      if (nodeTypes.get(e) instanceof pl.matsuo.elm.types.Ty.Record r && r.tail() == null) {
        return new ArrayList<>(new java.util.TreeSet<>(r.fields().keySet()));
      }
      throw unsupported("record with an unknown or open (row-polymorphic) type in WASM");
    }

    /** Dispatches a {@code case} to the list or the custom-type compiler by its branch patterns. */
    private void intCase(Expr.Case c) {
      boolean adt =
          c.branches().stream()
              .anyMatch(b -> b.pattern() instanceof Pattern.Ctor ct && ctorTag.containsKey(ct.name()));
      if (adt) {
        intAdtCase(c);
      } else {
        intListCase(c);
      }
    }

    /**
     * Compiles a {@code case} over a custom type: load the value's tag word once, then an if/else
     * chain comparing it to each constructor's tag, binding fields (by word offset) in the match.
     * Constructor arguments must be variable/wildcard patterns (no nested matching).
     */
    private void intAdtCase(Expr.Case c) {
      int s = freshLocal();
      intExpr(c.scrutinee());
      code.write(0x21);
      leb(code, s); // local.set scrutinee pointer
      emitAdtBranches(c.branches(), 0, s);
    }

    private void emitAdtBranches(List<Expr.Case.Branch> branches, int idx, int s) {
      if (idx >= branches.size()) {
        code.write(0x00); // unreachable: a well-typed case is exhaustive
        return;
      }
      Expr.Case.Branch br = branches.get(idx);
      switch (br.pattern()) {
        case Pattern.Var v -> {
          int local = local(v.name());
          code.write(0x20);
          leb(code, s);
          code.write(0x21);
          leb(code, local); // bind the whole value
          intExpr(br.body());
        }
        case Pattern.Wildcard ignored -> intExpr(br.body());
        case Pattern.Ctor ctor -> {
          load(s, 0); // tag word
          code.write(0x42);
          sleb(code, ctorTag.get(ctor.name())); // i64.const tag
          code.write(0x51); // i64.eq
          code.write(0x04);
          code.write(I64); // if -> i64
          for (int i = 0; i < ctor.args().size(); i++) {
            if (ctor.args().get(i) instanceof Pattern.Var fv) {
              int fl = local(fv.name());
              load(s, (1 + i) * 8); // field i lives at word 1+i
              code.write(0x21);
              leb(code, fl);
            } else if (!(ctor.args().get(i) instanceof Pattern.Wildcard)) {
              throw unsupported("nested constructor pattern in WASM");
            }
          }
          intExpr(br.body());
          code.write(0x05); // else
          emitAdtBranches(branches, idx + 1, s);
          code.write(0x0B); // end
        }
        default -> throw unsupported("custom-type case pattern in WASM");
      }
    }

    /** Compiles a {@code case} over a list: branches for {@code []} and {@code head :: tail}. */
    private void intListCase(Expr.Case c) {
      Expr nilBody = null;
      Pattern consHead = null, consTail = null;
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
          default -> throw unsupported("case pattern in WASM (only list [] / :: supported)");
        }
      }
      if (nilBody == null || consBody == null) {
        throw unsupported("case without both [] and :: branches");
      }
      int s = freshLocal();
      intExpr(c.scrutinee());
      code.write(0x21);
      leb(code, s); // local.set scrutinee
      code.write(0x20);
      leb(code, s);
      code.write(0x50); // i64.eqz  (1 if Nil)
      code.write(0x04);
      code.write(I64); // if -> i64
      intExpr(nilBody);
      code.write(0x05); // else
      if (consHead instanceof Pattern.Var hv) {
        int h = local(hv.name());
        load(s, 0);
        code.write(0x21);
        leb(code, h); // local.set head
      }
      if (consTail instanceof Pattern.Var tv) {
        int t = local(tv.name());
        load(s, 8);
        code.write(0x21);
        leb(code, t); // local.set tail
      }
      intExpr(consBody);
      code.write(0x0B); // end
    }

    private void intBinOp(Expr.BinOp b) {
      if (b.op().equals("::")) {
        emitCons(() -> intExpr(b.left()), () -> intExpr(b.right()));
        return;
      }
      int op =
          switch (b.op()) {
            case "+" -> 0x7C; // i64.add
            case "-" -> 0x7D; // i64.sub
            case "*" -> 0x7E; // i64.mul
            case "//" -> 0x7F; // i64.div_s
            default -> throw unsupported("operator " + b.op());
          };
      intExpr(b.left());
      intExpr(b.right());
      code.write(op);
    }

    /** Handles the builtin applications {@code modBy m x}, {@code abs x} and {@code (\x->..) arg}. */
    private void intApp(Expr.App app) {
      // (\x -> body) arg  ->  let x = arg
      if (app.fn() instanceof Expr.Lambda lam
          && lam.params().size() == 1
          && lam.params().get(0) instanceof Pattern.Var pv) {
        int idx = local(pv.name());
        intExpr(app.arg());
        code.write(0x21); // local.set
        leb(code, idx);
        intExpr(lam.body());
        return;
      }
      // abs x
      if (app.fn() instanceof Expr.Var v && v.name().equals("abs")) {
        int t = freshLocal();
        intExpr(app.arg());
        code.write(0x22); // local.tee
        leb(code, t);
        code.write(0x42);
        sleb(code, 0);
        code.write(0x59); // i64.ge_s  (x >= 0)
        code.write(0x04);
        code.write(I64); // if -> i64
        code.write(0x20);
        leb(code, t); // then: x
        code.write(0x05); // else
        code.write(0x42);
        sleb(code, 0);
        code.write(0x20);
        leb(code, t);
        code.write(0x7D); // 0 - x
        code.write(0x0B);
        return;
      }
      // modBy m x  ->  ((x % m) + m) % m   (matches Elm's floored modulo for m > 0)
      if (app.fn() instanceof Expr.App inner
          && inner.fn() instanceof Expr.Var v
          && v.name().equals("modBy")) {
        Expr m = inner.arg(), x = app.arg();
        intExpr(x);
        intExpr(m);
        code.write(0x81); // i64.rem_s
        intExpr(m);
        code.write(0x7C); // i64.add
        intExpr(m);
        code.write(0x81); // i64.rem_s
        return;
      }
      // A fully-applied call to a known top-level function (incl. recursion), or constructor.
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      // A constructor application `Ctor a b` -> a tagged heap cell {tag, a, b}.
      if (head instanceof Expr.Ctor ctor && ctorTag.containsKey(ctor.name())) {
        emitCtor(ctor.name(), args);
        return;
      }
      if (head instanceof Expr.Var v && funcs.containsKey(v.name()) && funcs.get(v.name())[1] == args.size()) {
        for (Expr arg : args) {
          intExpr(arg);
        }
        code.write(0x10); // call
        leb(code, funcs.get(v.name())[0]);
        return;
      }
      // Applying a first-class function value held in a local (a higher-order parameter): push the
      // args, then the function value (its table index, as i64), and dispatch via call_indirect.
      if (head instanceof Expr.Var v && locals.containsKey(v.name())) {
        Integer typeIdx = arityType.get(args.size());
        if (typeIdx == null) {
          throw unsupported("indirect call of arity " + args.size());
        }
        for (Expr arg : args) {
          intExpr(arg);
        }
        code.write(0x20); // local.get f (the i64 function value)
        leb(code, locals.get(v.name()));
        code.write(0xA7); // i32.wrap_i64 -> table index
        code.write(0x11); // call_indirect
        leb(code, typeIdx); // the (i64^n)->i64 type
        leb(code, 0); // table 0
        return;
      }
      throw unsupported("application");
    }

    /** Emits code leaving an i32 (0/1) for a Bool-typed expression. */
    private void boolExpr(Expr e) {
      switch (e) {
        case Expr.Ctor c when c.name().equals("True") -> i32const(1);
        case Expr.Ctor c when c.name().equals("False") -> i32const(0);
        case Expr.BinOp b -> boolBinOp(b);
        case Expr.App app when app.fn() instanceof Expr.Var v && v.name().equals("not") -> {
          boolExpr(app.arg());
          code.write(0x45); // i32.eqz
        }
        default -> throw unsupported("boolean expression " + e.getClass().getSimpleName());
      }
    }

    private void boolBinOp(Expr.BinOp b) {
      switch (b.op()) {
        case "&&" -> {
          boolExpr(b.left());
          boolExpr(b.right());
          code.write(0x71); // i32.and
        }
        case "||" -> {
          boolExpr(b.left());
          boolExpr(b.right());
          code.write(0x72); // i32.or
        }
        case "<", ">", "<=", ">=", "==", "/=" -> {
          intExpr(b.left());
          intExpr(b.right());
          code.write(
              switch (b.op()) {
                case "<" -> 0x53; // i64.lt_s
                case ">" -> 0x55; // i64.gt_s
                case "<=" -> 0x57; // i64.le_s
                case ">=" -> 0x59; // i64.ge_s
                case "==" -> 0x51; // i64.eq
                default -> 0x52; // i64.ne
              });
        }
        default -> throw unsupported("boolean operator " + b.op());
      }
    }

    private void i32const(int v) {
      code.write(0x41); // i32.const
      sleb(code, v);
    }
  }

  static ElmRuntimeError unsupported(String what) {
    return new ElmRuntimeError("WASM backend does not support " + what + " (numeric subset only)");
  }

  // --- module assembly ----------------------------------------------------

  private static byte[] assemble(
      List<Func> funcList, Map<String, Integer> ctorTag, Map<String, Integer> ctorArity) {
    return assemble(funcList, ctorTag, ctorArity, Map.of());
  }

  private static byte[] assemble(
      List<Func> funcList,
      Map<String, Integer> ctorTag,
      Map<String, Integer> ctorArity,
      Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes) {
    // Function table: name -> {index, arity}, so calls/recursion resolve to a call index.
    Map<String, int[]> table = new HashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      table.put(funcList.get(i).name(), new int[] {i, funcList.get(i).params().size()});
    }
    // One wasm function type per distinct arity: (i64 x arity) -> i64.
    List<Integer> arities = new ArrayList<>();
    Map<Integer, Integer> arityType = new HashMap<>();
    for (Func f : funcList) {
      int a = f.params().size();
      if (!arityType.containsKey(a)) {
        arityType.put(a, arities.size());
        arities.add(a);
      }
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00}); // magic + version

    // Type section: one functype per distinct arity.
    ByteArrayOutputStream types = new ByteArrayOutputStream();
    leb(types, arities.size());
    for (int a : arities) {
      types.write(0x60);
      leb(types, a);
      for (int i = 0; i < a; i++) {
        types.write(I64);
      }
      leb(types, 1);
      types.write(I64);
    }
    section(out, 1, types);

    // Function section: each function's type index (by its arity).
    ByteArrayOutputStream funcs = new ByteArrayOutputStream();
    leb(funcs, funcList.size());
    for (Func f : funcList) {
      leb(funcs, arityType.get(f.params().size()));
    }
    section(out, 3, funcs);

    // Table section (id 4): one funcref table holding every function, so a function value (carried
    // as its index) can be invoked with call_indirect. Sized exactly to the function count.
    ByteArrayOutputStream tableSec = new ByteArrayOutputStream();
    leb(tableSec, 1); // one table
    tableSec.write(0x70); // funcref
    tableSec.write(0x00); // limits: min only
    leb(tableSec, funcList.size()); // min = number of functions
    section(out, 4, tableSec);

    // Memory section (id 5): one memory, min 1 page (64 KiB), no max — the heap for cons-cells,
    // tuples and tagged values. The allocator grows it on demand (see bumpHeap), so the only ceiling
    // is the host's, not a fixed page count.
    ByteArrayOutputStream memory = new ByteArrayOutputStream();
    leb(memory, 1); // one memory
    memory.write(0x00); // limits: min only (growable, no maximum)
    leb(memory, 1); // min 1 page
    section(out, 5, memory);

    // Global section (id 6): a mutable i32 bump pointer $hp, initialised past the Nil sentinel (0).
    ByteArrayOutputStream globals = new ByteArrayOutputStream();
    leb(globals, 1); // one global
    globals.write(I32);
    globals.write(0x01); // mutable
    globals.write(0x41); // i32.const
    sleb(globals, 16); // start the heap at 16 (addresses are never 0, which means Nil)
    globals.write(0x0B); // end
    section(out, 6, globals);

    // Export section: each function by its name, plus f0..fN by position and "main".
    ByteArrayOutputStream exports = new ByteArrayOutputStream();
    java.util.LinkedHashMap<String, Integer> exportNames = new java.util.LinkedHashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      exportNames.putIfAbsent(funcList.get(i).name(), i);
      exportNames.putIfAbsent("f" + i, i);
    }
    exportNames.putIfAbsent("main", table.containsKey("main") ? table.get("main")[0] : 0);
    leb(exports, exportNames.size() + 1); // + the memory export
    exportNames.forEach(
        (name, idx) -> {
          name(exports, name);
          exports.write(0x00); // func export
          leb(exports, idx);
        });
    name(exports, "memory");
    exports.write(0x02); // memory export
    leb(exports, 0); // memory index 0
    section(out, 7, exports);

    // Element section (id 9): one active segment filling the table with funcref i -> function i, so
    // that a function's index is also its table slot (what a first-class function value carries).
    ByteArrayOutputStream elem = new ByteArrayOutputStream();
    leb(elem, 1); // one segment
    leb(elem, 0); // flags: active, table 0, funcidx vector
    elem.write(0x41);
    sleb(elem, 0); // i32.const 0 (offset)
    elem.write(0x0B); // end
    leb(elem, funcList.size());
    for (int i = 0; i < funcList.size(); i++) {
      leb(elem, i);
    }
    section(out, 9, elem);

    // Code section.
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    leb(code, funcList.size());
    for (Func f : funcList) {
      code.writeBytes(
          new FunctionGen(table, f.params(), ctorTag, ctorArity, arityType, nodeTypes)
              .compile(f.body()));
    }
    section(out, 10, code);
    return out.toByteArray();
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

  /** Unsigned LEB128. */
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

  /** Signed LEB128. */
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

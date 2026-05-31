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
 * <p>The supported subset is monomorphic {@code Int}/{@code Bool} and {@code List Int}: integer
 * arithmetic and comparisons, {@code if}, {@code let}, top-level functions and recursion, list
 * literals and {@code ::}, and a {@code case} over {@code []} / {@code head :: tail}. Each function's
 * parameter and result wasm types come from Hindley–Milner inference (so an {@code Int} is an
 * {@code i64} and a {@code List Int} a struct reference). Records, closures, strings and other
 * element types remain on the linear-memory backend — extending this one to them is future work.
 */
public final class WasmGc {

  private static final int I64 = 0x7E;
  // The cons struct is type index 0; a list value has type (ref null 0): bytes 0x63 0x00.
  private static final int[] LISTREF = {0x63, 0x00};

  private WasmGc() {}

  /** A compiled function: name, parameter (name,type) pairs, result type and body. */
  private record Func(String name, List<String> params, List<W> paramTypes, W result, Expr body) {}

  /** A wasm value type in the supported subset. */
  private enum W {
    INT, // i64
    LIST // (ref null $cons)
  }

  /** Compiles a module's monomorphic Int/List-Int functions to a WasmGC binary. */
  public static byte[] module(String source) {
    Module module = Parser.parseModule(source);
    Infer infer = new Infer();
    Map<String, Scheme> schemes = infer.inferModule(module, Signatures.globals());
    Map<Expr, Ty> nodeTypes = infer.nodeTypes();

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
          paramTypes.add(wOf(arrowParam(t, paramTypes.size())));
        }
        funcs.add(new Func(v.name(), params, paramTypes, wOf(resultType(t, params.size())), v.body()));
      }
    }
    return assemble(funcs, nodeTypes);
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

  private static W wOf(Ty t) {
    Ty p = Types.prune(t);
    if (p instanceof Ty.Con c && c.name().equals("List")) {
      return W.LIST;
    }
    if (p instanceof Ty.Con c && (c.name().equals("Int") || c.name().equals("Bool"))) {
      return W.INT;
    }
    // An unresolved type variable — a `number` (defaults to Int) or a polymorphic element — is
    // represented as an i64 in this monomorphic Int/List-Int subset.
    if (p instanceof Ty.Var) {
      return W.INT;
    }
    throw unsupported("type " + Types.show(p) + " (WasmGC backend handles Int/Bool and List Int)");
  }

  static ElmRuntimeError unsupported(String what) {
    return new ElmRuntimeError("WasmGC backend does not support " + what);
  }

  // --- per-function code generation --------------------------------------

  private static final class Gen {
    private final Map<String, int[]> funcs; // name -> {index, arity}
    private final Map<String, W> funcResult; // name -> result type
    private final Map<Expr, Ty> nodeTypes;
    private final Map<String, Integer> locals = new HashMap<>();
    private final Map<String, W> localTypes = new HashMap<>();
    private final List<W> extraLocals = new ArrayList<>(); // beyond params
    private final int numParams;
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    Gen(Map<String, int[]> funcs, Map<String, W> funcResult, Map<Expr, Ty> nodeTypes,
        List<String> params, List<W> paramTypes) {
      this.funcs = funcs;
      this.funcResult = funcResult;
      this.nodeTypes = nodeTypes;
      this.numParams = params.size();
      for (int i = 0; i < params.size(); i++) {
        locals.put(params.get(i), i);
        localTypes.put(params.get(i), paramTypes.get(i));
      }
    }

    byte[] compile(Expr body) {
      gen(body);
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
        case Expr.Negate n -> {
          code.write(0x42);
          sleb(code, 0);
          gen(n.operand());
          code.write(0x7D); // i64.sub
        }
        case Expr.BinOp b -> binOp(b);
        case Expr.If iff -> {
          boolGen(iff.cond());
          code.write(0x04); // if
          writeType(code, wOf(nodeType(iff))); // result type (i64 or list ref)
          gen(iff.thenBranch());
          code.write(0x05);
          gen(iff.elseBranch());
          code.write(0x0B);
        }
        case Expr.Let let -> {
          for (Decl d : let.defs()) {
            if (d instanceof Decl.Value v && v.params().isEmpty()) {
              W w = wOf(nodeType(v.body()));
              int idx = freshLocal(v.name(), w);
              gen(v.body());
              code.write(0x21);
              leb(code, idx);
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
        case Expr.App app -> app(app);
        case Expr.Case c -> listCase(c);
        default -> throw unsupported(e.getClass().getSimpleName());
      }
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
        case "+", "-", "*", "//" -> {
          gen(b.left());
          gen(b.right());
          code.write(switch (b.op()) {
            case "+" -> 0x7C;
            case "-" -> 0x7D;
            case "*" -> 0x7E;
            default -> 0x7F; // i64.div_s
          });
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
            code.write(switch (b.op()) {
              case "<" -> 0x53;
              case ">" -> 0x55;
              case "<=" -> 0x57;
              case ">=" -> 0x59;
              case "==" -> 0x51;
              default -> 0x52;
            });
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

    private void app(Expr.App app) {
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      if (head instanceof Expr.Var v && funcs.containsKey(v.name()) && funcs.get(v.name())[1] == args.size()) {
        for (Expr arg : args) {
          gen(arg);
        }
        code.write(0x10);
        leb(code, funcs.get(v.name())[0]);
        return;
      }
      throw unsupported("application of " + (head instanceof Expr.Var v ? v.name() : "expression"));
    }

    /** Compiles a {@code case} over a list: branches for {@code []} and {@code head :: tail}. */
    private void listCase(Expr.Case c) {
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
      int s = freshLocal("$scrut" + code.size(), W.LIST);
      gen(c.scrutinee());
      code.write(0x21);
      leb(code, s);
      // if ref.is_null then nil else { bind head/tail; cons }
      code.write(0x20);
      leb(code, s);
      code.write(0xD1); // ref.is_null
      code.write(0x04);
      writeType(code, wOf(nodeType(c))); // result type
      gen(nilBody);
      code.write(0x05);
      if (consHead instanceof Pattern.Var hv) {
        int h = freshLocal(hv.name(), W.INT);
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
        int t = freshLocal(tv.name(), W.LIST);
        code.write(0x20);
        leb(code, s);
        code.write(0xFB);
        code.write(0x02);
        leb(code, 0);
        leb(code, 1); // struct.get $cons 1 (tail)
        code.write(0x21);
        leb(code, t);
      }
      gen(consBody);
      code.write(0x0B);
    }

    private Ty nodeType(Expr e) {
      Ty t = nodeTypes.get(e);
      if (t == null) {
        throw unsupported("an expression whose type inference did not record");
      }
      return t;
    }
  }

  private static void writeType(ByteArrayOutputStream out, W w) {
    if (w == W.INT) {
      out.write(I64);
    } else {
      out.write(LISTREF[0]);
      out.write(LISTREF[1]);
    }
  }

  // --- module assembly ----------------------------------------------------

  private static byte[] assemble(List<Func> funcList, Map<Expr, Ty> nodeTypes) {
    Map<String, int[]> table = new HashMap<>();
    Map<String, W> funcResult = new HashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      table.put(funcList.get(i).name(), new int[] {i, funcList.get(i).params().size()});
      funcResult.put(funcList.get(i).name(), funcList.get(i).result());
    }

    // Distinct function signatures -> type indices (after the cons struct, which is type 0).
    List<Func> sigOrder = new ArrayList<>();
    Map<String, Integer> sigIndex = new LinkedHashMap<>();
    for (Func f : funcList) {
      String sig = sigKey(f);
      if (!sigIndex.containsKey(sig)) {
        sigIndex.put(sig, sigIndex.size() + 1); // +1: type 0 is the cons struct
        sigOrder.add(f);
      }
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00});

    // Type section: rec group with the cons struct (type 0), then one functype per signature.
    ByteArrayOutputStream types = new ByteArrayOutputStream();
    leb(types, 1 + sigOrder.size()); // total type definitions (the rec group counts as one)
    types.write(0x4E);
    leb(types, 1); // rec group of 1
    types.write(0x5F); // struct
    leb(types, 2); // 2 fields
    types.write(I64);
    types.write(0x00); // head : i64 (const)
    types.write(LISTREF[0]);
    types.write(LISTREF[1]);
    types.write(0x00); // tail : (ref null $cons) (const)
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
          new Gen(table, funcResult, nodeTypes, f.params(), f.paramTypes()).compile(f.body()));
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

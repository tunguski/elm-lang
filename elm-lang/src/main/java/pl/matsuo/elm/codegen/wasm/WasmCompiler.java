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
 * i64}), matching the interpreter, so a host reads results as {@code BigInt}. Lists, strings,
 * records and effects are out of scope (they need a heap and are left to the JS backend).
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
    return assemble(funcs);
  }

  /**
   * Compiles all numeric top-level functions of a module to a wasm module, exporting each by name
   * (calls and recursion become wasm {@code call}s). This is what lets {@code fib} compile, so the
   * WASM backend can join the recursive benchmark and differential tests.
   */
  public static byte[] moduleFromSource(String source) {
    List<Func> funcs = new ArrayList<>();
    for (Decl d : pl.matsuo.elm.parser.Parser.parseModule(source).decls()) {
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
    return assemble(funcs);
  }

  // --- per-function code generation --------------------------------------

  private static final class FunctionGen {
    private final Map<String, Integer> locals = new HashMap<>(); // name -> local index
    private final Map<String, int[]> funcs; // function name -> {index, arity}
    private int localCount; // all locals are i64; params occupy 0..numParams-1
    private final int numParams;
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    FunctionGen(Map<String, int[]> funcs, List<String> params) {
      this.funcs = funcs;
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
          } else {
            throw unsupported("variable " + v.name());
          }
        }
        case Expr.App app -> intApp(app);
        default -> throw unsupported(e.getClass().getSimpleName());
      }
    }

    private void intBinOp(Expr.BinOp b) {
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
      // A fully-applied call to a known top-level function (incl. recursion).
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      if (head instanceof Expr.Var v && funcs.containsKey(v.name()) && funcs.get(v.name())[1] == args.size()) {
        for (Expr arg : args) {
          intExpr(arg);
        }
        code.write(0x10); // call
        leb(code, funcs.get(v.name())[0]);
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

  private static byte[] assemble(List<Func> funcList) {
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

    // Export section: each function by its name, plus f0..fN by position and "main".
    ByteArrayOutputStream exports = new ByteArrayOutputStream();
    java.util.LinkedHashMap<String, Integer> exportNames = new java.util.LinkedHashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      exportNames.putIfAbsent(funcList.get(i).name(), i);
      exportNames.putIfAbsent("f" + i, i);
    }
    exportNames.putIfAbsent("main", table.containsKey("main") ? table.get("main")[0] : 0);
    leb(exports, exportNames.size());
    exportNames.forEach(
        (name, idx) -> {
          name(exports, name);
          exports.write(0x00); // func export
          leb(exports, idx);
        });
    section(out, 7, exports);

    // Code section.
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    leb(code, funcList.size());
    for (Func f : funcList) {
      code.writeBytes(new FunctionGen(table, f.params()).compile(f.body()));
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

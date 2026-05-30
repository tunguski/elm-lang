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

  /** Compiles one expression into a module exporting {@code main : () -> i64}. */
  public static byte[] module(String expression) {
    return module(List.of(Parser.parseExpression(expression)));
  }

  /** Compiles several expressions into a module exporting {@code f0..fN}, each {@code () -> i64}. */
  public static byte[] module(List<Expr> expressions) {
    List<byte[]> bodies = new ArrayList<>();
    for (Expr e : expressions) {
      bodies.add(new FunctionGen().compile(e));
    }
    return assemble(bodies);
  }

  // --- per-function code generation --------------------------------------

  private static final class FunctionGen {
    private final Map<String, Integer> locals = new HashMap<>(); // name -> local index
    private int localCount = 0; // all locals are i64
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    /** Compiles an Int-typed expression into a complete code entry (locals + body + end). */
    byte[] compile(Expr e) {
      intExpr(e);
      // Wrap: locals declaration (localCount i64) + body + end, then prefix size.
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      if (localCount == 0) {
        body.write(0x00); // no local groups
      } else {
        body.write(0x01); // one group
        leb(body, localCount);
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
          if (idx == null) {
            throw unsupported("variable " + v.name());
          }
          code.write(0x20); // local.get
          leb(code, idx);
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

  private static byte[] assemble(List<byte[]> codeEntries) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00}); // magic + version

    // Type section: one type, () -> i64.
    ByteArrayOutputStream types = new ByteArrayOutputStream();
    leb(types, 1);
    types.write(0x60);
    leb(types, 0); // no params
    leb(types, 1);
    types.write(I64); // one i64 result
    section(out, 1, types);

    // Function section: each function uses type 0.
    ByteArrayOutputStream funcs = new ByteArrayOutputStream();
    leb(funcs, codeEntries.size());
    for (int i = 0; i < codeEntries.size(); i++) {
      leb(funcs, 0);
    }
    section(out, 3, funcs);

    // Export section: export each function as f0..fN (and f0 also as "main").
    ByteArrayOutputStream exports = new ByteArrayOutputStream();
    leb(exports, codeEntries.size() + 1);
    name(exports, "main");
    exports.write(0x00);
    leb(exports, 0);
    for (int i = 0; i < codeEntries.size(); i++) {
      name(exports, "f" + i);
      exports.write(0x00); // func export
      leb(exports, i);
    }
    section(out, 7, exports);

    // Code section.
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    leb(code, codeEntries.size());
    for (byte[] entry : codeEntries) {
      code.writeBytes(entry);
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

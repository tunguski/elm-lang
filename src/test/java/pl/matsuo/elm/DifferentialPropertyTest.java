package pl.matsuo.elm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;
import pl.matsuo.elm.codegen.js.JsCompiler;
import pl.matsuo.elm.codegen.wasm.WasmCompiler;
import pl.matsuo.elm.codegen.wasm.WasmGc;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.parser.Parser;

/**
 * Property-based differential testing: randomly generated (well-typed, total) Elm expressions must
 * evaluate to the same value on all three backends — the Truffle interpreter, the bytecode VM and
 * the JavaScript compiler (run under Node). This is how cross-backend disagreements are found
 * proactively rather than one example at a time.
 */
class DifferentialPropertyTest {

  /** Generates closed, total, Int-typed Elm expressions over a small grammar. */
  private static final class Gen {
    final Random rng;

    Gen(long seed) {
      this.rng = new Random(seed);
    }

    String expr(int depth) {
      if (depth <= 0 || rng.nextInt(100) < 25) {
        return Integer.toString(rng.nextInt(20)); // small non-negative literal
      }
      if (closureSafe) {
        // First-class functions WasmGC now supports (and interp / bytecode / JS): multi-argument
        // lambdas, currying, and higher-order application of a multi-arg function value.
        return switch (rng.nextInt(8)) {
          case 0 -> "(" + expr(depth - 1) + " + " + expr(depth - 1) + ")";
          case 1 -> "(" + expr(depth - 1) + " * " + expr(depth - 1) + ")";
          case 2 -> "(" + expr(depth - 1) + " - " + expr(depth - 1) + ")";
          case 3 ->
              "(if " + expr(depth - 1) + " < " + expr(depth - 1) + " then "
                  + expr(depth - 1) + " else " + expr(depth - 1) + ")";
          case 4 -> // immediately-applied two-argument lambda
              "((\\a b -> a + b - " + expr(depth - 1) + ") " + expr(depth - 1) + " " + expr(depth - 1) + ")";
          case 5 -> // explicitly curried lambda
              "((\\a -> \\b -> a * b) " + expr(depth - 1) + " " + expr(depth - 1) + ")";
          case 6 -> // higher-order: apply a 2-arg function value
              "((\\f -> f " + expr(depth - 1) + " " + expr(depth - 1) + ") (\\a b -> a - b))";
          default -> "(let g = (\\a b -> a + b) in g " + expr(depth - 1) + " " + expr(depth - 1) + ")";
        };
      }
      if (gcSafe) {
        // The fragment all five backends share, including WasmGC: arithmetic, `if`, value `let`, and
        // an immediately-applied capture-free lambda (higher-order: ref.func + call_ref on WasmGC).
        return switch (rng.nextInt(6)) {
          case 0 -> "(" + expr(depth - 1) + " + " + expr(depth - 1) + ")";
          case 1 -> "(" + expr(depth - 1) + " * " + expr(depth - 1) + ")";
          case 2 -> "(" + expr(depth - 1) + " - " + expr(depth - 1) + ")";
          case 3 ->
              "(if " + expr(depth - 1) + " < " + expr(depth - 1) + " then "
                  + expr(depth - 1) + " else " + expr(depth - 1) + ")";
          case 4 -> "((\\n -> (n + " + expr(depth - 1) + ")) " + expr(depth - 1) + ")";
          default -> "(let x = " + expr(depth - 1) + " in (x + x))";
        };
      }
      // `wasmSafe` restricts to the fragment the WASM backend supports (no lists).
      int n = wasmSafe ? 8 : 9;
      return switch (rng.nextInt(n)) {
        case 0 -> "(" + expr(depth - 1) + " + " + expr(depth - 1) + ")";
        case 1 -> "(" + expr(depth - 1) + " * " + expr(depth - 1) + ")";
        case 2 -> "(" + expr(depth - 1) + " - " + expr(depth - 1) + ")";
        case 3 ->
            "(if "
                + expr(depth - 1)
                + " < "
                + expr(depth - 1)
                + " then "
                + expr(depth - 1)
                + " else "
                + expr(depth - 1)
                + ")";
        case 4 -> "(let x = " + expr(depth - 1) + " in (x + x))";
        case 5 -> "((\\n -> n * n - " + expr(depth - 1) + ") " + expr(depth - 1) + ")";
        case 6 -> "(modBy " + (1 + rng.nextInt(7)) + " " + expr(depth - 1) + ")";
        case 7 -> "(abs (" + expr(depth - 1) + " - " + expr(depth - 1) + "))";
        default -> "(List.sum (List.map (\\n -> n + 1) (List.range 0 " + (1 + rng.nextInt(5)) + ")))";
      };
    }

    boolean wasmSafe = false;
    boolean gcSafe = false;
    boolean closureSafe = false;

    /**
     * Generates expressions that evaluate to <em>compound</em> values — tuples, lists, records,
     * {@code Maybe}/{@code Result} constructors and strings (nested arbitrarily) — for the backends
     * that render a value back (interpreter / bytecode / JS). The constructs need not be well-typed:
     * all three evaluate dynamically, so the only contract under test is that they agree.
     */
    String compound(int depth) {
      if (depth <= 0 || rng.nextInt(100) < 30) {
        return leaf();
      }
      return switch (rng.nextInt(8)) {
        case 0 -> "( " + compound(depth - 1) + ", " + compound(depth - 1) + " )";
        case 1 ->
            "[ " + compound(depth - 1) + ", " + compound(depth - 1) + ", "
                + compound(depth - 1) + " ]";
        case 2 -> "(Just " + compound(depth - 1) + ")";
        case 3 -> "Nothing";
        case 4 -> "(Ok " + compound(depth - 1) + ")";
        case 5 -> "(Err " + str() + ")";
        case 6 -> "{ x = " + compound(depth - 1) + ", y = " + compound(depth - 1) + " }";
        default -> "(" + str() + " ++ " + str() + ")";
      };
    }

    private String leaf() {
      return switch (rng.nextInt(3)) {
        case 0 -> Integer.toString(rng.nextInt(20));
        case 1 -> str();
        default -> rng.nextBoolean() ? "True" : "False";
      };
    }

    private String str() {
      return "\"" + (char) ('a' + rng.nextInt(5)) + "\""; // single ASCII letter, no escaping needed
    }

    /**
     * Generates Int-valued expressions that <em>build and take apart</em> compound values — records
     * (literal, field access, update) and tuples (literal, destructured in a single-branch case) —
     * reducing back to an Int. Every construct here is supported by WasmGC (which returns a GC
     * reference for a compound value that JS can't render, so the observable must be a number), as
     * well as the interpreter, the bytecode VM and the JS backend.
     */
    String compoundInt(int depth) {
      if (depth <= 0 || rng.nextInt(100) < 30) {
        return Integer.toString(rng.nextInt(20));
      }
      return switch (rng.nextInt(7)) {
        case 0 -> "(" + compoundInt(depth - 1) + " + " + compoundInt(depth - 1) + ")";
        case 1 -> "(" + compoundInt(depth - 1) + " - " + compoundInt(depth - 1) + ")";
        case 2 -> // record literal + field access
            "(let cr = { a = " + compoundInt(depth - 1) + ", b = " + compoundInt(depth - 1)
                + " } in cr.a + cr.b)";
        case 3 -> // record update
            "(let cr = { a = " + compoundInt(depth - 1) + ", b = " + compoundInt(depth - 1)
                + " } in (let cs = { cr | a = " + compoundInt(depth - 1) + " } in cs.a - cs.b))";
        case 4 -> // tuple literal + single-branch destructure
            "(case ( " + compoundInt(depth - 1) + ", " + compoundInt(depth - 1)
                + " ) of ( cx, cy ) -> cx + cy)";
        case 5 -> // nested tuple destructure
            "(case ( ( " + compoundInt(depth - 1) + ", " + compoundInt(depth - 1) + " ), "
                + compoundInt(depth - 1) + " ) of ( ( cx, cy ), cz ) -> cx + cy - cz)";
        default ->
            "(if " + compoundInt(depth - 1) + " < " + compoundInt(depth - 1) + " then "
                + compoundInt(depth - 1) + " else " + compoundInt(depth - 1) + ")";
      };
    }
  }

  @Test
  void compoundValueConstructionAgreesIncludingWasmGc() throws Exception {
    // Records (literal/access/update) and tuples (literal/destructure, incl. nested) built and reduced
    // to an Int, compared across all five backends: the interpreter, bytecode VM, JS, linear-memory
    // WASM and WasmGC. (Linear WASM gained tuple-`case` destructuring, so it now joins this net.)
    Gen gen = new Gen(20260602L);
    List<String> exprs = new ArrayList<>();
    StringBuilder module = new StringBuilder();
    for (int i = 0; i < 80; i++) {
      String e = gen.compoundInt(4);
      exprs.add(e);
      module.append("f").append(i).append(" = ").append(e).append("\n");
    }

    List<String> interp = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      assertEquals(interp.get(interp.size() - 1), Show.plain(BytecodeInterpreter.eval(e)), "bytecode: " + e);
    }

    String js = runNode(JsCompiler.expressionsProgram(exprs));
    if (js != null) {
      String[] r = js.split("\n", -1);
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), r[i], "JS: " + exprs.get(i));
      }
    }
    // Linear-memory WASM via the whole-module path (type-directed records need inference).
    List<String> linear = runWasm(WasmCompiler.moduleFromSource(module.toString()), exprs.size());
    if (linear != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), linear.get(i), "linear WASM: " + exprs.get(i));
      }
    }
    List<String> gc = runWasm(WasmGc.module(module.toString()), exprs.size());
    if (gc != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), gc.get(i), "WasmGC: " + exprs.get(i));
      }
    }
  }

  @Test
  void backendsAgreeOnCompoundValues() throws Exception {
    // Tuples / lists / records / Maybe / Result / strings, nested — compared on the backends that
    // return a renderable value (interpreter, bytecode VM, and the JS compiler under Node). This
    // extends the differential net past Int arithmetic to the structured value model.
    Gen gen = new Gen(20260531L);
    List<String> exprs = new ArrayList<>();
    for (int i = 0; i < 150; i++) {
      exprs.add(gen.compound(4));
    }

    List<String> interp = new ArrayList<>();
    List<String> bytecode = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      bytecode.add(Show.plain(BytecodeInterpreter.eval(e)));
    }
    for (int i = 0; i < exprs.size(); i++) {
      assertEquals(interp.get(i), bytecode.get(i), "interp vs bytecode: " + exprs.get(i));
    }

    String node = runNode(JsCompiler.expressionsProgram(exprs));
    if (node != null) {
      String[] js = node.split("\n", -1);
      assertEquals(exprs.size(), js.length, "JS produced a result per expression");
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), js[i], "interp vs JS: " + exprs.get(i));
      }
    }
  }

  @Test
  void backendsAgreeOnRandomExpressions() throws Exception {
    Gen gen = new Gen(20240530L); // fixed seed -> reproducible
    List<String> exprs = new ArrayList<>();
    for (int i = 0; i < 120; i++) {
      exprs.add(gen.expr(4));
    }

    // Interpreter and bytecode VM are evaluated in-process.
    List<String> interp = new ArrayList<>();
    List<String> bytecode = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      bytecode.add(Show.plain(BytecodeInterpreter.eval(e)));
    }
    for (int i = 0; i < exprs.size(); i++) {
      assertEquals(interp.get(i), bytecode.get(i), "interp vs bytecode: " + exprs.get(i));
    }

    // The JS backend is compared too, in a single Node run (skipped if Node is unavailable).
    String node = runNode(JsCompiler.expressionsProgram(exprs));
    if (node != null) {
      String[] js = node.split("\n", -1);
      assertEquals(exprs.size(), js.length, "JS produced a result per expression");
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), js[i], "interp vs JS: " + exprs.get(i));
      }
    }
  }

  @Test
  void allFourBackendsAgreeOnNumericSubset() throws Exception {
    Gen gen = new Gen(424242L);
    gen.wasmSafe = true; // the fragment the WASM backend supports (no lists)
    List<String> exprs = new ArrayList<>();
    List<Expr> parsed = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      String e = gen.expr(4);
      exprs.add(e);
      parsed.add(Parser.parseExpression(e));
    }

    List<String> interp = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      assertEquals(
          interp.get(interp.size() - 1), Show.plain(BytecodeInterpreter.eval(e)), "bytecode: " + e);
    }

    // JS (one Node run) and WASM (one Node run) compared against the interpreter.
    String js = runNode(JsCompiler.expressionsProgram(exprs));
    if (js != null) {
      String[] r = js.split("\n", -1);
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), r[i], "JS: " + exprs.get(i));
      }
    }
    List<String> wasm = runWasm(WasmCompiler.module(parsed), exprs.size());
    if (wasm != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), wasm.get(i), "WASM: " + exprs.get(i));
      }
    }
  }

  @Test
  void allFiveBackendsAgreeIncludingWasmGc() throws Exception {
    // The fragment WasmGC also supports (arithmetic / if / value-let). Compiled as one synthetic
    // module exporting f0..fN, run under Node's WasmGC, and compared to the interpreter — alongside
    // the bytecode VM, JS and the linear-memory WASM backend.
    Gen gen = new Gen(777L);
    gen.gcSafe = true;
    List<String> exprs = new ArrayList<>();
    List<Expr> parsed = new ArrayList<>();
    StringBuilder module = new StringBuilder();
    for (int i = 0; i < 80; i++) {
      String e = gen.expr(4);
      exprs.add(e);
      parsed.add(Parser.parseExpression(e));
      module.append("f").append(i).append(" = ").append(e).append("\n");
    }

    List<String> interp = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      assertEquals(interp.get(interp.size() - 1), Show.plain(BytecodeInterpreter.eval(e)), "bytecode: " + e);
    }

    String js = runNode(JsCompiler.expressionsProgram(exprs));
    if (js != null) {
      String[] r = js.split("\n", -1);
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), r[i], "JS: " + exprs.get(i));
      }
    }
    List<String> wasm = runWasm(WasmCompiler.module(parsed), exprs.size());
    if (wasm != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), wasm.get(i), "WASM: " + exprs.get(i));
      }
    }
    List<String> gc = runWasm(WasmGc.module(module.toString()), exprs.size());
    if (gc != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), gc.get(i), "WasmGC: " + exprs.get(i));
      }
    }
  }

  @Test
  void closuresAgreeAcrossAllFiveBackends() throws Exception {
    // Fuzzes first-class functions (multi-arg lambdas, currying, higher-order application) across all
    // five backends — interpreter, bytecode VM, JS, linear-memory WASM and WasmGC — which all support
    // closures. Guards this session's WasmGC closure/currying work and the linear-memory closures.
    Gen gen = new Gen(20260601L);
    gen.closureSafe = true;
    List<String> exprs = new ArrayList<>();
    List<Expr> parsed = new ArrayList<>();
    StringBuilder module = new StringBuilder();
    for (int i = 0; i < 80; i++) {
      String e = gen.expr(4);
      exprs.add(e);
      parsed.add(Parser.parseExpression(e));
      module.append("f").append(i).append(" = ").append(e).append("\n");
    }

    List<String> interp = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      assertEquals(interp.get(interp.size() - 1), Show.plain(BytecodeInterpreter.eval(e)), "bytecode: " + e);
    }

    String js = runNode(JsCompiler.expressionsProgram(exprs));
    if (js != null) {
      String[] r = js.split("\n", -1);
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), r[i], "JS: " + exprs.get(i));
      }
    }
    // Linear-memory WASM via the whole-module path (which lifts lambdas, giving closures/currying).
    List<String> wasm = runWasm(WasmCompiler.moduleFromSource(module.toString()), exprs.size());
    if (wasm != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), wasm.get(i), "linear WASM: " + exprs.get(i));
      }
    }
    List<String> gc = runWasm(WasmGc.module(module.toString()), exprs.size());
    if (gc != null) {
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), gc.get(i), "WasmGC: " + exprs.get(i));
      }
    }
  }

  @Test
  void newStdlibFunctionsAgreeAcrossValueBackends() throws Exception {
    // Newly-added stdlib functions (List.map3, String.foldl/foldr/any/all) must evaluate identically
    // on the three value-rendering backends: the interpreter, the bytecode VM and the JS compiler.
    List<String> exprs =
        List.of(
            "List.map3 (\\a b c -> a + b + c) [ 1, 2, 3 ] [ 10, 20, 30 ] [ 100, 200, 300 ]",
            "List.map3 (\\a b c -> ( a, b, c )) [ 1, 2 ] [ 3, 4, 5 ] [ 6, 7 ]", // ragged -> shortest
            "String.foldl (\\_ n -> n + 1) 0 \"hello\"",
            "String.foldl (\\c acc -> String.cons c acc) \"\" \"abc\"", // reverse
            "String.foldr (\\c acc -> String.cons c acc) \"\" \"abc\"", // identity
            "String.any (\\c -> c == 'l') \"hello\"",
            "String.any (\\c -> c == 'z') \"hello\"",
            "String.all (\\c -> c /= 'z') \"hello\"",
            "String.all (\\c -> c == 'l') \"hello\"",
            // Dict.partition / Set.partition (rendered as a pair of collections) and Dict.merge.
            "Dict.partition (\\k _ -> k > 1) (Dict.fromList [ ( 1, \"a\" ), ( 2, \"b\" ), ( 3, \"c\" ) ])",
            "Set.partition (\\x -> modBy 2 x == 0) (Set.fromList [ 1, 2, 3, 4, 5 ])",
            "Dict.merge (\\k v acc -> acc ++ [ ( k, v ) ]) (\\k a b acc -> acc ++ [ ( k, a + b ) ]) "
                + "(\\k v acc -> acc ++ [ ( k, v ) ]) (Dict.fromList [ ( 1, 10 ), ( 2, 20 ) ]) "
                + "(Dict.fromList [ ( 2, 200 ), ( 3, 30 ) ]) []");

    List<String> interp = new ArrayList<>();
    for (String e : exprs) {
      interp.add(Show.plain(Interpreter.eval(e)));
      assertEquals(
          interp.get(interp.size() - 1), Show.plain(BytecodeInterpreter.eval(e)), "bytecode: " + e);
    }
    String node = runNode(JsCompiler.expressionsProgram(exprs));
    if (node != null) {
      String[] js = node.split("\n", -1);
      for (int i = 0; i < exprs.size(); i++) {
        assertEquals(interp.get(i), js[i], "JS: " + exprs.get(i));
      }
    }
  }

  @Test
  void recursionAgreesAcrossAllFiveBackends() throws Exception {
    // Top-level recursive functions (tail-recursive accumulators, branching tree recursion, and
    // recursion-as-multiplication) exercised across all five backends. This guards the JS backend's
    // self-tail-call optimisation as well as recursion through the bytecode VM and both WASM
    // backends — none of which is reached by the inline-expression grammar above.
    String helpers =
        """
        module M exposing (..)
        sumTo : Int -> Int -> Int
        sumTo n acc = if n <= 0 then acc else sumTo (n - 1) (acc + n)
        fib : Int -> Int
        fib n = if n < 2 then n else fib (n - 1) + fib (n - 2)
        pow2 : Int -> Int -> Int
        pow2 n acc = if n <= 0 then acc else pow2 (n - 1) (acc * 2)
        mulRec : Int -> Int -> Int -> Int
        mulRec a b acc = if a <= 0 then acc else mulRec (a - 1) b (acc + b)
        """;
    Random rng = new Random(20260601L);
    List<String> calls = new ArrayList<>();
    StringBuilder module = new StringBuilder(helpers);
    for (int i = 0; i < 80; i++) {
      String call =
          switch (rng.nextInt(5)) {
            case 0 -> "sumTo " + rng.nextInt(40) + " 0";
            case 1 -> "fib " + rng.nextInt(16);
            case 2 -> "pow2 " + rng.nextInt(20) + " 1";
            case 3 -> "mulRec " + rng.nextInt(30) + " " + rng.nextInt(30) + " 0";
            // Nested: a recursive result feeds another recursive call.
            default -> "sumTo (fib " + rng.nextInt(10) + ") 0";
          };
      calls.add(call);
      // Entry points are named r0..rN (not f0..fN): the linear-WASM backend also exports a positional
      // "f<index>" alias for every function, which would collide with `f`-prefixed entry names.
      module.append("r").append(i).append(" = ").append(call).append("\n");
    }
    String moduleSrc = module.toString();

    // Interpreter and bytecode VM evaluate each fN within the loaded module (so the recursive
    // helpers are in scope).
    Interpreter interpMod = Interpreter.load(moduleSrc);
    BytecodeInterpreter byteMod = BytecodeInterpreter.load(moduleSrc);
    List<String> interp = new ArrayList<>();
    for (int i = 0; i < calls.size(); i++) {
      String expected = Show.plain(interpMod.value("r" + i));
      interp.add(expected);
      assertEquals(expected, Show.plain(byteMod.value("r" + i)), "bytecode: " + calls.get(i));
    }

    String js = runNodeModule(moduleSrc, calls.size());
    if (js != null) {
      String[] r = js.split("\n", -1);
      for (int i = 0; i < calls.size(); i++) {
        assertEquals(interp.get(i), r[i], "JS: " + calls.get(i));
      }
    }
    List<String> wasm = runWasm(WasmCompiler.moduleFromSource(moduleSrc), calls.size(), "r");
    if (wasm != null) {
      for (int i = 0; i < calls.size(); i++) {
        assertEquals(interp.get(i), wasm.get(i), "linear WASM: " + calls.get(i));
      }
    }
    List<String> gc = runWasm(WasmGc.module(moduleSrc), calls.size(), "r");
    if (gc != null) {
      for (int i = 0; i < calls.size(); i++) {
        assertEquals(interp.get(i), gc.get(i), "WasmGC: " + calls.get(i));
      }
    }
  }

  /** Compiles a whole module's declarations and prints {@code $show(r0..rN-1)} (one per line). */
  private static String runNodeModule(String moduleSrc, int count) {
    StringBuilder p = new StringBuilder(JsCompiler.declarationsScript(moduleSrc));
    p.append("\nconst $out=[];");
    for (int i = 0; i < count; i++) {
      p.append("$out.push($show(_$r").append(i).append("));");
    }
    p.append("process.stdout.write($out.join('\\n'));");
    return runNode(p.toString());
  }

  /** Instantiates a wasm module under Node, runs f0..fN-1, returns results (or null without Node). */
  private static List<String> runWasm(byte[] module, int count) {
    return runWasm(module, count, "f");
  }

  /** As {@link #runWasm(byte[], int)} but invoking exports {@code <prefix>0..<prefix>N-1} (a module
   * may also export positional {@code f<index>} aliases, so a distinct prefix avoids collisions). */
  private static List<String> runWasm(byte[] module, int count, String prefix) {
    try {
      Path wasm = Files.createTempFile("elm-diff-", ".wasm");
      Files.write(wasm, module);
      Path js = Files.createTempFile("elm-diff-", ".js");
      Files.writeString(
          js,
          "const fs=require('fs');"
              + "WebAssembly.instantiate(fs.readFileSync(process.argv[2])).then(r=>{"
              + "const ex=r.instance.exports,out=[];for(let i=0;i<"
              + count
              + ";i++)out.push(ex['" + prefix + "'+i]().toString());"
              + "process.stdout.write(out.join('\\n'));}).catch(e=>{console.error(e);process.exit(1);});",
          StandardCharsets.UTF_8);
      Process p =
          new ProcessBuilder("node", js.toString(), wasm.toString())
              .redirectErrorStream(false)
              .start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      p.waitFor(60, TimeUnit.SECONDS);
      Files.deleteIfExists(wasm);
      Files.deleteIfExists(js);
      assertTrue(p.exitValue() == 0, "node/wasm failed: " + err);
      return List.of(out.split("\n", -1));
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /** Runs a JS program under Node, returning stdout, or null if Node is not installed. */
  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-diff-", ".js");
      Files.writeString(file, program, StandardCharsets.UTF_8);
      Process p = new ProcessBuilder("node", file.toString()).redirectErrorStream(false).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("node timed out");
      }
      Files.deleteIfExists(file);
      assertTrue(p.exitValue() == 0, "node failed: " + err);
      return out;
    } catch (IOException e) {
      return null; // Node not installed — interpreter/bytecode comparison still ran.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }
}

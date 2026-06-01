package pl.matsuo.elm.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>First-class functions, closures and currying</b> work via a uniform closure value — a heap
 * block {@code {funcIdx, arity, count, slot…}} — and a generic {@code $apply} runtime. Every
 * function lives in a funcref table; a function used as a value (or a partial application) becomes an
 * under-applied closure, and applying one accumulates arguments until {@code count == arity}, when
 * {@code $apply} invokes it via {@code call_indirect} (dispatched on the arity). Lambdas are
 * lambda-lifted: each becomes a top-level function whose leading parameters are its captured locals,
 * and the lambda expression a closure capturing them. So higher-order code, partial application
 * ({@code inc = add 1}) and closures ({@code adder x = \\y -> x + y}) all run in wasm.
 *
 * <p><b>Strings and records</b> are <b>type-directed</b>: the backend runs Hindley–Milner inference
 * over the module and consults each expression's type (see {@code Infer.nodeTypes}). A string is a
 * heap object {@code {byteLength : i64, bytes…}} — literals allocate, {@code String.length} loads
 * the length word, and {@code ++}/{@code ==} call two hand-assembled runtime functions
 * ({@code $strConcat}, {@code $strEq}). Because string dispatch is static, {@code ++}/{@code ==}
 * need operands typed concretely as {@code String} at the use site (a polymorphic {@code
 * comparable}/{@code appendable} function does not carry that).
 *
 * <p><b>Records are self-describing</b> and fully <b>row-polymorphic</b>: a record is a heap block
 * {@code {count, fieldId…, value…}} that stores its field-name ids, so the {@code $recordGet} /
 * {@code $recordSet} runtime looks fields up by name — a function that knows only some of a record's
 * fields ({@code getX r = r.x}) works on any shape, with no need for a closed type.
 *
 * <p><b>Floats</b> are type-directed too: an {@code f64} is stored as its i64 bit pattern, so values
 * stay uniformly i64 on the stack and heap; literals and the arithmetic ({@code + - * /}),
 * comparisons, {@code negate} and the {@code toFloat}/{@code round}/{@code floor}/{@code
 * ceiling}/{@code truncate} conversions reinterpret to {@code f64} as needed.
 *
 * <p>A small <b>standard library</b> ({@link #WASM_PRELUDE}) — {@code List.map}/{@code foldl}/{@code
 * foldr}/{@code filter}/{@code length}/{@code sum}/{@code range}/{@code reverse}, {@code
 * Maybe}/{@code Result} helpers — is written in this same subset and prepended when a module uses it
 * ({@code Maybe}/{@code Result} constructors are built in). Booleans are first-class {@code i64}
 * values (0/1), so they can be stored in lists, returned and compared like any other value.
 *
 * <p>Memory is a growable bump allocator with a sound <b>arena reclamation</b> (see {@code
 * emitApp}): a call whose result is scalar and which consumes a heap argument has the heap pointer
 * reset afterwards, freeing everything it allocated — correct because the language is pure and a
 * scalar result holds no heap pointer. This keeps "reduce a structure, in a loop" programs bounded.
 * A general moving/mark-sweep collector would need object headers and root scanning (or WasmGC) and
 * remains future work; the other gap is the rest of the larger standard library (most {@code
 * String}/{@code Dict}/{@code Array} operations).
 */
public final class WasmCompiler {

  // Value types and opcodes (WebAssembly binary format).
  private static final int I32 = 0x7F, I64 = 0x7E;

  private WasmCompiler() {}

  /** A compiled function: its name, parameter names (all i64) and body. */
  private record Func(String name, List<String> params, Expr body) {}

  /**
   * A small standard library, written in the WASM-supported subset and prepended (when used) so that
   * {@code List}/{@code Maybe}/{@code Result} functions compile like any other. Each maps from its
   * qualified Elm name via {@link #PRELUDE_NAMES}.
   */
  static final String WASM_PRELUDE =
      """
      listMap f xs = case xs of
          [] -> []
          h :: t -> f h :: listMap f t
      listFoldl f acc xs = case xs of
          [] -> acc
          h :: t -> listFoldl f (f h acc) t
      listFoldr f acc xs = case xs of
          [] -> acc
          h :: t -> f h (listFoldr f acc t)
      listFilter pred xs = case xs of
          [] -> []
          h :: t -> if pred h then h :: listFilter pred t else listFilter pred t
      listLength xs = case xs of
          [] -> 0
          h :: t -> 1 + listLength t
      listSum xs = case xs of
          [] -> 0
          h :: t -> h + listSum t
      listRange lo hi = if lo > hi then [] else lo :: listRange (lo + 1) hi
      listAppend a b = case a of
          [] -> b
          h :: t -> h :: listAppend t b
      listReverse xs = listFoldl (\\h acc -> h :: acc) [] xs
      listConcat xss = listFoldr listAppend [] xss
      listConcatMap f xs = listFoldr (\\x acc -> listAppend (f x) acc) [] xs
      listIsEmpty xs = case xs of
          [] -> True
          h :: t -> False
      listTake n xs = if n <= 0 then [] else case xs of
          [] -> []
          h :: t -> h :: listTake (n - 1) t
      listDrop n xs = if n <= 0 then xs else case xs of
          [] -> []
          h :: t -> listDrop (n - 1) t
      listRepeat n x = if n <= 0 then [] else x :: listRepeat (n - 1) x
      listProduct xs = listFoldl (\\x acc -> x * acc) 1 xs
      listAll pred xs = listFoldl (\\x acc -> acc && pred x) True xs
      listAny pred xs = listFoldl (\\x acc -> acc || pred x) False xs
      listMap2 f xs ys = case xs of
          [] -> []
          hx :: tx -> case ys of
              [] -> []
              hy :: ty -> f hx hy :: listMap2 f tx ty
      maxOf a b = if a > b then a else b
      minOf a b = if a < b then a else b
      listMaximum xs = case xs of
          [] -> Nothing
          h :: t -> Just (listFoldl maxOf h t)
      listMinimum xs = case xs of
          [] -> Nothing
          h :: t -> Just (listFoldl minOf h t)
      listMember x xs = listFoldl (\\y acc -> acc || x == y) False xs
      listInsert x xs = case xs of
          [] -> x :: []
          h :: t -> if x <= h then x :: h :: t else h :: listInsert x t
      listSort xs = listFoldr listInsert [] xs
      listSortInsert key x xs = case xs of
          [] -> x :: []
          h :: t -> if key x <= key h then x :: h :: t else h :: listSortInsert key x t
      listSortBy key xs = listFoldr (listSortInsert key) [] xs
      listIndexedHelp f i xs = case xs of
          [] -> []
          h :: t -> f i h :: listIndexedHelp f (i + 1) t
      listIndexedMap f xs = listIndexedHelp f 0 xs
      listHead xs = case xs of
          [] -> Nothing
          h :: t -> Just h
      listTail xs = case xs of
          [] -> Nothing
          h :: t -> Just t
      listFilterMap f xs = listFoldr (\\x acc -> case f x of
          Just y -> y :: acc
          Nothing -> acc) [] xs
      listMap3 f xs ys zs = case xs of
          [] -> []
          hx :: tx -> case ys of
              [] -> []
              hy :: ty -> case zs of
                  [] -> []
                  hz :: tz -> f hx hy hz :: listMap3 f tx ty tz
      stringIsEmpty s = String.length s == 0
      maybeWithDefault d m = case m of
          Just x -> x
          Nothing -> d
      maybeMap f m = case m of
          Just x -> Just (f x)
          Nothing -> Nothing
      maybeAndThen f m = case m of
          Just x -> f x
          Nothing -> Nothing
      resultWithDefault d r = case r of
          Ok x -> x
          Err e -> d
      resultMap f r = case r of
          Ok x -> Ok (f x)
          Err e -> Err e
      identity x = x
      always a b = a
      """;

  /** Maps qualified standard-library names to the prelude function that implements them. */
  static final Map<String, String> PRELUDE_NAMES =
      Map.ofEntries(
          Map.entry("List.map", "listMap"),
          Map.entry("List.foldl", "listFoldl"),
          Map.entry("List.foldr", "listFoldr"),
          Map.entry("List.filter", "listFilter"),
          Map.entry("List.length", "listLength"),
          Map.entry("List.sum", "listSum"),
          Map.entry("List.range", "listRange"),
          Map.entry("List.append", "listAppend"),
          Map.entry("List.reverse", "listReverse"),
          Map.entry("List.concat", "listConcat"),
          Map.entry("List.concatMap", "listConcatMap"),
          Map.entry("List.isEmpty", "listIsEmpty"),
          Map.entry("List.take", "listTake"),
          Map.entry("List.drop", "listDrop"),
          Map.entry("List.repeat", "listRepeat"),
          Map.entry("List.product", "listProduct"),
          Map.entry("List.all", "listAll"),
          Map.entry("List.any", "listAny"),
          Map.entry("List.map2", "listMap2"),
          Map.entry("List.maximum", "listMaximum"),
          Map.entry("List.minimum", "listMinimum"),
          Map.entry("List.member", "listMember"),
          Map.entry("List.sort", "listSort"),
          Map.entry("List.sortBy", "listSortBy"),
          Map.entry("List.indexedMap", "listIndexedMap"),
          Map.entry("List.head", "listHead"),
          Map.entry("List.tail", "listTail"),
          Map.entry("List.filterMap", "listFilterMap"),
          Map.entry("List.map3", "listMap3"),
          Map.entry("String.isEmpty", "stringIsEmpty"),
          Map.entry("Maybe.withDefault", "maybeWithDefault"),
          Map.entry("Maybe.map", "maybeMap"),
          Map.entry("Maybe.andThen", "maybeAndThen"),
          Map.entry("Result.withDefault", "resultWithDefault"),
          Map.entry("Result.map", "resultMap"),
          Map.entry("Basics.identity", "identity"),
          Map.entry("Basics.always", "always"));

  /** Operators whose result is a {@code Bool}; in value position they widen the i32 0/1 to i64. */
  private static final Set<String> BOOL_OPS =
      Set.of("==", "/=", "<", ">", "<=", ">=", "&&", "||");

  /** Builtin constructor tags so {@code Maybe}/{@code Result} values work without a user
   *  declaration: each union's variants are tagged by position. */
  private static void registerBuiltinCtors(Map<String, Integer> ctorTag, Map<String, Integer> ctorArity) {
    ctorTag.put("Just", 0);
    ctorArity.put("Just", 1);
    ctorTag.put("Nothing", 1);
    ctorArity.put("Nothing", 0);
    ctorTag.put("Ok", 0);
    ctorArity.put("Ok", 1);
    ctorTag.put("Err", 1);
    ctorArity.put("Err", 1);
  }

  /** Compiles one expression into a module exporting {@code main : () -> i64}. */
  public static byte[] module(String expression) {
    return module(List.of(Parser.parseExpression(expression)));
  }

  /** Compiles several expressions into a module exporting {@code f0..fN}, each {@code () -> i64}. */
  public static byte[] module(List<Expr> expressions) {
    List<Func> funcs = new ArrayList<>();
    for (int i = 0; i < expressions.size(); i++) {
      funcs.add(new Func("f" + i, List.of(), pl.matsuo.elm.opt.ConstantFold.fold(expressions.get(i))));
    }
    return assemble(funcs, Map.of(), Map.of(), Map.of(), Map.of());
  }

  /**
   * Compiles all numeric top-level functions of a module to a wasm module, exporting each by name
   * (calls and recursion become wasm {@code call}s). This is what lets {@code fib} compile, so the
   * WASM backend can join the recursive benchmark and differential tests.
   */
  public static byte[] moduleFromSource(String source) {
    pl.matsuo.elm.ast.Module parsed = pl.matsuo.elm.parser.Parser.parseModule(source);
    pl.matsuo.elm.ast.Module folded =
        new pl.matsuo.elm.ast.Module(
            parsed.name(), parsed.exposing(), parsed.imports(),
            pl.matsuo.elm.opt.ConstantFold.foldDecls(parsed.decls()), parsed.pos());
    return compileModules(List.of(folded), wantsPrelude(source));
  }

  /**
   * Compiles a whole project — the entry module plus its (local or installed-package) dependency
   * modules — to one wasm binary. The modules' top-level functions are merged into a single
   * compilation unit (by simple name, the entry's first), so a cross-module call like {@code
   * Util.square} resolves to the compiled {@code square} (qualifiers are dropped at codegen). This is
   * what lets the WASM backend run code that imports other modules / installed packages.
   */
  public static byte[] moduleFromSources(List<String> sources) {
    List<pl.matsuo.elm.ast.Module> modules = new ArrayList<>();
    boolean prelude = false;
    for (String s : sources) {
      modules.add(pl.matsuo.elm.parser.Parser.parseModule(s));
      prelude |= wantsPrelude(s);
    }
    return compileModules(modules, prelude);
  }

  /** Whether a source refers to a prelude (List/Maybe/Result/Basics/String) qualified name. */
  private static boolean wantsPrelude(String source) {
    return source.contains("List.") || source.contains("Maybe.") || source.contains("Result.")
        || source.contains("Basics.") || source.contains("String.");
  }

  private static byte[] compileModules(List<pl.matsuo.elm.ast.Module> modules, boolean wantPrelude) {
    List<Func> funcs = new ArrayList<>();
    // Custom-type constructors: each variant gets a tag (its index in the union) and an arity, so a
    // value `Ctor a b` is a heap cell {tag, a, b} and a `case` dispatches on the loaded tag word.
    Map<String, Integer> ctorTag = new HashMap<>();
    Map<String, Integer> ctorArity = new HashMap<>();
    registerBuiltinCtors(ctorTag, ctorArity); // Maybe/Result work without a user declaration
    // Merge every module's declarations into one unit (the entry module is first; later duplicates of
    // a name are dropped, so the entry's definitions win).
    List<Decl> decls = new ArrayList<>();
    List<pl.matsuo.elm.ast.Module.Import> imports = new ArrayList<>();
    for (pl.matsuo.elm.ast.Module m : modules) {
      decls.addAll(m.decls());
      imports.addAll(m.imports());
    }
    for (Decl d : decls) {
      if (d instanceof Decl.Union u) {
        for (int i = 0; i < u.variants().size(); i++) {
          Decl.Union.Variant variant = u.variants().get(i);
          ctorTag.putIfAbsent(variant.name(), i);
          ctorArity.putIfAbsent(variant.name(), variant.args().size());
        }
      }
    }
    Set<String> defined = new HashSet<>();
    for (Decl d : decls) {
      if (d instanceof Decl.Value v && defined.add(v.name())) {
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
    // One synthetic module holding the merged declarations, for cross-module type inference.
    pl.matsuo.elm.ast.Module module =
        new pl.matsuo.elm.ast.Module(
            "Main", pl.matsuo.elm.ast.Module.Exposing.ALL, imports, decls,
            new pl.matsuo.elm.error.Position(1, 1, 0));
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
    // Prepend the standard-library prelude (List/Maybe/Result helpers) when a module refers to it,
    // so those qualified names resolve to compiled functions. Gated to keep unrelated modules lean.
    if (wantPrelude) {
      for (Decl d : pl.matsuo.elm.parser.Parser.parseModule(WASM_PRELUDE).decls()) {
        if (d instanceof Decl.Value v && !names(funcs).contains(v.name())) {
          List<String> params = new ArrayList<>();
          boolean ok = true;
          for (Pattern p : v.params()) {
            if (p instanceof Pattern.Var pv) {
              params.add(pv.name());
            } else {
              ok = false;
            }
          }
          if (ok) {
            funcs.add(new Func(v.name(), params, v.body()));
          }
        }
      }
    }

    // Lambda-lift: every lambda becomes a top-level function (its captured locals lead its
    // parameters), and the lambda expression a closure that captures those locals. This, together
    // with the closure/$apply runtime, is what gives WASM closures and currying.
    Set<String> globalNames = new HashSet<>(ctorArity.keySet());
    for (Func f : funcs) {
      globalNames.add(f.name());
    }
    Map<Expr.Lambda, Lifted> lifted = new java.util.IdentityHashMap<>();
    funcs = liftLambdas(funcs, globalNames, lifted);
    return assemble(funcs, ctorTag, ctorArity, nodeTypes, lifted);
  }

  private static Set<String> names(List<Func> funcs) {
    Set<String> out = new HashSet<>();
    for (Func f : funcs) {
      out.add(f.name());
    }
    return out;
  }

  /** A lambda lifted to a top-level function: the function's name, its total arity (captures + own
   *  params) and the captured local names, in the order they lead the lifted parameter list. */
  record Lifted(String name, int arity, List<String> captures) {}

  /**
   * Replaces lambdas with lifted top-level functions. Each lambda gets a fresh {@code $lamN}
   * function whose parameters are its captured free variables followed by its own parameters; the
   * lambda site records the mapping so the compiler can emit a closure capturing those locals.
   * Nested lambdas are handled by processing the lifted functions' bodies in turn.
   */
  private static List<Func> liftLambdas(
      List<Func> userFuncs, Set<String> globalNames, Map<Expr.Lambda, Lifted> out) {
    List<Func> all = new ArrayList<>(userFuncs);
    java.util.Deque<Func> work = new java.util.ArrayDeque<>(userFuncs);
    int counter = 0;
    while (!work.isEmpty()) {
      Func f = work.poll();
      List<Expr.Lambda> lambdas = new ArrayList<>();
      collectTopLambdas(f.body(), lambdas);
      for (Expr.Lambda lam : lambdas) {
        List<String> params = new ArrayList<>();
        for (Pattern p : lam.params()) {
          if (p instanceof Pattern.Var pv) {
            params.add(pv.name());
          } else {
            throw unsupported("non-variable lambda parameter");
          }
        }
        Set<String> free = new java.util.LinkedHashSet<>();
        addFree(lam.body(), new HashSet<>(params), free);
        free.removeAll(globalNames);
        List<String> captures = new ArrayList<>(free);
        String name = "$lam" + (counter++);
        List<String> liftedParams = new ArrayList<>(captures);
        liftedParams.addAll(params);
        Func lifted = new Func(name, liftedParams, lam.body());
        all.add(lifted);
        work.add(lifted);
        out.put(lam, new Lifted(name, captures.size() + params.size(), captures));
      }
    }
    return all;
  }

  /** Adds the lambdas in {@code e} that are not nested inside another lambda (those belong to the
   *  inner lambda's lifted function and are handled when it is processed). */
  private static void collectTopLambdas(Expr e, List<Expr.Lambda> out) {
    switch (e) {
      case Expr.Lambda lam -> out.add(lam); // do not descend; its body is the lifted function's
      case Expr.App a -> {
        collectTopLambdas(a.fn(), out);
        collectTopLambdas(a.arg(), out);
      }
      case Expr.BinOp b -> {
        collectTopLambdas(b.left(), out);
        collectTopLambdas(b.right(), out);
      }
      case Expr.If i -> {
        collectTopLambdas(i.cond(), out);
        collectTopLambdas(i.thenBranch(), out);
        collectTopLambdas(i.elseBranch(), out);
      }
      case Expr.Negate n -> collectTopLambdas(n.operand(), out);
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            collectTopLambdas(v.body(), out);
          }
        }
        collectTopLambdas(let.body(), out);
      }
      case Expr.Case c -> {
        collectTopLambdas(c.scrutinee(), out);
        c.branches().forEach(br -> collectTopLambdas(br.body(), out));
      }
      case Expr.ListLit l -> l.items().forEach(x -> collectTopLambdas(x, out));
      case Expr.Tuple t -> t.items().forEach(x -> collectTopLambdas(x, out));
      case Expr.Record r -> r.fields().forEach(fld -> collectTopLambdas(fld.value(), out));
      case Expr.RecordAccess a -> collectTopLambdas(a.target(), out);
      case Expr.RecordUpdate u -> u.fields().forEach(fld -> collectTopLambdas(fld.value(), out));
      default -> {}
    }
  }

  /** Accumulates the free lowercase variable names of {@code e} given the currently-bound names. */
  private static void addFree(Expr e, Set<String> bound, Set<String> out) {
    switch (e) {
      case Expr.Var v -> {
        if (v.module() == null && !v.name().isEmpty() && Character.isLowerCase(v.name().charAt(0))
            && !bound.contains(v.name())) {
          out.add(v.name());
        }
      }
      case Expr.App a -> {
        addFree(a.fn(), bound, out);
        addFree(a.arg(), bound, out);
      }
      case Expr.BinOp b -> {
        addFree(b.left(), bound, out);
        addFree(b.right(), bound, out);
      }
      case Expr.If i -> {
        addFree(i.cond(), bound, out);
        addFree(i.thenBranch(), bound, out);
        addFree(i.elseBranch(), bound, out);
      }
      case Expr.Negate n -> addFree(n.operand(), bound, out);
      case Expr.Lambda lam -> {
        Set<String> inner = new HashSet<>(bound);
        for (Pattern p : lam.params()) {
          patternVars(p, inner);
        }
        addFree(lam.body(), inner, out);
      }
      case Expr.Let let -> {
        Set<String> inner = new HashSet<>(bound);
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            inner.add(v.name());
          }
        }
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            Set<String> defScope = new HashSet<>(inner);
            for (Pattern p : v.params()) {
              patternVars(p, defScope);
            }
            addFree(v.body(), defScope, out);
          }
        }
        addFree(let.body(), inner, out);
      }
      case Expr.Case c -> {
        addFree(c.scrutinee(), bound, out);
        for (Expr.Case.Branch br : c.branches()) {
          Set<String> inner = new HashSet<>(bound);
          patternVars(br.pattern(), inner);
          addFree(br.body(), inner, out);
        }
      }
      case Expr.ListLit l -> l.items().forEach(x -> addFree(x, bound, out));
      case Expr.Tuple t -> t.items().forEach(x -> addFree(x, bound, out));
      case Expr.Record r -> r.fields().forEach(fld -> addFree(fld.value(), bound, out));
      case Expr.RecordAccess a -> addFree(a.target(), bound, out);
      case Expr.RecordUpdate u -> {
        if (!bound.contains(u.base())) {
          out.add(u.base());
        }
        u.fields().forEach(fld -> addFree(fld.value(), bound, out));
      }
      default -> {}
    }
  }

  /** Adds the names a pattern binds to {@code bound}. */
  private static void patternVars(Pattern p, Set<String> bound) {
    switch (p) {
      case Pattern.Var v -> bound.add(v.name());
      case Pattern.Alias a -> {
        bound.add(a.name());
        patternVars(a.pattern(), bound);
      }
      case Pattern.Ctor c -> c.args().forEach(x -> patternVars(x, bound));
      case Pattern.Tuple t -> t.items().forEach(x -> patternVars(x, bound));
      case Pattern.ListPat l -> l.items().forEach(x -> patternVars(x, bound));
      case Pattern.Cons cs -> {
        patternVars(cs.head(), bound);
        patternVars(cs.tail(), bound);
      }
      case Pattern.RecordPat r -> bound.addAll(r.fields());
      default -> {}
    }
  }

  // --- per-function code generation --------------------------------------

  private static final class FunctionGen {
    private final Map<String, Integer> locals = new HashMap<>(); // name -> local index
    private final Map<String, int[]> funcs; // function name -> {index, arity}
    private final Map<String, Integer> ctorTag; // constructor name -> tag (index in its union)
    private final Map<String, Integer> ctorArity; // constructor name -> number of fields
    private final Map<Integer, Integer> arityType; // call arity -> wasm type index (for call_indirect)
    private final Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes; // inferred type per expression
    private final Map<Expr.Lambda, Lifted> lifted; // lambda -> its lifted top-level function
    private final Map<String, Integer> fieldIds; // record field name -> small global id (shared)
    private int localCount; // all locals are i64; params occupy 0..numParams-1
    private final int numParams;
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    FunctionGen(
        Map<String, int[]> funcs,
        List<String> params,
        Map<String, Integer> ctorTag,
        Map<String, Integer> ctorArity,
        Map<Integer, Integer> arityType,
        Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes,
        Map<Expr.Lambda, Lifted> lifted,
        Map<String, Integer> fieldIds) {
      this.funcs = funcs;
      this.ctorTag = ctorTag;
      this.ctorArity = ctorArity;
      this.arityType = arityType;
      this.nodeTypes = nodeTypes;
      this.lifted = lifted;
      this.fieldIds = fieldIds;
      this.numParams = params.size();
      for (int i = 0; i < params.size(); i++) {
        locals.put(params.get(i), i);
      }
      this.localCount = params.size();
    }

    /** Compiles an Int-typed expression into a complete code entry (locals + body + end). */
    byte[] compile(Expr e) {
      tailExpr(e); // the body is in tail position: a direct self/other call becomes a return_call
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
          if (isFloat(lit)) {
            // A numeric literal inference resolved to Float (e.g. a Float record field).
            emitFloatConst(lit.value());
          } else {
            code.write(0x42); // i64.const
            sleb(code, lit.value());
          }
        }
        case Expr.FloatLit lit -> emitFloatConst(lit.value());
        case Expr.Negate n -> {
          if (isFloat(n)) {
            pushF64(n.operand());
            code.write(0x9A); // f64.neg
            code.write(0xBD); // back to i64 bits
          } else {
            code.write(0x42);
            sleb(code, 0);
            intExpr(n.operand());
            code.write(0x7D); // i64.sub  (0 - x)
          }
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
          String name = resolvedName(v); // maps qualified stdlib names (List.map) to prelude funcs
          Integer idx = locals.get(name);
          if (idx != null) {
            code.write(0x20); // local.get
            leb(code, idx);
          } else if (funcs.containsKey(name) && funcs.get(name)[1] == 0) {
            code.write(0x10); // call (a zero-arg top-level value)
            leb(code, funcs.get(name)[0]);
          } else if (funcs.containsKey(name)) {
            // A top-level function used as a first-class value: an un-applied closure over it.
            makeClosure(funcs.get(name)[0], funcs.get(name)[1], List.of());
          } else {
            throw unsupported("variable " + (v.module() == null ? "" : v.module() + ".") + v.name());
          }
        }
        case Expr.App app -> emitApp(app);
        case Expr.ListLit l -> emitList(l.items(), 0);
        case Expr.Tuple t -> emitTuple(t.items());
        // Booleans are first-class i64 values (0/1), so they can be stored, returned and compared.
        case Expr.Ctor c when c.name().equals("True") -> { code.write(0x42); sleb(code, 1); }
        case Expr.Ctor c when c.name().equals("False") -> { code.write(0x42); sleb(code, 0); }
        case Expr.Ctor c when ctorTag.containsKey(c.name()) -> emitCtor(c.name(), List.of());
        case Expr.Case c -> intCase(c, this::intExpr);
        case Expr.Record r -> emitRecord(r);
        case Expr.RecordAccess a -> emitRecordAccess(a);
        case Expr.RecordUpdate u -> emitRecordUpdate(u);
        case Expr.StrLit s -> emitStringLit(s.value());
        case Expr.Lambda lam -> {
          Lifted l = lifted.get(lam);
          if (l == null) {
            throw unsupported("lambda (not lifted)");
          }
          emitClosure(l);
        }
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
    // Layout: {count:i64, fieldId_0..fieldId_{n-1}, value_0..value_{n-1}} (fields name-sorted). The
    // stored field-name ids make access and update look fields up by name at runtime, so even a
    // row-polymorphic function — which knows only some of a record's fields — resolves correctly.

    /** A small global integer id for a field name (shared across the module via {@code fieldIds}). */
    private int fieldId(String name) {
      return fieldIds.computeIfAbsent(name, k -> fieldIds.size());
    }

    /** Allocates a self-describing record literal; leaves the i64 pointer. */
    private void emitRecord(Expr.Record rec) {
      List<Expr.Record.Field> fs = new ArrayList<>(rec.fields());
      fs.sort(java.util.Comparator.comparing(Expr.Record.Field::name));
      int n = fs.size();
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr);
      bumpHeap((1 + 2 * n) * 8);
      store(addr, 0, () -> { code.write(0x42); sleb(code, n); }); // count
      for (int j = 0; j < n; j++) {
        int id = fieldId(fs.get(j).name());
        store(addr, (1 + j) * 8, () -> { code.write(0x42); sleb(code, id); }); // field id
      }
      for (int j = 0; j < n; j++) {
        int jj = j;
        store(addr, (1 + n + j) * 8, () -> intExpr(fs.get(jj).value())); // value
      }
      code.write(0x20);
      leb(code, addr);
    }

    /** Loads {@code target.field} via the {@code $recordGet} runtime (look up by field-name id). */
    private void emitRecordAccess(Expr.RecordAccess acc) {
      intExpr(acc.target()); // i64 record pointer
      code.write(0x42); // i64.const fieldId
      sleb(code, fieldId(acc.field()));
      code.write(0x10); // call $recordGet
      leb(code, funcs.get("$recordGet")[0]);
    }

    /** {@code { base | f = v, … }}: chained {@code $recordSet} calls, each replacing one field. */
    private void emitRecordUpdate(Expr.RecordUpdate up) {
      Integer baseLocal = locals.get(up.base());
      if (baseLocal == null) {
        throw unsupported("record update of non-local '" + up.base() + "'");
      }
      code.write(0x20);
      leb(code, baseLocal); // the base record pointer
      for (Expr.Record.Field f : up.fields()) {
        code.write(0x42); // i64.const fieldId
        sleb(code, fieldId(f.name()));
        intExpr(f.value());
        code.write(0x10); // call $recordSet(rec, id, value) -> new record
        leb(code, funcs.get("$recordSet")[0]);
      }
    }

    // --- strings: a heap object {byteLength : i64, bytes…}; the value is the i64 pointer ----------

    /** Allocates a string literal (length word + the UTF-8 bytes); leaves its i64 pointer. */
    private void emitStringLit(String s) {
      byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr); // addr = $hp (as i64)
      bumpHeap(8 + bytes.length);
      store(
          addr,
          0,
          () -> {
            code.write(0x42); // i64.const byteLength
            sleb(code, bytes.length);
          });
      for (int k = 0; k < bytes.length; k++) {
        code.write(0x20);
        leb(code, addr);
        code.write(0xA7); // i32.wrap_i64 -> address
        code.write(0x41);
        sleb(code, bytes[k] & 0xFF); // i32.const byte
        code.write(0x3A); // i32.store8
        leb(code, 0); // align
        leb(code, 8 + k); // offset
      }
      code.write(0x20);
      leb(code, addr); // leave the i64 pointer
    }

    /** Whether the recorded type of {@code e} is {@code String}. */
    private boolean isString(Expr e) {
      return nodeTypes.get(e) instanceof pl.matsuo.elm.types.Ty.Con c
          && c.name().equals("String")
          && c.args().isEmpty();
    }

    // --- floats: an f64 stored as its i64 bit pattern, so values stay uniformly i64 -------------
    // Arithmetic/comparison reinterpret the bits to f64 (0xBF), compute, and (for results) back to
    // i64 (0xBD). Whether an expression is a Float is read from its inferred type.

    /** Whether the recorded type of {@code e} is {@code Float}. */
    private boolean isFloat(Expr e) {
      return nodeTypes.get(e) instanceof pl.matsuo.elm.types.Ty.Con c
          && c.name().equals("Float")
          && c.args().isEmpty();
    }

    /** Leaves an f64 on the stack: evaluates {@code e} (an i64 bit pattern) and reinterprets it. */
    private void pushF64(Expr e) {
      intExpr(e);
      code.write(0xBF); // f64.reinterpret_i64
    }

    /** Pushes a bare f64 constant (8 little-endian bytes) onto the stack. */
    private void emitRawF64Const(double v) {
      code.write(0x44); // f64.const
      long bits = Double.doubleToRawLongBits(v);
      for (int i = 0; i < 8; i++) {
        code.write((int) ((bits >> (8 * i)) & 0xFF));
      }
    }

    /** Pushes the f64 constant {@code v} as its i64 bit pattern (the uniform value representation). */
    private void emitFloatConst(double v) {
      emitRawF64Const(v);
      code.write(0xBD); // i64.reinterpret_f64
    }

    /** Dispatches a {@code case} to the list or the custom-type compiler by its branch patterns. */
    /**
     * Compiles an expression in <b>tail position</b>: a direct, exactly-applied call to a top-level
     * function becomes a {@code return_call} (reusing the current frame), so recursive Elm runs at
     * any depth instead of overflowing the wasm call stack. Tail position flows through {@code if}
     * branches, {@code let} bodies and {@code case} branches; anything else falls back to the
     * ordinary value-leaving compilation (and is returned implicitly at the function's end).
     */
    private void tailExpr(Expr e) {
      switch (e) {
        case Expr.If iff -> {
          boolExpr(iff.cond());
          code.write(0x04);
          code.write(I64); // if -> i64
          tailExpr(iff.thenBranch());
          code.write(0x05);
          tailExpr(iff.elseBranch());
          code.write(0x0B);
        }
        case Expr.Let let -> {
          for (Decl d : let.defs()) {
            if (d instanceof Decl.Value v && v.params().isEmpty()) {
              int idx = local(v.name());
              intExpr(v.body());
              code.write(0x21);
              leb(code, idx);
            } else {
              throw unsupported("let definition with parameters");
            }
          }
          tailExpr(let.body());
        }
        case Expr.Case c -> intCase(c, this::tailExpr);
        case Expr.App app -> tailApp(app);
        default -> intExpr(e);
      }
    }

    /** A direct, exactly-applied call to a known top-level function in tail position emits {@code
     *  return_call}; anything else is left to the ordinary {@link #intApp}. */
    private void tailApp(Expr.App app) {
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      if (head instanceof Expr.Var v
          && funcs.containsKey(resolvedName(v))
          && !locals.containsKey(resolvedName(v))
          && funcs.get(resolvedName(v))[1] == args.size()
          && !arenaResettable(app)) {
        // A direct, exactly-applied call that does NOT need arena reclamation: tail-call it.
        for (Expr arg : args) {
          intExpr(arg);
        }
        code.write(0x12); // return_call
        leb(code, funcs.get(resolvedName(v))[0]);
        return;
      }
      // Otherwise go through emitApp so arena reclamation still runs (a tail call could not reset
      // the heap pointer afterwards). Builtins/ctors/partial/closures land here too.
      emitApp(app);
    }

    private void intCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      boolean adt =
          c.branches().stream()
              .anyMatch(b -> b.pattern() instanceof Pattern.Ctor ct && ctorTag.containsKey(ct.name()));
      if (adt) {
        intAdtCase(c, body);
      } else if (c.branches().stream().anyMatch(b -> b.pattern() instanceof Pattern.Tuple)) {
        intTupleCase(c, body);
      } else {
        intListCase(c, body);
      }
    }

    /**
     * Compiles a {@code case} whose pattern is a tuple. A tuple is the address of n contiguous i64
     * words (see {@link #emitTuple}); the pattern is irrefutable, so we bind its parts by word offset
     * (recursing into nested tuple patterns) and run the single branch body.
     */
    private void intTupleCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      Expr.Case.Branch br =
          c.branches().stream()
              .filter(b -> b.pattern() instanceof Pattern.Tuple)
              .findFirst()
              .orElseThrow();
      int s = freshLocal();
      intExpr(c.scrutinee());
      code.write(0x21);
      leb(code, s); // local.set tuple address
      bindTupleFromLocal(s, (Pattern.Tuple) br.pattern());
      body.accept(br.body());
    }

    /** Binds a tuple pattern's parts from the tuple whose address is in {@code addr} (word i -> item i),
     * recursing into nested tuple patterns. Var binds a local; wildcard is skipped. */
    private void bindTupleFromLocal(int addr, Pattern.Tuple pat) {
      List<Pattern> items = pat.items();
      for (int i = 0; i < items.size(); i++) {
        switch (items.get(i)) {
          case Pattern.Var v -> {
            int l = local(v.name());
            load(addr, i * 8);
            code.write(0x21);
            leb(code, l); // local.set item
          }
          case Pattern.Wildcard ignored -> {}
          case Pattern.Tuple inner -> {
            int innerAddr = freshLocal();
            load(addr, i * 8);
            code.write(0x21);
            leb(code, innerAddr); // local.set nested tuple address
            bindTupleFromLocal(innerAddr, inner);
          }
          default -> throw unsupported("tuple sub-pattern in WASM");
        }
      }
    }

    /**
     * Compiles a {@code case} over a custom type: load the value's tag word once, then an if/else
     * chain comparing it to each constructor's tag, binding fields (by word offset) in the match.
     * Constructor arguments must be variable/wildcard patterns (no nested matching).
     */
    private void intAdtCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      int s = freshLocal();
      intExpr(c.scrutinee());
      code.write(0x21);
      leb(code, s); // local.set scrutinee pointer
      emitAdtBranches(c.branches(), 0, s, body);
    }

    private void emitAdtBranches(
        List<Expr.Case.Branch> branches, int idx, int s, java.util.function.Consumer<Expr> body) {
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
          body.accept(br.body());
        }
        case Pattern.Wildcard ignored -> body.accept(br.body());
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
          body.accept(br.body());
          code.write(0x05); // else
          emitAdtBranches(branches, idx + 1, s, body);
          code.write(0x0B); // end
        }
        default -> throw unsupported("custom-type case pattern in WASM");
      }
    }

    /** Compiles a {@code case} over a list: branches for {@code []} and {@code head :: tail}. */
    private void intListCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
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
      body.accept(nilBody);
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
      body.accept(consBody);
      code.write(0x0B); // end
    }

    private void intBinOp(Expr.BinOp b) {
      if (b.op().equals("::")) {
        emitCons(() -> intExpr(b.left()), () -> intExpr(b.right()));
        return;
      }
      // A comparison or boolean operator used as a value: compute the i32 0/1 and widen to i64, so
      // booleans are first-class (stored in lists, returned from functions, etc.).
      if (BOOL_OPS.contains(b.op())) {
        boolBinOp(b);
        code.write(0xAD); // i64.extend_i32_u
        return;
      }
      if (b.op().equals("++")) {
        if (!isString(b.left()) && !isString(b.right())) {
          throw unsupported("++ on non-strings");
        }
        intExpr(b.left());
        intExpr(b.right());
        code.write(0x10); // call $strConcat
        leb(code, funcs.get("$strConcat")[0]);
        return;
      }
      // Float arithmetic: `/` is float-only; `+ - *` are float when the operands are Float.
      if (b.op().equals("/") || ((b.op().equals("+") || b.op().equals("-") || b.op().equals("*"))
          && (isFloat(b.left()) || isFloat(b.right())))) {
        pushF64(b.left());
        pushF64(b.right());
        code.write(
            switch (b.op()) {
              case "+" -> 0xA0; // f64.add
              case "-" -> 0xA1; // f64.sub
              case "*" -> 0xA2; // f64.mul
              default -> 0xA3; // f64.div
            });
        code.write(0xBD); // i64.reinterpret_f64 -> uniform i64 bits
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

    /**
     * Compiles an application, with a sound <b>arena reclamation</b>: if the call's result type is a
     * scalar ({@code Int}/{@code Float}/{@code Bool}) and it consumes a heap argument, the heap
     * pointer is saved before the call and restored after. Everything the call allocated is then
     * reclaimed — provably dead, since a scalar result holds no heap pointer and the language is pure
     * (no mutation makes those allocations reachable). This keeps memory bounded for the common
     * "reduce a structure, in a loop" shape (e.g. repeatedly {@code List.sum (List.range …)}).
     */
    private void emitApp(Expr.App app) {
      if (!arenaResettable(app)) {
        intApp(app);
        return;
      }
      int saved = freshLocal();
      code.write(0x23);
      leb(code, 0); // global.get $hp
      code.write(0xAD); // i64.extend_i32_u
      code.write(0x21);
      leb(code, saved); // saved = $hp
      intApp(app); // leaves the (scalar) result on the stack
      code.write(0x20);
      leb(code, saved);
      code.write(0xA7); // i32.wrap_i64 -> the saved address
      code.write(0x24);
      leb(code, 0); // global.set $hp (reclaim everything the call allocated)
    }

    /** Whether {@code app}'s result is scalar and it consumes a heap argument (so resetting the heap
     *  pointer after the call is both sound and worthwhile). */
    private boolean arenaResettable(Expr.App app) {
      if (!isScalarType(nodeTypes.get(app))) {
        return false;
      }
      Expr head = app;
      boolean anyHeapArg = false;
      while (head instanceof Expr.App a) {
        if (isHeapType(nodeTypes.get(a.arg()))) {
          anyHeapArg = true;
        }
        head = a.fn();
      }
      return anyHeapArg;
    }

    /** A scalar (non-heap) result/value type: Int, Float or Bool. */
    private static boolean isScalarType(pl.matsuo.elm.types.Ty t) {
      return t instanceof pl.matsuo.elm.types.Ty.Con c
          && c.args().isEmpty()
          && (c.name().equals("Int") || c.name().equals("Float") || c.name().equals("Bool"));
    }

    /** A definitely heap-allocated type (a known compound type), used to gate arena reclamation. */
    private static boolean isHeapType(pl.matsuo.elm.types.Ty t) {
      return t != null && !(t instanceof pl.matsuo.elm.types.Ty.Var) && !isScalarType(t);
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
      // String.length s  ->  load the string's i64 length word
      if (app.fn() instanceof Expr.Var sv
          && "String".equals(sv.module())
          && sv.name().equals("length")) {
        intExpr(app.arg()); // i64 pointer
        code.write(0xA7); // i32.wrap_i64
        code.write(0x29); // i64.load (offset 0 = the length)
        leb(code, 3);
        leb(code, 0);
        return;
      }
      // Int/Float conversions (Basics): toFloat : Int -> Float, and round/floor/ceiling/truncate :
      // Float -> Int. Results stay in the uniform i64 representation (float bits, or an integer).
      if (app.fn() instanceof Expr.Var cv && cv.module() == null) {
        switch (cv.name()) {
          case "toFloat" -> {
            intExpr(app.arg()); // i64 integer
            code.write(0xB9); // f64.convert_i64_s
            code.write(0xBD); // i64.reinterpret_f64 -> float bits
            return;
          }
          case "floor", "ceiling", "truncate" -> {
            pushF64(app.arg());
            code.write(switch (cv.name()) {
              case "floor" -> 0x9C; // f64.floor
              case "ceiling" -> 0x9B; // f64.ceil
              default -> 0x9D; // f64.trunc
            });
            code.write(0xB0); // i64.trunc_f64_s
            return;
          }
          case "round" -> {
            // Elm/JS round is half-up: floor(x + 0.5).
            pushF64(app.arg());
            emitRawF64Const(0.5);
            code.write(0xA0); // f64.add
            code.write(0x9C); // f64.floor
            code.write(0xB0); // i64.trunc_f64_s
            return;
          }
          default -> {}
        }
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
      // A call to a known top-level function (or a qualified stdlib name mapped to a prelude
      // function) that is NOT shadowed by a local.
      if (head instanceof Expr.Var v
          && funcs.containsKey(resolvedName(v))
          && !locals.containsKey(resolvedName(v))) {
        String fn = resolvedName(v);
        int arity = funcs.get(fn)[1];
        if (args.size() == arity) {
          for (Expr arg : args) {
            intExpr(arg);
          }
          code.write(0x10); // call (fast path: exact arity)
          leb(code, funcs.get(fn)[0]);
          return;
        }
        if (args.size() < arity) {
          // Partial application: a closure capturing the supplied args.
          makeClosure(funcs.get(fn)[0], arity, args);
          return;
        }
        // Over-application: call the function, then apply the surplus args to its result.
        for (int i = 0; i < arity; i++) {
          intExpr(args.get(i));
        }
        code.write(0x10);
        leb(code, funcs.get(fn)[0]);
        for (int i = arity; i < args.size(); i++) {
          intExpr(args.get(i));
          code.write(0x10); // call $apply
          leb(code, funcs.get("$apply")[0]);
        }
        return;
      }
      // Otherwise the head is a value (a local closure, a lambda, an application result): evaluate
      // it to a closure and apply each argument through the $apply runtime (currying).
      intExpr(head);
      for (Expr arg : args) {
        intExpr(arg);
        code.write(0x10); // call $apply
        leb(code, funcs.get("$apply")[0]);
      }
    }

    /** A variable's effective top-level name: a qualified stdlib name ({@code List.map}) maps to its
     *  prelude function ({@code listMap}); everything else keeps its own name. */
    private String resolvedName(Expr.Var v) {
      if (v.module() != null) {
        String qualified = v.module() + "." + v.name();
        if (PRELUDE_NAMES.containsKey(qualified)) {
          return PRELUDE_NAMES.get(qualified);
        }
      }
      return v.name();
    }

    /**
     * Allocates a closure {@code {funcIdx, arity, count, slot…}} (one i64 word each) with {@code
     * count} initially-applied arguments (captures for a lambda, or the leading args of a partial
     * application); leaves its i64 pointer. Closures are always under-applied, so count &lt; arity.
     */
    private void makeClosure(int funcIdx, int arity, List<Expr> argExprs) {
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr); // addr = $hp (i64)
      bumpHeap((3 + argExprs.size()) * 8);
      store(addr, 0, () -> { code.write(0x42); sleb(code, funcIdx); });
      store(addr, 8, () -> { code.write(0x42); sleb(code, arity); });
      store(addr, 16, () -> { code.write(0x42); sleb(code, argExprs.size()); });
      for (int j = 0; j < argExprs.size(); j++) {
        int jj = j;
        store(addr, (3 + j) * 8, () -> intExpr(argExprs.get(jj)));
      }
      code.write(0x20);
      leb(code, addr);
    }

    /** Emits the closure for a lifted lambda, capturing the named locals it closed over. */
    private void emitClosure(Lifted l) {
      int funcIdx = funcs.get(l.name())[0];
      int addr = freshLocal();
      code.write(0x23);
      leb(code, 0);
      code.write(0xAD);
      code.write(0x21);
      leb(code, addr);
      bumpHeap((3 + l.captures().size()) * 8);
      store(addr, 0, () -> { code.write(0x42); sleb(code, funcIdx); });
      store(addr, 8, () -> { code.write(0x42); sleb(code, l.arity()); });
      store(addr, 16, () -> { code.write(0x42); sleb(code, l.captures().size()); });
      for (int j = 0; j < l.captures().size(); j++) {
        Integer local = locals.get(l.captures().get(j));
        if (local == null) {
          throw unsupported("lambda capturing non-local '" + l.captures().get(j) + "'");
        }
        store(addr, (3 + j) * 8, () -> { code.write(0x20); leb(code, local); });
      }
      code.write(0x20);
      leb(code, addr);
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
        default -> {
          // Any other Bool-valued expression (a variable, a function call like `pred h`, an `if`):
          // evaluate it as the uniform i64 (0/1) and narrow to the i32 a condition needs.
          intExpr(e);
          code.write(0xA7); // i32.wrap_i64
        }
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
          if ((b.op().equals("==") || b.op().equals("/=")) && (isString(b.left()) || isString(b.right()))) {
            // String (in)equality: compare via the $strEq runtime, then map its i64 0/1 to an i32.
            intExpr(b.left());
            intExpr(b.right());
            code.write(0x10); // call $strEq
            leb(code, funcs.get("$strEq")[0]);
            code.write(0xA7); // i32.wrap_i64 -> 0/1
            if (b.op().equals("/=")) {
              code.write(0x45); // i32.eqz -> invert
            }
            return;
          }
          if (isFloat(b.left()) || isFloat(b.right())) {
            pushF64(b.left());
            pushF64(b.right());
            code.write(
                switch (b.op()) {
                  case "<" -> 0x63; // f64.lt
                  case ">" -> 0x64; // f64.gt
                  case "<=" -> 0x65; // f64.le
                  case ">=" -> 0x66; // f64.ge
                  case "==" -> 0x61; // f64.eq
                  default -> 0x62; // f64.ne
                });
            return;
          }
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

  // --- native string runtime ----------------------------------------------
  // A String is a heap object {byteLength : i64 at offset 0, then the UTF-8 bytes}. Its value is the
  // i64 pointer, like every other heap value. Equality and concatenation need byte loops, so they
  // are emitted once as two hand-assembled wasm functions ($strEq, $strConcat) and called directly.

  /** A function whose body is pre-assembled wasm bytes (the string runtime), not compiled Elm. */
  private record Native(String name, int arity, byte[] entry) {}

  private static List<Native> stringRuntime() {
    return List.of(new Native("$strEq", 2, strEqEntry()), new Native("$strConcat", 2, strConcatEntry()));
  }

  private static void nload64(ByteArrayOutputStream b, int off) {
    b.write(0x29);
    leb(b, 3);
    leb(b, off);
  }

  private static void nstore64(ByteArrayOutputStream b, int off) {
    b.write(0x37);
    leb(b, 3);
    leb(b, off);
  }

  /**
   * {@code $apply(clo, arg) -> i64}: the closure runtime. A closure is a heap block {@code {funcIdx,
   * arity, count, slot…}}. Applying copies it with one more slot; once {@code count} reaches {@code
   * arity} the underlying function is invoked via {@code call_indirect} (dispatched on the arity over
   * the arities that exist), otherwise the larger closure is returned. {@code arityTypes} maps each
   * callable arity to its wasm function-type index.
   */
  private static byte[] applyEntry(java.util.SortedMap<Integer, Integer> arityTypes) {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // funcIdx/arity/count from the closure header.
    lget(b, 0); b.write(0xA7); nload64(b, 0); lset(b, 2);
    lget(b, 0); b.write(0xA7); nload64(b, 8); lset(b, 3);
    lget(b, 0); b.write(0xA7); nload64(b, 16); lset(b, 4);
    // newClo = $hp; bytes = (4 + (i32)count) * 8; $hp += bytes
    b.write(0x23); leb(b, 0); lset(b, 5);
    i32c(b, 4); lget(b, 4); b.write(0xA7); b.write(0x6A); i32c(b, 8); b.write(0x6C); lset(b, 7);
    lget(b, 5); lget(b, 7); b.write(0x6A); b.write(0x24); leb(b, 0);
    // grow memory if $hp passed capacity
    b.write(0x23); leb(b, 0); i32c(b, 65535); b.write(0x6A); i32c(b, 16); b.write(0x76);
    b.write(0x3F); b.write(0x00); b.write(0x6B); lset(b, 8);
    lget(b, 8); i32c(b, 0); b.write(0x4A); b.write(0x04); b.write(0x40);
    lget(b, 8); b.write(0x40); b.write(0x00); b.write(0x1A); b.write(0x0B);
    // header: funcIdx, arity, count+1
    lget(b, 5); lget(b, 2); nstore64(b, 0);
    lget(b, 5); lget(b, 3); nstore64(b, 8);
    lget(b, 5); lget(b, 4); b.write(0x42); sleb(b, 1); b.write(0x7C); nstore64(b, 16);
    // copy slots 0..count-1
    i32c(b, 0); lset(b, 6);
    b.write(0x02); b.write(0x40); b.write(0x03); b.write(0x40);
    lget(b, 6); lget(b, 4); b.write(0xA7); b.write(0x4F); b.write(0x0D); leb(b, 1);
    lget(b, 5); i32c(b, 24); b.write(0x6A); lget(b, 6); i32c(b, 8); b.write(0x6C); b.write(0x6A); // dest
    lget(b, 0); b.write(0xA7); i32c(b, 24); b.write(0x6A); lget(b, 6); i32c(b, 8); b.write(0x6C);
    b.write(0x6A); nload64(b, 0); // value from clo
    nstore64(b, 0);
    lget(b, 6); i32c(b, 1); b.write(0x6A); lset(b, 6);
    b.write(0x0C); leb(b, 0); b.write(0x0B); b.write(0x0B);
    // newClo slot[count] = arg
    lget(b, 5); i32c(b, 24); b.write(0x6A); lget(b, 4); b.write(0xA7); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    lget(b, 1); nstore64(b, 0);
    // if count+1 == arity: invoke; else return newClo
    lget(b, 4); b.write(0x42); sleb(b, 1); b.write(0x7C); lget(b, 3); b.write(0x51);
    b.write(0x04); b.write(0x7E); // if (result i64)
    emitDispatch(b, new ArrayList<>(arityTypes.entrySet()), 0);
    b.write(0x05); // else
    lget(b, 5); b.write(0xAD); // newClo as i64 pointer
    b.write(0x0B); // end if
    return entry(b, new int[][] {{3, I64}, {4, I32}});
  }

  /** Emits the arity-dispatch if/else chain inside {@code $apply}, invoking via call_indirect. */
  private static void emitDispatch(
      ByteArrayOutputStream b, List<Map.Entry<Integer, Integer>> arities, int idx) {
    if (idx >= arities.size()) {
      b.write(0x00); // unreachable: a complete closure always has a known arity
      return;
    }
    int arity = arities.get(idx).getKey();
    int typeIdx = arities.get(idx).getValue();
    lget(b, 3); b.write(0x42); sleb(b, arity); b.write(0x51); // arity == a ?
    b.write(0x04); b.write(0x7E); // if (result i64)
    for (int k = 0; k < arity; k++) {
      lget(b, 5); i32c(b, 24 + 8 * k); b.write(0x6A); nload64(b, 0); // slot k
    }
    lget(b, 2); b.write(0xA7); // funcIdx as table index
    b.write(0x11); leb(b, typeIdx); leb(b, 0); // call_indirect
    b.write(0x05); // else
    emitDispatch(b, arities, idx + 1);
    b.write(0x0B); // end if
  }

  // A record is a self-describing heap block {count:i64, fieldId_0..fieldId_{n-1}, value_0..value_{n-1}}
  // with fields in name-sorted order. Storing the field-name ids lets access and update look fields
  // up by name at runtime, so a row-polymorphic function (which knows only some of a record's fields)
  // works without a closed type.

  /** {@code $recordGet(ptr, fieldId) -> i64}: the value of the named field (linear scan by id). */
  private static byte[] recordGetEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals: count i64 (2); i (3), base (4) i32
    lget(b, 0); b.write(0xA7); lset(b, 4); // base = wrap(ptr)
    lget(b, 4); nload64(b, 0); lset(b, 2); // count
    i32c(b, 0); lset(b, 3); // i = 0
    b.write(0x02); b.write(0x40); b.write(0x03); b.write(0x40);
    lget(b, 3); lget(b, 2); b.write(0xA7); b.write(0x4F); b.write(0x0D); leb(b, 1); // i>=count -> exit
    // if load(base + 8 + 8i) == fieldId: return load(base + 8 + 8*count + 8i)
    lget(b, 4); i32c(b, 8); b.write(0x6A); lget(b, 3); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    nload64(b, 0);
    lget(b, 1); b.write(0x51); // == fieldId
    b.write(0x04); b.write(0x40); // if (void)
    lget(b, 4); i32c(b, 8); b.write(0x6A); lget(b, 2); b.write(0xA7); i32c(b, 8); b.write(0x6C);
    b.write(0x6A); lget(b, 3); i32c(b, 8); b.write(0x6C); b.write(0x6A); // base + 8 + 8*count + 8i
    nload64(b, 0);
    b.write(0x0F); // return value
    b.write(0x0B); // end if
    lget(b, 3); i32c(b, 1); b.write(0x6A); lset(b, 3); // i++
    b.write(0x0C); leb(b, 0); b.write(0x0B); b.write(0x0B); // br 0; end loop; end block
    b.write(0x42); sleb(b, 0); // unreachable in well-typed code; yield 0
    return entry(b, new int[][] {{1, I64}, {2, I32}});
  }

  /** {@code $recordSet(ptr, fieldId, val) -> i64}: a copy of the record with one field replaced. */
  private static byte[] recordSetEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals: count i64 (3); i (4), src (5), dst (6), bytes (7), delta (8) i32
    lget(b, 0); b.write(0xA7); lset(b, 5); // src = wrap(ptr)
    lget(b, 5); nload64(b, 0); lset(b, 3); // count
    b.write(0x23); leb(b, 0); lset(b, 6); // dst = $hp
    // bytes = (1 + 2*count) * 8
    i32c(b, 1); lget(b, 3); b.write(0xA7); i32c(b, 2); b.write(0x6C); b.write(0x6A); i32c(b, 8); b.write(0x6C); lset(b, 7);
    lget(b, 6); lget(b, 7); b.write(0x6A); b.write(0x24); leb(b, 0); // $hp += bytes
    // grow
    b.write(0x23); leb(b, 0); i32c(b, 65535); b.write(0x6A); i32c(b, 16); b.write(0x76);
    b.write(0x3F); b.write(0x00); b.write(0x6B); lset(b, 8);
    lget(b, 8); i32c(b, 0); b.write(0x4A); b.write(0x04); b.write(0x40);
    lget(b, 8); b.write(0x40); b.write(0x00); b.write(0x1A); b.write(0x0B);
    lget(b, 6); lget(b, 3); nstore64(b, 0); // dst[0] = count
    i32c(b, 0); lset(b, 4); // i = 0
    b.write(0x02); b.write(0x40); b.write(0x03); b.write(0x40);
    lget(b, 4); lget(b, 3); b.write(0xA7); b.write(0x4F); b.write(0x0D); leb(b, 1); // i>=count -> exit
    // copy id: dst[8+8i] = src[8+8i]
    lget(b, 6); i32c(b, 8); b.write(0x6A); lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); // dst id addr
    lget(b, 5); i32c(b, 8); b.write(0x6A); lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); nload64(b, 0); // src id
    nstore64(b, 0);
    // dst value addr = dst + 8 + 8*count + 8i
    lget(b, 6); i32c(b, 8); b.write(0x6A); lget(b, 3); b.write(0xA7); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    // value: if src id_i == fieldId then val else src value_i
    lget(b, 5); i32c(b, 8); b.write(0x6A); lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); nload64(b, 0); // src id_i
    lget(b, 1); b.write(0x51); // == fieldId
    b.write(0x04); b.write(0x7E); // if (result i64)
    lget(b, 2); // val
    b.write(0x05); // else
    lget(b, 5); i32c(b, 8); b.write(0x6A); lget(b, 3); b.write(0xA7); i32c(b, 8); b.write(0x6C); b.write(0x6A);
    lget(b, 4); i32c(b, 8); b.write(0x6C); b.write(0x6A); nload64(b, 0); // src value_i
    b.write(0x0B); // end if
    nstore64(b, 0); // store the chosen value at dst value addr
    lget(b, 4); i32c(b, 1); b.write(0x6A); lset(b, 4); // i++
    b.write(0x0C); leb(b, 0); b.write(0x0B); b.write(0x0B);
    lget(b, 6); b.write(0xAD); // return dst as i64 pointer
    return entry(b, new int[][] {{1, I64}, {5, I32}});
  }

  private static void lget(ByteArrayOutputStream b, int i) {
    b.write(0x20);
    leb(b, i);
  }

  private static void lset(ByteArrayOutputStream b, int i) {
    b.write(0x21);
    leb(b, i);
  }

  private static void i32c(ByteArrayOutputStream b, int v) {
    b.write(0x41);
    sleb(b, v);
  }

  /** {@code $strEq(a, b) -> i64}: 1 if the two strings have equal length and bytes, else 0. */
  private static byte[] strEqEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals (after params a=0, b=1): lenA=2 (i64), i=3, baseA=4, baseB=5 (i32)
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0); // lenA = i64.load(a)
    lset(b, 2);
    lget(b, 2);
    lget(b, 1);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0); // i64.load(b)
    b.write(0x52); // i64.ne
    b.write(0x04);
    b.write(0x40); // if (void) -> lengths differ
    b.write(0x42);
    sleb(b, 0);
    b.write(0x0F); // return 0
    b.write(0x0B); // end if
    lget(b, 0);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lset(b, 4); // baseA = wrap(a) + 8
    lget(b, 1);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lset(b, 5); // baseB = wrap(b) + 8
    i32c(b, 0);
    lset(b, 3); // i = 0
    b.write(0x02);
    b.write(0x40); // block (void)
    b.write(0x03);
    b.write(0x40); // loop (void)
    lget(b, 3);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x4F); // i >= (i32)lenA ?
    b.write(0x0D);
    leb(b, 1); // br_if 1 -> exit block (all matched)
    lget(b, 4);
    lget(b, 3);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0); // load8(baseA + i)
    lget(b, 5);
    lget(b, 3);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0); // load8(baseB + i)
    b.write(0x47); // i32.ne
    b.write(0x04);
    b.write(0x40); // if bytes differ
    b.write(0x42);
    sleb(b, 0);
    b.write(0x0F); // return 0
    b.write(0x0B);
    lget(b, 3);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 3); // i++
    b.write(0x0C);
    leb(b, 0); // br 0 -> loop
    b.write(0x0B); // end loop
    b.write(0x0B); // end block
    b.write(0x42);
    sleb(b, 1); // result: 1 (equal)
    return entry(b, new int[][] {{1, I64}, {3, I32}});
  }

  /** {@code $strConcat(a, b) -> i64}: a fresh heap string holding a's bytes followed by b's. */
  private static byte[] strConcatEntry() {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    // locals: lenA=2, lenB=3 (i64); result=4, i=5, total=6, delta=7 (i32)
    lget(b, 0);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 2); // lenA
    lget(b, 1);
    b.write(0xA7);
    b.write(0x29);
    leb(b, 3);
    leb(b, 0);
    lset(b, 3); // lenB
    b.write(0x23);
    leb(b, 0);
    lset(b, 4); // result = $hp
    i32c(b, 8);
    lget(b, 2);
    b.write(0xA7);
    b.write(0x6A);
    lget(b, 3);
    b.write(0xA7);
    b.write(0x6A);
    lset(b, 6); // total = 8 + lenA + lenB
    lget(b, 4);
    lget(b, 6);
    b.write(0x6A);
    b.write(0x24);
    leb(b, 0); // $hp = result + total
    // grow: delta = ceilPages($hp) - memory.size; if delta > 0 memory.grow(delta)
    b.write(0x23);
    leb(b, 0);
    i32c(b, 65535);
    b.write(0x6A);
    i32c(b, 16);
    b.write(0x76); // ($hp + 65535) >> 16  (i32.shr_u)
    b.write(0x3F);
    b.write(0x00); // memory.size
    b.write(0x6B); // i32.sub
    lset(b, 7);
    lget(b, 7);
    i32c(b, 0);
    b.write(0x4A); // delta > 0 ? (i32.gt_s)
    b.write(0x04);
    b.write(0x40);
    lget(b, 7);
    b.write(0x40);
    b.write(0x00); // memory.grow(delta)
    b.write(0x1A); // drop
    b.write(0x0B);
    // result.length = lenA + lenB
    lget(b, 4);
    lget(b, 2);
    lget(b, 3);
    b.write(0x7C); // i64.add
    b.write(0x37);
    leb(b, 3);
    leb(b, 0); // i64.store(result, 0)
    copyLoop(b, /*destBaseExtra*/ 0, /*srcParam*/ 0, /*lenLocal*/ 2, /*destLenOffsetLocal*/ -1);
    copyLoop(b, 0, 1, 3, 2); // B copied after A's lenA bytes
    lget(b, 4);
    b.write(0xAD); // result as i64 pointer
    return entry(b, new int[][] {{2, I64}, {4, I32}});
  }

  /**
   * Emits a byte-copy loop into {@code $strConcat}'s body: copies {@code lenLocal} bytes from string
   * {@code srcParam}'s data into {@code result}'s data, offset by the length in {@code
   * destLenOffsetLocal} (or 0 when that is negative). Uses loop counter local 5.
   */
  private static void copyLoop(
      ByteArrayOutputStream b, int unused, int srcParam, int lenLocal, int destLenOffsetLocal) {
    i32c(b, 0);
    lset(b, 5); // i = 0
    b.write(0x02);
    b.write(0x40); // block
    b.write(0x03);
    b.write(0x40); // loop
    lget(b, 5);
    lget(b, lenLocal);
    b.write(0xA7);
    b.write(0x4F); // i >= (i32)len ?
    b.write(0x0D);
    leb(b, 1); // br_if 1
    // dest = result + 8 + [destLenOffset] + i
    lget(b, 4);
    i32c(b, 8);
    b.write(0x6A);
    if (destLenOffsetLocal >= 0) {
      lget(b, destLenOffsetLocal);
      b.write(0xA7);
      b.write(0x6A);
    }
    lget(b, 5);
    b.write(0x6A);
    // src = wrap(srcParam) + 8 + i ; then load8
    lget(b, srcParam);
    b.write(0xA7);
    i32c(b, 8);
    b.write(0x6A);
    lget(b, 5);
    b.write(0x6A);
    b.write(0x2D);
    leb(b, 0);
    leb(b, 0); // load8(src)
    b.write(0x3A);
    leb(b, 0);
    leb(b, 0); // store8(dest, byte)
    lget(b, 5);
    i32c(b, 1);
    b.write(0x6A);
    lset(b, 5); // i++
    b.write(0x0C);
    leb(b, 0); // br 0
    b.write(0x0B);
    b.write(0x0B); // end loop, end block
  }

  /** Wraps a pre-assembled function body in a code entry: locals declaration + body + end, size-led. */
  private static byte[] entry(ByteArrayOutputStream body, int[][] localGroups) {
    ByteArrayOutputStream full = new ByteArrayOutputStream();
    leb(full, localGroups.length);
    for (int[] g : localGroups) {
      leb(full, g[0]);
      full.write(g[1]);
    }
    full.writeBytes(body.toByteArray());
    full.write(0x0B); // end
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    leb(out, full.size());
    out.writeBytes(full.toByteArray());
    return out.toByteArray();
  }

  // --- module assembly ----------------------------------------------------

  private static byte[] assemble(
      List<Func> funcList,
      Map<String, Integer> ctorTag,
      Map<String, Integer> ctorArity,
      Map<Expr, pl.matsuo.elm.types.Ty> nodeTypes,
      Map<Expr.Lambda, Lifted> lifted) {
    // One wasm function type per distinct arity: (i64 x arity) -> i64. Computed over the user/lifted
    // functions plus arity 2 (every native — $strEq, $strConcat, $apply — takes two i64s).
    List<Integer> arities = new ArrayList<>();
    Map<Integer, Integer> arityType = new HashMap<>();
    for (Func f : funcList) {
      arityType.computeIfAbsent(f.params().size(), a -> { arities.add(a); return arities.size() - 1; });
    }
    arityType.computeIfAbsent(2, a -> { arities.add(a); return arities.size() - 1; }); // 2-arg natives
    arityType.computeIfAbsent(3, a -> { arities.add(a); return arities.size() - 1; }); // $recordSet

    // Field-name ids are assigned lazily but shared across every function, so a record literal and a
    // later access/update agree on each field's id.
    Map<String, Integer> fieldIds = new HashMap<>();

    // The closure runtime ($apply) dispatches over the arities a closure may carry — i.e. the
    // arities of the user/lifted functions (>= 1), each mapped to its function-type index.
    java.util.SortedMap<Integer, Integer> dispatch = new java.util.TreeMap<>();
    for (Func f : funcList) {
      if (f.params().size() >= 1) {
        dispatch.put(f.params().size(), arityType.get(f.params().size()));
      }
    }

    // Natives are appended after the user/lifted functions: positions are user funcs 0..U-1, then
    // the natives. Every function (user + native) shares the table, type, element and code sections.
    List<Native> natives = new ArrayList<>(stringRuntime());
    natives.add(new Native("$apply", 2, applyEntry(dispatch)));
    natives.add(new Native("$recordGet", 2, recordGetEntry()));
    natives.add(new Native("$recordSet", 3, recordSetEntry()));
    int userCount = funcList.size();
    int total = userCount + natives.size();

    // Function table: name -> {index, arity}, so calls/recursion resolve to a call index.
    Map<String, int[]> table = new HashMap<>();
    for (int i = 0; i < userCount; i++) {
      table.put(funcList.get(i).name(), new int[] {i, funcList.get(i).params().size()});
    }
    for (int i = 0; i < natives.size(); i++) {
      table.put(natives.get(i).name(), new int[] {userCount + i, natives.get(i).arity()});
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

    // Function section: each function's type index (by its arity), user functions then natives.
    ByteArrayOutputStream funcs = new ByteArrayOutputStream();
    leb(funcs, total);
    for (Func f : funcList) {
      leb(funcs, arityType.get(f.params().size()));
    }
    for (Native n : natives) {
      leb(funcs, arityType.get(n.arity()));
    }
    section(out, 3, funcs);

    // Table section (id 4): one funcref table holding every function, so a function value (carried
    // as its index) can be invoked with call_indirect. Sized exactly to the function count.
    ByteArrayOutputStream tableSec = new ByteArrayOutputStream();
    leb(tableSec, 1); // one table
    tableSec.write(0x70); // funcref
    tableSec.write(0x00); // limits: min only
    leb(tableSec, total); // min = number of functions
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
    leb(elem, total);
    for (int i = 0; i < total; i++) {
      leb(elem, i);
    }
    section(out, 9, elem);

    // Code section: user functions (compiled from Elm) then the native string runtime (raw bytes).
    ByteArrayOutputStream code = new ByteArrayOutputStream();
    leb(code, total);
    for (Func f : funcList) {
      code.writeBytes(
          new FunctionGen(table, f.params(), ctorTag, ctorArity, arityType, nodeTypes, lifted, fieldIds)
              .compile(f.body()));
    }
    for (Native n : natives) {
      code.writeBytes(n.entry());
    }
    section(out, 10, code);

    // Name section (custom, id 0): maps function indices back to their Elm names so disassemblers
    // and stack traces show `mySum` / `$apply` instead of `func[7]`. Index space is user funcs
    // 0..U-1 then the natives, matching the code section above.
    List<String> funcNames = new ArrayList<>();
    List<List<String>> localNames = new ArrayList<>();
    for (Func f : funcList) {
      funcNames.add(f.name());
      localNames.add(f.params()); // parameter names for the local-names subsection
    }
    for (Native n : natives) {
      funcNames.add(n.name());
      localNames.add(List.of()); // runtime helpers have no source-level parameter names
    }
    nameSection(out, funcNames, localNames);
    return out.toByteArray();
  }

  /** Emits the WebAssembly "name" custom section: a module name plus a function-name map. */
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

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
import pl.matsuo.elm.error.Position;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.types.Infer;
import pl.matsuo.elm.types.Scheme;
import pl.matsuo.elm.types.Signatures;
import pl.matsuo.elm.types.Ty;
import pl.matsuo.elm.types.Types;

/**
 * A second WebAssembly backend that uses the <b>WasmGC</b> proposal — host-garbage-collected
 * {@code struct} references — instead of the linear-memory bump allocator of {@link WasmCompiler}.
 * A cons-list is a GC struct {@code {head : E, tail : (ref null self)}} (one cons type per element
 * type {@code E}), so building and
 * discarding lists is reclaimed by the engine's collector with no manual memory management.
 *
 * <p>The supported subset is monomorphic {@code Int}/{@code Bool}/{@code Float}/{@code String}
 * (an {@code array i8}: literals, {@code String.length} counted in UTF-16 units like the other
 * backends — a loop over the UTF-8 bytes counting lead bytes, +1 for a 4-byte astral lead — and {@code ++}),
 * <b>lists of any supported element</b> ({@code List Int}/{@code List Float}/{@code List} of
 * tuples/records/lists), <b>tuples</b>, <b>closed records</b> and <b>nullary custom types</b>:
 * integer and floating-point
 * arithmetic and comparisons ({@code Float} is an {@code f64}, with {@code /} and {@code f64}
 * compares), {@code if}, {@code let} (including {@code let (a, b) = …}), top-level functions and
 * recursion, list literals and {@code ::}, tuple construction with {@code Tuple.first}/{@code
 * Tuple.second} and tuple-pattern {@code case}, record literals / {@code .field} access / {@code
 * { r | f = v }} update, and a {@code case} over {@code []} / {@code head :: tail} or a nullary
 * union. Each function's parameter and result wasm types come from Hindley–Milner inference: an
 * {@code Int} (and a nullary custom type, as its variant tag) is an {@code i64}, a {@code Float} an
 * {@code f64}, a {@code List Int} the cons struct, and each tuple/record shape its own GC struct
 * (record fields laid out in sorted-name order), and a {@code String} an {@code array i8}.
 * <b>Argument-carrying custom types</b> (including recursive ones like {@code Tree}) compile to a
 * shared base {@code sub (struct {tag : i32})} with a subtype per constructor; a {@code case} reads
 * the tag and {@code ref.cast}s to the matching subtype to bind its arguments. <b>Polymorphic</b>
 * custom types (a constructor argument that is a type variable, e.g. {@code Box a = Wrap a | Empty})
 * are <b>monomorphised</b>: each constructor's field wasm types are read from how it is applied in
 * the module ({@code Wrap 5} fixes the field to an {@code i64}, {@code Wrap "x"} to a string ref,
 * {@code Wrap [1]} to a cons-list ref), so a union used at a single instantiation compiles; a union
 * used at two representations in one module has no single layout and is reported unsupported. The
 * built-in {@code Maybe}/{@code Result} are predeclared when used, so {@code Just}/{@code Nothing}/
 * {@code Ok}/{@code Err} compile through the same boxed-union path. <b>Row-polymorphic</b> (open)
 * record parameters have no fixed struct layout, and closures remain on the linear-memory backend —
 * extending this one to them, and finishing the {@code String} API (code-point-aware length, more
 * ops), is future work.
 *
 * <h2>First-class functions</h2>
 *
 * A function type {@code a -> b} is a reference to its unary functype (one rec group holds structs
 * <em>and</em> functypes, so {@code wOf(Ty.Arrow)} resolves during the pre-pass). A <b>unary
 * top-level function</b> used as a value compiles to {@code ref.func}; a <b>capture-free lambda</b>
 * (one whose free variables are all top-level names) is lambda-lifted to a top-level function and
 * likewise referenced by {@code ref.func}; applying such a value emits {@code call_ref} on its
 * functype. This makes higher-order code work — function parameters, lambdas as arguments, and
 * user-defined {@code map}/{@code filter}/etc. over GC lists.
 *
 * <p>Still unsupported, reported as a clear "unsupported" error: <b>lambdas that capture a local
 * variable</b>, and <b>currying / multi-argument function values</b> (passing or partially applying
 * a function of arity &gt; 1). Both need <b>closure structs</b>, which requires replacing the
 * bare-funcref representation above (a parameter of type {@code a -> b} must accept both a capturing
 * and a non-capturing function as one wasm type). The migration:
 *
 * <ol>
 *   <li>Per arrow signature, a base struct {@code $clos = (struct { fn : (ref $cc) })} and a calling
 *       convention {@code $cc = (ref $clos, wOf(a)) -> wOf(b)} (both in the shared rec group, so they
 *       reference each other); {@code wOf(Ty.Arrow)} returns {@code (ref $clos)}.
 *   <li>A top-level function {@code f} used as a value becomes {@code struct.new $clos (ref.func
 *       f$wrap)} where the generated {@code f$wrap(env, x) = f x} ignores the env.
 *   <li>A lambda becomes a <em>subtype</em> {@code (struct $clos { fn, cap0, … })} holding its
 *       captured locals; the lifted body {@code ref.cast}s the env to that subtype to read them.
 *   <li>Applying {@code f x}: push {@code f} (the env), the arg, then {@code struct.get f.fn}, and
 *       {@code call_ref $cc}; an under-applied call builds a closure capturing the supplied args.
 * </ol>
 *
 * The wasm encoding is the easy part; the rework is invasive (it touches {@code wOf}, lambda
 * lifting, application and the type-section emitter) and is best done as a focused change so the
 * already-working unary/capture-free path above stays green.
 */
public final class WasmGc {

  private static final int I64 = 0x7E;
  private static final int F64 = 0x7C;

  private WasmGc() {}

  /** A compiled function: name, parameters, result and body. For a lambda-lifted closure body,
   * {@code captureNames}/{@code captureVariant} let codegen bind the captured locals from the
   * closure's {@code env} (param 0) at entry; {@code captureVariant} is {@code -1} otherwise. */
  private record Func(String name, List<String> params, List<W> paramTypes, W result, Expr body,
      List<String> captureNames, int captureVariant) {
    Func(String name, List<String> params, List<W> paramTypes, W result, Expr body) {
      this(name, params, paramTypes, result, body, List.of(), -1);
    }
  }

  /** How a lambda is realised as a closure value: the lifted function's index, the closure-struct
   * variant to {@code struct.new}, and the captured local names (pushed as the variant's fields). */
  private record ClosurePlan(int funcIndex, int variantIndex, List<String> captures) {}

  /** A wasm value type in the supported subset: a scalar (i64/f64) or a reference to a GC struct
   * type. Each distinct tuple/record shape and each distinct list element type gets its own struct. */
  private sealed interface W permits Sca, Ref {}

  private record Sca(int valtype) implements W {} // I64 or F64

  private record Ref(int typeIndex) implements W {} // (ref null typeIndex)

  /** A GC type definition: a cons cell {@code {head : E, tail : (ref null self)}} for {@code List
   * E}, a plain struct (a tuple/record's fields), or the {@code array i8} backing a {@code String}. */
  private sealed interface StructDef
      permits ConsDef, PlainDef, StrArrayDef, AdtBaseDef, AdtVariantDef, FuncDef,
          ClosBaseDef, ClosVariantDef {}

  private record ConsDef(W head) implements StructDef {}

  private record PlainDef(List<W> fields) implements StructDef {}

  private record StrArrayDef() implements StructDef {} // array i8 (a UTF-8 string's bytes)

  /** The shared supertype of every argument-carrying custom-type value: {@code sub (struct {tag:i32})}. */
  private record AdtBaseDef() implements StructDef {}

  /** A constructor's subtype of {@link AdtBaseDef}: {@code {tag : i32, args…}}. */
  private record AdtVariantDef(int baseIndex, List<W> argFields) implements StructDef {}

  /** A function type {@code (params…) -> result}. Shares the type-index space with the structs (one
   * rec group), so {@code wOf(Ty.Arrow)} can refer to one during the pre-pass — the basis for the
   * closure-calling convention and {@code call_ref}. */
  private record FuncDef(List<W> params, W result) implements StructDef {}

  /** A closure base for one arrow signature: {@code sub (struct { fn : (ref null cc) })}, where
   * {@code cc} is the calling-convention functype {@code (ref base, arg) -> result}. Subtypable so a
   * capturing lambda can add capture fields, and instantiable for capture-free function values. */
  private record ClosBaseDef(int ccIndex) implements StructDef {}

  /** A closure subtype for a lambda that captures locals: {@code sub base (struct { fn, caps… })}. */
  private record ClosVariantDef(int baseIndex, int ccIndex, List<W> captures) implements StructDef {}

  private static final W INT = new Sca(I64);
  private static final W FLOAT = new Sca(F64);

  /** Compiles a module's monomorphic Int/List-Int functions to a WasmGC binary. */
  public static byte[] module(String source) {
    Module parsed = Parser.parseModule(source);
    Module module =
        new Module(
            parsed.name(),
            parsed.exposing(),
            parsed.imports(),
            pl.matsuo.elm.opt.ConstantFold.foldDecls(parsed.decls()),
            parsed.pos());
    Infer infer = new Infer();
    Map<String, Scheme> schemes = infer.inferModule(module, Signatures.globals());
    Map<Expr, Ty> nodeTypes = infer.nodeTypes();

    // Pre-pass: register every tuple shape (innermost first) so it has a stable struct type index
    // before any function body is compiled.
    Tuples tuples = new Tuples();
    // The unions to compile: those declared in the module, plus the built-in `Maybe`/`Result` when
    // the program uses their constructors (so `Just`/`Nothing`/`Ok`/`Err` compile like a user union).
    List<Decl.Union> unions = effectiveUnions(module, nodeTypes);
    // Classify unions: all-nullary -> i64-tagged enum; otherwise -> a boxed ADT (base + subtypes).
    for (Decl.Union u : unions) {
      if (u.variants().stream().allMatch(v -> v.args().isEmpty())) {
        tuples.registerEnum(u.name(), u.variants().stream().map(Decl.Union.Variant::name).toList());
      } else {
        tuples.markBoxed(u.name()); // mark all boxed first so recursive arg types resolve
      }
    }
    // Concrete field layouts for (possibly polymorphic) boxed constructors, read from how they are
    // *applied* in this program: at `Just 5` the argument's inferred type (Int) fixes Just's field to
    // an i64; at `Just "x"` it fixes it to a string ref. This monomorphises polymorphic unions
    // (`Maybe a` / `Result e a` / user unions) to their single concrete instantiation in the module.
    Map<String, List<W>> inferredCtorFields =
        inferredConstructorFields(unions, nodeTypes, tuples);
    // Now register each boxed constructor's subtype (its argument wasm types): the inferred concrete
    // layout when the constructor is applied somewhere, else its declared (concrete) surface types.
    for (Decl.Union u : unions) {
      if (tuples.isBoxed(u.name())) {
        List<Decl.Union.Variant> vs = u.variants();
        for (int i = 0; i < vs.size(); i++) {
          List<W> argFields = inferredCtorFields.get(vs.get(i).name());
          if (argFields == null) {
            argFields = new ArrayList<>();
            for (pl.matsuo.elm.ast.Type arg : vs.get(i).args()) {
              argFields.add(wOfSurface(arg, tuples));
            }
          }
          tuples.registerVariant(vs.get(i).name(), i, argFields);
        }
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
    java.util.Set<String> topLevel = new java.util.HashSet<>();
    Map<String, Integer> arity = new HashMap<>();
    for (Func f : funcs) {
      topLevel.add(f.name());
      arity.put(f.name(), f.params().size());
    }
    // A top-level (unary) function used as a *value* gets a wrapper `f$w(env, x) = f x` so it can
    // become a closure {fn = ref.func f$w}; record name -> wrapper index.
    java.util.Set<String> valueUses = new java.util.HashSet<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && schemes.containsKey(v.name())) {
        collectValueUses(v.body(), arity, valueUses);
      }
    }
    Map<String, Integer> wrappers = new HashMap<>();
    Position p0 = new Position(0, 0, 0);
    for (int i = 0, k = funcs.size(); i < k; i++) {
      Func f = funcs.get(i);
      if (f.params().size() == 1 && valueUses.contains(f.name())) {
        int base = tuples.closureBaseIndex(f.paramTypes().get(0), f.result());
        wrappers.put(f.name(), funcs.size());
        funcs.add(
            new Func(f.name() + "$w", List.of("$env", "$x"),
                List.of(new Ref(base), f.paramTypes().get(0)), f.result(),
                new Expr.App(new Expr.Var(null, f.name(), p0), new Expr.Var(null, "$x", p0), p0)));
      }
    }
    // Lift unary lambdas to top-level functions (capture-free ones, and capturing ones whose captures
    // become closure-struct fields), so they can be passed and applied like any function value.
    Map<Expr.Lambda, ClosurePlan> liftedLambda = new java.util.IdentityHashMap<>();
    int[] counter = {0};
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && schemes.containsKey(v.name())) {
        collectLambdas(v.body(), topLevel, nodeTypes, tuples, funcs, liftedLambda, counter);
      }
    }
    // Eta-expand named multi-argument functions used as a value (`List.foldl add`) or partially
    // applied (`List.map (add 1)`) into closures: synthesise `\p… -> f args… p…`, lift it like any
    // lambda, and remember the resulting plan so the use site builds that closure.
    Map<String, ClosurePlan> valueClosures = new HashMap<>();
    for (String name : valueUses) {
      Integer ar = arity.get(name);
      if (ar != null && ar >= 2 && schemes.containsKey(name) && !valueClosures.containsKey(name)) {
        Expr.Lambda lam =
            etaExpand(name, Types.prune(schemes.get(name).body()), List.of(), ar, nodeTypes, counter, p0);
        collectLambdas(lam, topLevel, nodeTypes, tuples, funcs, liftedLambda, counter);
        if (liftedLambda.get(lam) != null) {
          valueClosures.put(name, liftedLambda.get(lam));
        }
      }
    }
    Map<Expr.App, ClosurePlan> partialClosures = new java.util.IdentityHashMap<>();
    List<Expr.App> partials = new ArrayList<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && schemes.containsKey(v.name())) {
        collectPartialApps(v.body(), arity, partials);
      }
    }
    for (Expr.App pa : partials) {
      List<Expr> args = new ArrayList<>();
      Expr head = pa;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      String fn = ((Expr.Var) head).name();
      if (schemes.containsKey(fn)) {
        Expr.Lambda lam =
            etaExpand(fn, Types.prune(schemes.get(fn).body()), args, arity.get(fn), nodeTypes, counter, p0);
        collectLambdas(lam, topLevel, nodeTypes, tuples, funcs, liftedLambda, counter);
        if (liftedLambda.get(lam) != null) {
          partialClosures.put(pa, liftedLambda.get(lam));
        }
      }
    }

    // All structs are now registered; register each function's own signature functype after them, so
    // functypes occupy the type indices above the structs (one shared rec group).
    for (Func f : funcs) {
      tuples.funcTypeIndex(f.paramTypes(), f.result());
    }
    return assemble(funcs, nodeTypes, tuples, liftedLambda, wrappers, valueClosures, partialClosures);
  }

  /** Builds `\p… -> f fixedArgs… p…` (nested unary lambdas) for eta-expanding a named function used
   * as a value or partially applied; registers the synthesised nodes' types so they compile. */
  private static Expr.Lambda etaExpand(
      String fname, Ty fType, List<Expr> fixedArgs, int arity, Map<Expr, Ty> nodeTypes,
      int[] counter, Position p0) {
    int k = fixedArgs.size();
    Expr cur = new Expr.Var(null, fname, p0);
    nodeTypes.put(cur, fType);
    for (int i = 0; i < k; i++) {
      cur = new Expr.App(cur, fixedArgs.get(i), p0);
      nodeTypes.put(cur, resultType(fType, i + 1));
    }
    List<String> params = new ArrayList<>();
    for (int i = k; i < arity; i++) {
      // Lowercase-initial (so FreeVars treats it as a capturable free var) but containing `$`
      // (which no user identifier can), so it never collides with a program variable.
      String pn = "eta$" + (counter[0]++);
      params.add(pn);
      Expr pv = new Expr.Var(null, pn, p0);
      nodeTypes.put(pv, arrowParam(fType, i));
      cur = new Expr.App(cur, pv, p0);
      nodeTypes.put(cur, resultType(fType, i + 1));
    }
    Expr inner = cur;
    for (int i = params.size() - 1; i >= 0; i--) {
      Expr.Lambda lam = new Expr.Lambda(List.of(new Pattern.Var(params.get(i))), inner, p0);
      nodeTypes.put(lam, resultType(fType, k + i));
      inner = lam;
    }
    return (Expr.Lambda) inner;
  }

  /** Collects the application nodes that under-apply a named top-level function (a partial call). */
  private static void collectPartialApps(Expr e, Map<String, Integer> arity, List<Expr.App> out) {
    if (e instanceof Expr.App app) {
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      if (head instanceof Expr.Var v
          && v.module() == null
          && arity.containsKey(v.name())
          && args.size() < arity.get(v.name())) {
        out.add(app);
      }
      for (Expr arg : args) {
        collectPartialApps(arg, arity, out);
      }
      if (!(head instanceof Expr.Var hv && arity.containsKey(hv.name()))) {
        collectPartialApps(head, arity, out);
      }
      return;
    }
    for (Expr child : children(e)) {
      collectPartialApps(child, arity, out);
    }
  }

  /** Top-level functions used as a value (not as the head of a full-arity application): they need a
   * closure wrapper. The head of a complete call is a direct call, so it isn't counted. */
  private static void collectValueUses(Expr e, Map<String, Integer> arity, java.util.Set<String> out) {
    if (e instanceof Expr.App app) {
      List<Expr> args = new ArrayList<>();
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      for (Expr arg : args) {
        collectValueUses(arg, arity, out);
      }
      boolean fullCall =
          head instanceof Expr.Var v && v.module() == null && arity.containsKey(v.name())
              && arity.get(v.name()) == args.size();
      if (!fullCall) {
        collectValueUses(head, arity, out); // head is itself a value (partial app, or an expr)
      }
      return;
    }
    if (e instanceof Expr.Var v && v.module() == null && arity.getOrDefault(v.name(), 0) >= 1) {
      out.add(v.name());
      return;
    }
    for (Expr child : children(e)) {
      collectValueUses(child, arity, out);
    }
  }

  /**
   * Lifts every capture-free unary lambda reachable in {@code e} to a fresh top-level {@link Func}
   * (appended to {@code funcs}) and records it in {@code lifted} (lambda → its function index). A
   * lambda is liftable when it has one variable parameter and every free variable is a top-level
   * name (so it captures no local) — then it is just a named function. Recurses into bodies for
   * nested lambdas regardless.
   */
  /** The inferred type of a captured variable {@code name}, taken from an occurrence of it inside
   * {@code e} that inference typed; {@code null} if none is found (then the lambda isn't lifted). */
  private static Ty captureType(Expr e, String name, Map<Expr, Ty> nodeTypes) {
    if (e instanceof Expr.Var v && v.module() == null && v.name().equals(name) && nodeTypes.containsKey(e)) {
      return Types.prune(nodeTypes.get(e));
    }
    for (Expr child : children(e)) {
      Ty t = captureType(child, name, nodeTypes);
      if (t != null) {
        return t;
      }
    }
    return null;
  }

  /** The immediate sub-expressions of {@code e} (for generic AST walks). */
  private static List<Expr> children(Expr e) {
    return switch (e) {
      case Expr.App a -> List.of(a.fn(), a.arg());
      case Expr.BinOp b -> List.of(b.left(), b.right());
      case Expr.If i -> List.of(i.cond(), i.thenBranch(), i.elseBranch());
      case Expr.Negate n -> List.of(n.operand());
      case Expr.Lambda l -> List.of(l.body());
      case Expr.Let let -> {
        List<Expr> out = new ArrayList<>();
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            out.add(v.body());
          } else if (d instanceof Decl.Destructure de) {
            out.add(de.body());
          }
        }
        out.add(let.body());
        yield out;
      }
      case Expr.Case c -> {
        List<Expr> out = new ArrayList<>();
        out.add(c.scrutinee());
        c.branches().forEach(br -> out.add(br.body()));
        yield out;
      }
      case Expr.ListLit l -> l.items();
      case Expr.Tuple t -> t.items();
      case Expr.Record r -> r.fields().stream().map(Expr.Record.Field::value).toList();
      case Expr.RecordAccess a -> List.of(a.target());
      case Expr.RecordUpdate u -> u.fields().stream().map(Expr.Record.Field::value).toList();
      default -> List.of();
    };
  }

  private static void collectLambdas(
      Expr e, java.util.Set<String> topLevel, Map<Expr, Ty> nodeTypes, Tuples tuples,
      List<Func> funcs, Map<Expr.Lambda, ClosurePlan> lifted, int[] counter) {
    switch (e) {
      case Expr.Lambda lam -> {
        Ty lt = nodeTypes.get(lam) == null ? null : Types.prune(nodeTypes.get(lam));
        if (lam.params().size() > 1 && lt != null) {
          // Desugar `\a b … -> e` to nested unary lambdas `\a -> \b -> … -> e` (typing each one from
          // the arrow chain), then lift those; map the original lambda to the outer closure plan.
          Expr desugared = lam.body();
          for (int i = lam.params().size() - 1; i >= 0; i--) {
            desugared = new Expr.Lambda(List.of(lam.params().get(i)), desugared, lam.pos());
            nodeTypes.put(desugared, resultType(lt, i));
          }
          collectLambdas(desugared, topLevel, nodeTypes, tuples, funcs, lifted, counter);
          if (lifted.containsKey((Expr.Lambda) desugared)) {
            lifted.put(lam, lifted.get((Expr.Lambda) desugared));
          }
          return;
        }
        if (lam.params().size() == 1 && lam.params().get(0) instanceof Pattern.Var pv
            && lt instanceof Ty.Arrow arr) {
          // Lift the unary lambda to a top-level function `lam(env, x)`. Its free locals (anything not
          // a top-level name) are captured: read from `env` at entry, and stored in the closure when
          // the lambda value is built. Bail (leave unsupported) if a capture's type can't be resolved.
          List<String> captures = new ArrayList<>();
          List<W> captureTypes = new ArrayList<>();
          boolean ok = true;
          for (String f : pl.matsuo.elm.ast.FreeVars.of(lam)) {
            if (topLevel.contains(f)) {
              continue;
            }
            Ty ct = captureType(lam.body(), f, nodeTypes);
            if (ct == null) {
              ok = false;
              break;
            }
            captures.add(f);
            captureTypes.add(wOf(ct, tuples));
          }
          if (ok) {
            int base = tuples.closureBaseIndex(wOf(arr.from(), tuples), wOf(arr.to(), tuples));
            // Capture-free lambdas use the base struct directly; capturing ones use a subtype that
            // adds the capture fields (and the lifted body reads them from `env` at entry).
            int structIdx = captures.isEmpty() ? base : tuples.closureVariantIndex(base, captureTypes);
            int prologueVariant = captures.isEmpty() ? -1 : structIdx;
            lifted.put(lam, new ClosurePlan(funcs.size(), structIdx, captures));
            funcs.add(
                new Func("lam$" + counter[0]++, List.of("$env", pv.name()),
                    List.of(new Ref(base), wOf(arr.from(), tuples)), wOf(arr.to(), tuples),
                    lam.body(), captures, prologueVariant));
          }
        }
        collectLambdas(lam.body(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      }
      case Expr.App a -> {
        collectLambdas(a.fn(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
        collectLambdas(a.arg(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      }
      case Expr.BinOp b -> {
        collectLambdas(b.left(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
        collectLambdas(b.right(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      }
      case Expr.If i -> {
        collectLambdas(i.cond(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
        collectLambdas(i.thenBranch(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
        collectLambdas(i.elseBranch(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      }
      case Expr.Negate n -> collectLambdas(n.operand(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            collectLambdas(v.body(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
          } else if (d instanceof Decl.Destructure de) {
            collectLambdas(de.body(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
          }
        }
        collectLambdas(let.body(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      }
      case Expr.Case c -> {
        collectLambdas(c.scrutinee(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
        for (Expr.Case.Branch br : c.branches()) {
          collectLambdas(br.body(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
        }
      }
      case Expr.ListLit l -> l.items().forEach(x -> collectLambdas(x, topLevel, nodeTypes, tuples, funcs, lifted, counter));
      case Expr.Tuple t -> t.items().forEach(x -> collectLambdas(x, topLevel, nodeTypes, tuples, funcs, lifted, counter));
      case Expr.Record r -> r.fields().forEach(f -> collectLambdas(f.value(), topLevel, nodeTypes, tuples, funcs, lifted, counter));
      case Expr.RecordAccess a -> collectLambdas(a.target(), topLevel, nodeTypes, tuples, funcs, lifted, counter);
      case Expr.RecordUpdate u -> u.fields().forEach(f -> collectLambdas(f.value(), topLevel, nodeTypes, tuples, funcs, lifted, counter));
      default -> {}
    }
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
      return new Ref(tuples.consIndexOf(wOf(listElem(c), tuples)));
    }
    if (p instanceof Ty.Con c && c.name().equals("String")) {
      return new Ref(tuples.strIndex());
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
    if (p instanceof Ty.Con c && tuples.isBoxed(c.name())) {
      return new Ref(tuples.adtBaseIndex()); // an argument-carrying custom type: a ref to the base
    }
    if (p instanceof Ty.Arrow arrow) {
      // A first-class function value: a closure (ref to its per-signature base struct). A curried
      // multi-argument type `a -> b -> c` nests (its result is itself a closure).
      return new Ref(
          tuples.closureBaseIndex(wOf(arrow.from(), tuples), wOf(arrow.to(), tuples)));
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

  /** The element type of a {@code List E} (defaulting to {@code Int} when the element is unresolved,
   * e.g. an empty list literal, matching the monomorphic subset's default). */
  private static Ty listElem(Ty.Con listCon) {
    return listCon.args().isEmpty() ? new Ty.Con("Int", List.of()) : listCon.args().get(0);
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

  /** A constructor argument's wasm type, from its declared surface {@link pl.matsuo.elm.ast.Type}
   * (concrete subset only — a type variable means a polymorphic union, which isn't supported). */
  private static W wOfSurface(pl.matsuo.elm.ast.Type t, Tuples tuples) {
    if (t instanceof pl.matsuo.elm.ast.Type.Con c) {
      return switch (c.name()) {
        case "Int", "Bool" -> INT;
        case "Float" -> FLOAT;
        case "String" -> new Ref(tuples.strIndex());
        case "List" -> new Ref(tuples.consIndexOf(wOfSurface(c.args().get(0), tuples)));
        default -> {
          if (tuples.isEnum(c.name())) {
            yield INT;
          }
          if (tuples.isBoxed(c.name())) {
            yield new Ref(tuples.adtBaseIndex());
          }
          throw unsupported("constructor argument of type " + c.name());
        }
      };
    }
    throw unsupported("a polymorphic or compound constructor argument");
  }

  /**
   * The concrete wasm field layout of each boxed constructor as it is <em>applied</em> in the module:
   * for every full constructor application {@code Ctor a b …}, the arguments' inferred types fix the
   * fields' wasm types. This is what lets a polymorphic union (e.g. {@code Maybe a}) compile — it is
   * monomorphised to the one instantiation the program uses. A constructor applied at two different
   * representations in the same module has no single layout, so that is reported as unsupported.
   */
  private static Map<String, List<W>> inferredConstructorFields(
      List<Decl.Union> unions, Map<Expr, Ty> nodeTypes, Tuples tuples) {
    Map<String, Integer> arity = new HashMap<>();
    for (Decl.Union u : unions) {
      if (tuples.isBoxed(u.name())) {
        for (Decl.Union.Variant va : u.variants()) {
          arity.put(va.name(), va.args().size());
        }
      }
    }
    Map<String, List<W>> out = new HashMap<>();
    for (Expr e : nodeTypes.keySet()) {
      if (!(e instanceof Expr.App)) {
        continue;
      }
      List<Expr> args = new ArrayList<>();
      Expr head = e;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        head = a.fn();
      }
      if (!(head instanceof Expr.Ctor ct)) {
        continue;
      }
      Integer n = arity.get(ct.name());
      if (n == null || args.size() != n) {
        continue; // not a full application of a boxed constructor
      }
      List<W> fields = new ArrayList<>();
      boolean ok = true;
      for (Expr arg : args) {
        Ty at = nodeTypes.get(arg);
        if (at == null) {
          ok = false;
          break;
        }
        fields.add(wOf(at, tuples));
      }
      if (!ok) {
        continue;
      }
      List<W> prev = out.put(ct.name(), fields);
      if (prev != null && !prev.equals(fields)) {
        throw unsupported(
            "a polymorphic custom type instantiated at more than one representation (constructor "
                + ct.name() + ")");
      }
    }
    return out;
  }

  /**
   * The unions to compile: every union the module declares, plus the built-in {@code Maybe a} /
   * {@code Result e a} when the program uses their constructors (and doesn't shadow them with its
   * own declaration). They are predeclared so {@code Just}/{@code Nothing}/{@code Ok}/{@code Err}
   * compile like any other boxed (and monomorphised) custom type.
   */
  private static List<Decl.Union> effectiveUnions(Module module, Map<Expr, Ty> nodeTypes) {
    List<Decl.Union> unions = new ArrayList<>();
    java.util.Set<String> declared = new java.util.HashSet<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        unions.add(u);
        declared.add(u.name());
      }
    }
    java.util.Set<String> usedCtors = new java.util.HashSet<>();
    for (Expr e : nodeTypes.keySet()) {
      if (e instanceof Expr.Ctor ct) {
        usedCtors.add(ct.name());
      }
    }
    if (!declared.contains("Maybe") && (usedCtors.contains("Just") || usedCtors.contains("Nothing"))) {
      unions.add(builtinUnion("Maybe", List.of("a"), "Just", "a", "Nothing", null));
    }
    if (!declared.contains("Result") && (usedCtors.contains("Ok") || usedCtors.contains("Err"))) {
      unions.add(builtinUnion("Result", List.of("e", "a"), "Ok", "a", "Err", "e"));
    }
    return unions;
  }

  /** A two-variant built-in union {@code type Name params = C1 t1 | C2 t2} (a null payload type
   *  means a nullary variant). Surface arg types are type variables — the actual field layout comes
   *  from {@link #inferredConstructorFields}, since the program always applies these constructors. */
  private static Decl.Union builtinUnion(
      String name, List<String> params, String c1, String t1, String c2, String t2) {
    Position p = new Position(0, 0, 0);
    List<pl.matsuo.elm.ast.Type> a1 =
        t1 == null ? List.of() : List.of(new pl.matsuo.elm.ast.Type.Var(t1));
    List<pl.matsuo.elm.ast.Type> a2 =
        t2 == null ? List.of() : List.of(new pl.matsuo.elm.ast.Type.Var(t2));
    return new Decl.Union(
        name,
        params,
        List.of(new Decl.Union.Variant(c1, a1), new Decl.Union.Variant(c2, a2)),
        p);
  }

  /**
   * The distinct tuple shapes a module uses, each assigned a GC struct type index (the cons list is
   * type 0, tuples are 1..T, function signatures come after). A pre-pass registers every tuple that
   * appears in a signature or inferred sub-expression type, innermost first, so that nested tuples
   * always have a lower index than the tuples that contain them.
   */
  private static final class Tuples {
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
          registerAll(listElem(c));
          consIndexOf(wOf(listElem(c), this));
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
        fields.add(wOf(it, this));
      }
      return register("T" + keyOf(fields), new PlainDef(fields));
    }

    /** The struct type index for a record shape (fields in sorted-name order, like the linear-memory
     * backend), registering it if new. Lists/records/tuples share the struct space but never collide
     * (their keys are prefixed), so field-by-index access stays well defined. */
    int recordIndexOf(Ty.Record rec) {
      List<W> fields = new ArrayList<>();
      StringBuilder names = new StringBuilder("R");
      for (String name : sortedFields(rec)) {
        names.append(name).append(':');
        fields.add(wOf(rec.fields().get(name), this));
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
     * calling-convention functype {@code (ref base, arg) -> result}) if new. {@code wOf(Ty.Arrow)}
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
    private final Map<Expr.Lambda, ClosurePlan> lifted; // lambda -> how to build its closure value
    private final Map<String, Integer> wrappers; // top-level fn name -> its closure-wrapper func index
    private final Map<String, ClosurePlan> valueClosures; // multi-arg fn name -> its eta closure plan
    private final Map<Expr.App, ClosurePlan> partialClosures; // partial-call node -> its eta closure
    private final List<String> captureNames; // captured locals to bind from env (closure bodies)
    private final int captureVariant; // the closure subtype to ref.cast env to, or -1
    private final Map<String, Integer> locals = new HashMap<>();
    private final Map<String, W> localTypes = new HashMap<>();
    private final List<W> extraLocals = new ArrayList<>(); // beyond params
    private final int numParams;
    private final ByteArrayOutputStream code = new ByteArrayOutputStream();

    Gen(Map<String, int[]> funcs, Map<String, W> funcResult, Map<Expr, Ty> nodeTypes, Tuples tuples,
        Map<Expr.Lambda, ClosurePlan> lifted, Map<String, Integer> wrappers,
        Map<String, ClosurePlan> valueClosures, Map<Expr.App, ClosurePlan> partialClosures, Func f) {
      this.funcs = funcs;
      this.funcResult = funcResult;
      this.nodeTypes = nodeTypes;
      this.tuples = tuples;
      this.lifted = lifted;
      this.wrappers = wrappers;
      this.valueClosures = valueClosures;
      this.partialClosures = partialClosures;
      this.captureNames = f.captureNames();
      this.captureVariant = f.captureVariant();
      this.numParams = f.params().size();
      for (int i = 0; i < f.params().size(); i++) {
        locals.put(f.params().get(i), i);
        localTypes.put(f.params().get(i), f.paramTypes().get(i));
      }
    }

    byte[] compile(Expr body) {
      // A closure body: bind each captured local by reading it from `env` (param 0) cast to the
      // closure's subtype.
      if (captureVariant >= 0 && !captureNames.isEmpty()) {
        List<W> capTypes = tuples.closureVariantCaptures(captureVariant);
        int cl = freshLocal("$clenv", new Ref(captureVariant));
        get(0); // env (param 0)
        code.write(0xFB);
        code.write(0x17); // ref.cast (ref variant)
        sleb(code, captureVariant);
        setLocal(cl);
        for (int i = 0; i < captureNames.size(); i++) {
          int idx = freshLocal(captureNames.get(i), capTypes.get(i));
          get(cl);
          code.write(0xFB);
          code.write(0x02); // struct.get
          leb(code, captureVariant);
          leb(code, i + 1); // field 0 is fn; captures start at 1
          setLocal(idx);
        }
      }
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

    /** Restores the name→local maps to a snapshot (the allocated wasm slots stay; only the lexical
     * bindings are rolled back), so a `let`'s names — including shadows — don't escape its body. */
    private void restoreScope(Map<String, Integer> savedL, Map<String, W> savedT) {
      locals.clear();
      locals.putAll(savedL);
      localTypes.clear();
      localTypes.putAll(savedT);
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
        case Expr.Ctor c when tuples.variantOf(c.name()) != null -> {
          // A nullary boxed constructor: struct.new $variant (just the i32 tag).
          int[] v = tuples.variantOf(c.name());
          code.write(0x41);
          sleb(code, v[1]); // i32.const tag
          code.write(0xFB);
          code.write(0x00);
          leb(code, v[0]); // struct.new subtype
        }
        case Expr.FloatLit lit -> emitF64(lit.value());
        case Expr.StrLit lit -> {
          // array.new_fixed $str <bytes…>: push each UTF-8 byte as an i32, then build the array.
          byte[] bytes = lit.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
          for (byte b : bytes) {
            code.write(0x41); // i32.const
            sleb(code, b & 0xFF);
          }
          code.write(0xFB);
          code.write(0x08); // array.new_fixed
          leb(code, tupleIndex(nodeType(lit)));
          leb(code, bytes.length);
        }
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
          Map<String, Integer> savedL = new HashMap<>(locals);
          Map<String, W> savedT = new HashMap<>(localTypes);
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
          restoreScope(savedL, savedT); // a let's bindings don't escape (handles `let x` shadowing)
        }
        case Expr.Var v -> {
          Integer idx = locals.get(v.name());
          if (idx != null) {
            code.write(0x20);
            leb(code, idx);
          } else if (funcs.containsKey(v.name()) && funcs.get(v.name())[1] == 0) {
            code.write(0x10);
            leb(code, funcs.get(v.name())[0]);
          } else if (funcs.containsKey(v.name()) && funcs.get(v.name())[1] == 1
              && wrappers.containsKey(v.name())) {
            // A unary top-level function used as a value: a closure {fn = ref.func f$wrap}.
            int base = ((Ref) wOf(nodeType(v), tuples)).typeIndex();
            code.write(0xD2); // ref.func -> the wrapper
            leb(code, wrappers.get(v.name()));
            code.write(0xFB);
            code.write(0x00); // struct.new $base
            leb(code, base);
          } else if (valueClosures.containsKey(v.name())) {
            // A multi-argument top-level function used as a value: its eta-expanded closure.
            emitClosure(valueClosures.get(v.name()));
          } else if (funcs.containsKey(v.name())) {
            // Reached only if a value-use wasn't pre-collected (it should have been eta-expanded).
            throw unsupported("the function `" + v.name() + "` used as a value here");
          } else {
            throw unsupported("variable " + v.name());
          }
        }
        case Expr.ListLit l -> emitList(l.items(), 0, tupleIndex(nodeType(l)));
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
        case Expr.Lambda lam -> {
          ClosurePlan plan = lifted.get(lam);
          if (plan == null) {
            throw unsupported("this lambda (unary lambdas with resolvable captures are supported on WasmGC)");
          }
          emitClosure(plan);
        }
        default -> throw unsupported(e.getClass().getSimpleName());
      }
    }

    /** Emits a closure value: {@code struct.new $variant [ ref.func lifted, capture0, … ]}. */
    private void emitClosure(ClosurePlan plan) {
      code.write(0xD2); // ref.func -> the lifted function
      leb(code, plan.funcIndex());
      Position p0 = new Position(0, 0, 0);
      for (String cap : plan.captures()) {
        gen(new Expr.Var(null, cap, p0)); // read the captured local from the enclosing scope
      }
      code.write(0xFB);
      code.write(0x00); // struct.new
      leb(code, plan.variantIndex());
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
        // struct.new $cons (head : E, tail : (ref null self)) — the cons type for this list.
        gen(b.left());
        gen(b.right());
        code.write(0xFB);
        code.write(0x00);
        leb(code, tupleIndex(nodeType(b)));
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
        case "++" -> {
          if (Types.prune(nodeType(b)) instanceof Ty.Con c && c.name().equals("List")) {
            listAppend(b);
          } else {
            strConcat(b);
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

    /** {@code a ++ b} for strings: a fresh mutable byte array of len(a)+len(b), with a then b
     * copied in (array.copy). Uses only ref locals; lengths are recomputed via array.len. */
    /**
     * Compiles {@code a ++ b} on lists. Cons cells are immutable GC structs, so we can't patch a
     * tail in place: we first reverse {@code a} (cons each head onto an accumulator), then cons those
     * reversed heads onto {@code b} — yielding {@code a ++ b} with {@code b}'s spine shared.
     */
    private void listAppend(Expr.BinOp b) {
      Ty listTy = Types.prune(nodeType(b));
      int ci = tupleIndex(listTy); // this list's cons struct type
      W elemW = listTy instanceof Ty.Con lc ? wOf(listElem(lc), tuples) : INT;
      Ref consRef = new Ref(ci);
      int cur = freshLocal("$apc" + code.size(), consRef); // cursor over the left list
      int rev = freshLocal("$apr" + code.size(), consRef); // left list, reversed
      int res = freshLocal("$aps" + code.size(), consRef); // the result being built
      int hd = freshLocal("$aph" + code.size(), elemW); // a head, in transit

      gen(b.left());
      setLocal(cur);
      code.write(0xD0); // ref.null ci  -> rev = []
      leb(code, ci);
      setLocal(rev);
      reverseSpine(cur, rev, hd, ci); // rev = reverse(left)

      gen(b.right());
      setLocal(res);
      reverseSpine(rev, res, hd, ci); // res = reverse(rev) prepended onto right = left ++ right

      get(res);
    }

    /** Emits a loop draining the list in local {@code src}, consing each head onto local {@code acc}
     *  (so {@code acc} ends as the reversal of {@code src} prepended to its initial value). */
    private void reverseSpine(int src, int acc, int hd, int ci) {
      code.write(0x02); // block void
      code.write(0x40);
      code.write(0x03); // loop void
      code.write(0x40);
      get(src);
      code.write(0xD1); // ref.is_null
      code.write(0x0D); // br_if 1 -> exit block when src is empty
      leb(code, 1);
      get(src); // hd = src.head
      structGet(ci, 0);
      setLocal(hd);
      get(hd); // acc = cons(hd, acc)
      get(acc);
      code.write(0xFB);
      code.write(0x00); // struct.new ci
      leb(code, ci);
      setLocal(acc);
      get(src); // src = src.tail
      structGet(ci, 1);
      setLocal(src);
      code.write(0x0C); // br 0 -> loop
      leb(code, 0);
      code.write(0x0B); // end loop
      code.write(0x0B); // end block
    }

    /** {@code struct.get ci field} on the cons ref on the stack. */
    private void structGet(int ci, int field) {
      code.write(0xFB);
      code.write(0x02); // struct.get
      leb(code, ci);
      leb(code, field);
    }

    private void strConcat(Expr.BinOp b) {
      int si = tupleIndex(nodeType(b)); // the string array type
      int a = freshLocal("$sa" + code.size(), new Ref(si));
      gen(b.left());
      code.write(0x21);
      leb(code, a);
      int bb = freshLocal("$sb" + code.size(), new Ref(si));
      gen(b.right());
      code.write(0x21);
      leb(code, bb);
      int res = freshLocal("$sr" + code.size(), new Ref(si));
      // res = array.new_default $str (len a + len b)
      arrayLen(a);
      arrayLen(bb);
      code.write(0x6A); // i32.add
      code.write(0xFB);
      code.write(0x07); // array.new_default
      leb(code, si);
      code.write(0x21);
      leb(code, res);
      // array.copy res 0 a 0 (len a)
      get(res);
      i32const(0);
      get(a);
      i32const(0);
      arrayLen(a);
      arrayCopy(si);
      // array.copy res (len a) b 0 (len b)
      get(res);
      arrayLen(a);
      get(bb);
      i32const(0);
      arrayLen(bb);
      arrayCopy(si);
      get(res);
    }

    private void get(int local) {
      code.write(0x20);
      leb(code, local);
    }

    private void i32const(int v) {
      code.write(0x41);
      sleb(code, v);
    }

    private void arrayLen(int local) {
      get(local);
      code.write(0xFB);
      code.write(0x0F); // array.len
    }

    private void setLocal(int local) {
      code.write(0x21);
      leb(code, local);
    }

    /**
     * Consumes a string ({@code array i8}) on the stack and leaves its length as an i64 — counted in
     * UTF-16 code units, matching {@code String.length} on the other backends (Java/JS strings). Each
     * UTF-8 lead byte ({@code (b & 0xC0) != 0x80}) is one unit, and a 4-byte (astral) lead
     * ({@code (b & 0xF8) == 0xF0}) is a surrogate pair, so it counts one extra.
     */
    private void emitCodePointCount() {
      int s = freshLocal("$cpStr" + code.size(), new Ref(tuples.strIndex()));
      int n = freshLocal("$cpLen" + code.size(), new Sca(0x7F)); // i32
      int i = freshLocal("$cpIdx" + code.size(), new Sca(0x7F));
      int c = freshLocal("$cpCnt" + code.size(), new Sca(0x7F));
      int b = freshLocal("$cpByte" + code.size(), new Sca(0x7F));
      setLocal(s); // the string ref was on the stack
      arrayLen(s);
      setLocal(n);
      i32const(0);
      setLocal(i);
      i32const(0);
      setLocal(c);
      code.write(0x02); // block void
      code.write(0x40);
      code.write(0x03); // loop void
      code.write(0x40);
      get(i);
      get(n);
      code.write(0x4E); // i32.ge_s
      code.write(0x0D); // br_if
      leb(code, 1); // -> exit the block when i >= n
      get(s);
      get(i);
      code.write(0xFB);
      code.write(0x0D); // array.get_u (unsigned byte from the packed i8 array)
      leb(code, tuples.strIndex());
      setLocal(b);
      get(b);
      i32const(0xC0);
      code.write(0x71); // i32.and
      i32const(0x80);
      code.write(0x47); // i32.ne  -> 1 for a (non-continuation) lead byte
      get(b);
      i32const(0xF8);
      code.write(0x71); // i32.and
      i32const(0xF0);
      code.write(0x46); // i32.eq  -> 1 for a 4-byte lead (a surrogate pair: +1 UTF-16 unit)
      code.write(0x6A); // i32.add  -> 1 or 2 for a lead byte, 0 for a continuation
      get(c);
      code.write(0x6A); // i32.add
      setLocal(c);
      get(i);
      i32const(1);
      code.write(0x6A); // i32.add
      setLocal(i);
      code.write(0x0C); // br
      leb(code, 0); // -> loop
      code.write(0x0B); // end loop
      code.write(0x0B); // end block
      get(c);
      code.write(0xAD); // i64.extend_i32_u
    }

    private void arrayCopy(int typeIdx) {
      code.write(0xFB);
      code.write(0x11); // array.copy
      leb(code, typeIdx); // dest type
      leb(code, typeIdx); // src type
    }

    private void emitList(List<Expr> items, int i, int consIndex) {
      if (i >= items.size()) {
        code.write(0xD0); // ref.null
        leb(code, consIndex); // heaptype: this list's cons type
        return;
      }
      gen(items.get(i));
      emitList(items, i + 1, consIndex);
      code.write(0xFB);
      code.write(0x00);
      leb(code, consIndex); // struct.new
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
      List<Expr> appNodes = new ArrayList<>(); // appNodes.get(i) = head applied through args.get(i)
      Expr head = app;
      while (head instanceof Expr.App a) {
        args.add(0, a.arg());
        appNodes.add(0, a);
        head = a.fn();
      }
      // A partial application of a named function (`add 1`): build its eta-expanded closure.
      if (partialClosures.containsKey(app)) {
        emitClosure(partialClosures.get(app));
        return;
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
      // String.length s: count UTF-8 code points (bytes that are not 0x80-0xBF continuation bytes),
      // so it's correct for non-ASCII, not just the byte length.
      if (head instanceof Expr.Var v
          && "String".equals(v.module())
          && v.name().equals("length")
          && args.size() == 1) {
        gen(args.get(0));
        emitCodePointCount();
        return;
      }
      // An applied boxed constructor `Ctor a b …`: struct.new $variant (tag, args…).
      if (head instanceof Expr.Ctor ct && tuples.variantOf(ct.name()) != null) {
        int[] v = tuples.variantOf(ct.name());
        code.write(0x41);
        sleb(code, v[1]); // i32.const tag
        for (Expr arg : args) {
          gen(arg);
        }
        code.write(0xFB);
        code.write(0x00);
        leb(code, v[0]); // struct.new subtype
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
      // Over-application of a named function: `(adder 10) 5` flattens to `adder 10 5` (arity 1, but
      // 2 args). Direct-call the first `n` args to get a closure, then apply the rest by peeling.
      if (head instanceof Expr.Var v && funcs.containsKey(v.name())
          && funcs.get(v.name())[1] < args.size()) {
        int n = funcs.get(v.name())[1];
        for (int i = 0; i < n; i++) {
          gen(args.get(i));
        }
        code.write(0x10); // call (not a tail call — more applications follow)
        leb(code, funcs.get(v.name())[0]);
        int base = ((Ref) wOf(nodeType(appNodes.get(n - 1)), tuples)).typeIndex();
        int cl = freshLocal("$cl" + code.size(), new Ref(base));
        setLocal(cl);
        for (int i = n; i < args.size(); i++) {
          boolean last = i == args.size() - 1;
          get(cl);
          gen(args.get(i));
          get(cl);
          code.write(0xFB);
          code.write(0x02);
          leb(code, base);
          leb(code, 0);
          code.write(last && tail ? 0x15 : 0x14);
          leb(code, tuples.closureCc(base));
          if (!last) {
            base = ((Ref) tuples.closureResult(base)).typeIndex();
            cl = freshLocal("$cl" + code.size(), new Ref(base));
            setLocal(cl);
          }
        }
        return;
      }
      // Applying a function VALUE to one or more arguments — a function-typed local (a parameter), an
      // immediately-applied lambda, or a partially-applied/closure-valued expression. The value is a
      // closure `(ref $base)` with calling convention `(env, arg) -> res`; for several arguments we
      // peel one at a time (each call returns the next closure) — i.e. real currying.
      if (!(head instanceof Expr.Var hv && funcs.containsKey(hv.name()))
          && nodeTypes.containsKey(head)
          && wOf(nodeType(head), tuples) instanceof Ref r
          && tuples.isClosureBase(r.typeIndex())) {
        int base = r.typeIndex();
        int cl = freshLocal("$cl" + code.size(), new Ref(base));
        gen(head);
        setLocal(cl);
        for (int i = 0; i < args.size(); i++) {
          boolean last = i == args.size() - 1;
          get(cl); // env (the current closure)
          gen(args.get(i)); // arg
          get(cl);
          code.write(0xFB);
          code.write(0x02); // struct.get $base 0 -> the funcref
          leb(code, base);
          leb(code, 0);
          code.write(last && tail ? 0x15 : 0x14); // return_call_ref / call_ref
          leb(code, tuples.closureCc(base));
          if (!last) {
            // The result is the next closure in the chain; stash it and continue with its base.
            base = ((Ref) tuples.closureResult(base)).typeIndex();
            cl = freshLocal("$cl" + code.size(), new Ref(base));
            setLocal(cl);
          }
        }
        return;
      }
      // Multi-argument application of a function value (currying) is not yet on WasmGC.
      throw unsupported("application of " + (head instanceof Expr.Var v ? v.name() : "expression")
          + " (first-class functions / closures are not yet on WasmGC — use the interpreter, JS or the linear-memory WASM backend)");
    }

    /** Dispatches a {@code case} to the tuple, enum, boxed-ADT or list compiler. */
    private void caseExpr(Expr.Case c, java.util.function.Consumer<Expr> body) {
      if (c.branches().stream().anyMatch(br -> br.pattern() instanceof Pattern.Tuple)) {
        tupleCase(c, body);
      } else if (c.branches().stream()
          .anyMatch(br -> br.pattern() instanceof Pattern.Ctor ct && tuples.tagOf(ct.name()) != null)) {
        enumCase(c, body);
      } else if (c.branches().stream()
          .anyMatch(br -> br.pattern() instanceof Pattern.Ctor ct && tuples.variantOf(ct.name()) != null)) {
        adtCase(c, body);
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

    /** A {@code case} over a boxed (argument-carrying) custom type: read the i32 tag from the base
     * struct, then a chain of {@code i32.eq} tests; the matching branch {@code ref.cast}s to the
     * constructor's subtype and binds its argument fields. */
    private void adtCase(Expr.Case c, java.util.function.Consumer<Expr> body) {
      int base = tuples.adtBaseIndex();
      int s = freshLocal("$adt" + code.size(), new Ref(base));
      gen(c.scrutinee());
      code.write(0x21);
      leb(code, s);
      emitAdtChain(c.branches(), 0, s, base, wOf(nodeType(c), tuples), body);
    }

    private void emitAdtChain(
        List<Expr.Case.Branch> branches, int i, int s, int base, W result,
        java.util.function.Consumer<Expr> body) {
      Expr.Case.Branch br = branches.get(i);
      boolean last = i == branches.size() - 1;
      Pattern p = br.pattern();
      // A wildcard / var / the final branch is the default (no tag test).
      if (p instanceof Pattern.Wildcard || p instanceof Pattern.Var || (last && tuples.variantOf(ctorName(p)) == null)) {
        body.accept(br.body());
        return;
      }
      Pattern.Ctor ct = (Pattern.Ctor) p;
      int[] v = tuples.variantOf(ct.name());
      // local.get s; struct.get base 0 (tag); i32.const variantTag; i32.eq
      code.write(0x20);
      leb(code, s);
      code.write(0xFB);
      code.write(0x02);
      leb(code, base);
      leb(code, 0);
      code.write(0x41);
      sleb(code, v[1]);
      code.write(0x46); // i32.eq
      code.write(0x04); // if
      writeType(code, result);
      bindAdtArgs(ct, v, s);
      body.accept(br.body());
      code.write(0x05); // else
      if (last) {
        code.write(0x00); // unreachable (a well-typed case is exhaustive)
      } else {
        emitAdtChain(branches, i + 1, s, base, result, body);
      }
      code.write(0x0B);
    }

    /** Casts the scrutinee to the constructor's subtype and binds each argument variable. */
    private void bindAdtArgs(Pattern.Ctor ct, int[] v, int s) {
      boolean anyVar = ct.args().stream().anyMatch(a -> a instanceof Pattern.Var);
      if (!anyVar) {
        return;
      }
      int sub = v[0];
      List<W> fields = tuples.variantFields(ct.name());
      int casted = freshLocal("$cast" + code.size(), new Ref(sub));
      code.write(0x20);
      leb(code, s);
      code.write(0xFB);
      code.write(0x17); // ref.cast (ref null sub)
      sleb(code, sub);
      code.write(0x21);
      leb(code, casted);
      for (int i = 0; i < ct.args().size(); i++) {
        if (ct.args().get(i) instanceof Pattern.Var pv) {
          int idx = freshLocal(pv.name(), fields.get(i));
          code.write(0x20);
          leb(code, casted);
          code.write(0xFB);
          code.write(0x02);
          leb(code, sub);
          leb(code, i + 1); // field 0 is the tag; args start at 1
          code.write(0x21);
          leb(code, idx);
        }
      }
    }

    private static String ctorName(Pattern p) {
      return p instanceof Pattern.Ctor ct ? ct.name() : "";
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
      bindTupleFromLocal(tp, tupleTy, scrut);
    }

    /** Binds a tuple pattern's fields from a local holding the tuple struct, recursing into nested
     * tuple patterns (so {@code ( ( x, y ), z )} works). */
    private void bindTupleFromLocal(Pattern.Tuple tp, Ty tupleTy, int scrut) {
      int ti = tupleIndex(tupleTy);
      List<Ty> items = ((Ty.Tuple) Types.prune(tupleTy)).items();
      for (int i = 0; i < tp.items().size(); i++) {
        Pattern item = tp.items().get(i);
        if (item instanceof Pattern.Wildcard) {
          continue;
        }
        if (item instanceof Pattern.Var pv) {
          int idx = freshLocal(pv.name(), wOf(items.get(i), tuples));
          structGet(scrut, ti, i);
          code.write(0x21);
          leb(code, idx);
        } else if (item instanceof Pattern.Tuple nested) {
          Ty nestedTy = items.get(i);
          int nestedLocal = freshLocal("$tup" + code.size(), new Ref(tupleIndex(nestedTy)));
          structGet(scrut, ti, i);
          code.write(0x21);
          leb(code, nestedLocal);
          bindTupleFromLocal(nested, nestedTy, nestedLocal);
        } else {
          throw unsupported("a nested non-tuple pattern in a tuple destructure");
        }
      }
    }

    /** Emits {@code (struct.get $ti field (local.get scrut))}, leaving the field on the stack. */
    private void structGet(int scrut, int ti, int field) {
      code.write(0x20);
      leb(code, scrut);
      code.write(0xFB);
      code.write(0x02); // struct.get
      leb(code, ti);
      leb(code, field);
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
      // The scrutinee's cons type and element type (the head's wasm type).
      Ty scrutTy = Types.prune(nodeType(c.scrutinee()));
      int ci = tupleIndex(scrutTy);
      W elemW = scrutTy instanceof Ty.Con lc ? wOf(listElem(lc), tuples) : INT;
      int s = freshLocal("$scrut" + code.size(), new Ref(ci));
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
        int h = freshLocal(hv.name(), elemW);
        code.write(0x20);
        leb(code, s);
        code.write(0xFB);
        code.write(0x02);
        leb(code, ci);
        leb(code, 0); // struct.get cons 0 (head)
        code.write(0x21);
        leb(code, h);
      }
      if (consTail instanceof Pattern.Var tv) {
        int t = freshLocal(tv.name(), new Ref(ci));
        code.write(0x20);
        leb(code, s);
        code.write(0xFB);
        code.write(0x02);
        leb(code, ci);
        leb(code, 1); // struct.get cons 1 (tail)
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

  private static byte[] assemble(
      List<Func> funcList, Map<Expr, Ty> nodeTypes, Tuples tuples,
      Map<Expr.Lambda, ClosurePlan> lifted, Map<String, Integer> wrappers,
      Map<String, ClosurePlan> valueClosures, Map<Expr.App, ClosurePlan> partialClosures) {
    Map<String, int[]> table = new HashMap<>();
    Map<String, W> funcResult = new HashMap<>();
    for (int i = 0; i < funcList.size(); i++) {
      table.put(funcList.get(i).name(), new int[] {i, funcList.get(i).params().size()});
      funcResult.put(funcList.get(i).name(), funcList.get(i).result());
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00});

    // Type section: a single rec group holding every GC type definition — list cons cells, tuples,
    // records, ADT base/variants, string arrays AND function types — so any can reference any other
    // (self-referential cons cells; closure structs that hold a (ref $functype)). Indices follow
    // registration order: all structs first (registered in the pre-pass), then functypes.
    List<StructDef> shapes = tuples.shapes();
    ByteArrayOutputStream types = new ByteArrayOutputStream();
    leb(types, shapes.isEmpty() ? 0 : 1);
    if (!shapes.isEmpty()) {
      types.write(0x4E);
      leb(types, shapes.size());
      for (int i = 0; i < shapes.size(); i++) {
        StructDef def = shapes.get(i);
        if (def instanceof ConsDef cd) {
          types.write(0x5F); // struct
          leb(types, 2);
          writeType(types, cd.head());
          types.write(0x00); // head : E (const)
          types.write(0x63);
          sleb(types, i); // tail : (ref null self)
          types.write(0x00);
        } else if (def instanceof PlainDef pd) {
          types.write(0x5F);
          leb(types, pd.fields().size());
          for (W w : pd.fields()) {
            writeType(types, w);
            types.write(0x00); // immutable field
          }
        } else if (def instanceof StrArrayDef) {
          types.write(0x5E); // array
          types.write(0x78); // i8 packed storage
          types.write(0x01); // mutable (so concat can array.copy into it)
        } else if (def instanceof AdtBaseDef) {
          // sub (no supertype) struct { tag : i32 } — subtypable.
          types.write(0x50);
          leb(types, 0);
          types.write(0x5F);
          leb(types, 1);
          types.write(0x7F); // i32 tag
          types.write(0x00);
        } else if (def instanceof AdtVariantDef vd) {
          // sub <base> struct { tag : i32, args… } — width-subtypes the base.
          types.write(0x50);
          leb(types, 1);
          leb(types, vd.baseIndex());
          types.write(0x5F);
          leb(types, 1 + vd.argFields().size());
          types.write(0x7F); // i32 tag (inherited slot)
          types.write(0x00);
          for (W w : vd.argFields()) {
            writeType(types, w);
            types.write(0x00);
          }
        } else if (def instanceof FuncDef fd) {
          types.write(0x60); // functype
          leb(types, fd.params().size());
          for (W w : fd.params()) {
            writeType(types, w);
          }
          leb(types, 1);
          writeType(types, fd.result());
        } else if (def instanceof ClosBaseDef cb) {
          // sub (no supertype) struct { fn : (ref null cc) } — a closure value.
          types.write(0x50);
          leb(types, 0);
          types.write(0x5F);
          leb(types, 1);
          types.write(0x63); // (ref null ccIndex)
          sleb(types, cb.ccIndex());
          types.write(0x00);
        } else if (def instanceof ClosVariantDef cv) {
          // sub <base> struct { fn : (ref null cc), captures… } — a capturing lambda.
          types.write(0x50);
          leb(types, 1);
          leb(types, cv.baseIndex());
          types.write(0x5F);
          leb(types, 1 + cv.captures().size());
          types.write(0x63); // fn : (ref null cc) (inherited slot)
          sleb(types, cv.ccIndex());
          types.write(0x00);
          for (W w : cv.captures()) {
            writeType(types, w);
            types.write(0x00);
          }
        }
      }
    }
    section(out, 1, types);

    // Function section: each function's type is its signature functype in the shared rec group.
    ByteArrayOutputStream funcs = new ByteArrayOutputStream();
    leb(funcs, funcList.size());
    for (Func f : funcList) {
      leb(funcs, tuples.funcTypeIndex(f.paramTypes(), f.result()));
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
          new Gen(table, funcResult, nodeTypes, tuples, lifted, wrappers, valueClosures, partialClosures, f)
              .compile(f.body()));
    }
    section(out, 10, code);

    // Name section (custom): function indices -> Elm names plus each function's parameter names,
    // for readable disassembly / traces.
    List<String> funcNames = new ArrayList<>();
    List<List<String>> localNames = new ArrayList<>();
    for (Func f : funcList) {
      funcNames.add(f.name());
      localNames.add(f.params());
    }
    WasmCompiler.nameSection(out, funcNames, localNames, tuples.typeNames(), tuples.fieldNames());
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

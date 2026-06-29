package pl.matsuo.elm.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.error.ElmTypeError;

/** Algorithm-W type inference over {@link Expr}, with let-generalization and Elm's constrained
 * variables. Variables are mutated in place; call {@link Types#prune} / {@link Types#show} on the
 * result. */
public final class Infer {

  private int level = 1;
  private final Map<String, AliasDef> aliases = new HashMap<>();

  /** Alias names currently being expanded by {@link #astToTy}, to break self-referential cycles. */
  private final java.util.Set<String> expandingAliases = new java.util.HashSet<>();
  private final Map<String, String> moduleAliases = new HashMap<>();
  private final Map<Expr, Ty> numericLiterals = new IdentityHashMap<>();
  private final Map<Expr, Ty> nodeTypes = new IdentityHashMap<>();
  /** Constructors (union variants and record-alias constructors) the last module declared. */
  private final Map<String, Scheme> declaredCtors = new HashMap<>();
  /** Type aliases the last module declared (so a project checker can re-expose them). */
  private final Map<String, AliasDef> declaredAliases = new HashMap<>();
  /** Each already-checked module's OWN aliases, with their bodies fully expanded (every nested alias
   *  inlined in the defining module's scope), keyed by module name. A module is seeded with the
   *  aliases of the modules it imports. Exposing only a module's own alias names (not the nested
   *  aliases their bodies use) keeps two modules' same-named `type alias Model = …` from shadowing
   *  each other project-wide; pre-expanding the bodies means a nested alias (e.g. a record alias used
   *  inside this module's `Model`, defined in a third module the importer doesn't import) is already
   *  inlined, so the alias expands identically everywhere instead of staying opaque downstream — the
   *  two-different-expansions case that broke record unification. */
  private final Map<String, Map<String, AliasDef>> moduleAliasesByModule = new HashMap<>();
  /** Each already-checked module's own constructors (union variants + record-alias constructors),
   *  keyed by module name. An importer is seeded with the constructors of the modules it imports, so
   *  a constructor exposed via {@code import M exposing (Type(..))} — whose {@code (..)} the parser
   *  drops — resolves to M's, not to a same-named constructor from an unrelated module (e.g. two
   *  pages whose {@code Msg} both have a {@code GotFields} variant of a different payload type). */
  private final Map<String, Map<String, Scheme>> moduleCtorsByModule = new HashMap<>();

  // Union registry for exhaustiveness checking: which constructors each union has, which union a
  // constructor belongs to, and each constructor's arity. Seeded with the builtin unions and
  // accumulated across a project's modules (so a `case` can be checked against an imported union).
  private final Map<String, List<String>> unionCtors = new HashMap<>();
  private final Map<String, String> ctorUnion = new HashMap<>();
  private final Map<String, Integer> ctorArity = new HashMap<>();

  {
    registerBuiltinUnion("Bool", List.of("True", "False"), List.of(0, 0));
    registerBuiltinUnion("Maybe", List.of("Just", "Nothing"), List.of(1, 0));
    registerBuiltinUnion("Result", List.of("Err", "Ok"), List.of(1, 1));
    registerBuiltinUnion("Order", List.of("LT", "EQ", "GT"), List.of(0, 0, 0));
  }

  private void registerBuiltinUnion(String union, List<String> ctors, List<Integer> arities) {
    unionCtors.put(union, ctors);
    for (int i = 0; i < ctors.size(); i++) {
      ctorUnion.put(ctors.get(i), union);
      ctorArity.put(ctors.get(i), arities.get(i));
    }
  }

  private record AliasDef(List<String> params, Type body) {}

  /**
   * The {@code Int} literal expressions that inference resolved to {@code Float}. The compiler uses
   * this to coerce them to floating-point at runtime, so untyped numeric literals get the right
   * representation in a {@code Float} context (e.g. {@code Rect 3 4} with {@code Float} fields).
   */
  public Set<Expr> floatLiterals() {
    Set<Expr> out = Collections.newSetFromMap(new IdentityHashMap<>());
    numericLiterals.forEach(
        (e, t) -> {
          Ty p = Types.prune(t);
          if (p instanceof Ty.Con c && c.name().equals("Float")) {
            out.add(e);
          }
        });
    return out;
  }

  private Ty fresh() {
    return new Ty.Var(level, Ty.Constraint.NONE);
  }

  // --- whole-module inference --------------------------------------------

  /** Infers the types of all top-level definitions, registering the module's custom types, record
   * aliases and constructors; throws {@link ElmTypeError} on a type error. */
  public Map<String, Scheme> inferModule(Module module, Map<String, Scheme> base) {
    Map<String, Scheme> globals = new HashMap<>(base);
    aliases.clear();
    moduleAliases.clear();
    // Builtin type aliases. `type alias Svg msg = Html msg`, so an annotation mentioning `Svg msg`
    // must expand to `Html msg` and unify with Html nodes. (A module-defined alias of the same
    // name, added below, takes precedence.)
    aliases.put(
        "Svg",
        new AliasDef(
            List.of("msg"), new Type.Con(null, "Html", List.of(new Type.Var("msg")))));
    // elm-explorations/webgl WebGL.Texture.Options is a record alias; model it so a record literal
    // type-checks against an `: Texture.Options` annotation (mirrors loadWith's parameter type).
    aliases.put(
        "Options",
        new AliasDef(
            List.of(),
            new Type.Record(
                java.util.Optional.empty(),
                List.of(
                    new Type.Record.Field("magnify", con("Resize")),
                    new Type.Record.Field("minify", con("Resize")),
                    new Type.Record.Field("horizontalWrap", con("Wrap")),
                    new Type.Record.Field("verticalWrap", con("Wrap")),
                    new Type.Record.Field("flipY", con("Bool"))))));
    // Browser.Dom.Viewport record alias (matches Browser.Dom.getViewport's result type).
    Type floatT = con("Float");
    Type scene =
        new Type.Record(
            java.util.Optional.empty(),
            List.of(
                new Type.Record.Field("width", floatT),
                new Type.Record.Field("height", floatT)));
    Type vp =
        new Type.Record(
            java.util.Optional.empty(),
            List.of(
                new Type.Record.Field("x", floatT),
                new Type.Record.Field("y", floatT),
                new Type.Record.Field("width", floatT),
                new Type.Record.Field("height", floatT)));
    aliases.put(
        "Viewport",
        new AliasDef(
            List.of(),
            new Type.Record(
                java.util.Optional.empty(),
                List.of(
                    new Type.Record.Field("scene", scene),
                    new Type.Record.Field("viewport", vp)))));
    // Browser.Dom.Element record alias (matches Browser.Dom.getElement's result type).
    aliases.put(
        "Element",
        new AliasDef(
            List.of(),
            new Type.Record(
                java.util.Optional.empty(),
                List.of(
                    new Type.Record.Field("scene", scene),
                    new Type.Record.Field("viewport", vp),
                    new Type.Record.Field("element", vp)))));
    // elm/url's `Url` is a record alias, so a value annotated `: Url` supports field access
    // (`url.fragment`, `url.path`, …) and unifies with `Url.fromString`/`onUrlChange`'s record. The
    // matching shape is used inline by Url.fromString/toString and Browser.application's url fields.
    Type stringT = con("String");
    Type maybeStrT = new Type.Con(null, "Maybe", List.of(stringT));
    aliases.put(
        "Url",
        new AliasDef(
            List.of(),
            new Type.Record(
                java.util.Optional.empty(),
                List.of(
                    new Type.Record.Field("protocol", con("Protocol")),
                    new Type.Record.Field("host", stringT),
                    new Type.Record.Field("port_", new Type.Con(null, "Maybe", List.of(con("Int")))),
                    new Type.Record.Field("path", stringT),
                    new Type.Record.Field("query", maybeStrT),
                    new Type.Record.Field("fragment", maybeStrT)))));
    // Browser.Document is a record alias `{ title : String, body : List (Html msg) }`, so a view
    // returning a `{ title = …, body = [ … ] }` literal unifies with a `: Document msg` annotation.
    aliases.put(
        "Document",
        new AliasDef(
            List.of("msg"),
            new Type.Record(
                java.util.Optional.empty(),
                List.of(
                    new Type.Record.Field("title", stringT),
                    new Type.Record.Field(
                        "body",
                        new Type.Con(
                            null,
                            "List",
                            List.of(new Type.Con(null, "Html", List.of(new Type.Var("msg"))))))))));
    // Seed only the aliases of the modules THIS module imports (scoped per import, not a flat
    // project-wide map): two pages may each define `type alias Model = …`, and a module must see the
    // `Model` of the module it imports — not whichever module happened to be checked last.
    for (Module.Import imp : module.imports()) {
      Map<String, AliasDef> fromImport = moduleAliasesByModule.get(imp.module());
      if (fromImport != null) {
        aliases.putAll(fromImport);
      }
    }
    declaredCtors.clear();
    declaredAliases.clear();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.TypeAlias ta) {
        AliasDef def = new AliasDef(ta.params(), ta.type());
        aliases.put(ta.name(), def);
        declaredAliases.put(ta.name(), def);
      }
    }
    // Mirror the runtime's import resolution so exposed names resolve during inference.
    for (Module.Import imp : module.imports()) {
      imp.alias().ifPresent(a -> moduleAliases.put(a, imp.module()));
      // Bring in the imported module's constructors (scoped to this import), so a constructor
      // exposed via `import M exposing (Type(..))` resolves to M's — not to a same-named constructor
      // from an unrelated module. The module's own constructors (registered below) still win.
      Map<String, Scheme> importedCtors = moduleCtorsByModule.get(imp.module());
      if (importedCtors != null) {
        importedCtors.forEach(globals::putIfAbsent);
      }
      if (imp.exposing().open()) {
        String prefix = imp.module() + ".";
        for (String key : new java.util.ArrayList<>(globals.keySet())) {
          if (key.startsWith(prefix)) {
            globals.putIfAbsent(key.substring(prefix.length()), globals.get(key));
          }
        }
      } else {
        for (String name : imp.exposing().names()) {
          Scheme s = globals.get(imp.module() + "." + name);
          if (s != null) {
            globals.put(name, s);
          }
        }
      }
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        registerUnion(u, globals);
      } else if (d instanceof Decl.TypeAlias ta && ta.type() instanceof Type.Record) {
        registerRecordAliasConstructor(ta, globals);
      }
    }
    // A port declares a value of its annotated type (outgoing `a -> Cmd msg`, incoming
    // `(a -> msg) -> Sub msg`). Generalize it so `msg` stays polymorphic, like any signature.
    Map<String, Scheme> ports = new LinkedHashMap<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Port p) {
        Scheme s = Types.generalize(Types.prune(astToTy(p.type(), new HashMap<>())), 0);
        globals.put(p.name(), s);
        ports.put(p.name(), s);
      }
    }

    // Infer top-level definitions in dependency order, one strongly-connected component (mutually
    // recursive group) at a time, generalizing each group before the next. This gives proper
    // module-level let-polymorphism: a helper like `render : ... -> Html msg` is generalized before
    // its callers, so each caller instantiates it with a fresh `msg` instead of forcing one type.
    Map<String, Decl.Value> values = new LinkedHashMap<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        values.put(v.name(), v);
      }
    }
    TypeEnv env = TypeEnv.root(globals);
    Map<String, Scheme> result = new LinkedHashMap<>();
    // Error recovery: instead of aborting on the first bad definition, record its error, bind it to
    // a fresh polymorphic type and carry on, so one pass reports every independent mistake. A group
    // that depends on a failed one stays permissive (its dependency unifies with anything), which
    // avoids spurious cascade errors.
    List<ElmTypeError> errors = new ArrayList<>();
    for (List<String> group : sccOrder(values)) {
      int outer = level;
      level++;
      Map<String, Ty> placeholders = new LinkedHashMap<>();
      java.util.Set<String> failed = new java.util.HashSet<>();
      TypeEnv rec = env;
      for (String name : group) {
        Ty ph = fresh();
        placeholders.put(name, ph);
        rec = rec.extend(name, Scheme.mono(ph));
      }
      for (String name : group) {
        Decl.Value v = values.get(name);
        Ty ph = placeholders.get(name);
        try {
          // Unify the annotation (the expected type) before the body, so an annotation/body
          // mismatch reads "expected <annotation> but got <body>". Locate it at the definition.
          v.annotation().ifPresent(ann -> Unify.unify(astToTy(ann, new HashMap<>()), ph));
          Ty rhs =
              v.params().isEmpty() ? infer(rec, v.body()) : inferLambda(rec, v.params(), v.body());
          Unify.unify(ph, rhs);
        } catch (ElmTypeError e) {
          errors.add(e.at(v.pos(), hintFor(e)));
          failed.add(name);
        }
      }
      level = outer;
      for (String name : group) {
        // A failed definition is generalized as `∀a. a` so dependents type-check against anything.
        Ty ty = failed.contains(name) ? new Ty.Var(outer + 1, Ty.Constraint.NONE) : Types.prune(placeholders.get(name));
        Scheme s = Types.generalize(ty, outer);
        result.put(name, s);
        env = env.extend(name, s); // visible (generalized) to later groups
      }
    }
    result.putAll(ports); // ports are values too; expose their declared types
    if (!errors.isEmpty()) {
      // Report in source order (SCC order is dependency order), so the list reads top-to-bottom.
      errors.sort(
          java.util.Comparator.comparingInt(
                  (ElmTypeError e) -> e.position == null ? 0 : e.position.line())
              .thenComparingInt(e -> e.position == null ? 0 : e.position.col()));
      throw errors.size() == 1
          ? errors.get(0)
          : new pl.matsuo.elm.error.ElmTypeErrors(errors.get(0).rawMessage(), errors);
    }
    return result;
  }

  /**
   * Orders the value definitions into mutually-recursive groups (Tarjan SCCs) such that a group is
   * listed after every group it depends on. References are over-approximated (shadowing may add a
   * spurious edge), which stays sound — it only ever groups too coarsely.
   */
  private static List<List<String>> sccOrder(Map<String, Decl.Value> values) {
    Map<String, java.util.Set<String>> deps = new LinkedHashMap<>();
    for (var e : values.entrySet()) {
      java.util.Set<String> refs = new java.util.HashSet<>();
      collectRefs(e.getValue().body(), values.keySet(), refs);
      refs.remove(e.getKey());
      deps.put(e.getKey(), refs);
    }
    // Tarjan's strongly-connected-components algorithm; emits SCCs in reverse-topological order,
    // which is exactly dependency order (a node's dependencies come out before it).
    Map<String, Integer> index = new HashMap<>();
    Map<String, Integer> low = new HashMap<>();
    java.util.Deque<String> stack = new java.util.ArrayDeque<>();
    java.util.Set<String> onStack = new java.util.HashSet<>();
    int[] counter = {0};
    List<List<String>> out = new ArrayList<>();
    for (String v : values.keySet()) {
      if (!index.containsKey(v)) {
        strongConnect(v, deps, index, low, stack, onStack, counter, out);
      }
    }
    return out;
  }

  private static void strongConnect(
      String v,
      Map<String, java.util.Set<String>> deps,
      Map<String, Integer> index,
      Map<String, Integer> low,
      java.util.Deque<String> stack,
      java.util.Set<String> onStack,
      int[] counter,
      List<List<String>> out) {
    index.put(v, counter[0]);
    low.put(v, counter[0]);
    counter[0]++;
    stack.push(v);
    onStack.add(v);
    for (String w : deps.get(v)) {
      if (!index.containsKey(w)) {
        strongConnect(w, deps, index, low, stack, onStack, counter, out);
        low.put(v, Math.min(low.get(v), low.get(w)));
      } else if (onStack.contains(w)) {
        low.put(v, Math.min(low.get(v), index.get(w)));
      }
    }
    if (low.get(v).equals(index.get(v))) {
      List<String> scc = new ArrayList<>();
      String w;
      do {
        w = stack.pop();
        onStack.remove(w);
        scc.add(w);
      } while (!w.equals(v));
      out.add(scc);
    }
  }

  /** Over-approximates the top-level value names referenced (qualified or not) within {@code e}. */
  private static void collectRefs(Expr e, java.util.Set<String> names, java.util.Set<String> out) {
    switch (e) {
      case Expr.Var v -> {
        if (names.contains(v.name())) {
          out.add(v.name());
        }
      }
      case Expr.App a -> {
        collectRefs(a.fn(), names, out);
        collectRefs(a.arg(), names, out);
      }
      case Expr.BinOp b -> {
        if (names.contains(b.op())) {
          out.add(b.op()); // a custom infix operator is a reference to its defining function
        }
        collectRefs(b.left(), names, out);
        collectRefs(b.right(), names, out);
      }
      case Expr.OpFunc o -> {
        if (names.contains(o.op())) {
          out.add(o.op()); // (op) used as a value references its defining function
        }
      }
      case Expr.Negate n -> collectRefs(n.operand(), names, out);
      case Expr.If i -> {
        collectRefs(i.cond(), names, out);
        collectRefs(i.thenBranch(), names, out);
        collectRefs(i.elseBranch(), names, out);
      }
      case Expr.Lambda l -> collectRefs(l.body(), names, out);
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value dv) {
            collectRefs(dv.body(), names, out);
          } else if (d instanceof Decl.Destructure dd) {
            collectRefs(dd.body(), names, out);
          }
        }
        collectRefs(let.body(), names, out);
      }
      case Expr.Case c -> {
        collectRefs(c.scrutinee(), names, out);
        c.branches().forEach(br -> collectRefs(br.body(), names, out));
      }
      case Expr.ListLit l -> l.items().forEach(i -> collectRefs(i, names, out));
      case Expr.Tuple t -> t.items().forEach(i -> collectRefs(i, names, out));
      case Expr.Record r -> r.fields().forEach(f -> collectRefs(f.value(), names, out));
      case Expr.RecordUpdate u -> {
        if (names.contains(u.base())) {
          out.add(u.base());
        }
        u.fields().forEach(f -> collectRefs(f.value(), names, out));
      }
      case Expr.RecordAccess a -> collectRefs(a.target(), names, out);
      default -> {}
    }
  }

  /**
   * Type-checks a multi-module project. Modules are ordered so that imported modules are checked
   * first; each module's exported values, constructors and aliases are then made visible (qualified
   * as {@code Module.name}) to the modules that import it. Returns the inferred top-level types of
   * the entry module (the one that defines {@code main}, else the last given).
   */
  public Map<String, Scheme> inferProject(List<Module> modules, Map<String, Scheme> base) {
    List<Module> ordered = orderByImports(modules);
    Map<String, Scheme> globals = new HashMap<>(base);
    moduleAliasesByModule.clear();
    moduleCtorsByModule.clear();
    Map<String, Scheme> entryTypes = Map.of();
    String entryName = null;
    for (Module m : ordered) {
      boolean hasMain =
          m.decls().stream().anyMatch(d -> d instanceof Decl.Value v && v.name().equals("main"));
      Map<String, Scheme> values = inferModule(m, globals);
      String prefix = m.name() + ".";
      values.forEach((n, s) -> globals.put(prefix + n, s));
      declaredCtors.forEach((n, s) -> globals.put(prefix + n, s));
      // Record this module's own constructors so the modules that import it (and only those) can
      // resolve them unqualified — `import M exposing (Type(..))` brings M's constructors into scope.
      // Scoping per import (rather than a flat project-wide table) keeps two modules' same-named
      // constructors (e.g. a `GotFields` variant in each page's `Msg`) from colliding.
      moduleCtorsByModule.put(m.name(), new HashMap<>(declaredCtors));
      // Store this module's OWN aliases with bodies fully expanded (nested aliases inlined in this
      // module's scope), so importers get self-contained definitions: no opacity (the alias expands
      // the same downstream as here) and no name bleed (only this module's alias names are exposed,
      // not the third-module aliases their bodies happen to use).
      Map<String, AliasDef> exported = new HashMap<>();
      declaredAliases.forEach(
          (name, def) ->
              exported.put(
                  name, new AliasDef(def.params(), expandAliasesInType(def.body(), new java.util.HashSet<>()))));
      moduleAliasesByModule.put(m.name(), exported);
      if (hasMain || entryName == null) {
        entryTypes = values;
        entryName = m.name();
      }
    }
    return entryTypes;
  }

  /** Orders modules so an imported module precedes its importer (best-effort; cycles keep order). */
  private static List<Module> orderByImports(List<Module> modules) {
    Map<String, Module> byName = new LinkedHashMap<>();
    for (Module m : modules) {
      byName.put(m.name(), m);
    }
    List<Module> out = new ArrayList<>();
    java.util.Set<String> done = new java.util.HashSet<>();
    boolean progress = true;
    while (out.size() < modules.size() && progress) {
      progress = false;
      for (Module m : modules) {
        if (done.contains(m.name())) {
          continue;
        }
        boolean ready =
            m.imports().stream()
                .allMatch(imp -> !byName.containsKey(imp.module()) || done.contains(imp.module()));
        if (ready) {
          out.add(m);
          done.add(m.name());
          progress = true;
        }
      }
    }
    for (Module m : modules) { // any left over (import cycle) — append in original order
      if (!done.contains(m.name())) {
        out.add(m);
      }
    }
    return out;
  }

  private void registerUnion(Decl.Union u, Map<String, Scheme> globals) {
    Map<String, Ty> params = new LinkedHashMap<>();
    for (String p : u.params()) {
      params.put(p, fresh());
    }
    Ty result = new Ty.Con(u.name(), new ArrayList<>(params.values()));
    List<Ty.Var> vars = params.values().stream().map(t -> (Ty.Var) t).toList();
    List<String> variantNames = new ArrayList<>();
    for (Decl.Union.Variant variant : u.variants()) {
      variantNames.add(variant.name());
      ctorUnion.put(variant.name(), u.name());
      ctorArity.put(variant.name(), variant.args().size());
    }
    unionCtors.put(u.name(), variantNames);
    for (Decl.Union.Variant variant : u.variants()) {
      Ty ctor = result;
      List<Type> args = variant.args();
      for (int i = args.size() - 1; i >= 0; i--) {
        ctor = new Ty.Arrow(astToTy(args.get(i), reuse(params)), ctor);
      }
      Scheme scheme = new Scheme(vars, ctor);
      globals.put(variant.name(), scheme);
      declaredCtors.put(variant.name(), scheme);
    }
  }

  private void registerRecordAliasConstructor(Decl.TypeAlias ta, Map<String, Scheme> globals) {
    Map<String, Ty> params = new LinkedHashMap<>();
    for (String p : ta.params()) {
      params.put(p, fresh());
    }
    Type.Record rec = (Type.Record) ta.type();
    Ty recordTy = astToTy(ta.type(), reuse(params));
    Ty ctor = recordTy;
    List<Type.Record.Field> fields = rec.fields();
    for (int i = fields.size() - 1; i >= 0; i--) {
      ctor = new Ty.Arrow(astToTy(fields.get(i).type(), reuse(params)), ctor);
    }
    Scheme scheme = new Scheme(params.values().stream().map(t -> (Ty.Var) t).toList(), ctor);
    globals.put(ta.name(), scheme);
    declaredCtors.put(ta.name(), scheme);
  }

  /** A param map that reuses the same variable objects (so a scheme's vars stay shared). */
  private Map<String, Ty> reuse(Map<String, Ty> params) {
    return new HashMap<>(params);
  }

  /** A nullary surface type constructor, e.g. {@code con("Bool")} = the {@code Bool} type. */
  private static Type con(String name) {
    return new Type.Con(null, name, List.of());
  }

  /**
   * Inlines every alias reference in {@code t} using the current {@link #aliases} scope — so the
   * result is self-contained (no {@code Con} that names an alias). Used to pre-expand a module's
   * exported alias bodies before handing them to importers, so the alias expands identically there.
   * {@code expanding} guards against (illegal) self-referential aliases by leaving them opaque.
   */
  private Type expandAliasesInType(Type t, java.util.Set<String> expanding) {
    return switch (t) {
      case Type.Var v -> v;
      case Type.Unit u -> u;
      case Type.Arrow a ->
          new Type.Arrow(expandAliasesInType(a.from(), expanding), expandAliasesInType(a.to(), expanding));
      case Type.Tuple tu ->
          new Type.Tuple(tu.items().stream().map(x -> expandAliasesInType(x, expanding)).toList());
      case Type.Record r ->
          new Type.Record(
              r.base(),
              r.fields().stream()
                  .map(f -> new Type.Record.Field(f.name(), expandAliasesInType(f.type(), expanding)))
                  .toList());
      case Type.Con c -> {
        // Resolve by the reference's defining module (not the flat simple-name table), and key the
        // cycle guard by defining-module + name, so a qualified cross-module alias nested in another
        // alias's body (`B.Model`'s `ws : A.Model doc`) expands instead of being dropped opaque as a
        // false self-cycle when it shares a simple name with the enclosing alias.
        AliasDef a = resolveAlias(c.module(), c.name());
        String key = aliasKey(c.module(), c.name());
        if (a != null && !expanding.contains(key)) {
          Map<String, Type> sub = new HashMap<>();
          for (int i = 0; i < a.params().size() && i < c.args().size(); i++) {
            sub.put(a.params().get(i), expandAliasesInType(c.args().get(i), expanding));
          }
          expanding.add(key);
          Type expanded = expandAliasesInType(substTypeVars(a.body(), sub), expanding);
          expanding.remove(key);
          yield expanded;
        }
        yield new Type.Con(
            c.module(), c.name(), c.args().stream().map(x -> expandAliasesInType(x, expanding)).toList());
      }
    };
  }

  /** Substitutes alias type parameters (e.g. the {@code a} of {@code type alias Box a = …}) by name. */
  private Type substTypeVars(Type t, Map<String, Type> sub) {
    return switch (t) {
      case Type.Var v -> sub.getOrDefault(v.name(), v);
      case Type.Unit u -> u;
      case Type.Arrow a -> new Type.Arrow(substTypeVars(a.from(), sub), substTypeVars(a.to(), sub));
      case Type.Tuple tu -> new Type.Tuple(tu.items().stream().map(x -> substTypeVars(x, sub)).toList());
      case Type.Record r ->
          new Type.Record(
              r.base(),
              r.fields().stream()
                  .map(f -> new Type.Record.Field(f.name(), substTypeVars(f.type(), sub)))
                  .toList());
      case Type.Con c ->
          new Type.Con(c.module(), c.name(), c.args().stream().map(x -> substTypeVars(x, sub)).toList());
    };
  }

  /** Resolves a type-alias reference. A QUALIFIED reference ({@code Tickets.Model}) resolves against
   * its module's exposed aliases, so two pages' {@code Model} don't collapse to one (the flat
   * by-simple-name table can't tell {@code Login.Model} from {@code Tickets.Model}). Falls back to
   * the simple-name table for unqualified references and modules not in the project map. */
  private AliasDef resolveAlias(String module, String name) {
    if (module != null) {
      String real = moduleAliases.getOrDefault(module, module);
      Map<String, AliasDef> ofModule = moduleAliasesByModule.get(real);
      if (ofModule != null && ofModule.containsKey(name)) {
        return ofModule.get(name);
      }
    }
    return aliases.get(name);
  }

  /** A cycle-guard key for an alias reference: the alias's DEFINING module (resolving an import alias)
   * and name. Keying by the defining module — not just the simple name — lets two genuinely different
   * aliases that share a simple name (e.g. {@code A.Model} nested inside {@code B.Model}) each expand,
   * while a self-referential alias (e.g. {@code type alias Io = Posix.Io}) still terminates. */
  private String aliasKey(String module, String name) {
    return (module == null ? "" : moduleAliases.getOrDefault(module, module)) + ":" + name;
  }

  /** Converts a surface {@link Type} to an inference {@link Ty}, expanding type aliases. */
  private Ty astToTy(Type type, Map<String, Ty> vars) {
    return switch (type) {
      case Type.Var v -> vars.computeIfAbsent(v.name(), n -> fresh());
      case Type.Unit ignored -> new Ty.Unit();
      case Type.Arrow a -> new Ty.Arrow(astToTy(a.from(), vars), astToTy(a.to(), vars));
      case Type.Tuple t -> new Ty.Tuple(t.items().stream().map(x -> astToTy(x, vars)).toList());
      case Type.Record r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Type.Record.Field f : r.fields()) {
          fields.put(f.name(), astToTy(f.type(), vars));
        }
        Ty tail = r.base().map(b -> vars.computeIfAbsent(b, n -> fresh())).orElse(null);
        yield new Ty.Record(fields, tail);
      }
      case Type.Con c -> {
        AliasDef alias = resolveAlias(c.module(), c.name());
        // Guard against alias cycles (e.g. `type alias Io = Posix.Io`, where the dropped module
        // qualifier makes the body refer to the same name): expand each alias at most once on a path,
        // otherwise treat it as an opaque constructor instead of recursing forever. The key is the
        // DEFINING module + name, so a nested cross-module alias sharing a simple name (a `B.Model`
        // field of type `A.Model doc`) is not mistaken for a self-cycle and dropped opaque.
        String key = aliasKey(c.module(), c.name());
        if (alias != null && expandingAliases.add(key)) {
          Map<String, Ty> sub = new HashMap<>();
          for (int i = 0; i < alias.params().size() && i < c.args().size(); i++) {
            sub.put(alias.params().get(i), astToTy(c.args().get(i), vars));
          }
          Ty expanded = astToTy(alias.body(), sub);
          expandingAliases.remove(key);
          yield expanded;
        }
        yield new Ty.Con(c.name(), c.args().stream().map(x -> astToTy(x, vars)).toList());
      }
    };
  }

  private Ty fresh(Ty.Constraint c) {
    return new Ty.Var(level, c);
  }

  /**
   * Per-expression inferred types (deep-pruned), recorded during inference. Lets a backend that
   * needs type information — notably the type-directed WASM codegen for strings and records — look
   * up the type of any sub-expression after {@link #inferModule}/{@link #inferProject} has run.
   */
  public Map<Expr, Ty> nodeTypes() {
    Map<Expr, Ty> out = new IdentityHashMap<>();
    nodeTypes.forEach((e, t) -> out.put(e, Types.deepPrune(t)));
    return out;
  }

  public Ty infer(TypeEnv env, Expr expr) {
    try {
      Ty t = infer0(env, expr);
      nodeTypes.put(expr, t);
      return t;
    } catch (ElmTypeError e) {
      // Attach the innermost expression's location (and a hint) the first time an error bubbles
      // through an expression that has one, so the report points at the actual offending code.
      throw e.at(expr.pos(), hintFor(e));
    }
  }

  private Ty infer0(TypeEnv env, Expr expr) {
    return switch (expr) {
      case Expr.IntLit lit -> {
        Ty t = fresh(Ty.Constraint.NUMBER);
        numericLiterals.put(lit, t);
        yield t;
      }
      case Expr.FloatLit ignored -> Ty.FLOAT;
      case Expr.StrLit ignored -> Ty.STRING;
      case Expr.CharLit ignored -> Ty.CHAR;
      case Expr.Unit ignored -> new Ty.Unit();
      case Expr.Shader ignored -> fresh();
      case Expr.Var v -> instantiate(resolve(env, v.module(), v.name()));
      case Expr.Ctor c -> instantiate(resolve(env, c.module(), c.name()));
      case Expr.OpFunc o -> {
        Scheme s = Signatures.operator(o.op());
        yield instantiate(s != null ? s : resolve(env, null, o.op()));
      }
      case Expr.ListLit l -> {
        Ty elem = fresh();
        for (int i = 0; i < l.items().size(); i++) {
          Ty itemTy = infer(env, l.items().get(i));
          try {
            Unify.unify(elem, itemTy);
          } catch (ElmTypeError ex) {
            // A heterogeneous list: keep the list context instead of a bare leaf mismatch.
            throw new ElmTypeError(
                "All elements of a list must have the same type, but element " + (i + 1)
                    + " is `" + Types.show(itemTy) + "` while the earlier element(s) are `"
                    + Types.show(elem) + "`.");
          }
        }
        yield Ty.list(elem);
      }
      case Expr.Tuple t -> new Ty.Tuple(t.items().stream().map(i -> infer(env, i)).toList());
      case Expr.Record r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Expr.Record.Field f : r.fields()) {
          fields.put(f.name(), infer(env, f.value()));
        }
        yield new Ty.Record(fields, null);
      }
      case Expr.RecordAccess a -> {
        Ty target = infer(env, a.target());
        Ty result = fresh();
        try {
          Unify.unify(target, new Ty.Record(Map.of(a.field(), result), fresh()));
        } catch (ElmTypeError ex) {
          Ty pruned = Types.prune(target);
          if (pruned instanceof Ty.Record rec && rec.tail() == null && !rec.fields().containsKey(a.field())) {
            String suggestion = pl.matsuo.elm.util.Suggest.closest(a.field(), rec.fields().keySet());
            throw new ElmTypeError(
                "This record has no field `" + a.field() + "`."
                    + (suggestion != null ? " Did you mean `" + suggestion + "`?" : "")
                    + " It has: " + String.join(", ", rec.fields().keySet()) + ".");
          }
          throw ex;
        }
        yield result;
      }
      case Expr.Accessor a -> {
        Ty result = fresh();
        yield new Ty.Arrow(new Ty.Record(Map.of(a.field(), result), fresh()), result);
      }
      case Expr.RecordUpdate u -> {
        Ty base = instantiate(resolve(env, null, u.base()));
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (Expr.Record.Field f : u.fields()) {
          fields.put(f.name(), infer(env, f.value()));
        }
        Unify.unify(base, new Ty.Record(fields, fresh()));
        yield base;
      }
      case Expr.App app -> {
        Ty fn = infer(env, app.fn());
        Ty arg = infer(env, app.arg());
        Ty result = fresh();
        try {
          Unify.unify(fn, new Ty.Arrow(arg, result));
        } catch (ElmTypeError ex) {
          // For a plain type mismatch, say which argument is wrong. (A richer message — e.g. a record
          // "did you mean?" — is kept as-is, and applying a non-function keeps the generic hint.)
          Ty pruned = Types.prune(fn);
          String raw = ex.rawMessage();
          boolean alreadySpecific =
              raw == null
                  || raw.startsWith("Record mismatch")
                  || raw.contains(" argument to ")
                  || raw.startsWith("The branches of this")
                  || raw.startsWith("All elements of a list")
                  || raw.startsWith("Infinite type");
          if (pruned instanceof Ty.Arrow fa && !alreadySpecific) {
            throw new ElmTypeError(
                "The " + ordinal(argumentIndex(app)) + " argument to " + calleeName(app)
                    + " is `" + Types.show(arg) + "` but " + calleeName(app) + " expects `"
                    + Types.show(fa.from()) + "`.");
          }
          throw ex;
        }
        yield result;
      }
      case Expr.BinOp b -> {
        // A built-in operator has a prelude signature; otherwise resolve a same-named value (a
        // user/package-defined infix function, e.g. `(+++) a b = …`).
        Scheme opScheme = Signatures.operator(b.op());
        Ty op = instantiate(opScheme != null ? opScheme : resolve(env, null, b.op()));
        Ty result = fresh();
        Unify.unify(op, new Ty.Arrow(infer(env, b.left()), new Ty.Arrow(infer(env, b.right()), result)));
        yield result;
      }
      case Expr.Negate n -> {
        Ty t = fresh(Ty.Constraint.NUMBER);
        Unify.unify(t, infer(env, n.operand()));
        yield t;
      }
      case Expr.If iff -> {
        Unify.unify(Ty.BOOL, infer(env, iff.cond()));
        Ty thenTy = infer(env, iff.thenBranch());
        Ty elseTy = infer(env, iff.elseBranch());
        try {
          Unify.unify(thenTy, elseTy);
        } catch (ElmTypeError ex) {
          throw new ElmTypeError(
              "The branches of this `if` must have the same type, but the `then` branch is `"
                  + Types.show(thenTy) + "` and the `else` branch is `" + Types.show(elseTy) + "`.");
        }
        yield thenTy;
      }
      case Expr.Lambda l -> inferLambda(env, l.params(), l.body());
      case Expr.Let let -> inferLet(env, let);
      case Expr.Case c -> inferCase(env, c);
    };
  }

  /** The 1-based position of {@code app}'s argument in its call spine ({@code f a b}: b is 2nd). */
  private static int argumentIndex(Expr.App app) {
    int index = 1;
    Expr head = app.fn();
    while (head instanceof Expr.App inner) {
      index++;
      head = inner.fn();
    }
    return index;
  }

  /** The called function, as "`name`" if it is a plain variable/constructor, else "this function". */
  private static String calleeName(Expr.App app) {
    Expr head = app.fn();
    while (head instanceof Expr.App inner) {
      head = inner.fn();
    }
    if (head instanceof Expr.Var v) {
      return "`" + (v.module() == null ? v.name() : v.module() + "." + v.name()) + "`";
    }
    if (head instanceof Expr.Ctor c) {
      return "`" + (c.module() == null ? c.name() : c.module() + "." + c.name()) + "`";
    }
    return "this function";
  }

  private static String ordinal(int n) {
    return switch (n) {
      case 1 -> "1st";
      case 2 -> "2nd";
      case 3 -> "3rd";
      default -> n + "th";
    };
  }

  private Ty inferLambda(TypeEnv env, List<Pattern> params, Expr body) {
    Map<String, Ty> binds = new LinkedHashMap<>();
    List<Ty> paramTypes = new ArrayList<>();
    for (Pattern p : params) {
      paramTypes.add(inferPattern(env, p, binds));
    }
    TypeEnv body_env = extendMono(env, binds);
    Ty result = infer(body_env, body);
    for (int i = paramTypes.size() - 1; i >= 0; i--) {
      result = new Ty.Arrow(paramTypes.get(i), result);
    }
    return result;
  }

  private Ty inferLet(TypeEnv env, Expr.Let let) {
    // Bind each definition monomorphically (for recursion), infer, then generalize.
    int outer = level;
    level++;
    record Binding(String name, Ty placeholder) {}
    List<Binding> bindings = new ArrayList<>();
    TypeEnv rec = env;
    // Let-local `type` / `type alias` declarations: register their constructors and alias
    // definitions (into the shared globals/alias registries) so the rest of the let can use them.
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Union u) {
        registerUnion(u, env.globals());
      } else if (d instanceof Decl.TypeAlias ta) {
        aliases.put(ta.name(), new AliasDef(ta.params(), ta.type()));
        if (ta.type() instanceof Type.Record) {
          registerRecordAliasConstructor(ta, env.globals());
        }
      }
    }
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        Ty ph = fresh();
        bindings.add(new Binding(v.name(), ph));
        rec = rec.extend(v.name(), Scheme.mono(ph));
      }
    }
    for (Decl d : let.defs()) {
      switch (d) {
        case Decl.Value v -> {
          Ty rhs = v.params().isEmpty() ? infer(rec, v.body()) : inferLambda(rec, v.params(), v.body());
          Ty ph = bindings.stream().filter(b -> b.name().equals(v.name())).findFirst().get().placeholder();
          Unify.unify(ph, rhs);
        }
        case Decl.Destructure de -> {
          Map<String, Ty> binds = new LinkedHashMap<>();
          Ty pat = inferPattern(rec, de.pattern(), binds);
          Unify.unify(pat, infer(rec, de.body()));
          for (var e : binds.entrySet()) {
            rec = rec.extend(e.getKey(), Scheme.mono(e.getValue()));
            bindings.add(new Binding(e.getKey(), e.getValue()));
          }
        }
        case Decl.Union ignored -> {} // already registered above (type-level only)
        case Decl.TypeAlias ignored -> {} // already registered above (type-level only)
        default -> throw new ElmTypeError("Unsupported declaration in let");
      }
    }
    level = outer;
    TypeEnv body_env = env;
    for (Binding b : bindings) {
      body_env = body_env.extend(b.name(), Types.generalize(Types.prune(b.placeholder()), outer));
    }
    return infer(body_env, let.body());
  }

  private Ty inferCase(TypeEnv env, Expr.Case c) {
    Ty scrut = infer(env, c.scrutinee());
    Ty result = fresh();
    int index = 0;
    for (Expr.Case.Branch branch : c.branches()) {
      index++;
      Map<String, Ty> binds = new LinkedHashMap<>();
      Ty pat = inferPattern(env, branch.pattern(), binds);
      Unify.unify(pat, scrut);
      Ty bodyTy = infer(extendMono(env, binds), branch.body());
      try {
        Unify.unify(result, bodyTy);
      } catch (ElmTypeError ex) {
        throw new ElmTypeError(
            "The branches of this `case` must all have the same type, but branch " + index
                + " is `" + Types.show(bodyTy) + "` while earlier branches are `"
                + Types.show(result) + "`.");
      }
    }
    new Exhaustiveness(unionCtors, ctorUnion, ctorArity).check(c);
    return result;
  }

  // --- patterns ----------------------------------------------------------

  private Ty inferPattern(TypeEnv env, Pattern pattern, Map<String, Ty> binds) {
    return switch (pattern) {
      case Pattern.Wildcard ignored -> fresh();
      case Pattern.Var v -> {
        Ty t = fresh();
        binds.put(v.name(), t);
        yield t;
      }
      case Pattern.Unit ignored -> new Ty.Unit();
      case Pattern.IntLit ignored -> fresh(Ty.Constraint.NUMBER);
      case Pattern.StrLit ignored -> Ty.STRING;
      case Pattern.CharLit ignored -> Ty.CHAR;
      case Pattern.Tuple t -> new Ty.Tuple(t.items().stream().map(p -> inferPattern(env, p, binds)).toList());
      case Pattern.ListPat l -> {
        Ty elem = fresh();
        for (Pattern p : l.items()) {
          Unify.unify(elem, inferPattern(env, p, binds));
        }
        yield Ty.list(elem);
      }
      case Pattern.Cons cons -> {
        Ty elem = inferPattern(env, cons.head(), binds);
        Unify.unify(Ty.list(elem), inferPattern(env, cons.tail(), binds));
        yield Ty.list(elem);
      }
      case Pattern.RecordPat r -> {
        Map<String, Ty> fields = new LinkedHashMap<>();
        for (String field : r.fields()) {
          Ty t = fresh();
          fields.put(field, t);
          binds.put(field, t);
        }
        yield new Ty.Record(fields, fresh());
      }
      case Pattern.Alias a -> {
        Ty t = inferPattern(env, a.pattern(), binds);
        binds.put(a.name(), t);
        yield t;
      }
      case Pattern.Ctor c -> {
        Ty ctor = instantiate(resolve(env, c.module(), c.name()));
        Ty cur = ctor;
        for (Pattern arg : c.args()) {
          Ty argTy = inferPattern(env, arg, binds);
          Ty rest = fresh();
          Unify.unify(cur, new Ty.Arrow(argTy, rest));
          cur = rest;
        }
        yield cur;
      }
    };
  }

  /** A one-line hint for common mistakes, derived from the bare error message, or {@code null}. */
  private static String hintFor(ElmTypeError e) {
    String m = e.rawMessage();
    if (m == null) {
      return null;
    }
    if (m.startsWith("All elements of a list")) {
      return "A list holds values of a single type. To combine different shapes, wrap them in a custom"
          + " type, or use a tuple/record for a fixed-size collection.";
    }
    if (m.startsWith("The branches of this `if`") || m.startsWith("The branches of this `case`")) {
      return "Every branch must produce the same type, since the expression has one type no matter"
          + " which branch is taken.";
    }
    if (m.contains(" argument to ")) {
      return "Check the argument's type — and that the function's arguments are in the right order.";
    }
    boolean hasInt = m.contains("Int"), hasFloat = m.contains("Float"), hasStr = m.contains("String");
    if (hasStr && (m.contains("number") || hasInt || hasFloat)) {
      return "Elm does not coerce numbers and strings. Convert with String.fromInt / String.fromFloat"
          + " (or String.toInt / String.toFloat).";
    }
    if (hasInt && hasFloat) {
      return "Int and Float are distinct in Elm. Use toFloat to widen an Int, or round/floor/truncate"
          + " to narrow a Float.";
    }
    if (m.contains("'number'")) {
      return "A 'number' must be an Int or a Float (the operator + - * works on both, but / needs Float"
          + " and // needs Int).";
    }
    if (m.contains("'appendable'")) {
      return "++ joins appendables: two Strings or two Lists of the same element type.";
    }
    if (m.contains("'comparable'")) {
      return "Comparison and ordering need a comparable: Int, Float, Char, String, or a List/tuple of"
          + " those.";
    }
    if (m.startsWith("Infinite type")) {
      return "A value cannot contain itself. This often means a function is applied to one too few or"
          + " too many arguments.";
    }
    if (m.startsWith("Record mismatch")) {
      return "Two records must have the same set of fields. A `{ r | … }` annotation only requires the"
          + " fields it names; check for a missing, extra or misspelled field.";
    }
    if (m.startsWith("Type mismatch") && m.contains("->")) {
      return "If this is a function call, check the number of arguments — a function/non-function"
          + " mismatch usually means it was applied to too many (or too few) arguments.";
    }
    return null;
  }

  // --- helpers -----------------------------------------------------------

  private TypeEnv extendMono(TypeEnv env, Map<String, Ty> binds) {
    TypeEnv e = env;
    for (var entry : binds.entrySet()) {
      e = e.extend(entry.getKey(), Scheme.mono(entry.getValue()));
    }
    return e;
  }

  private Ty instantiate(Scheme scheme) {
    return Types.instantiate(scheme, level);
  }

  private Scheme resolve(TypeEnv env, String module, String name) {
    // A qualified reference (e.g. `D.map`) must resolve through its module first: otherwise an
    // unqualified name brought in by `import X exposing (..)` (say `map` from Html) would shadow
    // the qualified `Json.Decode.map`. Only when the module-qualified form is unknown do we fall
    // back to the local/global unqualified binding.
    if (module != null) {
      String real = moduleAliases.getOrDefault(module, module);
      Scheme s = env.globals().get(real + "." + name);
      if (s == null) {
        s = env.lookup(name);
      }
      if (s == null) {
        s = env.globals().get(name);
      }
      if (s == null) {
        throw new ElmTypeError(
            "Unknown name: " + module + "." + name + suggest(name, env));
      }
      return s;
    }
    Scheme s = env.lookup(name);
    if (s == null) {
      s = env.globals().get(name);
    }
    if (s == null) {
      throw new ElmTypeError("Unknown name: " + name + suggest(name, env));
    }
    return s;
  }

  private Scheme operator(String op) {
    Scheme s = Signatures.operator(op);
    if (s == null) {
      throw new ElmTypeError("Unknown operator: " + op);
    }
    return s;
  }

  /** A "Did you mean …?" hint for an unknown {@code name}, drawn from the names in scope. */
  private static String suggest(String name, TypeEnv env) {
    return pl.matsuo.elm.util.Suggest.hint(name, env.candidateNames());
  }
}

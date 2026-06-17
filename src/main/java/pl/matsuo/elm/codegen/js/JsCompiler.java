package pl.matsuo.elm.codegen.js;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.ast.Type;
import pl.matsuo.elm.error.ElmRuntimeError;
import pl.matsuo.elm.interp.Prelude;
import pl.matsuo.elm.parser.Parser;

/**
 * Compiles an Elm {@link Module} (or single expression) to JavaScript source that runs on top of
 * {@link JsRuntime}. Functions become native curried arrow functions, so application is {@code
 * f(x)(y)}. The value model and {@code $show} mirror the interpreter exactly, enabling differential
 * testing.
 */
public final class JsCompiler {

  private final Set<String> builtinKeys = Prelude.builtins().keySet();
  private final Map<String, String> unqualified;
  private final Map<String, String> aliases = new HashMap<>();
  private final Map<String, Integer> ctorArity;
  /** Record type aliases: alias name -> ordered field names (they double as record constructors). */
  private final Map<String, List<String>> recordAliases = new HashMap<>();
  /** This module's OWN constructors, before the bundle-wide tables are merged in, so a constructor
   * reference can be resolved against its defining module — a name that is a record alias in one
   * module and a union constructor in another must not collide on its bare simple name. */
  private final Map<String, List<String>> ownRecordAliases = new HashMap<>();
  private final Map<String, Integer> ownCtorArity = new HashMap<>();
  private final Set<String> topLevelNames = new HashSet<>();
  private final String currentModule;
  private final Module module;

  private final Deque<Set<String>> localFrames = new ArrayDeque<>();
  private int counter = 0;

  /** Per-module export info for multi-module bundling (null for a single-module compile). */
  record ModuleInfo(
      String tag,
      Set<String> values,
      Map<String, List<String>> recordAliases,
      Map<String, Integer> ctorArity) {}

  /** Other modules in the same bundle, by module name. Null when compiling a single module. */
  private final Map<String, ModuleInfo> project;
  private final String moduleTag;

  private JsCompiler(Module module) {
    this(module, null);
  }

  private JsCompiler(Module module, Map<String, ModuleInfo> project) {
    // Constant-fold every declaration body before codegen (smaller, faster output).
    module =
        new Module(
            module.name(),
            module.exposing(),
            module.imports(),
            pl.matsuo.elm.opt.ConstantFold.foldDecls(module.decls()),
            module.pos());
    this.module = module;
    this.project = project;
    this.currentModule = module.name();
    this.moduleTag = sanitizeTag(module.name());
    this.ctorArity = Prelude.defaultCtorArity();
    this.unqualified = Prelude.defaultUnqualified();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        for (Decl.Union.Variant v : u.variants()) {
          ctorArity.put(v.name(), v.args().size());
          ownCtorArity.put(v.name(), v.args().size());
        }
      }
      if (d instanceof Decl.Value v) {
        topLevelNames.add(v.name());
      }
      if (d instanceof Decl.Port p) {
        topLevelNames.add(p.name()); // a port is referenced by name like any top-level value
      }
      if (d instanceof Decl.TypeAlias ta && ta.type() instanceof Type.Record rec) {
        List<String> fields = rec.fields().stream().map(Type.Record.Field::name).toList();
        recordAliases.put(ta.name(), fields);
        ownRecordAliases.put(ta.name(), fields);
      }
    }
    // In a bundle, other modules' constructors are referenced by name, so merge their record-alias
    // field lists and union-constructor arities into this module's tables.
    if (project != null) {
      for (var e : project.entrySet()) {
        if (!e.getKey().equals(currentModule)) {
          e.getValue().recordAliases().forEach(recordAliases::putIfAbsent);
          e.getValue().ctorArity().forEach(ctorArity::putIfAbsent);
        }
      }
    }
    for (Module.Import imp : module.imports()) {
      imp.alias().ifPresent(a -> aliases.put(a, imp.module()));
      if (imp.exposing().open()) {
        String prefix = imp.module() + ".";
        for (String key : builtinKeys) {
          if (key.startsWith(prefix)) {
            unqualified.put(key.substring(prefix.length()), key);
          }
        }
      } else {
        for (String name : imp.exposing().names()) {
          unqualified.put(name, imp.module() + "." + name);
        }
      }
    }
  }

  private static String sanitizeTag(String moduleName) {
    return moduleName.replaceAll("[^A-Za-z0-9]", "_");
  }

  /** The global JS identifier for one of this module's top-level definitions. */
  /** True if a port's type ultimately returns {@code Sub} (an incoming port), else outgoing. */
  private static boolean portReturnsSub(Type type) {
    Type t = type;
    while (t instanceof Type.Arrow a) {
      t = a.to();
    }
    return t instanceof Type.Con c && c.name().equals("Sub");
  }

  private String topLevelId(String name) {
    String id = jsIdent(name);
    return project == null ? "_$" + id : "_$" + moduleTag + "$" + id;
  }

  /** Encodes a name to a valid JS identifier fragment, so operator names like {@code +++} are safe. */
  private static String jsIdent(String name) {
    return name.chars()
        .mapToObj(
            c ->
                Character.isLetterOrDigit(c) || c == '_' || c == '$'
                    ? String.valueOf((char) c)
                    : "$op" + c)
        .collect(Collectors.joining());
  }

  // --- public API --------------------------------------------------------

  /** Full program that prints {@code main} (a pure value) via {@code $show}. */
  public static String moduleProgram(String source) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return Js.lines(JsRuntime.SOURCE, c.declarations(), Js.printShow(Js.local("main"))) + "\n";
  }

  /** The compiled program ({@code code}) and a Source Map v3 ({@code map}) tying it to the source. */
  public record Mapped(String code, String map) {}

  /**
   * Compiles to JS with a Source Map v3. Each generated declaration line carries two segments: its
   * column 0 maps to the declaration's source line/column, and the body column maps to the body
   * expression's source line/column. The code carries an inline {@code //# sourceMappingURL} URI.
   */
  public static Mapped moduleProgramWithSourceMap(String source, String sourceName) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    String prefix = JsRuntime.SOURCE + "\n";
    String decls = c.declarations();
    int prefixLines = prefix.split("\n", -1).length - 1; // generated lines before the declarations
    String map = SourceMap.json(sourceName, source, prefixLines, c.declSourceMappings());
    String b64 = java.util.Base64.getEncoder().encodeToString(map.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String code =
        prefix + decls + "\nprocess.stdout.write($show(_$main));\n"
            + "//# sourceMappingURL=data:application/json;base64," + b64 + "\n";
    return new Mapped(code, map);
  }

  /** Kernel + the module's compiled top-level declarations (no entry point), for embedding/calling. */
  public static String declarationsScript(String source) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return Js.lines(JsRuntime.SOURCE, c.declarations());
  }

  /**
   * Kernel + DOM/TEA runtime + the module's declarations, with no entry point. Use this (rather than
   * {@link #declarationsScript}) for a module whose top-level values reference the browser runtime
   * (e.g. {@code main = Browser.sandbox …}); without the DOM kernel those eager initialisers throw.
   */
  public static String declarationsScriptWithDom(String source) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return Js.lines(JsRuntime.SOURCE, JsRuntime.DOM, c.declarations());
  }

  /**
   * Kernel + DOM/TEA runtime + every module's declarations of a multi-module project, with no entry
   * point (no mount). Top-level names are module-qualified ({@code _$Module$name}). For embedding or
   * calling specific functions of a bundled project (e.g. under Node) without mounting a program.
   */
  public static String declarationsScriptWithDomProject(String... sources) {
    List<Module> modules = new ArrayList<>();
    for (String s : sources) {
      modules.add(Parser.parseModule(s));
    }
    Map<String, ModuleInfo> scope = buildScope(modules);
    return Js.lines(JsRuntime.SOURCE, JsRuntime.DOM, compileModules(modules, scope));
  }

  /** Every module's compiled declarations, in import order, concatenated. */
  private static String compileModules(List<Module> modules, Map<String, ModuleInfo> scope) {
    return orderModules(modules).stream()
        .map(m -> new JsCompiler(m, scope).declarations())
        .collect(Collectors.joining());
  }

  /** A browser app bundle: kernel + DOM/TEA runtime + module + a mount call. */
  public static String appBundle(String source) {
    // If the program imports pure bundled libraries (List.Extra, …), compile them in as a project.
    List<String> resolved = pl.matsuo.elm.interp.BundledLibs.resolve(List.of(source));
    if (resolved.size() > 1) {
      return appBundleProject(resolved.toArray(new String[0]));
    }
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return Js.lines(JsRuntime.SOURCE, JsRuntime.DOM, c.declarations(), Js.mount(Js.local("main"))) + "\n";
  }

  /**
   * A browser app bundle for a multi-module program: every module's source is compiled into one
   * bundle (top-level names are module-qualified to avoid collisions) and the entry module's
   * {@code main} is mounted. The entry is the module defining {@code main} (else the last source).
   */
  public static String appBundleProject(String... sources) {
    List<Module> modules = new ArrayList<>();
    for (String s : pl.matsuo.elm.interp.BundledLibs.resolve(List.of(sources))) {
      modules.add(Parser.parseModule(s));
    }
    Map<String, ModuleInfo> scope = buildScope(modules);
    String mainId = Js.qualifiedId(sanitizeTag(entryModule(modules).name()), "main");
    return Js.lines(JsRuntime.SOURCE, JsRuntime.DOM, compileModules(modules, scope), Js.mount(mainId))
        + "\n";
  }

  /**
   * Like {@link #appBundleProject} but with an <b>incremental, content-hashed build cache</b>: each
   * module's compiled declarations are stored under {@code cacheDir} keyed by a hash of its source
   * plus a project "interface salt" (every module's declared/exposed names). Editing one module's
   * body recompiles only that module — its source hash changes while the salt (names) does not, so
   * the other modules' fragments are reused. (Adding/removing/renaming a top-level changes the salt
   * and conservatively rebuilds all, which is sound.) Produces byte-identical output to
   * {@link #appBundleProject}.
   */
  public static String appBundleProjectCached(java.nio.file.Path cacheDir, String... sources) {
    List<Module> modules = new ArrayList<>();
    Map<String, String> sourceByName = new HashMap<>();
    for (String s : sources) {
      Module m = Parser.parseModule(s);
      modules.add(m);
      sourceByName.put(m.name(), s);
    }
    Map<String, ModuleInfo> scope = buildScope(modules);
    Module entry = entryModule(modules);
    String salt = interfaceSalt(modules);
    List<String> fragments = new ArrayList<>();
    try {
      java.nio.file.Files.createDirectories(cacheDir);
      for (Module m : orderModules(modules)) {
        String key = sha256(sourceByName.get(m.name()) + " " + salt);
        java.nio.file.Path frag = cacheDir.resolve(key + ".js");
        if (java.nio.file.Files.exists(frag)) {
          fragments.add(java.nio.file.Files.readString(frag, java.nio.charset.StandardCharsets.UTF_8));
        } else {
          String compiled = new JsCompiler(m, scope).declarations();
          java.nio.file.Files.writeString(frag, compiled, java.nio.charset.StandardCharsets.UTF_8);
          fragments.add(compiled);
        }
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    String mainId = Js.qualifiedId(sanitizeTag(entry.name()), "main");
    return Js.lines(JsRuntime.SOURCE, JsRuntime.DOM, String.join("", fragments), Js.mount(mainId))
        + "\n";
  }

  /** A salt over every module's declared/exposed names — what another module's codegen can depend
   *  on. Bodies and types are excluded, so a body-only edit doesn't invalidate other modules. */
  private static String interfaceSalt(List<Module> modules) {
    List<String> ifaces = new ArrayList<>();
    for (Module m : modules) {
      List<String> decls = new ArrayList<>();
      for (Decl d : m.decls()) {
        switch (d) {
          case Decl.Value v -> decls.add("v:" + v.name());
          case Decl.Port p -> decls.add("p:" + p.name());
          case Decl.TypeAlias ta -> decls.add("a:" + ta.name() + "/" + ta.params().size());
          case Decl.Union u ->
              decls.add(
                  "u:"
                      + u.name()
                      + u.variants().stream()
                          .map(variant -> "," + variant.name() + "/" + variant.args().size())
                          .collect(Collectors.joining()));
          default -> {}
        }
      }
      java.util.Collections.sort(decls);
      String exposing = m.exposing().open() ? "*" : m.exposing().names().toString();
      ifaces.add(m.name() + "|" + exposing + "|" + String.join(";", decls));
    }
    java.util.Collections.sort(ifaces);
    return sha256(String.join("\n", ifaces));
  }

  /** Hex SHA-256 of a string (the cache key / interface salt). */
  private static String sha256(String s) {
    try {
      byte[] h = java.security.MessageDigest.getInstance("SHA-256")
          .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A full HTML page hosting {@link #appBundleProject}. */
  public static String htmlPageProject(String driver, String... sources) {
    return Js.htmlAppPage(appBundleProject(sources) + "\n" + (driver == null ? "" : driver));
  }

  /** Orders modules so an imported module is emitted before its importer (cycles keep order). */
  private static List<Module> orderModules(List<Module> modules) {
    Set<String> names = new HashSet<>();
    for (Module m : modules) {
      names.add(m.name());
    }
    List<Module> out = new ArrayList<>();
    Set<String> done = new HashSet<>();
    boolean progress = true;
    while (out.size() < modules.size() && progress) {
      progress = false;
      for (Module m : modules) {
        if (done.contains(m.name())) {
          continue;
        }
        boolean ready =
            m.imports().stream()
                .allMatch(imp -> !names.contains(imp.module()) || done.contains(imp.module()));
        if (ready) {
          out.add(m);
          done.add(m.name());
          progress = true;
        }
      }
    }
    for (Module m : modules) {
      if (!done.contains(m.name())) {
        out.add(m);
      }
    }
    return out;
  }

  private static Module entryModule(List<Module> modules) {
    for (int i = modules.size() - 1; i >= 0; i--) {
      Module m = modules.get(i);
      if (m.decls().stream().anyMatch(d -> d instanceof Decl.Value v && v.name().equals("main"))) {
        return m;
      }
    }
    return modules.get(modules.size() - 1);
  }

  private static Map<String, ModuleInfo> buildScope(List<Module> modules) {
    Map<String, ModuleInfo> scope = new HashMap<>();
    for (Module m : modules) {
      Set<String> values = new HashSet<>();
      Map<String, List<String>> recAliases = new HashMap<>();
      Map<String, Integer> arity = new HashMap<>();
      for (Decl d : m.decls()) {
        if (d instanceof Decl.Value v) {
          values.add(v.name());
        } else if (d instanceof Decl.TypeAlias ta && ta.type() instanceof Type.Record rec) {
          recAliases.put(ta.name(), rec.fields().stream().map(Type.Record.Field::name).toList());
        } else if (d instanceof Decl.Union u) {
          for (Decl.Union.Variant variant : u.variants()) {
            arity.put(variant.name(), variant.args().size());
          }
        }
      }
      scope.put(m.name(), new ModuleInfo(sanitizeTag(m.name()), values, recAliases, arity));
    }
    return scope;
  }

  /** A full HTML page hosting {@link #appBundle}; {@code driver} (may be null) runs after mount. */
  public static String htmlPage(String source, String driver) {
    return Js.htmlAppPage(appBundle(source) + "\n" + (driver == null ? "" : driver));
  }

  /** Full program that prints the value of a single expression. */
  public static String expressionProgram(String expression) {
    Module empty =
        new Module(
            "Main",
            Module.Exposing.ALL,
            List.of(),
            List.of(),
            new pl.matsuo.elm.error.Position(1, 1, 0));
    JsCompiler c = new JsCompiler(empty);
    String e = c.compile(pl.matsuo.elm.opt.ConstantFold.fold(Parser.parseExpression(expression)));
    return Js.lines(JsRuntime.SOURCE, Js.printShow(Js.paren(e))) + "\n";
  }

  /**
   * A Node program that benchmarks a compiled single-argument function {@code fn} applied to {@code
   * n}: it prints "{@code <coldMs> <bestWarmMs>}" measured with {@code process.hrtime}. Used by the
   * cross-backend benchmark to time the JS backend on the same workload as the interpreter.
   */
  public static String benchProgram(String source, String fn, long n, int warmup, int measured) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return JsRuntime.SOURCE
        + "\n"
        + c.declarations()
        + "\nvar $f=_$" + fn + ";"
        + "\nvar $t=process.hrtime.bigint();$f(" + n + ");var $cold=Number(process.hrtime.bigint()-$t)/1e6;"
        + "\nfor(var i=1;i<" + warmup + ";i++)$f(" + n + ");"
        + "\nvar $best=Infinity;for(var j=0;j<" + measured + ";j++){var $s=process.hrtime.bigint();$f(" + n + ");var $ms=Number(process.hrtime.bigint()-$s)/1e6;if($ms<$best)$best=$ms;}"
        + "\nprocess.stdout.write($cold+' '+$best);\n";
  }

  /**
   * Browser-ready script: the kernel plus a global {@code $evalAll()} returning the {@code $show}
   * form of each expression. Used by the gallery's JS-vs-WASM page to evaluate snippets client-side.
   */
  public static String expressionsEvalScript(List<String> expressions) {
    Module empty =
        new Module(
            "Main",
            Module.Exposing.ALL,
            List.of(),
            List.of(),
            new pl.matsuo.elm.error.Position(1, 1, 0));
    JsCompiler c = new JsCompiler(empty);
    String shows =
        expressions.stream()
            .map(e -> Js.call("$show", Js.paren(c.compile(Parser.parseExpression(e)))))
            .collect(Collectors.joining(","));
    return Js.lines(JsRuntime.SOURCE, "function $evalAll(){return [" + shows + "];}") + "\n";
  }

  /**
   * One program that evaluates several expressions and prints their {@code $show} forms, one per
   * line. Used by differential testing to compare many results against the other backends in a
   * single Node invocation.
   */
  public static String expressionsProgram(List<String> expressions) {
    Module empty =
        new Module(
            "Main",
            Module.Exposing.ALL,
            List.of(),
            List.of(),
            new pl.matsuo.elm.error.Position(1, 1, 0));
    JsCompiler c = new JsCompiler(empty);
    String pushes =
        expressions.stream()
            .map(
                expr ->
                    "$out.push("
                        + Js.call(
                            "$show",
                            Js.paren(c.compile(pl.matsuo.elm.opt.ConstantFold.fold(Parser.parseExpression(expr)))))
                        + ");\n")
            .collect(Collectors.joining());
    return JsRuntime.SOURCE + "\nvar $out=[];\n" + pushes + "process.stdout.write($out.join(\"\\n\"));\n";
  }

  /**
   * Compiled top-level declarations: functions first (their bodies run lazily when called), then
   * parameterless values in dependency order, so a value that references another is emitted after
   * it (JS {@code var} initialisers run eagerly, unlike the interpreter's lazy thunks).
   */
  /** Source-map segments for each generated declaration line, in generated-line order. */
  private final List<List<SourceMap.Seg>> declMappings = new ArrayList<>();

  /** Source-map segments for each declaration line emitted by the last {@link #declarations}. */
  public List<List<SourceMap.Seg>> declSourceMappings() {
    return declMappings;
  }

  /** Two segments for a declaration line: column 0 -> the name, and the body column -> the body. */
  private void recordMapping(String name, pl.matsuo.elm.error.Position declPos, Expr body) {
    List<SourceMap.Seg> segs = new ArrayList<>();
    segs.add(new SourceMap.Seg(0, declPos.line() - 1, declPos.col() - 1));
    if (body != null) {
      int prefixLen = Js.topLevelVarPrefix(topLevelId(name)).length();
      segs.add(new SourceMap.Seg(prefixLen, body.pos().line() - 1, body.pos().col() - 1));
    }
    declMappings.add(segs);
  }

  public String declarations() {
    declMappings.clear();
    JsBlock out = new JsBlock();
    java.util.Map<String, Decl.Value> values = new java.util.LinkedHashMap<>(); // parameterless
    java.util.Map<String, Decl.Value> byName = new java.util.LinkedHashMap<>(); // all top-levels
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        byName.put(v.name(), v);
        if (v.params().isEmpty()) {
          values.put(v.name(), v);
        } else {
          out.add(Js.topLevelVar(topLevelId(v.name()), compileNamedFunction(v.name(), v.params(), v.body())));
          recordMapping(v.name(), v.pos(), v.body());
        }
      }
    }
    // Ports compile to kernel helpers: an outgoing port (`a -> Cmd msg`) becomes a function that
    // builds a Cmd which notifies JS subscribers; an incoming port (`(a -> msg) -> Sub msg`) becomes
    // a Sub that JS can `send` into. Both register the named port on the app's `ports` object.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Port p) {
        String portFn = portReturnsSub(p.type()) ? "$portIn" : "$portOut";
        out.add(Js.topLevelVar(topLevelId(p.name()), Js.call(portFn, Js.str(p.name()))));
        recordMapping(p.name(), p.pos(), null);
      }
    }
    java.util.Set<String> emitted = new java.util.HashSet<>();
    for (String name : values.keySet()) {
      emitValue(name, values, byName, emitted, new java.util.HashSet<>(), out);
    }
    return out.toString();
  }

  private void emitValue(
      String name,
      java.util.Map<String, Decl.Value> values,
      java.util.Map<String, Decl.Value> byName,
      java.util.Set<String> emitted,
      java.util.Set<String> visiting,
      JsBlock out) {
    if (emitted.contains(name) || !values.containsKey(name) || !visiting.add(name)) {
      return; // already emitted, not a value, or a cycle — emit in whatever order we reach it
    }
    // A value must be initialised after every value it depends on — including values reached
    // transitively through the top-level functions it calls (those run eagerly when this value's
    // initialiser does), so follow references through functions too.
    java.util.Set<String> deps = new java.util.HashSet<>();
    valueDeps(values.get(name).body(), byName, deps, new java.util.HashSet<>());
    for (String dep : deps) {
      emitValue(dep, values, byName, emitted, visiting, out);
    }
    if (emitted.add(name)) {
      out.add(Js.topLevelVar(topLevelId(name), compile(values.get(name).body())));
      recordMapping(name, values.get(name).pos(), values.get(name).body());
    }
  }

  /** Collects the parameterless-value names {@code body} depends on, following calls into functions. */
  private void valueDeps(
      Expr body,
      java.util.Map<String, Decl.Value> byName,
      java.util.Set<String> outValues,
      java.util.Set<String> visitedFns) {
    java.util.Set<String> refs = new java.util.HashSet<>();
    collectRefs(body, byName.keySet(), refs);
    for (String r : refs) {
      Decl.Value rv = byName.get(r);
      if (rv == null) {
        continue;
      }
      if (rv.params().isEmpty()) {
        outValues.add(r); // a value dependency
      } else if (visitedFns.add(r)) {
        valueDeps(rv.body(), byName, outValues, visitedFns); // a called function — follow its refs
      }
    }
  }

  /** Collects the names of top-level values referenced (unqualified) within {@code e}. */
  private void collectRefs(Expr e, java.util.Set<String> names, java.util.Set<String> out) {
    switch (e) {
      case Expr.Var v -> {
        if (v.module() == null && names.contains(v.name())) {
          out.add(v.name());
        }
      }
      case Expr.App a -> {
        collectRefs(a.fn(), names, out);
        collectRefs(a.arg(), names, out);
      }
      case Expr.BinOp b -> {
        collectRefs(b.left(), names, out);
        collectRefs(b.right(), names, out);
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

  // --- expression compilation -------------------------------------------

  private String compile(Expr e) {
    return switch (e) {
      case Expr.IntLit i -> Long.toString(i.value());
      case Expr.FloatLit f -> Double.toString(f.value());
      case Expr.StrLit s -> Js.str(s.value());
      case Expr.CharLit c -> Js.charLit(c.codePoint());
      case Expr.Shader s -> Js.data("$Shader", List.of(Js.str(s.source())));
      case Expr.Unit ignored -> "$unit";
      case Expr.Var v -> compileVar(v);
      case Expr.Ctor c -> compileCtor(c.module(), c.name());
      case Expr.OpFunc o ->
          pl.matsuo.elm.interp.Operators.isBuiltin(o.op())
              ? Js.paren(Js.arrow("a", Js.arrow("b", Js.binOp(o.op(), "a", "b"))))
              : compile(new Expr.Var(null, o.op(), o.pos())); // (op) as a value -> its function
      case Expr.ListLit l -> Js.list(compileEach(l.items()));
      case Expr.Tuple t -> Js.tuple(compileEach(t.items()));
      case Expr.Record r -> compileRecord(r);
      case Expr.RecordUpdate u -> compileRecordUpdate(u);
      case Expr.RecordAccess a -> Js.index(Js.paren(compile(a.target())), Js.str(a.field()));
      case Expr.Accessor a -> Js.paren(Js.arrow("r", Js.index("r", Js.str(a.field()))));
      case Expr.App app -> compileApp(app);
      case Expr.BinOp b ->
          pl.matsuo.elm.interp.Operators.isBuiltin(b.op())
              ? Js.binOp(b.op(), compile(b.left()), compile(b.right()))
              // A user/package-defined operator: `a op b` is the function `(op)` applied to a and b.
              : Js.apply(
                  Js.apply(compile(new Expr.Var(null, b.op(), b.pos())), compile(b.left())),
                  compile(b.right()));
      case Expr.Negate n -> Js.negate(compile(n.operand()));
      case Expr.If iff -> Js.ternary(compile(iff.cond()), compile(iff.thenBranch()), compile(iff.elseBranch()));
      case Expr.Lambda l -> compileLambda(l.params(), l.body());
      case Expr.Let let -> compileLet(let);
      case Expr.Case c -> compileCase(c);
    };
  }

  /** Each expression compiled to JS source, in order — for argument and element lists. */
  /**
   * Compiles an application. A constructor applied to exactly its arity (the common case: {@code
   * Just x}, a custom-type or record-alias constructor) is built directly as its {@code $data}/object
   * value, skipping the curried arrow chain and the intermediate closures each partial application
   * would otherwise allocate. Anything else (partial application, or a function/builtin head) falls
   * back to one-argument-at-a-time currying.
   */
  private String compileApp(Expr.App app) {
    List<Expr> args = new ArrayList<>();
    Expr cur = app;
    while (cur instanceof Expr.App a) {
      args.add(0, a.arg());
      cur = a.fn();
    }
    if (cur instanceof Expr.Ctor c) {
      String direct = compileSaturatedCtor(c.module(), c.name(), args);
      if (direct != null) {
        return direct;
      }
    }
    String out = compile(cur);
    for (Expr arg : args) {
      out = Js.apply(out, compile(arg));
    }
    return out;
  }

  /** The directly-built value for a constructor applied to exactly its arity, or null otherwise. */
  private String compileSaturatedCtor(String module, String name, List<Expr> args) {
    CtorRes r = resolveCtor(module, name);
    List<String> fields = r.recordFields() != null ? r.recordFields() : (r.known() ? null : recordAliases.get(name));
    if (fields != null) {
      if (args.size() != fields.size()) {
        return null;
      }
      List<String> entries = new ArrayList<>(fields.size());
      for (int i = 0; i < fields.size(); i++) {
        entries.add(Js.entry(fields.get(i), compile(args.get(i))));
      }
      return Js.object(entries);
    }
    int arity = r.unionArity() >= 0 ? r.unionArity() : ctorArity.getOrDefault(name, 0);
    if (arity == 0 || args.size() != arity) {
      return null; // 0-arity ctors (incl. True/False) and partial applications stay on the curried path
    }
    return Js.data(name, compileEach(args));
  }

  /** A constructor resolved against its defining module: a record alias (its field names) or a union
   * tag (its arity). {@link #UNKNOWN} when the module can't be determined, so the caller falls back
   * to the flat bundle-wide tables. */
  private record CtorRes(List<String> recordFields, int unionArity) {
    boolean known() {
      return recordFields != null || unionArity >= 0;
    }
  }

  private static final CtorRes UNKNOWN = new CtorRes(null, -1);

  /**
   * Resolves a constructor reference to the kind it has in its DEFINING module, so a name that is a
   * record alias in one module and a union constructor in another is disambiguated by its module
   * qualifier — qualified {@code Game.Move} is Game's union constructor, not Render's record-alias
   * {@code Move}, even though both share the simple name {@code Move}. Returns {@link #UNKNOWN} when
   * the defining module isn't known (e.g. an unqualified constructor imported from another module),
   * so the caller falls back to the flat, bundle-wide tables (prior behaviour).
   */
  private CtorRes resolveCtor(String qualifier, String name) {
    if (qualifier != null) {
      String real = aliases.getOrDefault(qualifier, qualifier);
      if (real.equals(currentModule)) {
        return ownCtor(name);
      }
      if (project != null && project.containsKey(real)) {
        ModuleInfo mi = project.get(real);
        if (mi.recordAliases().containsKey(name)) {
          return new CtorRes(mi.recordAliases().get(name), -1);
        }
        if (mi.ctorArity().containsKey(name)) {
          return new CtorRes(null, mi.ctorArity().get(name));
        }
      }
      return UNKNOWN;
    }
    // Unqualified: this module's own definition wins; otherwise leave it to the flat tables.
    return ownCtor(name);
  }

  /** The constructor `name` as defined in THIS module, or {@link #UNKNOWN}. */
  private CtorRes ownCtor(String name) {
    if (ownRecordAliases.containsKey(name)) {
      return new CtorRes(ownRecordAliases.get(name), -1);
    }
    if (ownCtorArity.containsKey(name)) {
      return new CtorRes(null, ownCtorArity.get(name));
    }
    return UNKNOWN;
  }

  private List<String> compileEach(List<Expr> items) {
    List<String> out = new ArrayList<>(items.size());
    for (Expr item : items) {
      out.add(compile(item));
    }
    return out;
  }

  private String compileVar(Expr.Var v) {
    if (v.module() == null) {
      if (isLocal(v.name())) {
        return Js.local(v.name());
      }
      if (topLevelNames.contains(v.name())) {
        return topLevelId(v.name());
      }
      String owner = externalOwner(v.name()); // a value imported from another bundled module
      if (owner != null) {
        return externalId(owner, v.name());
      }
      String canonical = unqualified.get(v.name());
      if (canonical != null && builtinKeys.contains(canonical)) {
        return Js.global(canonical);
      }
      if (builtinKeys.contains(v.name())) {
        return Js.global(v.name());
      }
      throw new ElmRuntimeError("Unbound variable in JS codegen: " + v.name());
    }
    String realModule = aliases.getOrDefault(v.module(), v.module());
    String canonical = realModule + "." + v.name();
    if (builtinKeys.contains(canonical)) {
      return Js.global(canonical);
    }
    if (realModule.equals(currentModule) && topLevelNames.contains(v.name())) {
      return topLevelId(v.name());
    }
    if (project != null
        && project.containsKey(realModule)
        && project.get(realModule).values().contains(v.name())) {
      return externalId(realModule, v.name());
    }
    // Not a known interpreter builtin, local or project name: assume it is provided by the JS
    // runtime kernel (which has effect/Math/WebGL builtins the interpreter list doesn't enumerate)
    // and resolve it dynamically. $g throws a clear "Unbound: <name>" at runtime if it is missing.
    return Js.global(canonical);
  }

  /** The module that exports unqualified {@code name} to this one (via imports), or null. */
  private String externalOwner(String name) {
    if (project == null) {
      return null;
    }
    for (Module.Import imp : module.imports()) {
      ModuleInfo info = project.get(imp.module());
      if (info == null || !info.values().contains(name)) {
        continue;
      }
      if (imp.exposing().open() || imp.exposing().names().contains(name)) {
        return imp.module();
      }
    }
    return null;
  }

  private String externalId(String moduleName, String name) {
    return "_$" + project.get(moduleName).tag() + "$" + name;
  }

  private String compileCtor(String module, String name) {
    if (name.equals("True")) {
      return "true";
    }
    if (name.equals("False")) {
      return "false";
    }
    CtorRes r = resolveCtor(module, name);
    // A record type alias is also a constructor: positional args build the record, curried.
    List<String> fields = r.recordFields() != null ? r.recordFields() : (r.known() ? null : recordAliases.get(name));
    if (fields != null) {
      List<String> params = argNames(fields.size());
      List<String> entries = new ArrayList<>(fields.size());
      for (int i = 0; i < fields.size(); i++) {
        entries.add(Js.entry(fields.get(i), params.get(i)));
      }
      return Js.curried(params, Js.object(entries));
    }
    int arity = r.unionArity() >= 0 ? r.unionArity() : ctorArity.getOrDefault(name, 0);
    if (arity == 0) {
      return Js.data(name, List.of());
    }
    // A union constructor: a curried function of its arguments that tags them.
    List<String> params = argNames(arity);
    return Js.curried(params, Js.data(name, params));
  }

  /** The positional argument names {@code [a0, a1, …, a(n-1)]} for a curried constructor. */
  private static List<String> argNames(int n) {
    List<String> names = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      names.add(Js.tmp("a", i));
    }
    return names;
  }

  private String compileRecord(Expr.Record r) {
    List<String> entries = new ArrayList<>(r.fields().size());
    for (Expr.Record.Field f : r.fields()) {
      entries.add(Js.entry(f.name(), compile(f.value())));
    }
    return Js.object(entries);
  }

  private String compileRecordUpdate(Expr.RecordUpdate u) {
    List<String> entries = new ArrayList<>(u.fields().size());
    for (Expr.Record.Field f : u.fields()) {
      entries.add(Js.entry(f.name(), compile(f.value())));
    }
    return Js.update(compileVar(new Expr.Var(null, u.base(), u.pos())), entries);
  }

  /**
   * The JS parameter name for each Elm pattern parameter: a plain var binds directly, anything else
   * gets a fresh {@code $p}<i>i</i> parameter whose destructuring bindings are appended to {@code
   * binds} (to run at the function-body top). The bound Elm names are added to {@code names}.
   */
  private String[] paramVars(List<Pattern> params, List<String> binds, Set<String> names) {
    String[] arg = new String[params.size()];
    for (int i = 0; i < params.size(); i++) {
      Pattern p = params.get(i);
      if (p instanceof Pattern.Var v) {
        arg[i] = Js.local(v.name());
        names.add(v.name());
      } else {
        arg[i] = Js.tmp("$p", i);
        if (!(p instanceof Pattern.Wildcard)) {
          patBinds(p, arg[i], binds, names);
        }
      }
    }
    return arg;
  }

  private String compileLambda(List<Pattern> params, Expr body) {
    List<String> binds = new ArrayList<>();
    Set<String> names = new HashSet<>();
    String[] arg = paramVars(params, binds, names);
    localFrames.push(names);
    String b = compile(body);
    localFrames.pop();
    return Js.curried(List.of(arg), Js.block(String.join("", binds) + Js.ret(b)));
  }

  /**
   * Compiles a named function (top-level or {@code let}-bound). If its body calls itself in tail
   * position at full arity, it is emitted as a {@code while(true)} loop that reassigns the parameters
   * and {@code continue}s — self-tail-call optimisation, so a tail-recursive sum over a million
   * elements runs in constant JS stack space instead of overflowing. Otherwise it is a plain curried
   * arrow chain. Detection bails out wherever the name is shadowed (by a parameter, or a nested
   * {@code let}/{@code case} binding), so a different binding of the same name is never mistaken for
   * the recursive call.
   */
  private String compileNamedFunction(String elmName, List<Pattern> params, Expr body) {
    if (params.isEmpty()
        || paramsBindName(params, elmName)
        || !isSelfTailRecursive(elmName, params.size(), body)) {
      return compileLambda(params, body);
    }
    List<String> binds = new ArrayList<>();
    Set<String> names = new HashSet<>();
    String[] arg = paramVars(params, binds, names);
    localFrames.push(names);
    JsBlock loop = new JsBlock();
    emitTail(body, elmName, arg, loop);
    localFrames.pop();
    // The loop reassigns the parameters each iteration, but in a curried function the outer
    // parameters are captured by the inner arrows' closures. Reassigning them there would corrupt a
    // *partial application* that is shared across calls — e.g. `List.map (f x) xs`, where each element
    // must see the same `x`, not the mutated remnant left by the previous element. So the wrapper
    // takes fresh parameters and copies them into the body's own parameter locals at the top of the
    // innermost body (once per call); the loop only ever mutates those locals.
    String[] outer = new String[arg.length];
    StringBuilder copies = new StringBuilder();
    for (int i = 0; i < arg.length; i++) {
      outer[i] = Js.tmp("$ca", counter++);
      copies.append(Js.varDecl(arg[i], outer[i]));
    }
    // Pattern binds re-run at the loop top so they reflect the reassigned parameters each iteration.
    String inner = Js.block(copies + Js.labeledWhileTrue("$tco", String.join("", binds) + loop));
    return Js.curried(List.of(outer), inner);
  }

  /** If {@code e} is a saturated application of the function named {@code elmName} (arity args, head
   * an unqualified or current-module reference to it), returns the argument expressions; else null. */
  private List<Expr> selfCallArgs(Expr e, String elmName, int arity) {
    List<Expr> args = new ArrayList<>();
    Expr cur = e;
    while (cur instanceof Expr.App app) {
      args.add(0, app.arg());
      cur = app.fn();
    }
    if (args.size() != arity || !(cur instanceof Expr.Var v) || !v.name().equals(elmName)) {
      return null;
    }
    // The head is the function name, unqualified or qualified with the current module. Shadowing is
    // ruled out by the callers (compileNamedFunction / the guards below) before we get here.
    String realModule = v.module() == null ? null : aliases.getOrDefault(v.module(), v.module());
    boolean refersToSelf = v.module() == null || currentModule.equals(realModule);
    return refersToSelf ? args : null;
  }

  /** Whether the function's body reaches a saturated self-call in tail position — stopping at any
   * nested binding that shadows the name. */
  private boolean isSelfTailRecursive(String elmName, int arity, Expr e) {
    if (selfCallArgs(e, elmName, arity) != null) {
      return true;
    }
    return switch (e) {
      case Expr.If iff ->
          isSelfTailRecursive(elmName, arity, iff.thenBranch())
              || isSelfTailRecursive(elmName, arity, iff.elseBranch());
      case Expr.Case c ->
          c.branches().stream()
              .anyMatch(
                  br ->
                      !patternBindsName(br.pattern(), elmName)
                          && isSelfTailRecursive(elmName, arity, br.body()));
      case Expr.Let let ->
          !letBindsName(let, elmName) && isSelfTailRecursive(elmName, arity, let.body());
      default -> false;
    };
  }

  /** Whether any of a function's parameters binds {@code name}. */
  private boolean paramsBindName(List<Pattern> params, String name) {
    Set<String> bound = new HashSet<>();
    for (Pattern p : params) {
      collectNames(p, bound);
    }
    return bound.contains(name);
  }

  /** Whether a {@code let} binds {@code name} (so an inner reference is not the outer self). */
  private boolean letBindsName(Expr.Let let, String name) {
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v && v.name().equals(name)) {
        return true;
      }
      if (d instanceof Decl.Destructure de) {
        Set<String> bound = new HashSet<>();
        collectNames(de.pattern(), bound);
        if (bound.contains(name)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean patternBindsName(Pattern p, String name) {
    Set<String> bound = new HashSet<>();
    collectNames(p, bound);
    return bound.contains(name);
  }

  /** Emits the body of a tail-recursive function as loop statements: a tail self-call reassigns the
   * parameters and {@code continue}s; if/case/let recurse into tail position; anything else returns. */
  private void emitTail(Expr e, String elmName, String[] arg, JsBlock out) {
    List<Expr> selfArgs = selfCallArgs(e, elmName, arg.length);
    if (selfArgs != null) {
      // Evaluate every new argument into a temp first (they may read the current parameters), then
      // reassign the parameters and loop.
      String[] tmp = new String[arg.length];
      for (int i = 0; i < arg.length; i++) {
        tmp[i] = Js.tmp("$tc", counter++);
        out.varDecl(tmp[i], compile(selfArgs.get(i)));
      }
      for (int i = 0; i < arg.length; i++) {
        out.assign(arg[i], tmp[i]);
      }
      out.continueTo("$tco");
      return;
    }
    switch (e) {
      case Expr.If iff -> {
        JsBlock thenBlock = new JsBlock();
        emitTail(iff.thenBranch(), elmName, arg, thenBlock);
        JsBlock elseBlock = new JsBlock();
        emitTail(iff.elseBranch(), elmName, arg, elseBlock);
        out.add(Js.ifElse(compile(iff.cond()), thenBlock.toString(), elseBlock.toString()));
      }
      case Expr.Case c -> {
        String sv = Js.tmp("$s", counter++);
        out.varDecl(sv, compile(c.scrutinee()));
        for (Expr.Case.Branch br : c.branches()) {
          List<String> conds = new ArrayList<>();
          List<String> binds = new ArrayList<>();
          Set<String> names = new HashSet<>();
          matchJs(br.pattern(), sv, conds, binds, names);
          localFrames.push(names);
          JsBlock branch = new JsBlock();
          // If the branch's pattern shadows the function name, its body can't be a self-tail-call.
          if (patternBindsName(br.pattern(), elmName)) {
            branch.ret(compile(br.body()));
          } else {
            emitTail(br.body(), elmName, arg, branch);
          }
          localFrames.pop();
          out.add(Js.ifStmt(Js.and(conds), String.join("", binds) + branch));
        }
        out.throwError("non-exhaustive pattern");
      }
      case Expr.Let let when !letBindsName(let, elmName) -> {
        Set<String> names = new HashSet<>();
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            names.add(v.name());
          } else if (d instanceof Decl.Destructure de) {
            collectNames(de.pattern(), names);
          }
        }
        localFrames.push(names);
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            String rhs =
                v.params().isEmpty()
                    ? compile(v.body())
                    : compileNamedFunction(v.name(), v.params(), v.body());
            out.varDecl(Js.local(v.name()), rhs);
          } else if (d instanceof Decl.Destructure de) {
            String t = Js.tmp("$d", counter++);
            out.varDecl(t, compile(de.body()));
            List<String> binds = new ArrayList<>();
            patBinds(de.pattern(), t, binds, new HashSet<>());
            out.add(String.join("", binds));
          }
        }
        emitTail(let.body(), elmName, arg, out);
        localFrames.pop();
      }
      default -> out.ret(compile(e));
    }
  }

  private String compileLet(Expr.Let let) {
    Set<String> names = new HashSet<>();
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        names.add(v.name());
      } else if (d instanceof Decl.Destructure de) {
        collectNames(de.pattern(), names);
      }
    }
    localFrames.push(names);
    JsBlock stmts = new JsBlock();
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        // A let-bound function gets self-tail-call optimisation too (like top-level functions).
        String rhs =
            v.params().isEmpty()
                ? compile(v.body())
                : compileNamedFunction(v.name(), v.params(), v.body());
        stmts.varDecl(Js.local(v.name()), rhs);
      } else if (d instanceof Decl.Destructure de) {
        String tmp = Js.tmp("$d", counter++);
        stmts.varDecl(tmp, compile(de.body()));
        List<String> binds = new ArrayList<>();
        patBinds(de.pattern(), tmp, binds, new HashSet<>());
        stmts.add(String.join("", binds));
      }
    }
    String body = compile(let.body());
    localFrames.pop();
    return Js.iife(stmts + Js.ret(body));
  }

  private String compileCase(Expr.Case c) {
    // When every branch dispatches on a literal (the scrutinee itself, or element 0 of a tuple
    // scrutinee), emit a JS `switch` — O(1) — instead of the sequential if-chain below. This turns a
    // big name-dispatch like the interpreter's `case (name, args) of` from O(branches) into O(1).
    String switched = trySwitchCase(c);
    if (switched != null) {
      return switched;
    }
    String sv = Js.tmp("$s", counter++);
    JsBlock body = new JsBlock();
    for (Expr.Case.Branch br : c.branches()) {
      List<String> conds = new ArrayList<>();
      List<String> binds = new ArrayList<>();
      Set<String> names = new HashSet<>();
      matchJs(br.pattern(), sv, conds, binds, names);
      localFrames.push(names);
      String e = compile(br.body());
      localFrames.pop();
      body.add(Js.ifStmt(Js.and(conds), String.join("", binds) + Js.ret(e)));
    }
    body.throwError("non-exhaustive pattern");
    return Js.iife(sv, body.toString(), compile(c.scrutinee()));
  }

  /**
   * Compiles a `case` to a JS `switch` when it is a literal dispatch: every branch (bar an optional
   * trailing wildcard/var default) matches a string/int literal in a fixed position — either the
   * scrutinee itself (`case s of "a" -> …`) or element 0 of a tuple scrutinee (`case (name, args) of
   * ("List.map", …) -> …`). The rest of a tuple pattern is matched with an `if` inside the case, and
   * branches sharing a literal are grouped under one label (JS forbids duplicate case labels), so the
   * first-match semantics of the if-chain are preserved. Returns null when not applicable.
   */
  private String trySwitchCase(Expr.Case c) {
    List<Expr.Case.Branch> brs = c.branches();
    if (brs.size() < 6) {
      return null; // not worth a switch for a handful of branches
    }
    // An optional trailing wildcard/var becomes the switch default.
    Expr.Case.Branch def = null;
    Pattern lastPat = brs.get(brs.size() - 1).pattern();
    if (lastPat instanceof Pattern.Wildcard || lastPat instanceof Pattern.Var) {
      def = brs.get(brs.size() - 1);
    }
    int dispatchCount = def == null ? brs.size() : brs.size() - 1;
    if (dispatchCount == 0) {
      return null;
    }
    // Detect the dispatch shape from the branches: all scalar-literal, or all tuple-with-literal-0,
    // of one literal kind (all string or all int) and (for tuples) one arity.
    boolean tupleMode = false;
    boolean intKind = false;
    int tupleSize = -1;
    for (int i = 0; i < dispatchCount; i++) {
      Pattern p = brs.get(i).pattern();
      boolean t;
      boolean isInt;
      if (p instanceof Pattern.StrLit) {
        t = false;
        isInt = false;
      } else if (p instanceof Pattern.IntLit) {
        t = false;
        isInt = true;
      } else if (p instanceof Pattern.Tuple tup && !tup.items().isEmpty()) {
        Pattern p0 = tup.items().get(0);
        if (p0 instanceof Pattern.StrLit) {
          isInt = false;
        } else if (p0 instanceof Pattern.IntLit) {
          isInt = true;
        } else {
          return null; // first element isn't a literal -> can't switch on it
        }
        t = true;
        if (tupleSize == -1) {
          tupleSize = tup.items().size();
        } else if (tupleSize != tup.items().size()) {
          return null; // mixed tuple arities
        }
      } else {
        return null; // a non-default branch that isn't a literal dispatch
      }
      if (i == 0) {
        tupleMode = t;
        intKind = isInt;
      } else if (t != tupleMode || isInt != intKind) {
        return null; // mixed shapes/kinds
      }
    }

    String sv = "$s" + (counter++);
    String key = tupleMode ? sv + ".vs[0]" : sv;

    // Group branches by literal value (insertion order), so duplicate keys share one case label.
    java.util.LinkedHashMap<String, List<Expr.Case.Branch>> groups = new java.util.LinkedHashMap<>();
    for (int i = 0; i < dispatchCount; i++) {
      Expr.Case.Branch b = brs.get(i);
      Pattern keyPat = tupleMode ? ((Pattern.Tuple) b.pattern()).items().get(0) : b.pattern();
      String label =
          intKind ? Long.toString(((Pattern.IntLit) keyPat).value()) : Js.str(((Pattern.StrLit) keyPat).value());
      groups.computeIfAbsent(label, k -> new ArrayList<>()).add(b);
    }

    List<String> cases = new ArrayList<>();
    for (var entry : groups.entrySet()) {
      JsBlock caseBody = new JsBlock();
      for (Expr.Case.Branch b : entry.getValue()) {
        List<String> conds = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (tupleMode) {
          List<Pattern> items = ((Pattern.Tuple) b.pattern()).items();
          for (int j = 1; j < items.size(); j++) {
            matchJs(items.get(j), Js.index(Js.dot(sv, "vs"), j), conds, binds, names);
          }
        }
        localFrames.push(names);
        String e = compile(b.body());
        localFrames.pop();
        String stmt = String.join("", binds) + Js.ret(e);
        caseBody.add(conds.isEmpty() ? stmt : Js.ifStmt(Js.and(conds), stmt));
      }
      cases.add(Js.caseLabel(entry.getKey(), caseBody.toString()));
    }
    JsBlock body = new JsBlock();
    body.add(Js.switchStmt(key, String.join("", cases)));
    // Default: reached when no case matched, or a case `break`s out (its arg patterns all failed).
    if (def != null) {
      Set<String> names = new HashSet<>();
      if (def.pattern() instanceof Pattern.Var v) {
        body.varDecl(Js.local(v.name()), sv);
        names.add(v.name());
      }
      localFrames.push(names);
      String e = compile(def.body());
      localFrames.pop();
      body.ret(e);
    } else {
      body.throwError("non-exhaustive pattern");
    }
    return Js.iife(sv, body.toString(), compile(c.scrutinee()));
  }

  // --- pattern compilation ----------------------------------------------

  /** Emits structural test conditions plus binding statements for a {@code case} pattern. */
  private void matchJs(Pattern p, String subj, List<String> conds, List<String> binds, Set<String> names) {
    switch (p) {
      case Pattern.Wildcard ignored -> {}
      case Pattern.Unit ignored -> {}
      case Pattern.Var v -> {
        binds.add(Js.varDecl(Js.local(v.name()), subj));
        names.add(v.name());
      }
      case Pattern.IntLit i -> conds.add(Js.strictEq(subj, Long.toString(i.value())));
      case Pattern.StrLit s -> conds.add(Js.strictEq(subj, Js.str(s.value())));
      case Pattern.CharLit c -> conds.add(Js.strictEq(Js.dot(subj, "c"), Integer.toString(c.codePoint())));
      case Pattern.Alias a -> {
        matchJs(a.pattern(), subj, conds, binds, names);
        binds.add(Js.varDecl(Js.local(a.name()), subj));
        names.add(a.name());
      }
      case Pattern.Ctor c -> {
        if (c.name().equals("True")) {
          conds.add(Js.strictEq(subj, "true"));
        } else if (c.name().equals("False")) {
          conds.add(Js.strictEq(subj, "false"));
        } else {
          conds.add(Js.strictEq(Js.dot(subj, "$"), Js.str(c.name())));
          for (int i = 0; i < c.args().size(); i++) {
            matchJs(c.args().get(i), Js.index(Js.dot(subj, "_"), i), conds, binds, names);
          }
        }
      }
      case Pattern.Tuple t -> {
        for (int i = 0; i < t.items().size(); i++) {
          matchJs(t.items().get(i), Js.index(Js.dot(subj, "vs"), i), conds, binds, names);
        }
      }
      case Pattern.Cons cons -> {
        conds.add(Js.strictEq(Js.dot(subj, "$"), Js.str("::")));
        matchJs(cons.head(), Js.dot(subj, "a"), conds, binds, names);
        matchJs(cons.tail(), Js.dot(subj, "b"), conds, binds, names);
      }
      case Pattern.ListPat l -> {
        String cur = subj;
        for (Pattern item : l.items()) {
          conds.add(Js.strictEq(Js.dot(cur, "$"), Js.str("::")));
          matchJs(item, Js.dot(cur, "a"), conds, binds, names);
          cur = Js.dot(cur, "b");
        }
        conds.add(Js.strictEq(Js.dot(cur, "$"), Js.str("[]")));
      }
      case Pattern.RecordPat r -> {
        for (String f : r.fields()) {
          binds.add(Js.varDecl(Js.local(f), Js.index(subj, Js.str(f))));
          names.add(f);
        }
      }
    }
  }

  /** Like {@link #matchJs} but only emits bindings (used for irrefutable let/lambda patterns). */
  private void patBinds(Pattern p, String subj, List<String> binds, Set<String> names) {
    List<String> ignoredConds = new ArrayList<>();
    matchJs(p, subj, ignoredConds, binds, names);
  }

  private void collectNames(Pattern p, Set<String> names) {
    patBinds(p, "$x", new ArrayList<>(), names);
  }

  // --- operators & helpers ----------------------------------------------

  private boolean isLocal(String name) {
    for (Set<String> frame : localFrames) {
      if (frame.contains(name)) {
        return true;
      }
    }
    return false;
  }

}

package pl.matsuo.elm.codegen.js;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        }
      }
      if (d instanceof Decl.Value v) {
        topLevelNames.add(v.name());
      }
      if (d instanceof Decl.Port p) {
        topLevelNames.add(p.name()); // a port is referenced by name like any top-level value
      }
      if (d instanceof Decl.TypeAlias ta && ta.type() instanceof Type.Record rec) {
        recordAliases.put(ta.name(), rec.fields().stream().map(Type.Record.Field::name).toList());
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
    StringBuilder sb = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
        sb.append(c);
      } else {
        sb.append("$op").append((int) c);
      }
    }
    return sb.toString();
  }

  // --- public API --------------------------------------------------------

  /** Full program that prints {@code main} (a pure value) via {@code $show}. */
  public static String moduleProgram(String source) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return JsRuntime.SOURCE + "\n" + c.declarations() + "\nprocess.stdout.write($show(_$main));\n";
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
    return JsRuntime.SOURCE + "\n" + c.declarations();
  }

  /**
   * Kernel + DOM/TEA runtime + the module's declarations, with no entry point. Use this (rather than
   * {@link #declarationsScript}) for a module whose top-level values reference the browser runtime
   * (e.g. {@code main = Browser.sandbox …}); without the DOM kernel those eager initialisers throw.
   */
  public static String declarationsScriptWithDom(String source) {
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return JsRuntime.SOURCE + "\n" + JsRuntime.DOM + "\n" + c.declarations();
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
    StringBuilder decls = new StringBuilder();
    for (Module m : orderModules(modules)) {
      decls.append(new JsCompiler(m, scope).declarations());
    }
    return JsRuntime.SOURCE + "\n" + JsRuntime.DOM + "\n" + decls;
  }

  /** A browser app bundle: kernel + DOM/TEA runtime + module + a mount call. */
  public static String appBundle(String source) {
    // If the program imports pure bundled libraries (List.Extra, …), compile them in as a project.
    List<String> resolved = pl.matsuo.elm.interp.BundledLibs.resolve(List.of(source));
    if (resolved.size() > 1) {
      return appBundleProject(resolved.toArray(new String[0]));
    }
    JsCompiler c = new JsCompiler(Parser.parseModule(source));
    return JsRuntime.SOURCE
        + "\n"
        + JsRuntime.DOM
        + "\n"
        + c.declarations()
        + "\nwindow.$start(_$main, document.getElementById('app'));\n";
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
    Module entry = entryModule(modules);
    StringBuilder decls = new StringBuilder();
    for (Module m : orderModules(modules)) { // imported modules first, so eager values resolve
      decls.append(new JsCompiler(m, scope).declarations());
    }
    String mainId = "_$" + sanitizeTag(entry.name()) + "$main";
    return JsRuntime.SOURCE
        + "\n"
        + JsRuntime.DOM
        + "\n"
        + decls
        + "\nwindow.$start(" + mainId + ", document.getElementById('app'));\n";
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
    StringBuilder decls = new StringBuilder();
    try {
      java.nio.file.Files.createDirectories(cacheDir);
      for (Module m : orderModules(modules)) {
        String key = sha256(sourceByName.get(m.name()) + " " + salt);
        java.nio.file.Path frag = cacheDir.resolve(key + ".js");
        if (java.nio.file.Files.exists(frag)) {
          decls.append(java.nio.file.Files.readString(frag, java.nio.charset.StandardCharsets.UTF_8));
        } else {
          String compiled = new JsCompiler(m, scope).declarations();
          java.nio.file.Files.writeString(frag, compiled, java.nio.charset.StandardCharsets.UTF_8);
          decls.append(compiled);
        }
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    String mainId = "_$" + sanitizeTag(entry.name()) + "$main";
    return JsRuntime.SOURCE + "\n" + JsRuntime.DOM + "\n" + decls
        + "\nwindow.$start(" + mainId + ", document.getElementById('app'));\n";
  }

  /** A salt over every module's declared/exposed names — what another module's codegen can depend
   *  on. Bodies and types are excluded, so a body-only edit doesn't invalidate other modules. */
  private static String interfaceSalt(List<Module> modules) {
    List<String> ifaces = new ArrayList<>();
    for (Module m : modules) {
      StringBuilder b = new StringBuilder(m.name()).append('|');
      b.append(m.exposing().open() ? "*" : m.exposing().names()).append('|');
      List<String> decls = new ArrayList<>();
      for (Decl d : m.decls()) {
        switch (d) {
          case Decl.Value v -> decls.add("v:" + v.name());
          case Decl.Port p -> decls.add("p:" + p.name());
          case Decl.TypeAlias ta -> decls.add("a:" + ta.name() + "/" + ta.params().size());
          case Decl.Union u -> {
            StringBuilder u2 = new StringBuilder("u:" + u.name());
            for (Decl.Union.Variant variant : u.variants()) {
              u2.append(',').append(variant.name()).append('/').append(variant.args().size());
            }
            decls.add(u2.toString());
          }
          default -> {}
        }
      }
      java.util.Collections.sort(decls);
      b.append(String.join(";", decls));
      ifaces.add(b.toString());
    }
    java.util.Collections.sort(ifaces);
    return sha256(String.join("\n", ifaces));
  }

  /** Hex SHA-256 of a string (the cache key / interface salt). */
  private static String sha256(String s) {
    try {
      byte[] h = java.security.MessageDigest.getInstance("SHA-256")
          .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(h.length * 2);
      for (byte b : h) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A full HTML page hosting {@link #appBundleProject}. */
  public static String htmlPageProject(String driver, String... sources) {
    return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><div id=\"app\"></div>\n"
        + "<script>\n"
        + appBundleProject(sources)
        + "\n"
        + (driver == null ? "" : driver)
        + "\n</script></body></html>\n";
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
    return "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><div id=\"app\"></div>\n"
        + "<script>\n"
        + appBundle(source)
        + "\n"
        + (driver == null ? "" : driver)
        + "\n</script></body></html>\n";
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
    return JsRuntime.SOURCE + "\nprocess.stdout.write($show((" + e + ")));\n";
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
    StringBuilder sb = new StringBuilder(JsRuntime.SOURCE).append("\nfunction $evalAll(){return [");
    for (int i = 0; i < expressions.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("$show((").append(c.compile(Parser.parseExpression(expressions.get(i)))).append("))");
    }
    return sb.append("];}\n").toString();
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
    StringBuilder sb = new StringBuilder(JsRuntime.SOURCE).append("\nvar $out=[];\n");
    for (String expr : expressions) {
      sb.append("$out.push($show((")
          .append(c.compile(pl.matsuo.elm.opt.ConstantFold.fold(Parser.parseExpression(expr))))
          .append(")));\n");
    }
    return sb.append("process.stdout.write($out.join(\"\\n\"));\n").toString();
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
      int prefixLen = ("var " + topLevelId(name) + " = ").length();
      segs.add(new SourceMap.Seg(prefixLen, body.pos().line() - 1, body.pos().col() - 1));
    }
    declMappings.add(segs);
  }

  public String declarations() {
    declMappings.clear();
    StringBuilder sb = new StringBuilder();
    java.util.Map<String, Decl.Value> values = new java.util.LinkedHashMap<>(); // parameterless
    java.util.Map<String, Decl.Value> byName = new java.util.LinkedHashMap<>(); // all top-levels
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        byName.put(v.name(), v);
        if (v.params().isEmpty()) {
          values.put(v.name(), v);
        } else {
          sb.append("var ").append(topLevelId(v.name())).append(" = ")
              .append(compileNamedFunction(v.name(), v.params(), v.body())).append(";\n");
          recordMapping(v.name(), v.pos(), v.body());
        }
      }
    }
    // Ports compile to kernel helpers: an outgoing port (`a -> Cmd msg`) becomes a function that
    // builds a Cmd which notifies JS subscribers; an incoming port (`(a -> msg) -> Sub msg`) becomes
    // a Sub that JS can `send` into. Both register the named port on the app's `ports` object.
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Port p) {
        boolean incoming = portReturnsSub(p.type());
        sb.append("var ")
            .append(topLevelId(p.name()))
            .append(incoming ? " = $portIn(" : " = $portOut(")
            .append(jsString(p.name()))
            .append(");\n");
        recordMapping(p.name(), p.pos(), null);
      }
    }
    java.util.Set<String> emitted = new java.util.HashSet<>();
    for (String name : values.keySet()) {
      emitValue(name, values, byName, emitted, new java.util.HashSet<>(), sb);
    }
    return sb.toString();
  }

  private void emitValue(
      String name,
      java.util.Map<String, Decl.Value> values,
      java.util.Map<String, Decl.Value> byName,
      java.util.Set<String> emitted,
      java.util.Set<String> visiting,
      StringBuilder sb) {
    if (emitted.contains(name) || !values.containsKey(name) || !visiting.add(name)) {
      return; // already emitted, not a value, or a cycle — emit in whatever order we reach it
    }
    // A value must be initialised after every value it depends on — including values reached
    // transitively through the top-level functions it calls (those run eagerly when this value's
    // initialiser does), so follow references through functions too.
    java.util.Set<String> deps = new java.util.HashSet<>();
    valueDeps(values.get(name).body(), byName, deps, new java.util.HashSet<>());
    for (String dep : deps) {
      emitValue(dep, values, byName, emitted, visiting, sb);
    }
    if (emitted.add(name)) {
      sb.append("var ").append(topLevelId(name)).append(" = ").append(compile(values.get(name).body()))
          .append(";\n");
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
      case Expr.StrLit s -> jsString(s.value());
      case Expr.CharLit c -> "$char(" + c.codePoint() + ")";
      case Expr.Shader s -> "$data(\"$Shader\",[" + jsString(s.source()) + "])";
      case Expr.Unit ignored -> "$unit";
      case Expr.Var v -> compileVar(v);
      case Expr.Ctor c -> compileCtor(c.name());
      case Expr.OpFunc o ->
          pl.matsuo.elm.interp.Operators.isBuiltin(o.op())
              ? "(a=>b=>" + binJs(o.op(), "a", "b") + ")"
              : compile(new Expr.Var(null, o.op(), o.pos())); // (op) as a value -> its function
      case Expr.ListLit l -> "$list([" + compileList(l.items()) + "])";
      case Expr.Tuple t -> "$tuple([" + compileList(t.items()) + "])";
      case Expr.Record r -> compileRecord(r);
      case Expr.RecordUpdate u -> compileRecordUpdate(u);
      case Expr.RecordAccess a -> "(" + compile(a.target()) + ")[" + jsString(a.field()) + "]";
      case Expr.Accessor a -> "(r=>r[" + jsString(a.field()) + "])";
      case Expr.App app -> "(" + compile(app.fn()) + ")(" + compile(app.arg()) + ")";
      case Expr.BinOp b ->
          pl.matsuo.elm.interp.Operators.isBuiltin(b.op())
              ? binJs(b.op(), compile(b.left()), compile(b.right()))
              // A user/package-defined operator: `a op b` is the function `(op)` applied to a and b.
              : "(" + compile(new Expr.Var(null, b.op(), b.pos())) + ")(" + compile(b.left()) + ")(" + compile(b.right()) + ")";
      case Expr.Negate n -> "(-(" + compile(n.operand()) + "))";
      case Expr.If iff ->
          "(" + compile(iff.cond()) + " ? " + compile(iff.thenBranch()) + " : "
              + compile(iff.elseBranch()) + ")";
      case Expr.Lambda l -> compileLambda(l.params(), l.body());
      case Expr.Let let -> compileLet(let);
      case Expr.Case c -> compileCase(c);
    };
  }

  private String compileList(List<Expr> items) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(compile(items.get(i)));
    }
    return sb.toString();
  }

  private String compileVar(Expr.Var v) {
    if (v.module() == null) {
      if (isLocal(v.name())) {
        return jsVar(v.name());
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
        return "$g(" + jsString(canonical) + ")";
      }
      if (builtinKeys.contains(v.name())) {
        return "$g(" + jsString(v.name()) + ")";
      }
      throw new ElmRuntimeError("Unbound variable in JS codegen: " + v.name());
    }
    String realModule = aliases.getOrDefault(v.module(), v.module());
    String canonical = realModule + "." + v.name();
    if (builtinKeys.contains(canonical)) {
      return "$g(" + jsString(canonical) + ")";
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
    return "$g(" + jsString(canonical) + ")";
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

  private String compileCtor(String name) {
    if (name.equals("True")) {
      return "true";
    }
    if (name.equals("False")) {
      return "false";
    }
    // A record type alias is also a constructor: positional args build the record, curried.
    List<String> fields = recordAliases.get(name);
    if (fields != null) {
      StringBuilder chain = new StringBuilder();
      StringBuilder body = new StringBuilder("({");
      for (int i = 0; i < fields.size(); i++) {
        chain.append("a").append(i).append("=>");
        if (i > 0) {
          body.append(",");
        }
        body.append(jsString(fields.get(i))).append(":a").append(i);
      }
      return "(" + chain + body.append("})") + ")";
    }
    int arity = ctorArity.getOrDefault(name, 0);
    if (arity == 0) {
      return "$data(" + jsString(name) + ",[])";
    }
    StringBuilder args = new StringBuilder();
    StringBuilder chain = new StringBuilder();
    for (int i = 0; i < arity; i++) {
      chain.append("a").append(i).append("=>");
      if (i > 0) {
        args.append(",");
      }
      args.append("a").append(i);
    }
    return "(" + chain + "$data(" + jsString(name) + ",[" + args + "]))";
  }

  private String compileRecord(Expr.Record r) {
    StringBuilder sb = new StringBuilder("({");
    for (int i = 0; i < r.fields().size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      Expr.Record.Field f = r.fields().get(i);
      sb.append(jsString(f.name())).append(":").append(compile(f.value()));
    }
    return sb.append("})").toString();
  }

  private String compileRecordUpdate(Expr.RecordUpdate u) {
    StringBuilder sb = new StringBuilder("$update(").append(compileVar(new Expr.Var(null, u.base(), u.pos())));
    sb.append(",{");
    for (int i = 0; i < u.fields().size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      Expr.Record.Field f = u.fields().get(i);
      sb.append(jsString(f.name())).append(":").append(compile(f.value()));
    }
    return sb.append("})").toString();
  }

  private String compileLambda(List<Pattern> params, Expr body) {
    List<String> binds = new ArrayList<>();
    Set<String> names = new HashSet<>();
    String[] arg = new String[params.size()];
    for (int i = 0; i < params.size(); i++) {
      Pattern p = params.get(i);
      if (p instanceof Pattern.Var v) {
        arg[i] = jsVar(v.name());
        names.add(v.name());
      } else if (p instanceof Pattern.Wildcard) {
        arg[i] = "$p" + i;
      } else {
        arg[i] = "$p" + i;
        patBinds(p, "$p" + i, binds, names);
      }
    }
    localFrames.push(names);
    String b = compile(body);
    localFrames.pop();
    String inner = "{" + String.join("", binds) + "return " + b + ";}";
    StringBuilder sb = new StringBuilder(inner);
    for (int i = params.size() - 1; i >= 0; i--) {
      sb = new StringBuilder(arg[i] + "=>" + sb);
    }
    return "(" + sb + ")";
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
    String[] arg = new String[params.size()];
    for (int i = 0; i < params.size(); i++) {
      Pattern p = params.get(i);
      if (p instanceof Pattern.Var v) {
        arg[i] = jsVar(v.name());
        names.add(v.name());
      } else if (p instanceof Pattern.Wildcard) {
        arg[i] = "$p" + i;
      } else {
        arg[i] = "$p" + i;
        patBinds(p, "$p" + i, binds, names);
      }
    }
    localFrames.push(names);
    StringBuilder loop = new StringBuilder();
    emitTail(body, elmName, arg, loop);
    localFrames.pop();
    // Pattern binds re-run at the loop top so they reflect the reassigned parameters each iteration.
    String inner = "{$tco: while(true){" + String.join("", binds) + loop + "}}";
    StringBuilder sb = new StringBuilder(inner);
    for (int i = params.size() - 1; i >= 0; i--) {
      sb = new StringBuilder(arg[i] + "=>" + sb);
    }
    return "(" + sb + ")";
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
  private void emitTail(Expr e, String elmName, String[] arg, StringBuilder out) {
    List<Expr> selfArgs = selfCallArgs(e, elmName, arg.length);
    if (selfArgs != null) {
      // Evaluate every new argument into a temp first (they may read the current parameters), then
      // reassign the parameters and loop.
      String[] tmp = new String[arg.length];
      for (int i = 0; i < arg.length; i++) {
        tmp[i] = "$tc" + (counter++);
        out.append("var ").append(tmp[i]).append("=").append(compile(selfArgs.get(i))).append(";");
      }
      for (int i = 0; i < arg.length; i++) {
        out.append(arg[i]).append("=").append(tmp[i]).append(";");
      }
      out.append("continue $tco;");
      return;
    }
    switch (e) {
      case Expr.If iff -> {
        out.append("if(").append(compile(iff.cond())).append("){");
        emitTail(iff.thenBranch(), elmName, arg, out);
        out.append("}else{");
        emitTail(iff.elseBranch(), elmName, arg, out);
        out.append("}");
      }
      case Expr.Case c -> {
        String sv = "$s" + (counter++);
        out.append("var ").append(sv).append("=").append(compile(c.scrutinee())).append(";");
        for (Expr.Case.Branch br : c.branches()) {
          List<String> conds = new ArrayList<>();
          List<String> binds = new ArrayList<>();
          Set<String> names = new HashSet<>();
          matchJs(br.pattern(), sv, conds, binds, names);
          String cond = conds.isEmpty() ? "true" : String.join(" && ", conds);
          out.append("if(").append(cond).append("){").append(String.join("", binds));
          localFrames.push(names);
          // If the branch's pattern shadows the function name, its body can't be a self-tail-call.
          if (patternBindsName(br.pattern(), elmName)) {
            out.append("return ").append(compile(br.body())).append(";");
          } else {
            emitTail(br.body(), elmName, arg, out);
          }
          localFrames.pop();
          out.append("}");
        }
        out.append("throw new Error('non-exhaustive pattern');");
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
            out.append("var ").append(jsVar(v.name())).append("=").append(rhs).append(";");
          } else if (d instanceof Decl.Destructure de) {
            String t = "$d" + (counter++);
            out.append("var ").append(t).append("=").append(compile(de.body())).append(";");
            List<String> binds = new ArrayList<>();
            patBinds(de.pattern(), t, binds, new HashSet<>());
            out.append(String.join("", binds));
          }
        }
        emitTail(let.body(), elmName, arg, out);
        localFrames.pop();
      }
      default -> out.append("return ").append(compile(e)).append(";");
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
    StringBuilder stmts = new StringBuilder();
    for (Decl d : let.defs()) {
      if (d instanceof Decl.Value v) {
        // A let-bound function gets self-tail-call optimisation too (like top-level functions).
        String rhs =
            v.params().isEmpty()
                ? compile(v.body())
                : compileNamedFunction(v.name(), v.params(), v.body());
        stmts.append("var ").append(jsVar(v.name())).append("=").append(rhs).append(";");
      } else if (d instanceof Decl.Destructure de) {
        String tmp = "$d" + (counter++);
        stmts.append("var ").append(tmp).append("=").append(compile(de.body())).append(";");
        List<String> binds = new ArrayList<>();
        patBinds(de.pattern(), tmp, binds, new HashSet<>());
        stmts.append(String.join("", binds));
      }
    }
    String body = compile(let.body());
    localFrames.pop();
    return "(function(){" + stmts + "return " + body + ";})()";
  }

  private String compileCase(Expr.Case c) {
    // When every branch dispatches on a literal (the scrutinee itself, or element 0 of a tuple
    // scrutinee), emit a JS `switch` — O(1) — instead of the sequential if-chain below. This turns a
    // big name-dispatch like the interpreter's `case (name, args) of` from O(branches) into O(1).
    String switched = trySwitchCase(c);
    if (switched != null) {
      return switched;
    }
    String sv = "$s" + (counter++);
    String scrut = compile(c.scrutinee());
    StringBuilder body = new StringBuilder();
    for (Expr.Case.Branch br : c.branches()) {
      List<String> conds = new ArrayList<>();
      List<String> binds = new ArrayList<>();
      Set<String> names = new HashSet<>();
      matchJs(br.pattern(), sv, conds, binds, names);
      String cond = conds.isEmpty() ? "true" : String.join(" && ", conds);
      localFrames.push(names);
      String e = compile(br.body());
      localFrames.pop();
      body.append("if(").append(cond).append("){").append(String.join("", binds))
          .append("return ").append(e).append(";}");
    }
    body.append("throw new Error('non-exhaustive pattern');");
    return "(function(" + sv + "){" + body + "})(" + scrut + ")";
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
          intKind ? Long.toString(((Pattern.IntLit) keyPat).value()) : jsString(((Pattern.StrLit) keyPat).value());
      groups.computeIfAbsent(label, k -> new ArrayList<>()).add(b);
    }

    StringBuilder body = new StringBuilder();
    body.append("switch(").append(key).append("){");
    for (var entry : groups.entrySet()) {
      body.append("case ").append(entry.getKey()).append(":{");
      for (Expr.Case.Branch b : entry.getValue()) {
        List<String> conds = new ArrayList<>();
        List<String> binds = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (tupleMode) {
          List<Pattern> items = ((Pattern.Tuple) b.pattern()).items();
          for (int j = 1; j < items.size(); j++) {
            matchJs(items.get(j), sv + ".vs[" + j + "]", conds, binds, names);
          }
        }
        localFrames.push(names);
        String e = compile(b.body());
        localFrames.pop();
        String bindStr = String.join("", binds);
        if (conds.isEmpty()) {
          body.append(bindStr).append("return ").append(e).append(";");
        } else {
          body.append("if(").append(String.join(" && ", conds)).append("){").append(bindStr)
              .append("return ").append(e).append(";}");
        }
      }
      body.append("break;}");
    }
    body.append("}");
    // Default: reached when no case matched, or a case `break`s out (its arg patterns all failed).
    if (def != null) {
      Set<String> names = new HashSet<>();
      String bind = "";
      if (def.pattern() instanceof Pattern.Var v) {
        bind = "var " + jsVar(v.name()) + "=" + sv + ";";
        names.add(v.name());
      }
      localFrames.push(names);
      String e = compile(def.body());
      localFrames.pop();
      body.append(bind).append("return ").append(e).append(";");
    } else {
      body.append("throw new Error('non-exhaustive pattern');");
    }
    return "(function(" + sv + "){" + body + "})(" + compile(c.scrutinee()) + ")";
  }

  // --- pattern compilation ----------------------------------------------

  /** Emits structural test conditions plus binding statements for a {@code case} pattern. */
  private void matchJs(Pattern p, String subj, List<String> conds, List<String> binds, Set<String> names) {
    switch (p) {
      case Pattern.Wildcard ignored -> {}
      case Pattern.Unit ignored -> {}
      case Pattern.Var v -> {
        binds.add("var " + jsVar(v.name()) + "=" + subj + ";");
        names.add(v.name());
      }
      case Pattern.IntLit i -> conds.add(subj + " === " + i.value());
      case Pattern.StrLit s -> conds.add(subj + " === " + jsString(s.value()));
      case Pattern.CharLit c -> conds.add(subj + ".c === " + c.codePoint());
      case Pattern.Alias a -> {
        matchJs(a.pattern(), subj, conds, binds, names);
        binds.add("var " + jsVar(a.name()) + "=" + subj + ";");
        names.add(a.name());
      }
      case Pattern.Ctor c -> {
        if (c.name().equals("True")) {
          conds.add(subj + " === true");
        } else if (c.name().equals("False")) {
          conds.add(subj + " === false");
        } else {
          conds.add(subj + ".$ === " + jsString(c.name()));
          for (int i = 0; i < c.args().size(); i++) {
            matchJs(c.args().get(i), subj + "._[" + i + "]", conds, binds, names);
          }
        }
      }
      case Pattern.Tuple t -> {
        for (int i = 0; i < t.items().size(); i++) {
          matchJs(t.items().get(i), subj + ".vs[" + i + "]", conds, binds, names);
        }
      }
      case Pattern.Cons cons -> {
        conds.add(subj + ".$ === '::'");
        matchJs(cons.head(), subj + ".a", conds, binds, names);
        matchJs(cons.tail(), subj + ".b", conds, binds, names);
      }
      case Pattern.ListPat l -> {
        String cur = subj;
        for (Pattern item : l.items()) {
          conds.add(cur + ".$ === '::'");
          matchJs(item, cur + ".a", conds, binds, names);
          cur = cur + ".b";
        }
        conds.add(cur + ".$ === '[]'");
      }
      case Pattern.RecordPat r -> {
        for (String f : r.fields()) {
          binds.add("var " + jsVar(f) + "=" + subj + "[" + jsString(f) + "];");
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

  private String binJs(String op, String l, String r) {
    return switch (op) {
      case "+" -> "(" + l + " + " + r + ")";
      case "-" -> "(" + l + " - " + r + ")";
      case "*" -> "(" + l + " * " + r + ")";
      case "/" -> "(" + l + " / " + r + ")";
      case "//" -> "Math.trunc(" + l + " / " + r + ")";
      case "^" -> "Math.pow(" + l + ", " + r + ")";
      case "==" -> "$eq(" + l + ", " + r + ")";
      case "/=" -> "(!$eq(" + l + ", " + r + "))";
      case "<" -> "($cmp(" + l + ", " + r + ") < 0)";
      case ">" -> "($cmp(" + l + ", " + r + ") > 0)";
      case "<=" -> "($cmp(" + l + ", " + r + ") <= 0)";
      case ">=" -> "($cmp(" + l + ", " + r + ") >= 0)";
      case "&&" -> "(" + l + " && " + r + ")";
      case "||" -> "(" + l + " || " + r + ")";
      case "++" -> "$append(" + l + ", " + r + ")";
      case "::" -> "$cons(" + l + ", " + r + ")";
      case "|>" -> "(" + r + ")(" + l + ")";
      case "<|" -> "(" + l + ")(" + r + ")";
      case "<<" -> "$compose(" + l + ", " + r + ")";
      case ">>" -> "$compose(" + r + ", " + l + ")";
      default -> throw new ElmRuntimeError("Unknown operator in JS codegen: " + op);
    };
  }

  private boolean isLocal(String name) {
    for (Set<String> frame : localFrames) {
      if (frame.contains(name)) {
        return true;
      }
    }
    return false;
  }

  private static String jsVar(String name) {
    return "_$" + name;
  }

  /** Produces a JavaScript double-quoted string literal. */
  static String jsString(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append("\"").toString();
  }
}

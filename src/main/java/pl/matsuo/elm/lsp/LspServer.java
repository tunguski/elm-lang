package pl.matsuo.elm.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Expr;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.ast.Pattern;
import pl.matsuo.elm.error.ElmSyntaxError;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.json.JsonEncode;
import pl.matsuo.elm.json.JsonParse;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.types.TypeChecker;

/**
 * A minimal Language Server (LSP) over stdio for Elm: it publishes diagnostics (parse and type
 * errors, located) on open/change, answers hover with the inferred type of the definition under the
 * cursor, resolves go-to-definition, offers completion (module-local names plus the stdlib), lists
 * document symbols (the outline), produces semantic tokens for highlighting, and finds references /
 * renames an identifier. Go-to-definition, references and rename are <b>workspace-wide</b>: on
 * initialize the server indexes every {@code .elm} file under the workspace root, so navigation and
 * rename cross module boundaries. Reuses the existing parser and Hindley–Milner type checker.
 */
public final class LspServer {

  /** A diagnostic at a 0-based (line, character) range. {@code severity} is the LSP severity
   * (1 = error, 2 = warning); the 4-arg form defaults to an error. */
  public record Diagnostic(int line, int startChar, int endChar, String message, int severity) {
    public Diagnostic(int line, int startChar, int endChar, String message) {
      this(line, startChar, endChar, message, 1);
    }
  }

  /** A workspace location: the document URI and a 0-based (line, character). */
  public record Location(String uri, int line, int character) {}

  private final Map<String, String> docs = new LinkedHashMap<>();

  // --- core analysis (pure, unit-tested directly) ------------------------

  /** Parse + type-check the source, returning located diagnostics (empty if it's clean). */
  public List<Diagnostic> diagnose(String source) {
    List<Diagnostic> out = new ArrayList<>();
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (ElmSyntaxError e) {
      out.add(at(e.position(), e.getMessage()));
      return out;
    } catch (RuntimeException e) {
      out.add(new Diagnostic(0, 0, 1, e.getMessage()));
      return out;
    }
    try {
      TypeChecker.checkModule(source);
    } catch (pl.matsuo.elm.error.ElmTypeErrors errs) {
      // Multi-error recovery: surface one diagnostic per independent type error.
      for (ElmTypeError e : errs.errors) {
        if (e.position != null) {
          out.add(at(e.position, e.rawMessage() + (e.hint != null ? " — " + e.hint : "")));
        } else {
          out.add(new Diagnostic(0, 0, 1, e.rawMessage()));
        }
      }
    } catch (ElmTypeError e) {
      if (e.position != null) {
        out.add(at(e.position, e.rawMessage() + (e.hint != null ? " — " + e.hint : "")));
      } else {
        out.add(new Diagnostic(0, 0, 1, e.getMessage()));
      }
    } catch (RuntimeException ignored) {
      // best-effort: a checker limitation shouldn't crash the server
    }
    out.addAll(unusedImportWarnings(source, module));
    out.addAll(unusedBindingWarnings(module));
    return out;
  }

  /**
   * Warns about bindings nothing uses: a private (non-exposed) top-level definition referenced
   * nowhere, an unused function parameter, and an unused {@code let} binding. Conservative — a name
   * used anywhere in its scope (even via shadowing) counts, and names starting with {@code _} are
   * ignored, so this never wrongly flags a live binding.
   */
  List<Diagnostic> unusedBindingWarnings(Module module) {
    List<Diagnostic> out = new ArrayList<>();
    java.util.Set<String> exposed =
        module.exposing().open() ? null : new java.util.HashSet<>(module.exposing().names());
    java.util.Set<String> allRefs = new java.util.HashSet<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        refs(v.body(), allRefs);
      } else if (d instanceof Decl.Port p) {
        allRefs.add(p.name()); // a port is "used" by the runtime
      }
    }
    for (Decl d : module.decls()) {
      if (!(d instanceof Decl.Value v)) {
        continue;
      }
      // `main` is the entry point — never flag it as unused or warn on its parameters — but still
      // check its body's `let` bindings.
      if (!v.name().equals("main")) {
        if (exposed != null && !exposed.contains(v.name()) && !allRefs.contains(v.name())) {
          out.add(warn(v.pos(), "Unused definition: `" + v.name() + "` is never used."));
        }
        java.util.Set<String> bodyRefs = new java.util.HashSet<>();
        refs(v.body(), bodyRefs);
        for (Pattern p : v.params()) {
          if (p instanceof Pattern.Var pv && !pv.name().startsWith("_") && !bodyRefs.contains(pv.name())) {
            out.add(warn(v.pos(), "Unused parameter: `" + pv.name() + "` in `" + v.name() + "`."));
          }
        }
      }
      walkLets(v.body(), out);
    }
    return out;
  }

  /** At each {@code let}, flags a binding whose name is used in neither the body nor any sibling
   * binding (the let's scope); then recurses into every sub-expression. */
  private void walkLets(Expr e, List<Diagnostic> out) {
    if (e instanceof Expr.Let let) {
      java.util.Set<String> scope = new java.util.HashSet<>();
      refs(let.body(), scope);
      for (Decl d : let.defs()) {
        if (d instanceof Decl.Value v) {
          refs(v.body(), scope);
        } else if (d instanceof Decl.Destructure de) {
          refs(de.body(), scope);
        }
      }
      for (Decl d : let.defs()) {
        if (d instanceof Decl.Value v && !v.name().startsWith("_") && !scope.contains(v.name())) {
          out.add(warn(v.pos(), "Unused `let` binding: `" + v.name() + "`."));
        }
      }
    }
    for (Expr child : children(e)) {
      walkLets(child, out);
    }
  }

  /** Collects the unqualified names {@code e} references (values, custom operators, record-update
   * bases), recursively. Over-approximates, which keeps the unused checks free of false positives. */
  private static void refs(Expr e, java.util.Set<String> out) {
    switch (e) {
      case Expr.Var v -> {
        if (v.module() == null) {
          out.add(v.name());
        }
      }
      case Expr.OpFunc o -> out.add(o.op());
      case Expr.BinOp b -> out.add(b.op());
      case Expr.RecordUpdate u -> out.add(u.base());
      default -> {}
    }
    for (Expr child : children(e)) {
      refs(child, out);
    }
  }

  /** The immediate sub-expressions of {@code e}. */
  private static List<Expr> children(Expr e) {
    List<Expr> cs = new ArrayList<>();
    switch (e) {
      case Expr.App a -> {
        cs.add(a.fn());
        cs.add(a.arg());
      }
      case Expr.BinOp b -> {
        cs.add(b.left());
        cs.add(b.right());
      }
      case Expr.Negate n -> cs.add(n.operand());
      case Expr.If i -> {
        cs.add(i.cond());
        cs.add(i.thenBranch());
        cs.add(i.elseBranch());
      }
      case Expr.Lambda l -> cs.add(l.body());
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            cs.add(v.body());
          } else if (d instanceof Decl.Destructure de) {
            cs.add(de.body());
          }
        }
        cs.add(let.body());
      }
      case Expr.Case c -> {
        cs.add(c.scrutinee());
        c.branches().forEach(br -> cs.add(br.body()));
      }
      case Expr.ListLit l -> cs.addAll(l.items());
      case Expr.Tuple t -> cs.addAll(t.items());
      case Expr.Record r -> r.fields().forEach(f -> cs.add(f.value()));
      case Expr.RecordUpdate u -> u.fields().forEach(f -> cs.add(f.value()));
      case Expr.RecordAccess a -> cs.add(a.target());
      default -> {}
    }
    return cs;
  }

  private static Diagnostic warn(pl.matsuo.elm.error.Position p, String message) {
    int line = Math.max(0, p.line() - 1);
    int col = Math.max(0, p.col() - 1);
    return new Diagnostic(line, col, col + 1, message, 2);
  }

  /**
   * Warns about imports nothing in the module uses: an {@code import M [as A]} whose qualified
   * prefix never appears, or an {@code exposing (a, b, …)} name never referenced. Conservative — a
   * name that appears anywhere outside the import lines (even in a comment) counts as used, so this
   * never wrongly flags a live import. {@code exposing (..)} is left alone (its names are unknown).
   */
  List<Diagnostic> unusedImportWarnings(String source, Module module) {
    List<Diagnostic> out = new ArrayList<>();
    // Everything outside the `import …` lines — where a real use would appear.
    String[] lines = source.split("\n", -1);
    StringBuilder body = new StringBuilder();
    for (String line : lines) {
      if (!line.stripLeading().startsWith("import ")) {
        body.append(line).append('\n');
      }
    }
    String rest = body.toString();
    for (Module.Import imp : module.imports()) {
      int line = Math.max(0, imp.pos().line() - 1);
      if (imp.exposing().open()) {
        // `exposing (..)` — can't tell which names are used; only check the qualified prefix.
        String prefix = imp.alias().orElse(imp.module());
        if (!usesQualified(rest, prefix) && !usesAnyName(rest, imp.module())) {
          out.add(new Diagnostic(line, 0, 1, "Unused import: nothing from `" + imp.module() + "` is used.", 2));
        }
        continue;
      }
      if (imp.exposing().names().isEmpty()) {
        // Qualified-only import: used iff `M.`/`A.` appears.
        String prefix = imp.alias().orElse(imp.module());
        if (!usesQualified(rest, prefix)) {
          out.add(new Diagnostic(line, 0, 1, "Unused import: `" + imp.module() + "` is never used.", 2));
        }
        continue;
      }
      for (String name : imp.exposing().names()) {
        // A type/operator exposed as `(Type(..))` or `((<|))` — keep just the bare name to search.
        String bare = name.replaceAll("\\(\\.\\.\\)$", "").replaceAll("[()]", "");
        if (!bare.isEmpty() && !usesAnyName(rest, bare)) {
          out.add(new Diagnostic(line, 0, 1, "Unused import: `" + bare + "` from `" + imp.module() + "` is never used.", 2));
        }
      }
    }
    return out;
  }

  /** Whether {@code name} appears as a whole identifier in {@code text}. */
  private static boolean usesAnyName(String text, String name) {
    return java.util.regex.Pattern.compile("(?<![\\w.])" + java.util.regex.Pattern.quote(name) + "\\b")
        .matcher(text)
        .find();
  }

  /** Whether a qualified reference {@code prefix.something} appears in {@code text}. */
  private static boolean usesQualified(String text, String prefix) {
    return java.util.regex.Pattern.compile("(?<![\\w.])" + java.util.regex.Pattern.quote(prefix) + "\\.")
        .matcher(text)
        .find();
  }

  /** The elm-format-style reformatting of the source, or empty if it is unparseable or already
   * formatted (so the client makes no edit). */
  public Optional<String> formatDocument(String source) {
    try {
      String formatted = pl.matsuo.elm.fmt.Formatter.format(source);
      return formatted.equals(source) ? Optional.empty() : Optional.of(formatted);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** The inferred type of the top-level definition on the given 0-based line, if any. */
  public Optional<String> hoverType(String source, int line0) {
    Map<String, String> types;
    try {
      types = TypeChecker.checkModule(source);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.pos().line() == line0 + 1 && types.containsKey(v.name())) {
        return Optional.of(v.name() + " : " + types.get(v.name()));
      }
    }
    return Optional.empty();
  }

  /**
   * The 0-based (line, character) where the name under the cursor is defined, if it is a top-level
   * value, union constructor or type/alias declared in this module. Returns empty otherwise (e.g.
   * the name comes from an import, or the cursor isn't on an identifier).
   */
  public Optional<int[]> definition(String source, int line0, int char0) {
    String word = wordAt(source, line0, char0);
    if (word.isEmpty()) {
      return Optional.empty();
    }
    return localDefinition(source, word);
  }

  /** The 0-based {@code [line, char]} of the top-level definition of {@code name} in {@code source}. */
  private static Optional<int[]> localDefinition(String source, String name) {
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    for (Decl d : module.decls()) {
      switch (d) {
        case Decl.Value v -> {
          if (v.name().equals(name)) {
            return Optional.of(new int[] {v.pos().line() - 1, v.pos().col() - 1});
          }
        }
        case Decl.Union u -> {
          if (u.name().equals(name)) {
            return Optional.of(new int[] {u.pos().line() - 1, u.pos().col() - 1});
          }
          for (Decl.Union.Variant variant : u.variants()) {
            if (variant.name().equals(name)) {
              return Optional.of(new int[] {u.pos().line() - 1, u.pos().col() - 1});
            }
          }
        }
        case Decl.TypeAlias ta -> {
          if (ta.name().equals(name)) {
            return Optional.of(new int[] {ta.pos().line() - 1, ta.pos().col() - 1});
          }
        }
        default -> {}
      }
    }
    return Optional.empty();
  }

  /**
   * Workspace-wide go-to-definition. Resolves the name under the cursor to its top-level definition,
   * looking first in the current document, then — for a name imported from another module — in the
   * other indexed documents. A qualified name ({@code Module.value}) targets that module's file when
   * it is in the workspace; an unqualified name matches the first document that declares it.
   */
  public Optional<Location> workspaceDefinition(
      Map<String, String> workspace, String uri, int line0, int char0) {
    String src = workspace.getOrDefault(uri, "");
    String word = wordAt(src, line0, char0);
    if (word.isEmpty()) {
      return Optional.empty();
    }
    Optional<int[]> here = localDefinition(src, word);
    if (here.isPresent()) {
      return Optional.of(new Location(uri, here.get()[0], here.get()[1]));
    }
    String qualifier = qualifierAt(src, line0, char0); // e.g. "Foo" in `Foo.bar`, or "" / an alias
    String targetModule = qualifier.isEmpty() ? null : resolveModuleAlias(src, qualifier);
    for (Map.Entry<String, String> e : workspace.entrySet()) {
      if (e.getKey().equals(uri)) {
        continue;
      }
      if (targetModule != null && !targetModule.equals(moduleName(e.getValue()))) {
        continue;
      }
      Optional<int[]> there = localDefinition(e.getValue(), word);
      if (there.isPresent()) {
        return Optional.of(new Location(e.getKey(), there.get()[0], there.get()[1]));
      }
    }
    return Optional.empty();
  }

  /**
   * Completion labels for the document: the module's own top-level values, union constructors and
   * type aliases, plus the builtin/stdlib names known to the type checker. Sorted and de-duplicated.
   */
  public List<String> complete(String source) {
    java.util.TreeSet<String> names = new java.util.TreeSet<>();
    names.addAll(pl.matsuo.elm.types.Signatures.globals().keySet());
    try {
      Module module = Parser.parseModule(source);
      for (Decl d : module.decls()) {
        switch (d) {
          case Decl.Value v -> names.add(v.name());
          case Decl.TypeAlias ta -> names.add(ta.name());
          case Decl.Union u -> u.variants().forEach(variant -> names.add(variant.name()));
          default -> {}
        }
      }
    } catch (RuntimeException ignored) {
      // offer at least the builtins even while the document doesn't parse
    }
    return new ArrayList<>(names);
  }

  /** The module's own top-level value/type/constructor names (for sorting them ahead of the stdlib). */
  private java.util.Set<String> localNames(String source) {
    java.util.Set<String> names = new java.util.HashSet<>();
    try {
      for (Decl d : Parser.parseModule(source).decls()) {
        switch (d) {
          case Decl.Value v -> names.add(v.name());
          case Decl.TypeAlias ta -> names.add(ta.name());
          case Decl.Union u -> u.variants().forEach(variant -> names.add(variant.name()));
          default -> {}
        }
      }
    } catch (RuntimeException ignored) {
      // no document-local names while it doesn't parse
    }
    return names;
  }

  /** The type of {@code name} as rendered text: a module-local inferred type, else a stdlib
   *  signature. Used for completion detail, signature help and inlay hints. */
  public Optional<String> typeOf(String source, String name) {
    try {
      String local = TypeChecker.checkModule(source).get(name);
      if (local != null) {
        return Optional.of(local);
      }
    } catch (RuntimeException ignored) {
      // fall back to the stdlib signatures even when the document doesn't type-check
    }
    var scheme = pl.matsuo.elm.types.Signatures.globals().get(name);
    return scheme == null ? Optional.empty() : Optional.of(pl.matsuo.elm.types.Types.show(scheme.body()));
  }

  /** An inlay hint: a 0-based position and the text to show there (e.g. {@code ": Int -> Int"}). */
  public record InlayHint(int line, int character, String label) {}

  /**
   * Inlay hints for the document: the inferred type of each top-level value that lacks an explicit
   * annotation, shown just after its name — the editor's quiet "here's what I inferred". (The LSP
   * has the real Hindley–Milner checker, so these are genuine inferred types.)
   */
  public List<InlayHint> inlayHints(String source) {
    List<InlayHint> out = new ArrayList<>();
    Module module;
    Map<String, String> types;
    try {
      module = Parser.parseModule(source);
      types = TypeChecker.checkModule(source);
    } catch (RuntimeException e) {
      return out;
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v
          && v.annotation().isEmpty()
          && !v.name().equals("main")
          && types.containsKey(v.name())) {
        int line = Math.max(0, v.pos().line() - 1);
        int character = Math.max(0, v.pos().col() - 1) + v.name().length();
        out.add(new InlayHint(line, character, " : " + types.get(v.name())));
      }
    }
    return out;
  }

  /**
   * Signature help at a 0-based cursor: the type of the function the current application is calling —
   * the identifier after the innermost unclosed {@code (} on the line, else the line's first
   * identifier. Returns {@code "name : Type"} when the type is known.
   */
  public Optional<String> signatureHelp(String source, int line0, int char0) {
    String[] lines = source.split("\n", -1);
    if (line0 < 0 || line0 >= lines.length) {
      return Optional.empty();
    }
    String prefix = lines[line0].substring(0, Math.min(char0, lines[line0].length()));
    // Start of the current application: after the binding's ` = `, the last `->`, or — taking
    // precedence when deeper — just after the innermost unmatched `(`.
    int start = 0;
    int eq = prefix.lastIndexOf(" = ");
    if (eq >= 0) {
      start = eq + 3;
    }
    int arrow = prefix.lastIndexOf("-> ");
    if (arrow + 3 > start) {
      start = arrow + 3;
    }
    int open = -1; // index just after the innermost unmatched '('
    int depth = 0;
    for (int i = 0; i < prefix.length(); i++) {
      char c = prefix.charAt(i);
      if (c == '(') {
        depth++;
        open = i + 1;
      } else if (c == ')') {
        depth--;
        if (depth < 0) {
          depth = 0;
        }
        open = -1;
      }
    }
    if (open > start) {
      start = open;
    }
    String fn = firstIdentifier(prefix.substring(start));
    if (fn.isEmpty()) {
      return Optional.empty();
    }
    // The line being typed is usually incomplete (so the whole module wouldn't parse); type-check
    // with it blanked, since the called function is defined elsewhere.
    String[] cleaned = source.split("\n", -1);
    cleaned[line0] = "";
    return typeOf(String.join("\n", cleaned), fn).map(t -> fn + " : " + t);
  }

  /** The first (possibly qualified) identifier token in {@code s}, or "". */
  private static String firstIdentifier(String s) {
    int i = 0;
    int n = s.length();
    while (i < n && !isIdentChar(s.charAt(i))) {
      i++;
    }
    int start = i;
    while (i < n && isIdentChar(s.charAt(i))) {
      i++;
    }
    String token = s.substring(start, i);
    int dot = token.lastIndexOf('.');
    return dot >= 0 ? token.substring(dot + 1) : token;
  }

  /** A code action that replaces the 0-based range [(line, character), (endLine, endChar)] with
   * {@code newText}. The 4-arg form is a zero-width insertion at (line, character). */
  public record CodeAction(
      String title, int line, int character, int endLine, int endChar, String newText) {
    public CodeAction(String title, int line, int character, String newText) {
      this(title, line, character, line, character, newText);
    }
  }

  /**
   * Code actions available at the 0-based cursor line: "Add type annotation" for a top-level value
   * that lacks one, and "Add missing branches" for a non-exhaustive {@code case} (constructors of
   * the scrutinee's union — declared here or a builtin — that no branch matches).
   */
  public List<CodeAction> codeActions(String source, int line0) {
    List<CodeAction> out = new ArrayList<>();
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (RuntimeException e) {
      return out;
    }
    addTypeAnnotationAction(source, module, line0, out);
    fillCaseBranchesAction(module, line0, out);
    removeUnusedImportAction(source, module, line0, out);
    addMissingImportAction(source, module, line0, out);
    return out;
  }

  /** "Remove unused import" when the cursor is on an import line nothing in the module uses. */
  private void removeUnusedImportAction(String source, Module module, int line0, List<CodeAction> out) {
    for (Diagnostic d : unusedImportWarnings(source, module)) {
      if (d.line() == line0) {
        // Delete the whole import line (from its start to the start of the next line).
        out.add(new CodeAction("Remove unused import", line0, 0, line0 + 1, 0, ""));
        return;
      }
    }
  }

  /** "import M" when the cursor line refers to a qualified `M.name` whose module isn't imported. */
  private void addMissingImportAction(String source, Module module, int line0, List<CodeAction> out) {
    String[] lines = source.split("\n", -1);
    if (line0 < 0 || line0 >= lines.length) {
      return;
    }
    java.util.Set<String> imported = new java.util.HashSet<>();
    int lastImportLine = 0;
    for (Module.Import imp : module.imports()) {
      imported.add(imp.module());
      imp.alias().ifPresent(imported::add);
      lastImportLine = Math.max(lastImportLine, imp.pos().line() - 1);
    }
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?<![\\w.])([A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\.[a-z]")
            .matcher(lines[line0]);
    java.util.Set<String> offered = new java.util.HashSet<>();
    while (m.find()) {
      String mod = m.group(1);
      if (!imported.contains(mod) && offered.add(mod)) {
        // Insert the import after the last existing import (or at the top).
        out.add(new CodeAction("import " + mod, lastImportLine + 1, 0, "import " + mod + "\n"));
      }
    }
  }

  private void addTypeAnnotationAction(String source, Module module, int line0, List<CodeAction> out) {
    Map<String, String> types;
    try {
      types = TypeChecker.checkModule(source);
    } catch (RuntimeException e) {
      return;
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v
          && v.pos().line() == line0 + 1
          && v.annotation().isEmpty()
          && types.containsKey(v.name())) {
        int col = Math.max(0, v.pos().col() - 1);
        String indent = " ".repeat(col);
        out.add(
            new CodeAction(
                "Add type annotation",
                line0,
                0,
                indent + v.name() + " : " + types.get(v.name()) + "\n"));
      }
    }
  }

  private void fillCaseBranchesAction(Module module, int line0, List<CodeAction> out) {
    Map<String, List<String>> unionCtors = new java.util.HashMap<>();
    Map<String, String> ctorUnion = new java.util.HashMap<>();
    Map<String, Integer> ctorArity = new java.util.HashMap<>();
    seedBuiltinUnions(unionCtors, ctorUnion, ctorArity);
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Union u) {
        List<String> names = new ArrayList<>();
        for (Decl.Union.Variant variant : u.variants()) {
          names.add(variant.name());
          ctorUnion.put(variant.name(), u.name());
          ctorArity.put(variant.name(), variant.args().size());
        }
        unionCtors.put(u.name(), names);
      }
    }
    // Find the case nearest the cursor line.
    List<Expr.Case> cases = new ArrayList<>();
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v) {
        collectCases(v.body(), cases);
      }
    }
    Expr.Case best = null;
    int bestDist = Integer.MAX_VALUE;
    for (Expr.Case c : cases) {
      int dist = Math.abs(c.pos().line() - 1 - line0);
      if (dist < bestDist) {
        bestDist = dist;
        best = c;
      }
    }
    if (best == null) {
      return;
    }
    java.util.Set<String> matched = new java.util.HashSet<>();
    String union = null;
    for (Expr.Case.Branch br : best.branches()) {
      if (br.pattern() instanceof Pattern.Ctor ct) {
        matched.add(ct.name());
        if (ctorUnion.containsKey(ct.name())) {
          union = ctorUnion.get(ct.name());
        }
      }
    }
    if (union == null) {
      return;
    }
    StringBuilder branches = new StringBuilder();
    for (String ctor : unionCtors.get(union)) {
      if (!matched.contains(ctor)) {
        branches.append("        ").append(ctor);
        for (int i = 0; i < ctorArity.getOrDefault(ctor, 0); i++) {
          branches.append(" _");
        }
        branches.append(" ->\n            Debug.todo \"branch\"\n\n");
      }
    }
    if (branches.length() > 0) {
      out.add(new CodeAction("Add missing case branches", line0 + 1, 0, branches.toString()));
    }
  }

  private static void seedBuiltinUnions(
      Map<String, List<String>> unionCtors,
      Map<String, String> ctorUnion,
      Map<String, Integer> ctorArity) {
    record U(String name, List<String> ctors, List<Integer> arities) {}
    for (U u :
        List.of(
            new U("Bool", List.of("True", "False"), List.of(0, 0)),
            new U("Maybe", List.of("Just", "Nothing"), List.of(1, 0)),
            new U("Result", List.of("Err", "Ok"), List.of(1, 1)),
            new U("Order", List.of("LT", "EQ", "GT"), List.of(0, 0, 0)))) {
      unionCtors.put(u.name(), u.ctors());
      for (int i = 0; i < u.ctors().size(); i++) {
        ctorUnion.put(u.ctors().get(i), u.name());
        ctorArity.put(u.ctors().get(i), u.arities().get(i));
      }
    }
  }

  private static void collectCases(Expr e, List<Expr.Case> out) {
    switch (e) {
      case Expr.Case c -> {
        out.add(c);
        collectCases(c.scrutinee(), out);
        c.branches().forEach(b -> collectCases(b.body(), out));
      }
      case Expr.App a -> {
        collectCases(a.fn(), out);
        collectCases(a.arg(), out);
      }
      case Expr.BinOp b -> {
        collectCases(b.left(), out);
        collectCases(b.right(), out);
      }
      case Expr.If i -> {
        collectCases(i.cond(), out);
        collectCases(i.thenBranch(), out);
        collectCases(i.elseBranch(), out);
      }
      case Expr.Lambda l -> collectCases(l.body(), out);
      case Expr.Let let -> {
        for (Decl d : let.defs()) {
          if (d instanceof Decl.Value v) {
            collectCases(v.body(), out);
          }
        }
        collectCases(let.body(), out);
      }
      case Expr.ListLit l -> l.items().forEach(x -> collectCases(x, out));
      case Expr.Tuple t -> t.items().forEach(x -> collectCases(x, out));
      case Expr.Negate n -> collectCases(n.operand(), out);
      case Expr.RecordAccess a -> collectCases(a.target(), out);
      default -> {}
    }
  }

  /** A top-level symbol for the outline: its name, LSP {@code SymbolKind}, and 0-based location. */
  public record Symbol(String name, int kind, int line, int character) {}

  /** Top-level symbols (values, type aliases, unions and their constructors, ports) for the outline. */
  public List<Symbol> documentSymbols(String source) {
    List<Symbol> out = new ArrayList<>();
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (RuntimeException e) {
      return out;
    }
    for (Decl d : module.decls()) {
      switch (d) {
        case Decl.Value v -> out.add(symbol(v.name(), v.params().isEmpty() ? 13 : 12, v.pos())); // Variable / Function
        case Decl.TypeAlias ta -> out.add(symbol(ta.name(), 5, ta.pos())); // Class
        case Decl.Union u -> {
          out.add(symbol(u.name(), 10, u.pos())); // Enum
          for (Decl.Union.Variant variant : u.variants()) {
            out.add(symbol(variant.name(), 9, u.pos())); // Constructor (located at the union)
          }
        }
        case Decl.Port p -> out.add(symbol(p.name(), 12, p.pos())); // Function
        default -> {}
      }
    }
    return out;
  }

  private static Symbol symbol(String name, int kind, pl.matsuo.elm.error.Position p) {
    return new Symbol(name, kind, Math.max(0, p.line() - 1), Math.max(0, p.col() - 1));
  }

  /** All 0-based {@code [line, char]} occurrences of the identifier under the cursor (token-level). */
  public List<int[]> references(String source, int line0, int char0) {
    return occurrences(source, wordAt(source, line0, char0));
  }

  /** All 0-based {@code [line, char]} positions where the identifier {@code name} appears as a token. */
  public List<int[]> occurrences(String source, String name) {
    List<int[]> out = new ArrayList<>();
    if (name.isEmpty()) {
      return out;
    }
    List<pl.matsuo.elm.lexer.Token> tokens;
    try {
      tokens = pl.matsuo.elm.lexer.Lexer.tokenize(source);
    } catch (RuntimeException e) {
      return out;
    }
    for (pl.matsuo.elm.lexer.Token t : tokens) {
      if (name.equals(t.text())
          && (t.is(pl.matsuo.elm.lexer.TokenType.LOWER)
              || t.is(pl.matsuo.elm.lexer.TokenType.UPPER))) {
        out.add(new int[] {t.line() - 1, t.col() - 1});
      }
    }
    return out;
  }

  /**
   * Workspace-wide references: every occurrence of the identifier under the cursor, in every indexed
   * document, keyed by URI. The same token-level matching as {@link #occurrences} — adequate for an
   * unqualified value name, which is unique per module by Elm's scoping rules.
   */
  public Map<String, List<int[]>> workspaceReferences(
      Map<String, String> workspace, String uri, int line0, int char0) {
    String word = wordAt(workspace.getOrDefault(uri, ""), line0, char0);
    Map<String, List<int[]>> out = new LinkedHashMap<>();
    if (word.isEmpty()) {
      return out;
    }
    for (Map.Entry<String, String> e : workspace.entrySet()) {
      List<int[]> locs = occurrences(e.getValue(), word);
      if (!locs.isEmpty()) {
        out.put(e.getKey(), locs);
      }
    }
    return out;
  }

  /** The declared module name of {@code source} (its {@code module X …} header), or "" if none. */
  static String moduleName(String source) {
    try {
      return Parser.parseModule(source).name();
    } catch (RuntimeException e) {
      return "";
    }
  }

  /** The module qualifier of the (possibly qualified) identifier at a 0-based (line, char), or "". */
  static String qualifierAt(String source, int line0, int char0) {
    String[] lines = source.split("\n", -1);
    if (line0 < 0 || line0 >= lines.length) {
      return "";
    }
    String line = lines[line0];
    int n = line.length();
    int start = Math.min(Math.max(char0, 0), n);
    while (start > 0 && isIdentChar(line.charAt(start - 1))) {
      start--;
    }
    int end = Math.min(Math.max(char0, 0), n);
    while (end < n && isIdentChar(line.charAt(end))) {
      end++;
    }
    String token = line.substring(start, end);
    int dot = token.lastIndexOf('.');
    return dot >= 0 ? token.substring(0, dot) : "";
  }

  /** Resolves a qualifier (a module alias or full name) to the imported module it refers to. */
  static String resolveModuleAlias(String source, String qualifier) {
    try {
      for (Module.Import imp : Parser.parseModule(source).imports()) {
        if (imp.alias().isPresent() && imp.alias().get().equals(qualifier)) {
          return imp.module();
        }
      }
    } catch (RuntimeException ignored) {
      // fall through: treat the qualifier as a module name itself
    }
    return qualifier;
  }

  // --- semantic tokens (syntax highlighting) -----------------------------

  /** The semantic token type legend, in the order their indices are emitted. */
  public static final List<String> SEMANTIC_TOKEN_TYPES =
      List.of("keyword", "type", "function", "number", "string", "operator");

  /**
   * Semantic tokens for the whole document, in the LSP delta encoding: a flat array of 5 ints per
   * token — {@code [deltaLine, deltaStartChar, length, tokenType, tokenModifiers]} — where the type
   * index references {@link #SEMANTIC_TOKEN_TYPES}. Built straight from the lexer.
   */
  public int[] semanticTokens(String source) {
    List<pl.matsuo.elm.lexer.Token> tokens;
    try {
      tokens = pl.matsuo.elm.lexer.Lexer.tokenize(source);
    } catch (RuntimeException e) {
      return new int[0];
    }
    List<int[]> rows = new ArrayList<>(); // {line, col, length, typeIndex} (0-based line/col)
    for (pl.matsuo.elm.lexer.Token t : tokens) {
      int type = semanticType(t.type());
      if (type < 0) {
        continue; // punctuation/EOF: not highlighted
      }
      String text = t.text();
      if (text == null || text.isEmpty() || text.indexOf('\n') >= 0) {
        continue; // skip multi-line tokens (e.g. triple-quoted strings) — they break line deltas
      }
      rows.add(new int[] {t.line() - 1, t.col() - 1, text.length(), type});
    }
    int[] data = new int[rows.size() * 5];
    int prevLine = 0, prevCol = 0;
    for (int i = 0; i < rows.size(); i++) {
      int[] r = rows.get(i);
      int deltaLine = r[0] - prevLine;
      int deltaCol = deltaLine == 0 ? r[1] - prevCol : r[1];
      data[i * 5] = deltaLine;
      data[i * 5 + 1] = deltaCol;
      data[i * 5 + 2] = r[2];
      data[i * 5 + 3] = r[3];
      data[i * 5 + 4] = 0; // no modifiers
      prevLine = r[0];
      prevCol = r[1];
    }
    return data;
  }

  /** Maps a lexer token kind to a {@link #SEMANTIC_TOKEN_TYPES} index, or -1 to skip it. */
  private static int semanticType(pl.matsuo.elm.lexer.TokenType t) {
    return switch (t) {
      case KW_IF, KW_THEN, KW_ELSE, KW_CASE, KW_OF, KW_LET, KW_IN, KW_TYPE, KW_MODULE, KW_WHERE,
              KW_IMPORT, KW_EXPOSING, KW_AS, KW_PORT ->
          0; // keyword
      case UPPER -> 1; // type / constructor / module
      case LOWER -> 2; // value / field
      case INT, FLOAT -> 3; // number
      case STRING, CHAR -> 4; // string
      case OPERATOR, ARROW, EQUALS, COLON, PIPE, BACKSLASH -> 5; // operator
      default -> -1;
    };
  }

  /** The identifier (possibly qualified, e.g. {@code List.map}) at a 0-based (line, char), or "". */
  static String wordAt(String source, int line0, int char0) {
    String[] lines = source.split("\n", -1);
    if (line0 < 0 || line0 >= lines.length) {
      return "";
    }
    String line = lines[line0];
    int n = line.length();
    if (char0 < 0 || char0 > n) {
      return "";
    }
    int start = Math.min(char0, n);
    while (start > 0 && isIdentChar(line.charAt(start - 1))) {
      start--;
    }
    int end = Math.min(char0, n);
    while (end < n && isIdentChar(line.charAt(end))) {
      end++;
    }
    // For a qualified name like `List.map`, take only the final segment (the value's own name).
    String token = line.substring(start, end);
    int dot = token.lastIndexOf('.');
    return dot >= 0 ? token.substring(dot + 1) : token;
  }

  private static boolean isIdentChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.';
  }

  private static Diagnostic at(pl.matsuo.elm.error.Position p, String message) {
    int line = Math.max(0, p.line() - 1);
    int col = Math.max(0, p.col() - 1);
    return new Diagnostic(line, col, col + 1, message);
  }

  // --- JSON-RPC over stdio ------------------------------------------------

  /** Runs the server loop, reading LSP messages from {@code in} and writing replies to {@code out}. */
  public void serve(InputStream in, OutputStream out) throws IOException {
    while (true) {
      Map<String, Object> msg = readMessage(in);
      if (msg == null) {
        return; // stream closed
      }
      Object method = msg.get("method");
      if ("exit".equals(method)) {
        return;
      }
      handle(msg, out);
    }
  }

  @SuppressWarnings("unchecked")
  void handle(Map<String, Object> msg, OutputStream out) throws IOException {
    String method = (String) msg.get("method");
    Object id = msg.get("id");
    Map<String, Object> params =
        msg.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    switch (method == null ? "" : method) {
      case "initialize" -> {
        indexWorkspace(params);
        reply(out, id, capabilities());
      }
      case "textDocument/didOpen" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        putDoc(uri, (String) td.get("text"));
        publishDiagnostics(out, uri);
      }
      case "textDocument/didChange" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        List<Object> changes = (List<Object>) params.get("contentChanges");
        if (changes != null && !changes.isEmpty()) {
          putDoc(uri, (String) ((Map<String, Object>) changes.get(changes.size() - 1)).get("text"));
        }
        publishDiagnostics(out, uri);
      }
      case "textDocument/hover" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        int line = ((Number) pos.get("line")).intValue();
        Optional<String> type = hoverType(docs.getOrDefault(uri, ""), line);
        Map<String, Object> result = new LinkedHashMap<>();
        if (type.isPresent()) {
          Map<String, Object> contents = new LinkedHashMap<>();
          contents.put("kind", "markdown");
          contents.put("value", "```elm\n" + type.get() + "\n```");
          result.put("contents", contents);
          reply(out, id, result);
        } else {
          reply(out, id, JsonEncode.NULL);
        }
      }
      case "textDocument/definition" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        int line = ((Number) pos.get("line")).intValue();
        int ch = ((Number) pos.get("character")).intValue();
        Optional<Location> loc = workspaceDefinition(docs, uri, line, ch);
        if (loc.isPresent()) {
          Map<String, Object> location = new LinkedHashMap<>();
          location.put("uri", loc.get().uri());
          location.put("range", range(loc.get().line(), loc.get().character(), loc.get().character()));
          reply(out, id, location);
        } else {
          reply(out, id, JsonEncode.NULL);
        }
      }
      case "textDocument/completion" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        String src = docs.getOrDefault(uri, "");
        java.util.Set<String> local = localNames(src);
        List<Object> items = new ArrayList<>();
        for (String name : complete(src)) {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("label", name);
          // Uppercase first letter -> a constructor/type (kind 4/7); else a function (kind 3).
          item.put("kind", !name.isEmpty() && Character.isUpperCase(name.charAt(0)) ? 4L : 3L);
          typeOf(src, name).ifPresent(t -> item.put("detail", name + " : " + t));
          // Sort the module's own names ahead of the stdlib.
          item.put("sortText", (local.contains(name) ? "0" : "1") + name);
          items.add(item);
        }
        reply(out, id, items);
      }
      case "textDocument/documentSymbol" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        List<Object> symbols = new ArrayList<>();
        for (Symbol s : documentSymbols(docs.getOrDefault(uri, ""))) {
          Map<String, Object> sym = new LinkedHashMap<>();
          sym.put("name", s.name());
          sym.put("kind", (long) s.kind());
          sym.put("range", range(s.line(), s.character(), s.character() + s.name().length()));
          sym.put("selectionRange", range(s.line(), s.character(), s.character() + s.name().length()));
          symbols.add(sym);
        }
        reply(out, id, symbols);
      }
      case "textDocument/documentHighlight" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        int line = ((Number) pos.get("line")).intValue();
        int ch = ((Number) pos.get("character")).intValue();
        String src = docs.getOrDefault(uri, "");
        String word = wordAt(src, line, ch);
        List<Object> highlights = new ArrayList<>();
        for (int[] loc : references(src, line, ch)) {
          Map<String, Object> h = new LinkedHashMap<>();
          h.put("range", range(loc[0], loc[1], loc[1] + word.length()));
          h.put("kind", 1L); // Text
          highlights.add(h);
        }
        reply(out, id, highlights);
      }
      case "textDocument/references" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        int line = ((Number) pos.get("line")).intValue();
        int ch = ((Number) pos.get("character")).intValue();
        String word = wordAt(docs.getOrDefault(uri, ""), line, ch);
        List<Object> locations = new ArrayList<>();
        workspaceReferences(docs, uri, line, ch)
            .forEach(
                (docUri, locs) -> {
                  for (int[] loc : locs) {
                    Map<String, Object> location = new LinkedHashMap<>();
                    location.put("uri", docUri);
                    location.put("range", range(loc[0], loc[1], loc[1] + word.length()));
                    locations.add(location);
                  }
                });
        reply(out, id, locations);
      }
      case "textDocument/rename" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        String newName = (String) params.get("newName");
        int line = ((Number) pos.get("line")).intValue();
        int ch = ((Number) pos.get("character")).intValue();
        String word = wordAt(docs.getOrDefault(uri, ""), line, ch);
        Map<String, Object> changes = new LinkedHashMap<>();
        workspaceReferences(docs, uri, line, ch)
            .forEach(
                (docUri, locs) -> {
                  List<Object> edits = new ArrayList<>();
                  for (int[] loc : locs) {
                    Map<String, Object> edit = new LinkedHashMap<>();
                    edit.put("range", range(loc[0], loc[1], loc[1] + word.length()));
                    edit.put("newText", newName);
                    edits.add(edit);
                  }
                  changes.put(docUri, edits);
                });
        Map<String, Object> workspaceEdit = new LinkedHashMap<>();
        workspaceEdit.put("changes", changes);
        reply(out, id, workspaceEdit);
      }
      case "textDocument/formatting" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        String source = docs.getOrDefault(uri, "");
        List<Object> edits = new ArrayList<>();
        formatDocument(source)
            .ifPresent(
                formatted -> {
                  // One edit replacing the whole document (start of file → end of the last line).
                  String[] lines = source.split("\n", -1);
                  int lastLine = lines.length - 1;
                  Map<String, Object> r = new LinkedHashMap<>();
                  r.put("start", point(0, 0));
                  r.put("end", point(lastLine, lines[lastLine].length()));
                  Map<String, Object> edit = new LinkedHashMap<>();
                  edit.put("range", r);
                  edit.put("newText", formatted);
                  edits.add(edit);
                });
        reply(out, id, edits);
      }
      case "textDocument/codeAction" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> range = (Map<String, Object>) params.get("range");
        Map<String, Object> start = (Map<String, Object>) range.get("start");
        String uri = (String) td.get("uri");
        int line = ((Number) start.get("line")).intValue();
        List<Object> actions = new ArrayList<>();
        for (CodeAction a : codeActions(docs.getOrDefault(uri, ""), line)) {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("start", point(a.line(), a.character()));
          r.put("end", point(a.endLine(), a.endChar()));
          Map<String, Object> edit = new LinkedHashMap<>();
          edit.put("range", r);
          edit.put("newText", a.newText());
          Map<String, Object> changes = new LinkedHashMap<>();
          changes.put(uri, List.of(edit));
          Map<String, Object> workspaceEdit = new LinkedHashMap<>();
          workspaceEdit.put("changes", changes);
          Map<String, Object> action = new LinkedHashMap<>();
          action.put("title", a.title());
          action.put("kind", "quickfix");
          action.put("edit", workspaceEdit);
          actions.add(action);
        }
        reply(out, id, actions);
      }
      case "textDocument/inlayHint" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        List<Object> hints = new ArrayList<>();
        for (InlayHint h : inlayHints(docs.getOrDefault(uri, ""))) {
          Map<String, Object> hint = new LinkedHashMap<>();
          Map<String, Object> position = new LinkedHashMap<>();
          position.put("line", (long) h.line());
          position.put("character", (long) h.character());
          hint.put("position", position);
          hint.put("label", h.label());
          hint.put("kind", 1L); // Type
          hint.put("paddingLeft", true);
          hints.add(hint);
        }
        reply(out, id, hints);
      }
      case "textDocument/signatureHelp" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        int line = ((Number) pos.get("line")).intValue();
        int ch = ((Number) pos.get("character")).intValue();
        Optional<String> sig = signatureHelp(docs.getOrDefault(uri, ""), line, ch);
        if (sig.isPresent()) {
          Map<String, Object> signature = new LinkedHashMap<>();
          signature.put("label", sig.get());
          Map<String, Object> result = new LinkedHashMap<>();
          result.put("signatures", List.of(signature));
          result.put("activeSignature", 0L);
          result.put("activeParameter", 0L);
          reply(out, id, result);
        } else {
          reply(out, id, JsonEncode.NULL);
        }
      }
      case "textDocument/semanticTokens/full" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        int[] tokens = semanticTokens(docs.getOrDefault(uri, ""));
        List<Object> data = new ArrayList<>(tokens.length);
        for (int v : tokens) {
          data.add((long) v);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        reply(out, id, result);
      }
      case "shutdown" -> reply(out, id, JsonEncode.NULL);
      default -> {
        if (id != null) {
          reply(out, id, JsonEncode.NULL); // unknown request — empty result
        }
      }
    }
  }

  private static Map<String, Object> capabilities() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("textDocumentSync", 1L); // full document sync
    caps.put("hoverProvider", true);
    caps.put("definitionProvider", true);
    caps.put("completionProvider", new LinkedHashMap<>()); // no trigger characters
    caps.put("referencesProvider", true);
    caps.put("documentHighlightProvider", true);
    caps.put("documentSymbolProvider", true);
    caps.put("renameProvider", true);
    caps.put("codeActionProvider", true);
    caps.put("documentFormattingProvider", true);
    caps.put("inlayHintProvider", true);
    Map<String, Object> sig = new LinkedHashMap<>();
    sig.put("triggerCharacters", List.of(" ", "("));
    caps.put("signatureHelpProvider", sig);

    Map<String, Object> legend = new LinkedHashMap<>();
    legend.put("tokenTypes", SEMANTIC_TOKEN_TYPES);
    legend.put("tokenModifiers", List.of());
    Map<String, Object> semantic = new LinkedHashMap<>();
    semantic.put("legend", legend);
    semantic.put("full", true);
    caps.put("semanticTokensProvider", semantic);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("capabilities", caps);
    return result;
  }

  /**
   * Indexes every {@code .elm} file under the workspace root(s) given in the {@code initialize}
   * params ({@code rootUri} or {@code workspaceFolders}) into {@code docs}, so workspace-wide
   * navigation and rename work before the files are opened. Best-effort: skips unreadable trees.
   */
  @SuppressWarnings("unchecked")
  void indexWorkspace(Map<String, Object> params) {
    List<String> roots = new ArrayList<>();
    if (params.get("rootUri") instanceof String r) {
      roots.add(r);
    }
    if (params.get("workspaceFolders") instanceof List<?> folders) {
      for (Object f : folders) {
        if (f instanceof Map<?, ?> m && m.get("uri") instanceof String u) {
          roots.add(u);
        }
      }
    }
    for (String root : roots) {
      Path dir;
      try {
        dir = Path.of(URI.create(root));
      } catch (RuntimeException e) {
        continue; // not a file:// root we can walk
      }
      if (!Files.isDirectory(dir)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(dir)) {
        walk.filter(p -> p.toString().endsWith(".elm") && Files.isRegularFile(p))
            .forEach(
                p -> {
                  String uri = p.toUri().toString();
                  if (!docs.containsKey(uri)) {
                    try {
                      docs.put(uri, Files.readString(p, StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                      // skip files we can't read
                    }
                  }
                });
      } catch (IOException ignored) {
        // skip roots we can't walk
      }
    }
  }

  /** Stores a document, dropping any stale entry that points at the same file under a different URI. */
  private void putDoc(String uri, String source) {
    Path target = pathOf(uri);
    if (target != null) {
      docs.keySet()
          .removeIf(
              existing -> {
                Path p = pathOf(existing);
                return p != null && !existing.equals(uri) && p.equals(target);
              });
    }
    docs.put(uri, source);
  }

  /** The normalized filesystem path for a {@code file://} URI, or null if it isn't one. */
  private static Path pathOf(String uri) {
    try {
      return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void publishDiagnostics(OutputStream out, String uri) throws IOException {
    List<Object> diags = new ArrayList<>();
    for (Diagnostic d : diagnose(docs.getOrDefault(uri, ""))) {
      Map<String, Object> range = range(d.line(), d.startChar(), d.endChar());
      Map<String, Object> diag = new LinkedHashMap<>();
      diag.put("range", range);
      diag.put("severity", (long) d.severity()); // 1 = Error, 2 = Warning
      diag.put("source", "elm-lang");
      diag.put("message", d.message());
      diags.add(diag);
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("uri", uri);
    params.put("diagnostics", diags);
    notify(out, "textDocument/publishDiagnostics", params);
  }

  private static Map<String, Object> point(int line, int character) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("line", (long) line);
    p.put("character", (long) character);
    return p;
  }

  private static Map<String, Object> range(int line, int startChar, int endChar) {
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("line", (long) line);
    start.put("character", (long) startChar);
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("line", (long) line);
    end.put("character", (long) endChar);
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("start", start);
    range.put("end", end);
    return range;
  }

  private void reply(OutputStream out, Object id, Object result) throws IOException {
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("jsonrpc", "2.0");
    msg.put("id", id);
    msg.put("result", result);
    write(out, msg);
  }

  private void notify(OutputStream out, String method, Object params) throws IOException {
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("jsonrpc", "2.0");
    msg.put("method", method);
    msg.put("params", params);
    write(out, msg);
  }

  static void write(OutputStream out, Map<String, Object> msg) throws IOException {
    byte[] body = JsonEncode.serialize(msg, 0).getBytes(StandardCharsets.UTF_8);
    out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readMessage(InputStream in) throws IOException {
    int length = -1;
    StringBuilder header = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      header.append((char) c);
      if (header.toString().endsWith("\r\n\r\n")) {
        for (String h : header.toString().split("\r\n")) {
          if (h.toLowerCase().startsWith("content-length:")) {
            length = Integer.parseInt(h.substring(h.indexOf(':') + 1).trim());
          }
        }
        break;
      }
    }
    if (length < 0) {
      return null;
    }
    byte[] body = in.readNBytes(length);
    Object parsed = JsonParse.parse(new String(body, StandardCharsets.UTF_8));
    return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }
}

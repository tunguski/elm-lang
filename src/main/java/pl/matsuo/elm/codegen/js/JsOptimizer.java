package pl.matsuo.elm.codegen.js;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Post-processing passes over a compiled JavaScript bundle: dead-code elimination over the generated
 * top-level Elm declarations, unused-kernel-entry pruning, and conservative whitespace minification.
 *
 * <p>These are pure {@code String -> String} text transforms with no dependency on the compiler's
 * state, kept apart from {@link JsCompiler} (which is about Elm semantics) so the "what we emit"
 * concern is separate from the "how we trim the output" concern.
 */
public final class JsOptimizer {

  private JsOptimizer() {}

  /**
   * Conservative whitespace minification: drops blank lines, standalone {@code //} comment lines and
   * per-line indentation. It never touches a line that contains code (so string contents such as
   * {@code https://...} and trailing comments are preserved), trading maximal compression for safety.
   */
  public static String minify(String js) {
    StringBuilder sb = new StringBuilder(js.length());
    for (String line : js.split("\n", -1)) {
      String t = line.trim();
      if (t.isEmpty() || t.startsWith("//")) {
        continue;
      }
      sb.append(t).append('\n');
    }
    return sb.toString();
  }

  /**
   * Dead-code elimination over a bundle's top-level Elm declarations: drops {@code var _$… = …;}
   * lines that are unreachable from the program's entry. Reachability is textual over the generated
   * {@code _$} identifiers — a <em>superset</em> of real use (an id mentioned only in a string still
   * counts), so it is conservative and never drops a declaration that is actually used. The kernel
   * runtime (referenced dynamically via {@code $g(...)}) is untouched.
   */
  public static String treeShake(String bundle) {
    String[] lines = bundle.split("\n", -1);
    java.util.regex.Pattern declPat = java.util.regex.Pattern.compile("^var (_\\$[A-Za-z0-9_$]+) = ");
    java.util.regex.Pattern idPat = java.util.regex.Pattern.compile("_\\$[A-Za-z0-9_$]+");
    Map<String, Integer> declLine = new HashMap<>();
    for (int i = 0; i < lines.length; i++) {
      var m = declPat.matcher(lines[i]);
      if (m.find()) {
        declLine.put(m.group(1), i);
      }
    }
    // Seed reachable ids from every non-declaration line (the kernel and the mount/entry call).
    Set<String> reachable = new HashSet<>();
    java.util.Deque<String> work = new java.util.ArrayDeque<>();
    for (int i = 0; i < lines.length; i++) {
      var d = declPat.matcher(lines[i]);
      if (d.find() && declLine.get(d.group(1)) == i) {
        continue; // a declaration line — its RHS is followed only once it's reachable
      }
      var m = idPat.matcher(lines[i]);
      while (m.find()) {
        if (reachable.add(m.group())) {
          work.add(m.group());
        }
      }
    }
    // Follow references through reachable declarations' right-hand sides (fixpoint).
    while (!work.isEmpty()) {
      Integer li = declLine.get(work.poll());
      if (li == null) {
        continue;
      }
      var m = idPat.matcher(lines[li]);
      while (m.find()) {
        if (reachable.add(m.group())) {
          work.add(m.group());
        }
      }
    }
    StringBuilder sb = new StringBuilder(bundle.length());
    for (int i = 0; i < lines.length; i++) {
      var m = declPat.matcher(lines[i]);
      if (m.find() && declLine.get(m.group(1)) == i) {
        if (reachable.contains(m.group(1))) {
          sb.append(lines[i]).append("\n");
        }
      } else {
        sb.append(lines[i]).append(i == lines.length - 1 ? "" : "\n");
      }
    }
    return sb.toString();
  }

  /**
   * Drops unused kernel runtime entries. Each {@code $rt} entry is a single line — either an
   * object-literal {@code 'Module.name': …,} or an assignment {@code $rt['Module.name']=…;} — and is
   * always <em>referenced</em> as a double-quoted {@code $g("Module.name")} (definitions use single
   * quotes; {@code Js.str} emits double). So an entry whose {@code "Module.name"} never
   * appears is unreachable and removed. Conservative: only self-contained (brace/paren-balanced)
   * lines are dropped, and the double-quoted form never collides with a definition or a longer name.
   */
  public static String pruneKernel(String bundle) {
    java.util.regex.Pattern obj = java.util.regex.Pattern.compile("^\\s*'([\\w.]+)'\\s*:");
    java.util.regex.Pattern asg = java.util.regex.Pattern.compile("^\\s*\\$rt\\['([\\w.]+)'\\]\\s*=");
    String[] lines = bundle.split("\n", -1);
    // Pass 1: the definition line(s) of each kernel entry (a name may be defined in both kernel.js
    // and dom.js).
    String[] entryName = new String[lines.length];
    Map<String, Set<Integer>> defLines = new HashMap<>();
    for (int i = 0; i < lines.length; i++) {
      var mo = obj.matcher(lines[i]);
      var ma = asg.matcher(lines[i]);
      String name = mo.find() ? mo.group(1) : (ma.find() ? ma.group(1) : null);
      if (name != null && balancedLine(lines[i])) {
        entryName[i] = name;
        defLines.computeIfAbsent(name, k -> new HashSet<>()).add(i);
      }
    }
    // Pass 2: an entry is used if its quoted name ("name" from $g, or 'name' from a direct
    // $rt['name'] read) appears on any line that isn't one of its own definition lines.
    Set<String> used = new HashSet<>();
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      for (Map.Entry<String, Set<Integer>> e : defLines.entrySet()) {
        if (used.contains(e.getKey()) || e.getValue().contains(i)) {
          continue;
        }
        String n = e.getKey();
        if (line.contains("\"" + n + "\"") || line.contains("'" + n + "'")) {
          used.add(n);
        }
      }
    }
    StringBuilder sb = new StringBuilder(bundle.length());
    for (int i = 0; i < lines.length; i++) {
      if (entryName[i] != null && !used.contains(entryName[i])) {
        continue; // a kernel entry nothing references — drop it
      }
      sb.append(lines[i]).append(i == lines.length - 1 ? "" : "\n");
    }
    return sb.toString();
  }

  /** Tree-shake, prune unused kernel entries, then minify — the {@code --optimize} pipeline. */
  public static String optimize(String bundle) {
    return minify(pruneKernel(treeShake(bundle)));
  }

  // --- code splitting ----------------------------------------------------

  /**
   * The result of {@link #partition}: which top-level decl ids stay in the base bundle, which move to
   * each named chunk, and which chunk roots could NOT be split off.
   *
   * @param baseDecls decl ids that remain in the base bundle (reachable from the program entry, or
   *     shared by more than one chunk so hoisted to base).
   * @param chunkDecls chunk name → the decl ids owned exclusively by that chunk (reachable from its
   *     roots, not from base, not shared with another chunk).
   * @param stuckInBase chunk roots the base references DIRECTLY (by bare id, not via a string-keyed
   *     {@code $g(...)} loader lookup) — so they cannot be lazy-loaded as written. A non-empty set
   *     means a call site needs to be routed through the chunk loader to actually defer.
   */
  public record Partition(
      Set<String> baseDecls,
      java.util.LinkedHashMap<String, Set<String>> chunkDecls,
      Set<String> stuckInBase) {}

  /** The defining module tag of a generated top-level id {@code _$tag$name} (e.g. {@code _$Eval$run}
   * → {@code Eval}; {@code _$Eval_Core$x} → {@code Eval_Core}), or {@code ""} if it isn't of that
   * shape. The tag never contains {@code $} (sanitizeTag maps non-alphanumerics to {@code _}), so the
   * first {@code $} after the {@code _$} prefix delimits tag from name. */
  static String tagOf(String id) {
    if (!id.startsWith("_$")) {
      return "";
    }
    int sep = id.indexOf('$', 2);
    return sep < 0 ? "" : id.substring(2, sep);
  }

  /**
   * Partitions a bundle's top-level decls into a base and one or more lazy chunks by reachability —
   * the multi-root generalization of {@link #treeShake}. A decl moves to a chunk only if it is
   * reachable from that chunk's roots and NOT reachable from the program entry except through a root
   * (the chunk roots are traversal <em>barriers</em> for the base reachability). This is exactly the
   * code-splitting constraint: you can only lazy-load code the base doesn't already reference
   * synchronously — a base reference to a chunk root by bare id keeps the whole subtree in base and is
   * reported in {@link Partition#stuckInBase}. A decl reachable from two chunks is hoisted to base
   * (the common-chunk rule), so every id is defined exactly once.
   *
   * @param chunkRoots chunk name → the set of module tags that root that chunk (a decl {@code _$t$n}
   *     roots the chunk when {@code t} is in the set). Reachability pulls in the rest of the chunk.
   */
  public static Partition partition(String bundle, Map<String, Set<String>> chunkRoots) {
    String[] lines = bundle.split("\n", -1);
    java.util.regex.Pattern declPat = java.util.regex.Pattern.compile("^var (_\\$[A-Za-z0-9_$]+) = ");
    java.util.regex.Pattern idPat = java.util.regex.Pattern.compile("_\\$[A-Za-z0-9_$]+");
    Map<String, Integer> declLine = new HashMap<>();
    for (int i = 0; i < lines.length; i++) {
      var m = declPat.matcher(lines[i]);
      if (m.find()) {
        declLine.put(m.group(1), i);
      }
    }
    // Each chunk's root decl ids: decls whose module tag is one of the chunk's root tags.
    java.util.LinkedHashMap<String, Set<String>> rootIds = new java.util.LinkedHashMap<>();
    Set<String> allRootIds = new HashSet<>();
    for (var e : chunkRoots.entrySet()) {
      Set<String> ids = new HashSet<>();
      for (String id : declLine.keySet()) {
        if (e.getValue().contains(tagOf(id))) {
          ids.add(id);
          allRootIds.add(id);
        }
      }
      rootIds.put(e.getKey(), ids);
    }
    // Base reachability, with chunk roots as traversal barriers (so their private subtrees can move
    // out). A root that base references DIRECTLY (by bare id) is "stuck": it can't be deferred as
    // written. For SAFETY the partition must still be runnable, so a stuck root is un-barriered and
    // its whole subtree folded back into base — repeat to a fixpoint (a subtree may contain another
    // root). The roots ever un-barriered are reported in Partition.stuckInBase.
    Set<String> barriers = new HashSet<>(allRootIds);
    Set<String> baseKeep = baseReach(lines, declPat, idPat, declLine, barriers);
    // The reported "stuck" set is the roots base reaches with EVERY root still a barrier — i.e. the
    // ones base references DIRECTLY (by bare id), the minimal actionable diagnostic. (Roots pulled in
    // only by folding a stuck root's subtree below are collateral, not reported.)
    Set<String> stuck = new java.util.TreeSet<>();
    for (String r : allRootIds) {
      if (baseKeep.contains(r)) {
        stuck.add(r);
      }
    }
    // Safety fold: a stuck root's whole subtree must stay in base (else base would reference a decl
    // that lives in an unloaded chunk). Un-barrier reachable roots and recompute to a fixpoint, so the
    // partition is always runnable.
    Set<String> folded = new HashSet<>(stuck);
    while (!folded.isEmpty()) {
      barriers.removeAll(folded);
      baseKeep = baseReach(lines, declPat, idPat, declLine, barriers);
      folded = new HashSet<>();
      for (String r : allRootIds) {
        if (barriers.contains(r) && baseKeep.contains(r)) {
          folded.add(r);
        }
      }
    }
    // Reachability from each chunk's roots (no barriers): the chunk's candidate decls.
    java.util.LinkedHashMap<String, Set<String>> reach = new java.util.LinkedHashMap<>();
    for (var e : rootIds.entrySet()) {
      Set<String> seen = new HashSet<>(e.getValue());
      java.util.Deque<String> w = new java.util.ArrayDeque<>(e.getValue());
      while (!w.isEmpty()) {
        Integer li = declLine.get(w.poll());
        if (li == null) {
          continue;
        }
        var m = idPat.matcher(lines[li]);
        while (m.find()) {
          String r = m.group();
          if (declLine.containsKey(r) && seen.add(r)) {
            w.add(r);
          }
        }
      }
      reach.put(e.getKey(), seen);
    }
    // A non-base decl shared by >1 chunk is hoisted to base; otherwise it is owned by its one chunk.
    Map<String, Integer> chunkCount = new HashMap<>();
    for (Set<String> ds : reach.values()) {
      for (String d : ds) {
        if (!baseKeep.contains(d)) {
          chunkCount.merge(d, 1, Integer::sum);
        }
      }
    }
    java.util.LinkedHashMap<String, Set<String>> chunkDecls = new java.util.LinkedHashMap<>();
    Set<String> owned = new HashSet<>();
    for (var e : reach.entrySet()) {
      Set<String> own = new java.util.TreeSet<>();
      for (String d : e.getValue()) {
        if (!baseKeep.contains(d) && chunkCount.get(d) == 1) {
          own.add(d);
        }
      }
      chunkDecls.put(e.getKey(), own);
      owned.addAll(own);
    }
    Set<String> baseDecls = new java.util.TreeSet<>();
    for (String d : declLine.keySet()) {
      if (!owned.contains(d)) {
        baseDecls.add(d);
      }
    }
    return new Partition(baseDecls, chunkDecls, stuck);
  }

  /** One base-reachability fixpoint for {@link #partition}: the ids reachable from the bundle's
   * non-declaration lines (entry mount + kernel), following decls' RHS but never traversing into a
   * {@code barriers} id (a chunk root whose subtree is a split candidate). A barrier id that is
   * referenced is still included (base needs it) but not followed. */
  private static Set<String> baseReach(
      String[] lines,
      java.util.regex.Pattern declPat,
      java.util.regex.Pattern idPat,
      Map<String, Integer> declLine,
      Set<String> barriers) {
    Set<String> keep = new HashSet<>();
    java.util.Deque<String> work = new java.util.ArrayDeque<>();
    for (int i = 0; i < lines.length; i++) {
      var d = declPat.matcher(lines[i]);
      if (d.find() && declLine.get(d.group(1)) == i) {
        continue; // a declaration line — followed only once reachable
      }
      var m = idPat.matcher(lines[i]);
      while (m.find()) {
        if (keep.add(m.group())) {
          work.add(m.group());
        }
      }
    }
    while (!work.isEmpty()) {
      String id = work.poll();
      if (barriers.contains(id)) {
        continue; // barrier: reachable, but not traversed into
      }
      Integer li = declLine.get(id);
      if (li == null) {
        continue;
      }
      var m = idPat.matcher(lines[li]);
      while (m.find()) {
        String r = m.group();
        if (!r.equals(id) && keep.add(r)) {
          work.add(r);
        }
      }
    }
    return keep;
  }

  /** Whether a line's quotes, braces and parens are all balanced (so dropping it can't corrupt a
   * multi-line construct). */
  private static boolean balancedLine(String line) {
    int braces = 0;
    int parens = 0;
    boolean inStr = false;
    char quote = 0;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inStr) {
        if (c == '\\') {
          i++;
        } else if (c == quote) {
          inStr = false;
        }
      } else if (c == '"' || c == '\'') {
        inStr = true;
        quote = c;
      } else if (c == '{') {
        braces++;
      } else if (c == '}') {
        braces--;
      } else if (c == '(') {
        parens++;
      } else if (c == ')') {
        parens--;
      }
    }
    return braces == 0 && parens == 0 && !inStr;
  }
}

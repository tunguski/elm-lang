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

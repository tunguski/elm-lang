package pl.matsuo.elm.repl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;
import pl.matsuo.elm.types.TypeChecker;

/**
 * A read-eval-print loop for Elm. Each entry is either a <b>definition</b> ({@code name = expr} or
 * {@code name args = expr}) — which is remembered and visible to later entries — or an
 * <b>expression</b>, which is evaluated and printed. Multi-line input is supported: the loop keeps
 * reading (showing a {@code |} continuation prompt) until brackets are balanced and the entry
 * doesn't end on a continuation token. Commands: {@code :type <expr>} (alias {@code :t}) shows the
 * inferred type, {@code :info <name>} (aliases {@code :i}/{@code :doc}) shows a name's type (a
 * session binding or any builtin) and its source if defined this session, {@code :load <file.elm>}
 * (alias {@code :l}, accepts several files at once) brings a module's definitions into scope,
 * {@code :browse} (alias {@code :b}) lists the session's bindings with their types,
 * {@code :complete <prefix>} lists in-scope names starting with a prefix (the hook a line-reader's
 * tab-completion would call), {@code :history} lists the session's entries, {@code :reset} forgets
 * all definitions, {@code :help}, {@code :quit}/{@code :q}.
 */
public final class Repl {

  private Repl() {}

  /** Runs the loop against the given reader/writer (so it is testable without real stdin). */
  public static void loop(Reader in, PrintStream out) throws IOException {
    loop(in, out, List.of());
  }

  /** As {@link #loop(Reader, PrintStream)}, but pre-loading the top-level definitions of the given
   * module sources (a whole project's local modules + installed dependencies) into scope. */
  public static void loop(Reader in, PrintStream out, List<String> moduleSources) throws IOException {
    loop(in, out, moduleSources, null);
  }

  /** As {@link #loop(Reader, PrintStream, List)}, but persisting command history to {@code
   * historyFile} (loaded at startup, appended as entries are made) so it survives across sessions.
   * Pass {@code null} to keep the session hermetic (e.g. in tests). */
  public static void loop(Reader in, PrintStream out, List<String> moduleSources, Path historyFile)
      throws IOException {
    BufferedReader reader = in instanceof BufferedReader b ? b : new BufferedReader(in);
    List<String> defs = new ArrayList<>(); // accumulated `name … = …` definitions
    for (String src : moduleSources) {
      defs.addAll(topLevelDefs(src));
    }
    List<String> history = loadHistory(historyFile); // prior sessions' entries (most recent last)
    if (!defs.isEmpty()) {
      out.println("(loaded " + defs.size() + " definitions from the project)");
    }
    out.println("elm-lang REPL — expressions, definitions (x = …), :type, :load, :browse, :history, :reset, :help, :quit");
    out.print("> ");
    out.flush();
    StringBuilder buffer = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (buffer.length() > 0) {
        buffer.append("\n");
      }
      buffer.append(line);
      String entry = buffer.toString();
      if (!complete(entry)) {
        out.print("| ");
        out.flush();
        continue;
      }
      buffer.setLength(0);
      String trimmed = entry.trim();
      if (!trimmed.isEmpty() && !trimmed.equals(":history")) {
        history.add(trimmed);
        appendHistory(historyFile, trimmed);
      }
      if (trimmed.equals(":quit") || trimmed.equals(":q")) {
        break;
      } else if (trimmed.isEmpty()) {
        // nothing
      } else if (trimmed.equals(":help")) {
        out.println(
            "expressions are evaluated; `x = …` defines a binding; :type <expr>, :info <name>,"
                + " :load <file.elm> [more.elm …], :browse, :complete <prefix>, :history, :reset, :quit");
      } else if (trimmed.equals(":reset")) {
        defs.clear();
        out.println("(forgot all definitions)");
      } else if (trimmed.equals(":history")) {
        for (int i = 0; i < history.size(); i++) {
          out.println((i + 1) + "  " + history.get(i).replace("\n", "\n   "));
        }
      } else if (trimmed.startsWith(":load ") || trimmed.startsWith(":l ")) {
        // Multiple files may be given at once: `:load A.elm B.elm` loads each in turn.
        String[] paths = trimmed.substring(trimmed.indexOf(' ') + 1).trim().split("\\s+");
        for (String path : paths) {
          if (path.isEmpty()) {
            continue;
          }
          try {
            List<String> loaded = topLevelDefs(Files.readString(Path.of(path)));
            defs.addAll(loaded);
            out.println("(loaded " + loaded.size() + " definitions from " + path + ")");
          } catch (Exception e) {
            out.println("Error: could not load " + path + ": " + e.getMessage());
          }
        }
      } else if (trimmed.equals(":browse") || trimmed.equals(":b")) {
        out.print(browse(defs));
      } else if (trimmed.startsWith(":complete ")) {
        List<String> matches = completions(defs, trimmed.substring(trimmed.indexOf(' ') + 1).trim());
        out.println(matches.isEmpty() ? "(no matches)" : String.join(" ", matches));
      } else if (trimmed.startsWith(":type ") || trimmed.startsWith(":t ")) {
        String expr = trimmed.substring(trimmed.indexOf(' ') + 1);
        out.println(typeOf(defs, expr));
      } else if (trimmed.startsWith(":info ") || trimmed.startsWith(":i ") || trimmed.startsWith(":doc ")) {
        out.println(infoOf(defs, trimmed.substring(trimmed.indexOf(' ') + 1).trim()));
      } else if (isDefinition(trimmed)) {
        defs.add(trimmed);
        out.println(definedName(trimmed) + " : " + typeOfName(defs, definedName(trimmed)));
      } else {
        out.println(evalExpr(defs, trimmed));
      }
      out.print("> ");
      out.flush();
    }
    out.println();
  }

  /** Evaluates {@code expr} against the accumulated definitions, returning the shown value or error. */
  private static String evalExpr(List<String> defs, String expr) {
    try {
      Object v = Interpreter.load(moduleWith(defs, "replResult = " + expr)).value("replResult");
      return Show.pretty(v); // multi-line layout for large records/lists; compact for small values
    } catch (RuntimeException e) {
      return "Error: " + e.getMessage();
    }
  }

  /** The default persistent-history file: {@code $ELM_REPL_HISTORY} or {@code ~/.elm_repl_history}. */
  public static Path defaultHistoryFile() {
    String env = System.getenv("ELM_REPL_HISTORY");
    if (env != null && !env.isEmpty()) {
      return Path.of(env);
    }
    return Path.of(System.getProperty("user.home", "."), ".elm_repl_history");
  }

  /** Loads prior-session history (one entry per line, internal newlines stored as {@code \n}); an
   *  absent file or read error yields an empty list. {@code null} disables persistence. */
  static List<String> loadHistory(Path file) {
    List<String> history = new ArrayList<>();
    if (file == null || !Files.exists(file)) {
      return history;
    }
    try {
      for (String line : Files.readAllLines(file)) {
        if (!line.isEmpty()) {
          history.add(line.replace("\\n", "\n"));
        }
      }
    } catch (IOException ignored) {
      // an unreadable history file is non-fatal
    }
    return history;
  }

  /** Appends one entry to the history file (newlines escaped to keep it one line). No-op for {@code
   *  null} or on error — history persistence never breaks the REPL. */
  static void appendHistory(Path file, String entry) {
    if (file == null) {
      return;
    }
    try {
      Files.writeString(
          file,
          entry.replace("\n", "\\n") + "\n",
          java.nio.charset.StandardCharsets.UTF_8,
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // a non-writable history file is non-fatal
    }
  }

  /** {@code :browse}: every session binding with its inferred type, then the count of built-ins in
   * scope (which {@code :complete <prefix>} can enumerate). */
  static String browse(List<String> defs) {
    StringBuilder sb = new StringBuilder();
    List<String> names = sessionNames(defs);
    if (names.isEmpty()) {
      sb.append("(no session bindings yet)\n");
    } else {
      for (String name : names) {
        sb.append(name).append(" : ").append(typeOfName(defs, name)).append("\n");
      }
    }
    sb.append("-- ").append(pl.matsuo.elm.types.Signatures.globals().size())
        .append(" built-ins in scope (e.g. :complete List., :complete map)\n");
    return sb.toString();
  }

  /** In-scope names beginning with {@code prefix}: this session's bindings and the typed built-ins,
   * sorted and de-duplicated. This is the lookup a line-reader's tab-completion would call. */
  static List<String> completions(List<String> defs, String prefix) {
    java.util.TreeSet<String> names = new java.util.TreeSet<>(sessionNames(defs));
    names.addAll(pl.matsuo.elm.types.Signatures.globals().keySet());
    List<String> out = new ArrayList<>();
    for (String name : names) {
      if (name.startsWith(prefix)) {
        out.add(name);
      }
    }
    return out;
  }

  /** The names bound by this session's definitions (most recent shadowing wins), in first-seen order. */
  private static List<String> sessionNames(List<String> defs) {
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    for (String d : defs) {
      if (isDefinition(d)) {
        String name = definedName(d);
        if (!name.equals("type")) { // skip `type`/`type alias` chunks, whose lead word isn't a value
          names.add(name);
        }
      }
    }
    return new ArrayList<>(names);
  }

  /** {@code :info}/{@code :doc <name>}: the inferred type of {@code name} (works for a session
   * definition or any builtin), followed by the definition's source when it was defined this session. */
  private static String infoOf(List<String> defs, String name) {
    StringBuilder sb = new StringBuilder(typeOf(defs, name));
    for (String d : defs) {
      if (isDefinition(d) && definedName(d).equals(name)) {
        sb.append("\n").append(d);
        break;
      }
    }
    return sb.toString();
  }

  /** The inferred type of {@code expr} (with the accumulated definitions in scope). */
  private static String typeOf(List<String> defs, String expr) {
    try {
      var types = TypeChecker.checkModule(moduleWith(defs, "replResult = " + expr));
      return expr + " : " + types.getOrDefault("replResult", "?");
    } catch (RuntimeException e) {
      return "Type error: " + e.getMessage();
    }
  }

  private static String typeOfName(List<String> defs, String name) {
    try {
      var types = TypeChecker.checkModule(moduleWith(defs, ""));
      return types.getOrDefault(name, "?");
    } catch (RuntimeException e) {
      return "?";
    }
  }

  /** The top-level definition chunks of a module's source (value/type/alias/port declarations),
   * dropping the {@code module} header, {@code import}s and comment-only chunks — so {@code :load}
   * brings a file's definitions into REPL scope. Chunks split on column-0 lines (layout-lite). */
  static List<String> topLevelDefs(String content) {
    List<String> chunks = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    for (String line : content.split("\n", -1)) {
      boolean topStart = !line.isEmpty() && !Character.isWhitespace(line.charAt(0));
      if (topStart && cur.length() > 0) {
        addChunk(chunks, cur.toString());
        cur.setLength(0);
      }
      cur.append(line).append("\n");
    }
    addChunk(chunks, cur.toString());
    return chunks;
  }

  private static void addChunk(List<String> chunks, String chunk) {
    String t = chunk.strip();
    if (t.isEmpty() || t.startsWith("--") || t.startsWith("{-")) {
      return;
    }
    String first = t.split("\\s+", 2)[0];
    if (first.equals("module") || first.equals("import")) {
      return;
    }
    chunks.add(chunk.stripTrailing());
  }

  private static String moduleWith(List<String> defs, String extra) {
    StringBuilder sb = new StringBuilder();
    for (String d : defs) {
      sb.append(d).append("\n\n");
    }
    sb.append(extra).append("\n");
    return sb.toString();
  }

  private static long count(java.util.List<String> tokens, String word) {
    return tokens.stream().filter(word::equals).count();
  }

  /** A definition is a lowercase name with optional parameters, then a single `=` (not `==`). */
  static boolean isDefinition(String entry) {
    return entry.matches("(?s)[a-z][A-Za-z0-9_]*(\\s+[a-zA-Z0-9_]+)*\\s*=([^=].*|)");
  }

  private static String definedName(String entry) {
    int i = 0;
    while (i < entry.length() && (Character.isLetterOrDigit(entry.charAt(i)) || entry.charAt(i) == '_')) {
      i++;
    }
    return entry.substring(0, i);
  }

  /** Whether a (possibly multi-line) entry is syntactically complete enough to evaluate. */
  static boolean complete(String entry) {
    int depth = 0;
    boolean inString = false;
    for (int i = 0; i < entry.length(); i++) {
      char c = entry.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == '"') {
          inString = false;
        }
      } else if (c == '"') {
        inString = true;
      } else if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      }
    }
    if (depth > 0 || inString) {
      return false;
    }
    String t = entry.stripTrailing();
    if (t.isEmpty()) {
      return true;
    }
    // An `if` awaits its `else`, and a `let` awaits its `in` — count whole-word keywords.
    java.util.List<String> tokens = List.of(t.split("[^A-Za-z]+"));
    if (count(tokens, "if") > count(tokens, "else") || count(tokens, "let") > count(tokens, "in")) {
      return false;
    }
    // A trailing keyword (as a whole word) continues onto the next line.
    String[] words = t.split("\\s+");
    String lastWord = words[words.length - 1];
    if (List.of("let", "if", "then", "else", "of", "in").contains(lastWord)) {
      return false;
    }
    // A trailing operator (other than a completed comparison) continues too.
    for (String op : new String[] {"=", "->", "\\", ",", "(", "[", "{", "+", "-", "*", "/", "++", "::", "|>", "<|", "&&", "||"}) {
      if (t.endsWith(op) && !t.endsWith("==") && !t.endsWith(">=") && !t.endsWith("<=") && !t.endsWith("/=")) {
        return false;
      }
    }
    return true;
  }
}

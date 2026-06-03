package pl.matsuo.elm.interp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pl.matsuo.elm.util.Resources;

/**
 * On-demand resolution of the pure bundled libraries. When a program {@code import}s one of these
 * modules and doesn't supply it itself, its source is pulled from the classpath and added to the
 * module set, so {@code import List.Extra}/{@code Maybe.Extra}/{@code Hex}/… "just work" without the
 * caller having to know which resource backs them. Resolution is transitive (a bundled lib that
 * imports another pulls it in too).
 *
 * <p>Only side-effect-free, dependency-light libraries are auto-resolved here. Context-specific
 * libraries (Posix/Bash for scripts, Server for the HTTP runner, Site for the gallery) are still
 * injected explicitly by the command that needs them, so this never changes their behaviour.
 */
public final class BundledLibs {

  private BundledLibs() {}

  /** Module name -> classpath resource for the auto-resolvable pure libraries. */
  private static final Map<String, String> KNOWN =
      Map.ofEntries(
          Map.entry("List.Extra", "/elm/lib/List/Extra.elm"),
          Map.entry("Maybe.Extra", "/elm/lib/Maybe/Extra.elm"),
          Map.entry("Result.Extra", "/elm/lib/Result/Extra.elm"),
          Map.entry("String.Extra", "/elm/lib/String/Extra.elm"),
          Map.entry("Dict.Extra", "/elm/lib/Dict/Extra.elm"),
          Map.entry("Set.Extra", "/elm/lib/Set/Extra.elm"),
          Map.entry("Hex", "/elm/lib/Hex.elm"),
          Map.entry("Base64", "/elm/lib/Base64.elm"),
          Map.entry("Url.Builder", "/elm/lib/Url/Builder.elm"));

  private static final Pattern MODULE = Pattern.compile("(?m)^module\\s+([A-Za-z0-9_.]+)");
  private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+([A-Za-z0-9_.]+)");

  /** Whether {@code module} is one of the auto-resolvable bundled libraries. */
  public static boolean isKnown(String module) {
    return KNOWN.containsKey(module);
  }

  /**
   * Returns the source of every auto-resolvable bundled library that {@code sources} import
   * (transitively) and don't already define, followed by {@code sources} unchanged. Resolved
   * libraries are placed <em>first</em> so the caller's original last source stays last — callers
   * that treat the last source as the entry module keep working.
   */
  public static List<String> resolve(List<String> sources) {
    List<String> libs = new ArrayList<>();
    Set<String> present = new HashSet<>();
    for (String s : sources) {
      String name = firstMatch(MODULE, s);
      if (name != null) {
        present.add(name);
      }
    }
    Deque<String> toScan = new ArrayDeque<>(sources);
    while (!toScan.isEmpty()) {
      String source = toScan.poll();
      Matcher m = IMPORT.matcher(source);
      while (m.find()) {
        String imp = m.group(1);
        if (KNOWN.containsKey(imp) && present.add(imp)) {
          String libSource = Resources.read(KNOWN.get(imp));
          libs.add(libSource);
          toScan.add(libSource); // resolve the library's own bundled imports too
        }
      }
    }
    if (libs.isEmpty()) {
      return new ArrayList<>(sources);
    }
    libs.addAll(sources);
    return libs;
  }

  private static String firstMatch(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }
}

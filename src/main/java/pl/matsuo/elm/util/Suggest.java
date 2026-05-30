package pl.matsuo.elm.util;

import java.util.Collection;

/** Levenshtein-based "did you mean …?" suggestions for misspelled names and fields. */
public final class Suggest {

  private Suggest() {}

  /**
   * The candidate closest to {@code target} by edit distance, if one is close enough to be a likely
   * typo (distance ≤ ~⅓ of the length, and ≤ 3), else {@code null}. Qualified candidates are matched
   * on their final segment too, so {@code lenght} suggests {@code List.length}.
   */
  public static String closest(String target, Collection<String> candidates) {
    String best = null;
    int bestDist = Integer.MAX_VALUE;
    int limit = Math.min(3, Math.max(1, target.length() / 3 + 1));
    for (String c : candidates) {
      String simple = c.contains(".") ? c.substring(c.lastIndexOf('.') + 1) : c;
      int d = Math.min(distance(target, c), distance(target, simple));
      if (d < bestDist && d <= limit && !c.equals(target)) {
        bestDist = d;
        best = c;
      }
    }
    return best;
  }

  /** A " Did you mean `X`?" fragment for the closest candidate, or "" if none is close. */
  public static String hint(String target, Collection<String> candidates) {
    String c = closest(target, candidates);
    return c == null ? "" : " Did you mean `" + c + "`?";
  }

  /** Standard Levenshtein edit distance. */
  public static int distance(String a, String b) {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      cur[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = cur;
      cur = tmp;
    }
    return prev[b.length()];
  }
}

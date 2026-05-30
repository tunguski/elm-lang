package pl.matsuo.elm.pkg;

/**
 * An Elm version constraint of the form {@code "LOWER <= v < UPPER"} (the only shape Elm's package
 * {@code elm.json} uses), inclusive of {@code lower} and exclusive of {@code upper}. An exact
 * version pin ({@code "1.0.5"}, as application {@code elm.json} uses) parses to {@code [v, v.next)}.
 */
public record Constraint(Version lower, Version upper) {

  /** Parses {@code "1.0.0 <= v < 2.0.0"} or an exact {@code "1.0.0"}. */
  public static Constraint parse(String s) {
    String t = s.trim();
    int le = t.indexOf("<=");
    if (le < 0) {
      Version v = Version.parse(t); // an exact pin
      return new Constraint(v, nextPatch(v));
    }
    int lt = t.indexOf('<', le + 2);
    if (lt < 0) {
      throw new IllegalArgumentException("not a 'LOWER <= v < UPPER' constraint: " + s);
    }
    Version lower = Version.parse(t.substring(0, le));
    Version upper = Version.parse(t.substring(lt + 1));
    return new Constraint(lower, upper);
  }

  /** The constraint a fresh dependency on {@code v} gets: {@code v <= w < (v+1).0.0}. */
  public static Constraint forNewDependency(Version v) {
    return new Constraint(v, v.nextMajor());
  }

  private static Version nextPatch(Version v) {
    return new Version(v.major(), v.minor(), v.patch() + 1);
  }

  /** Whether {@code v} satisfies the constraint ({@code lower <= v < upper}). */
  public boolean allows(Version v) {
    return v.compareTo(lower) >= 0 && v.compareTo(upper) < 0;
  }

  /** The intersection with {@code other}, or {@code null} if the two cannot both be satisfied. */
  public Constraint intersect(Constraint other) {
    Version lo = lower.compareTo(other.lower) >= 0 ? lower : other.lower;
    Version hi = upper.compareTo(other.upper) <= 0 ? upper : other.upper;
    return lo.compareTo(hi) < 0 ? new Constraint(lo, hi) : null;
  }

  @Override
  public String toString() {
    return lower + " <= v < " + upper;
  }
}

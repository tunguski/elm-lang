package pl.matsuo.elm.pkg;

/**
 * A semantic version {@code major.minor.patch}, as used in Elm's {@code elm.json}. Comparable in the
 * natural (numeric, field-by-field) order, so the highest compatible version is just the maximum.
 */
public record Version(int major, int minor, int patch) implements Comparable<Version> {

  /** Parses {@code "1.2.3"}; throws {@link IllegalArgumentException} on a malformed string. */
  public static Version parse(String s) {
    String[] parts = s.trim().split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("not a MAJOR.MINOR.PATCH version: " + s);
    }
    try {
      return new Version(
          Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("not a numeric version: " + s, e);
    }
  }

  /** The next major version ({@code 1.4.2 -> 2.0.0}) — the open upper bound Elm uses by default. */
  public Version nextMajor() {
    return new Version(major + 1, 0, 0);
  }

  @Override
  public int compareTo(Version o) {
    if (major != o.major) {
      return Integer.compare(major, o.major);
    }
    if (minor != o.minor) {
      return Integer.compare(minor, o.minor);
    }
    return Integer.compare(patch, o.patch);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}

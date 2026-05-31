package pl.matsuo.elm.pkg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import pl.matsuo.elm.doc.ApiDocs;

/**
 * Compares two module APIs ({@link ApiDocs}) and derives a semantic-version change magnitude, the
 * way {@code elm diff}/{@code elm bump} do: a removed or changed public entry is a <b>MAJOR</b>
 * change, a purely additive one is <b>MINOR</b>, and no API change is <b>PATCH</b>. The recorded
 * change list is shown to the user.
 */
public final class ApiDiff {

  /** A semver change magnitude, ordered least-to-most significant. */
  public enum Magnitude {
    PATCH,
    MINOR,
    MAJOR
  }

  private final Magnitude magnitude;
  private final List<String> changes;

  private ApiDiff(Magnitude magnitude, List<String> changes) {
    this.magnitude = magnitude;
    this.changes = changes;
  }

  public Magnitude magnitude() {
    return magnitude;
  }

  public List<String> changes() {
    return changes;
  }

  /** Diffs {@code old} → {@code now}. */
  public static ApiDiff compare(ApiDocs old, ApiDocs now) {
    List<String> added = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    List<String> changed = new ArrayList<>();

    diffEntries(old.values().keySet(), now.values().keySet(), added, removed, changed,
        name -> !signature(old, name).equals(signature(now, name)), "value");
    diffEntries(old.unions().keySet(), now.unions().keySet(), added, removed, changed,
        name -> !union(old, name).equals(union(now, name)), "type");
    diffEntries(old.aliases().keySet(), now.aliases().keySet(), added, removed, changed,
        name -> !alias(old, name).equals(alias(now, name)), "type alias");

    Magnitude m;
    if (!removed.isEmpty() || !changed.isEmpty()) {
      m = Magnitude.MAJOR;
    } else if (!added.isEmpty()) {
      m = Magnitude.MINOR;
    } else {
      m = Magnitude.PATCH;
    }
    List<String> all = new ArrayList<>();
    removed.forEach(s -> all.add("- removed " + s));
    changed.forEach(s -> all.add("~ changed " + s));
    added.forEach(s -> all.add("+ added " + s));
    return new ApiDiff(m, all);
  }

  private interface Changed {
    boolean test(String name);
  }

  private static void diffEntries(
      Set<String> oldNames, Set<String> nowNames, List<String> added, List<String> removed,
      List<String> changed, Changed isChanged, String kind) {
    Set<String> all = new TreeSet<>(oldNames);
    all.addAll(nowNames);
    for (String name : all) {
      boolean inOld = oldNames.contains(name);
      boolean inNew = nowNames.contains(name);
      if (inOld && !inNew) {
        removed.add(kind + " " + name);
      } else if (!inOld && inNew) {
        added.add(kind + " " + name);
      } else if (isChanged.test(name)) {
        changed.add(kind + " " + name);
      }
    }
  }

  private static String signature(ApiDocs d, String name) {
    ApiDocs.Value v = d.values().get(name);
    return v == null ? "" : v.type();
  }

  private static String union(ApiDocs d, String name) {
    ApiDocs.Union u = d.unions().get(name);
    return u == null ? "" : u.params() + " " + u.tags();
  }

  private static String alias(ApiDocs d, String name) {
    ApiDocs.Alias a = d.aliases().get(name);
    return a == null ? "" : a.params() + " " + a.type();
  }

  /** The next version after applying {@code magnitude} to {@code current} (standard semver bump). */
  public static Version bump(Version current, Magnitude magnitude) {
    return switch (magnitude) {
      case MAJOR -> new Version(current.major() + 1, 0, 0);
      case MINOR -> new Version(current.major(), current.minor() + 1, 0);
      case PATCH -> new Version(current.major(), current.minor(), current.patch() + 1);
    };
  }
}

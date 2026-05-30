package pl.matsuo.elm.pkg;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import pl.matsuo.elm.json.JsonEncode;
import pl.matsuo.elm.json.JsonParse;

/**
 * An in-memory model of an application {@code elm.json}: its source directories and its resolved
 * dependencies, split into {@code direct} (the ones imported here) and {@code indirect} (their
 * transitive closure), each pinned to an exact {@link Version}. Round-trips through the project's
 * JSON reader/writer, preserving the field layout {@code elm} itself produces.
 */
public final class ElmJson {

  private final Map<String, Object> root; // the raw parsed object, kept so unknown fields survive
  private final Map<String, Version> direct;
  private final Map<String, Version> indirect;

  private ElmJson(Map<String, Object> root, Map<String, Version> direct, Map<String, Version> indirect) {
    this.root = root;
    this.direct = direct;
    this.indirect = indirect;
  }

  /** Parses an application {@code elm.json}; throws if it isn't an {@code "application"} project. */
  @SuppressWarnings("unchecked")
  public static ElmJson parse(String json) {
    Object parsed = JsonParse.parse(json);
    if (!(parsed instanceof Map<?, ?> obj)) {
      throw new IllegalArgumentException("elm.json is not a JSON object");
    }
    Map<String, Object> map = (Map<String, Object>) obj;
    if (!"application".equals(map.get("type"))) {
      throw new IllegalArgumentException("only application elm.json is supported (type was "
          + map.get("type") + ")");
    }
    Map<String, Object> deps =
        map.get("dependencies") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
    return new ElmJson(
        map,
        parseVersions(deps.get("direct")),
        parseVersions(deps.get("indirect")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Version> parseVersions(Object section) {
    Map<String, Version> out = new TreeMap<>();
    if (section instanceof Map<?, ?> m) {
      for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
        out.put(e.getKey(), Version.parse((String) e.getValue()));
      }
    }
    return out;
  }

  /** Parses a {@code "pkg": "constraint"} map (a package manifest's dependencies). */
  public static Map<String, Constraint> parseConstraints(Map<String, Object> deps) {
    Map<String, Constraint> out = new TreeMap<>();
    for (Map.Entry<String, Object> e : deps.entrySet()) {
      out.put(e.getKey(), Constraint.parse((String) e.getValue()));
    }
    return out;
  }

  public Map<String, Version> direct() {
    return direct;
  }

  public Map<String, Version> indirect() {
    return indirect;
  }

  /** Every dependency, direct and indirect, as the current pinned solution. */
  public Map<String, Version> all() {
    Map<String, Version> out = new TreeMap<>(indirect);
    out.putAll(direct);
    return out;
  }

  /**
   * Replaces the dependency solution: {@code newDirect} become direct dependencies and everything
   * else in {@code solution} is recorded as indirect. Source directories and other fields are kept.
   */
  public void setSolution(java.util.Set<String> newDirect, Map<String, Version> solution) {
    direct.clear();
    indirect.clear();
    for (Map.Entry<String, Version> e : solution.entrySet()) {
      if (newDirect.contains(e.getKey())) {
        direct.put(e.getKey(), e.getValue());
      } else {
        indirect.put(e.getKey(), e.getValue());
      }
    }
  }

  /** Serialises back to a 4-space-indented {@code elm.json} string with sorted dependency maps. */
  public String render() {
    Map<String, Object> deps = new LinkedHashMap<>();
    deps.put("direct", toJsonMap(direct));
    deps.put("indirect", toJsonMap(indirect));
    root.put("dependencies", deps);
    return JsonEncode.serialize(root, 4) + "\n";
  }

  private static Map<String, Object> toJsonMap(Map<String, Version> versions) {
    Map<String, Object> out = new LinkedHashMap<>();
    versions.forEach((k, v) -> out.put(k, v.toString()));
    return out;
  }
}

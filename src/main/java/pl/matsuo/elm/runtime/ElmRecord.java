package pl.matsuo.elm.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** An immutable Elm record. Functional update returns a new record. */
public final class ElmRecord {

  private final Map<String, Object> fields;

  public ElmRecord(Map<String, Object> fields) {
    // Defensive copy keeps the record immutable and gives stable iteration order.
    this.fields = new LinkedHashMap<>(fields);
  }

  public Object get(String name) {
    return fields.get(name);
  }

  public boolean has(String name) {
    return fields.containsKey(name);
  }

  public Map<String, Object> fields() {
    return fields;
  }

  /** Returns a copy with {@code name} set to {@code value}. */
  public ElmRecord with(String name, Object value) {
    Map<String, Object> copy = new LinkedHashMap<>(fields);
    copy.put(name, value);
    return new ElmRecord(copy);
  }

  /** Returns a copy with all the given updates applied. */
  public ElmRecord withAll(Map<String, Object> updates) {
    Map<String, Object> copy = new LinkedHashMap<>(fields);
    copy.putAll(updates);
    return new ElmRecord(copy);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ElmRecord r && fields.equals(r.fields);
  }

  @Override
  public int hashCode() {
    return fields.hashCode();
  }

  @Override
  public String toString() {
    return fields.entrySet().stream()
        .map(e -> e.getKey() + " = " + e.getValue())
        .collect(Collectors.joining(", ", "{ ", " }"));
  }
}

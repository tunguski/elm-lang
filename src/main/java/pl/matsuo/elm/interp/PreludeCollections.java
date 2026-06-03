package pl.matsuo.elm.interp;

import static pl.matsuo.elm.interp.Prelude.NOTHING;
import static pl.matsuo.elm.interp.Prelude.fn;
import static pl.matsuo.elm.interp.Prelude.isJust;
import static pl.matsuo.elm.interp.Prelude.just;
import static pl.matsuo.elm.interp.Prelude.justValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.runtime.ElmArray;
import pl.matsuo.elm.runtime.ElmDict;
import pl.matsuo.elm.runtime.ElmList;
import pl.matsuo.elm.runtime.ElmSet;
import pl.matsuo.elm.runtime.ElmTuple;

/**
 * The {@code Array}, {@code Dict} and {@code Set} builtins of the interpreter prelude. These three
 * modules form a cohesive, self-contained group (their helpers {@code arr}/{@code clampIndex}/
 * {@code CMP}/{@code asDict}/{@code asSet} are local) and only touch {@link Prelude}'s shared
 * registration helpers, so they live in their own file. {@link Prelude}'s static initializer calls
 * {@link #registerArray()}, {@link #registerDict()} and {@link #registerSet()}.
 */
final class PreludeCollections {

  private PreludeCollections() {}

  // --- Array -------------------------------------------------------------

  private static Object[] arr(Object o) {
    return ((ElmArray) o).items();
  }

  private static int clampIndex(long i, int len) {
    long v = i < 0 ? len + i : i;
    return (int) Math.max(0, Math.min(v, len));
  }

  static void registerArray() {
    Prelude.BUILTINS.put("Array.empty", ElmArray.EMPTY);
    fn("Array.isEmpty", 1, a -> arr(a[0]).length == 0);
    fn("Array.length", 1, a -> (long) arr(a[0]).length);
    fn("Array.repeat", 2, a -> {
      int n = (int) Math.max(0, Operators.asLong(a[0]));
      Object[] out = new Object[n];
      java.util.Arrays.fill(out, a[1]);
      return new ElmArray(out);
    });
    fn("Array.initialize", 2, a -> {
      int n = (int) Math.max(0, Operators.asLong(a[0]));
      Object[] out = new Object[n];
      for (int i = 0; i < n; i++) {
        out[i] = Apply.apply(a[1], (long) i);
      }
      return new ElmArray(out);
    });
    fn("Array.fromList", 1, a -> new ElmArray(((ElmList) a[0]).toJava().toArray()));
    fn("Array.toList", 1, a -> ElmList.fromJava(java.util.Arrays.asList(arr(a[0]))));
    fn("Array.get", 2, a -> {
      int i = (int) Operators.asLong(a[0]);
      Object[] items = arr(a[1]);
      return (i >= 0 && i < items.length) ? just(items[i]) : NOTHING;
    });
    fn("Array.set", 3, a -> ((ElmArray) a[2]).set((int) Operators.asLong(a[0]), a[1]));
    fn("Array.push", 2, a -> ((ElmArray) a[1]).push(a[0]));
    fn("Array.append", 2, a -> {
      Object[] x = arr(a[0]);
      Object[] y = arr(a[1]);
      Object[] out = java.util.Arrays.copyOf(x, x.length + y.length);
      System.arraycopy(y, 0, out, x.length, y.length);
      return new ElmArray(out);
    });
    fn("Array.slice", 3, a -> {
      Object[] items = arr(a[2]);
      int from = clampIndex(Operators.asLong(a[0]), items.length);
      int to = clampIndex(Operators.asLong(a[1]), items.length);
      return from < to
          ? new ElmArray(java.util.Arrays.copyOfRange(items, from, to))
          : ElmArray.EMPTY;
    });
    fn("Array.map", 2, a -> {
      Object[] items = arr(a[1]);
      Object[] out = new Object[items.length];
      for (int i = 0; i < items.length; i++) {
        out[i] = Apply.apply(a[0], items[i]);
      }
      return new ElmArray(out);
    });
    fn("Array.indexedMap", 2, a -> {
      Object[] items = arr(a[1]);
      Object[] out = new Object[items.length];
      for (int i = 0; i < items.length; i++) {
        out[i] = Apply.applyAll(a[0], (long) i, items[i]);
      }
      return new ElmArray(out);
    });
    fn("Array.foldl", 3, a -> {
      Object acc = a[1];
      for (Object x : arr(a[2])) {
        acc = Apply.applyAll(a[0], x, acc);
      }
      return acc;
    });
    fn("Array.foldr", 3, a -> {
      Object[] items = arr(a[2]);
      Object acc = a[1];
      for (int i = items.length - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], items[i], acc);
      }
      return acc;
    });
    fn("Array.filter", 2, a -> {
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (Object x : arr(a[1])) {
        if (Boolean.TRUE.equals(Apply.apply(a[0], x))) {
          out.add(x);
        }
      }
      return new ElmArray(out.toArray());
    });
    fn("Array.toIndexedList", 1, a -> {
      Object[] items = arr(a[0]);
      java.util.List<Object> out = new java.util.ArrayList<>();
      for (int i = 0; i < items.length; i++) {
        out.add(new ElmTuple(new Object[] {(long) i, items[i]}));
      }
      return ElmList.fromJava(out);
    });
  }

  // --- Dict / Set --------------------------------------------------------

  private static final Comparator<Object> CMP = Operators::compareValues;

  private static ElmDict asDict(Object o) {
    return (ElmDict) o;
  }

  static void registerDict() {
    Prelude.BUILTINS.put("Dict.empty", ElmDict.empty(CMP));
    fn("Dict.singleton", 2, a -> ElmDict.empty(CMP).insert(a[0], a[1]));
    fn("Dict.insert", 3, a -> asDict(a[2]).insert(a[0], a[1]));
    fn("Dict.remove", 2, a -> asDict(a[1]).remove(a[0]));
    fn("Dict.member", 2, a -> asDict(a[1]).member(a[0]));
    fn("Dict.size", 1, a -> (long) asDict(a[0]).size());
    fn("Dict.isEmpty", 1, a -> asDict(a[0]).size() == 0);
    fn("Dict.get", 2, a -> {
      Object v = asDict(a[1]).getOrNull(a[0]);
      return v == null ? NOTHING : just(v);
    });
    fn("Dict.update", 3, a -> {
      ElmDict dict = asDict(a[2]);
      Object current = dict.getOrNull(a[0]);
      Object maybe = current == null ? NOTHING : just(current);
      Object result = Apply.apply(a[1], maybe);
      if (isJust(result)) {
        return dict.insert(a[0], justValue(result));
      }
      return dict.remove(a[0]);
    });
    fn("Dict.keys", 1, a -> ElmList.fromJava(new ArrayList<>(asDict(a[0]).entries().keySet())));
    fn("Dict.values", 1, a -> ElmList.fromJava(new ArrayList<>(asDict(a[0]).entries().values())));
    fn("Dict.toList", 1, a -> {
      List<Object> out = new ArrayList<>();
      asDict(a[0]).entries().forEach((k, v) -> out.add(new ElmTuple(new Object[] {k, v})));
      return ElmList.fromJava(out);
    });
    fn("Dict.fromList", 1, a -> {
      ElmDict dict = ElmDict.empty(CMP);
      for (Object pair : ((ElmList) a[0]).toJava()) {
        ElmTuple t = (ElmTuple) pair;
        dict = dict.insert(t.get(0), t.get(1));
      }
      return dict;
    });
    fn("Dict.map", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      for (Map.Entry<Object, Object> e : asDict(a[1]).entries().entrySet()) {
        out = out.insert(e.getKey(), Apply.applyAll(a[0], e.getKey(), e.getValue()));
      }
      return out;
    });
    fn("Dict.filter", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      for (Map.Entry<Object, Object> e : asDict(a[1]).entries().entrySet()) {
        if ((Boolean) Apply.applyAll(a[0], e.getKey(), e.getValue())) {
          out = out.insert(e.getKey(), e.getValue());
        }
      }
      return out;
    });
    fn("Dict.foldl", 3, a -> {
      Object acc = a[1];
      for (Map.Entry<Object, Object> e : asDict(a[2]).entries().entrySet()) {
        acc = Apply.applyAll(a[0], e.getKey(), e.getValue(), acc);
      }
      return acc;
    });
    fn("Dict.foldr", 3, a -> {
      Object acc = a[1];
      java.util.List<Map.Entry<Object, Object>> entries =
          new java.util.ArrayList<>(asDict(a[2]).entries().entrySet());
      for (int i = entries.size() - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], entries.get(i).getKey(), entries.get(i).getValue(), acc);
      }
      return acc;
    });
    fn("Dict.union", 2, a -> {
      ElmDict out = asDict(a[1]);
      for (Map.Entry<Object, Object> e : asDict(a[0]).entries().entrySet()) {
        out = out.insert(e.getKey(), e.getValue());
      }
      return out;
    });
    fn("Dict.intersect", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      ElmDict other = asDict(a[1]);
      for (Map.Entry<Object, Object> e : asDict(a[0]).entries().entrySet()) {
        if (other.member(e.getKey())) {
          out = out.insert(e.getKey(), e.getValue());
        }
      }
      return out;
    });
    fn("Dict.diff", 2, a -> {
      ElmDict out = ElmDict.empty(CMP);
      ElmDict other = asDict(a[1]);
      for (Map.Entry<Object, Object> e : asDict(a[0]).entries().entrySet()) {
        if (!other.member(e.getKey())) {
          out = out.insert(e.getKey(), e.getValue());
        }
      }
      return out;
    });
    fn("Dict.partition", 2, a -> {
      ElmDict yes = ElmDict.empty(CMP);
      ElmDict no = ElmDict.empty(CMP);
      for (Map.Entry<Object, Object> e : asDict(a[1]).entries().entrySet()) {
        if ((Boolean) Apply.applyAll(a[0], e.getKey(), e.getValue())) {
          yes = yes.insert(e.getKey(), e.getValue());
        } else {
          no = no.insert(e.getKey(), e.getValue());
        }
      }
      return new ElmTuple(new Object[] {yes, no});
    });
    // Dict.merge leftStep bothStep rightStep leftDict rightDict initial -> result. Steps the two
    // dicts in ascending key order, calling leftStep / bothStep / rightStep per key.
    fn("Dict.merge", 6, a -> {
      List<Map.Entry<Object, Object>> ls = new ArrayList<>(asDict(a[3]).entries().entrySet());
      List<Map.Entry<Object, Object>> rs = new ArrayList<>(asDict(a[4]).entries().entrySet());
      Object acc = a[5];
      int i = 0;
      int j = 0;
      while (i < ls.size() && j < rs.size()) {
        Map.Entry<Object, Object> l = ls.get(i);
        Map.Entry<Object, Object> r = rs.get(j);
        int c = CMP.compare(l.getKey(), r.getKey());
        if (c < 0) {
          acc = Apply.applyAll(a[0], l.getKey(), l.getValue(), acc);
          i++;
        } else if (c > 0) {
          acc = Apply.applyAll(a[2], r.getKey(), r.getValue(), acc);
          j++;
        } else {
          acc = Apply.applyAll(a[1], l.getKey(), l.getValue(), r.getValue(), acc);
          i++;
          j++;
        }
      }
      for (; i < ls.size(); i++) {
        acc = Apply.applyAll(a[0], ls.get(i).getKey(), ls.get(i).getValue(), acc);
      }
      for (; j < rs.size(); j++) {
        acc = Apply.applyAll(a[2], rs.get(j).getKey(), rs.get(j).getValue(), acc);
      }
      return acc;
    });
  }

  private static ElmSet asSet(Object o) {
    return (ElmSet) o;
  }

  static void registerSet() {
    Prelude.BUILTINS.put("Set.empty", ElmSet.empty(CMP));
    fn("Set.singleton", 1, a -> ElmSet.empty(CMP).insert(a[0]));
    fn("Set.insert", 2, a -> asSet(a[1]).insert(a[0]));
    fn("Set.remove", 2, a -> asSet(a[1]).remove(a[0]));
    fn("Set.member", 2, a -> asSet(a[1]).member(a[0]));
    fn("Set.size", 1, a -> (long) asSet(a[0]).size());
    fn("Set.isEmpty", 1, a -> asSet(a[0]).size() == 0);
    fn("Set.toList", 1, a -> ElmList.fromJava(new ArrayList<>(asSet(a[0]).elements())));
    fn("Set.fromList", 1, a -> {
      ElmSet set = ElmSet.empty(CMP);
      for (Object x : ((ElmList) a[0]).toJava()) {
        set = set.insert(x);
      }
      return set;
    });
    fn("Set.union", 2, a -> {
      ElmSet out = asSet(a[1]);
      for (Object x : asSet(a[0]).elements()) {
        out = out.insert(x);
      }
      return out;
    });
    fn("Set.intersect", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      ElmSet other = asSet(a[1]);
      for (Object x : asSet(a[0]).elements()) {
        if (other.member(x)) {
          out = out.insert(x);
        }
      }
      return out;
    });
    fn("Set.diff", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      ElmSet other = asSet(a[1]);
      for (Object x : asSet(a[0]).elements()) {
        if (!other.member(x)) {
          out = out.insert(x);
        }
      }
      return out;
    });
    fn("Set.map", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      for (Object x : asSet(a[1]).elements()) {
        out = out.insert(Apply.apply(a[0], x));
      }
      return out;
    });
    fn("Set.filter", 2, a -> {
      ElmSet out = ElmSet.empty(CMP);
      for (Object x : asSet(a[1]).elements()) {
        if ((Boolean) Apply.apply(a[0], x)) {
          out = out.insert(x);
        }
      }
      return out;
    });
    fn("Set.partition", 2, a -> {
      ElmSet yes = ElmSet.empty(CMP);
      ElmSet no = ElmSet.empty(CMP);
      for (Object x : asSet(a[1]).elements()) {
        if ((Boolean) Apply.apply(a[0], x)) {
          yes = yes.insert(x);
        } else {
          no = no.insert(x);
        }
      }
      return new ElmTuple(new Object[] {yes, no});
    });
    fn("Set.foldl", 3, a -> {
      Object acc = a[1];
      for (Object x : asSet(a[2]).elements()) {
        acc = Apply.applyAll(a[0], x, acc);
      }
      return acc;
    });
    fn("Set.foldr", 3, a -> {
      Object acc = a[1];
      java.util.List<Object> elems = new java.util.ArrayList<>(asSet(a[2]).elements());
      for (int i = elems.size() - 1; i >= 0; i--) {
        acc = Apply.applyAll(a[0], elems.get(i), acc);
      }
      return acc;
    });
  }
}

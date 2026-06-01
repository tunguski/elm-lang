package pl.matsuo.elm.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import pl.matsuo.elm.interp.Apply;
import pl.matsuo.elm.runtime.ElmData;
import pl.matsuo.elm.runtime.ElmList;

/**
 * Interprets {@code Json.Decode} decoder values (the {@code $Dec_*} tags produced by the prelude)
 * against a parsed JSON tree from {@link JsonParse}. Returns an Elm {@code Result String a}.
 */
public final class DecoderRunner {

  private DecoderRunner() {}

  private static ElmData ok(Object v) {
    return new ElmData("Ok", new Object[] {v});
  }

  private static ElmData err(String message) {
    return new ElmData("Err", new Object[] {message});
  }

  /** Returns an Elm {@code Result String a}: {@code Ok value} or {@code Err message}. */
  public static ElmData run(Object decoder, Object json) {
    ElmData d = (ElmData) decoder;
    switch (d.ctor()) {
      case "$Dec_String" -> {
        return json instanceof String s ? ok(s) : err("expecting a STRING");
      }
      case "$Dec_Int" -> {
        if (json instanceof Long l) {
          return ok(l);
        }
        if (json instanceof Double dd && dd == Math.rint(dd)) {
          return ok((long) (double) dd);
        }
        return err("expecting an INT");
      }
      case "$Dec_Float" -> {
        if (json instanceof Long l) {
          return ok((double) l);
        }
        return json instanceof Double dd ? ok(dd) : err("expecting a FLOAT");
      }
      case "$Dec_Bool" -> {
        return json instanceof Boolean b ? ok(b) : err("expecting a BOOL");
      }
      case "$Dec_Field" -> {
        String name = (String) d.arg(0);
        if (!(json instanceof Map<?, ?> map) || !map.containsKey(name)) {
          return err("expecting an OBJECT with a field named '" + name + "'");
        }
        return run(d.arg(1), map.get(name));
      }
      case "$Dec_List" -> {
        if (!(json instanceof List<?> arr)) {
          return err("expecting a LIST");
        }
        List<Object> out = new ArrayList<>();
        for (Object item : arr) {
          // Json.Decode.list carries the element decoder as its single argument (arg 0).
          ElmData r = run(d.arg(0), item);
          if (r.ctor().equals("Err")) {
            return r;
          }
          out.add(r.arg(0));
        }
        return ok(ElmList.fromJava(out));
      }
      case "$Dec_Map" -> {
        ElmData r = run(d.arg(1), json);
        return r.ctor().equals("Err") ? r : ok(Apply.apply(d.arg(0), r.arg(0)));
      }
      case "$Dec_MapN" -> {
        Object fn = d.arg(0);
        Object acc = fn;
        for (int i = 1; i < d.args().length; i++) {
          ElmData r = run(d.arg(i), json);
          if (r.ctor().equals("Err")) {
            return r;
          }
          acc = Apply.apply(acc, r.arg(0));
        }
        return ok(acc);
      }
      case "$Dec_Succeed" -> {
        return ok(d.arg(0));
      }
      case "$Dec_AndThen" -> {
        ElmData r = run(d.arg(1), json);
        return r.ctor().equals("Err") ? r : run(Apply.apply(d.arg(0), r.arg(0)), json);
      }
      case "$Dec_Maybe" -> {
        ElmData r = run(d.arg(0), json);
        return r.ctor().equals("Err")
            ? ok(new ElmData("Nothing", new Object[0]))
            : ok(new ElmData("Just", new Object[] {r.arg(0)}));
      }
      case "$Dec_Nullable" -> {
        if (json == JsonParse.NULL) {
          return ok(new ElmData("Nothing", new Object[0]));
        }
        ElmData r = run(d.arg(0), json);
        return r.ctor().equals("Err") ? r : ok(new ElmData("Just", new Object[] {r.arg(0)}));
      }
      case "$Dec_Null" -> {
        // null : a -> Decoder a — succeeds with the given value iff the JSON is null.
        return json == JsonParse.NULL ? ok(d.arg(0)) : err("expecting null");
      }
      case "$Dec_Fail" -> {
        // fail : String -> Decoder a — always fails with the given message.
        return err((String) d.arg(0));
      }
      case "$Dec_Lazy" -> {
        // lazy : (() -> Decoder a) -> Decoder a — force the thunk, then decode.
        return run(Apply.apply(d.arg(0), pl.matsuo.elm.runtime.ElmUnit.INSTANCE), json);
      }
      case "$Dec_At" -> {
        // at : List String -> Decoder a -> Decoder a — drill through nested object fields.
        Object node = json;
        for (Object fieldObj : ((ElmList) d.arg(0)).toJava()) {
          String name = (String) fieldObj;
          if (!(node instanceof Map<?, ?> map) || !map.containsKey(name)) {
            return err("expecting an OBJECT with a field named '" + name + "'");
          }
          node = map.get(name);
        }
        return run(d.arg(1), node);
      }
      case "$Dec_Index" -> {
        // index : Int -> Decoder a -> Decoder a — decode the element at a list position.
        int i = (int) (long) (Long) d.arg(0);
        if (!(json instanceof List<?> arr)) {
          return err("expecting a LIST");
        }
        if (i < 0 || i >= arr.size()) {
          return err("expecting a LIST with index " + i);
        }
        return run(d.arg(1), arr.get(i));
      }
      case "$Dec_OneOf" -> {
        // oneOf : List (Decoder a) -> Decoder a — first decoder that succeeds wins.
        ElmData last = err("oneOf: no decoders");
        for (Object dec : ((ElmList) d.arg(0)).toJava()) {
          last = run(dec, json);
          if (!last.ctor().equals("Err")) {
            return last;
          }
        }
        return last;
      }
      case "$Dec_Value" -> {
        // value : Decoder Value — keep the raw JSON tree as an opaque Value ($Json wrapper).
        return ok(new ElmData("$Json", new Object[] {json}));
      }
      case "$Dec_Dict" -> {
        // dict : Decoder a -> Decoder (Dict String a)
        if (!(json instanceof Map<?, ?> map)) {
          return err("expecting an OBJECT");
        }
        pl.matsuo.elm.runtime.ElmDict out =
            pl.matsuo.elm.runtime.ElmDict.empty(pl.matsuo.elm.interp.Operators::compareValues);
        for (Map.Entry<?, ?> e : map.entrySet()) {
          ElmData r = run(d.arg(0), e.getValue());
          if (r.ctor().equals("Err")) {
            return r;
          }
          out = out.insert(e.getKey(), r.arg(0));
        }
        return ok(out);
      }
      case "$Dec_KeyValuePairs" -> {
        // keyValuePairs : Decoder a -> Decoder (List (String, a)) — preserves JSON object order.
        if (!(json instanceof Map<?, ?> map)) {
          return err("expecting an OBJECT");
        }
        List<Object> pairs = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
          ElmData r = run(d.arg(0), e.getValue());
          if (r.ctor().equals("Err")) {
            return r;
          }
          pairs.add(new pl.matsuo.elm.runtime.ElmTuple(new Object[] {e.getKey(), r.arg(0)}));
        }
        return ok(ElmList.fromJava(pairs));
      }
      default -> {
        return err("unsupported decoder " + d.ctor());
      }
    }
  }
}

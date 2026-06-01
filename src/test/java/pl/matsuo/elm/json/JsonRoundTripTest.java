package pl.matsuo.elm.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * Property-based round-trip testing for the JSON layer: a randomly generated value tree, serialized
 * by {@link JsonEncode} and re-parsed by {@link JsonParse}, must come back structurally unchanged
 * (compact and pretty-printed alike). Plus a few interpreter-level {@code Json.Encode} →
 * {@code Json.Decode.decodeString} round-trips that exercise the {@link DecoderRunner}.
 */
class JsonRoundTripTest {

  /** Generates a random JSON tree using JsonEncode's value model (objects/arrays/scalars/NULL). */
  private static final class Gen {
    final Random rng;

    Gen(long seed) {
      this.rng = new Random(seed);
    }

    Object tree(int depth) {
      if (depth <= 0 || rng.nextInt(100) < 35) {
        return leaf();
      }
      return rng.nextBoolean() ? array(depth) : object(depth);
    }

    private List<Object> array(int depth) {
      int n = rng.nextInt(5);
      List<Object> out = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        out.add(tree(depth - 1));
      }
      return out;
    }

    private Map<String, Object> object(int depth) {
      int n = rng.nextInt(5);
      Map<String, Object> out = new LinkedHashMap<>();
      for (int i = 0; i < n; i++) {
        out.put("k" + i + key(), tree(depth - 1));
      }
      return out;
    }

    private Object leaf() {
      return switch (rng.nextInt(6)) {
        case 0 -> (long) (rng.nextInt(2_000_000) - 1_000_000);
        case 1 -> fractionalDouble();
        case 2 -> str();
        case 3 -> Boolean.TRUE;
        case 4 -> Boolean.FALSE;
        default -> JsonEncode.NULL;
      };
    }

    /** A number leaf: a non-integral double stays a Double through the round-trip, while an integral
     * value is a Long (the serializer normalizes integral doubles to integers, which parse as Long).
     * Uses if/return — a ternary would promote `(long) d` back to double. */
    private Object fractionalDouble() {
      double d = (rng.nextInt(1_000_000) + 1) / 7.0;
      if (d == Math.floor(d)) {
        return (long) d;
      }
      return d;
    }

    /** A short string covering the characters the serializer must escape. */
    private String str() {
      String alphabet = "ab \"\\\n\t/{}[]:,";
      StringBuilder sb = new StringBuilder();
      int n = rng.nextInt(6);
      for (int i = 0; i < n; i++) {
        sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
      }
      return sb.toString();
    }

    private String key() {
      return rng.nextBoolean() ? "\"x" : "_y";
    }
  }

  @Test
  void serializeThenParseRoundTripsForRandomTrees() {
    Gen gen = new Gen(20260601L);
    for (int i = 0; i < 500; i++) {
      Object tree = gen.tree(4);
      // Compact form.
      Object back = JsonParse.parse(JsonEncode.serialize(tree, 0));
      assertTrue(jsonEquals(tree, back), () -> "compact round-trip differs in " + JsonEncode.serialize(tree, 0));
      // Pretty-printed form must parse to the same tree.
      Object backPretty = JsonParse.parse(JsonEncode.serialize(tree, 2));
      assertTrue(jsonEquals(tree, backPretty), "pretty round-trip differs: " + JsonEncode.serialize(tree, 2));
    }
  }

  @Test
  void serializeIsIdempotentThroughParse() {
    Gen gen = new Gen(7L);
    for (int i = 0; i < 200; i++) {
      String once = JsonEncode.serialize(gen.tree(4), 0);
      // parse → serialize → parse → serialize must reach a fixed point after the first normalization.
      String twice = JsonEncode.serialize(JsonParse.parse(once), 0);
      String thrice = JsonEncode.serialize(JsonParse.parse(twice), 0);
      assertEquals(twice, thrice, "serialization not idempotent for: " + once);
    }
  }

  /** Structural equality treating JsonParse.NULL and JsonEncode.NULL as the same null. */
  @SuppressWarnings("unchecked")
  private static boolean jsonEquals(Object a, Object b) {
    boolean aNull = a == JsonParse.NULL || a == JsonEncode.NULL || a == null;
    boolean bNull = b == JsonParse.NULL || b == JsonEncode.NULL || b == null;
    if (aNull || bNull) {
      return aNull && bNull;
    }
    if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
      if (!ma.keySet().equals(mb.keySet())) {
        return false;
      }
      for (Object k : ma.keySet()) {
        if (!jsonEquals(((Map<String, Object>) ma).get(k), ((Map<String, Object>) mb).get(k))) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof List<?> la && b instanceof List<?> lb) {
      if (la.size() != lb.size()) {
        return false;
      }
      for (int i = 0; i < la.size(); i++) {
        if (!jsonEquals(la.get(i), lb.get(i))) {
          return false;
        }
      }
      return true;
    }
    return a.equals(b);
  }

  // --- interpreter-level encode -> decodeString round-trips ----------------

  private static String decode(String module) {
    return Show.plain(Interpreter.load(module).value("result"));
  }

  @Test
  void encodeThenDecodeStringRoundTripsThroughTheInterpreter() {
    // Int, String, and a List of Ints, each encoded to JSON text and decoded straight back.
    assertEquals(
        "Ok 42",
        decode(
            "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n"
                + "result = D.decodeString D.int (E.encode 0 (E.int 42))\n"));
    assertEquals(
        "Ok \"hello\"",
        decode(
            "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n"
                + "result = D.decodeString D.string (E.encode 0 (E.string \"hello\"))\n"));
    assertEquals(
        "Ok [1,2,3]",
        decode(
            "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n"
                + "result = D.decodeString (D.list D.int) "
                + "(E.encode 0 (E.list E.int [ 1, 2, 3 ]))\n"));
  }

  @Test
  void encodeThenDecodeFieldRoundTripsThroughTheInterpreter() {
    // An object encoded then read back through a field decoder.
    assertEquals(
        "Ok 7",
        decode(
            "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n"
                + "json = E.encode 0 (E.object [ ( \"n\", E.int 7 ) ])\n"
                + "result = D.decodeString (D.field \"n\" D.int) json\n"));
  }

  /** A decoder over an object {@code {"a":1,"b":2}} for the dict/keyValuePairs tests. */
  private static String twoFieldObject() {
    return "json = E.encode 0 (E.object [ ( \"a\", E.int 1 ), ( \"b\", E.int 2 ) ])\n";
  }

  @Test
  void nullFailLazyDecoders() {
    String head = "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n";
    // null succeeds with the supplied value when the JSON is null.
    assertEquals("Ok 0", decode(head + "result = D.decodeString (D.null 0) (E.encode 0 E.null)\n"));
    // null fails when the JSON is not null.
    assertTrue(
        decode(head + "result = D.decodeString (D.null 0) (E.encode 0 (E.int 1))\n")
            .startsWith("Err"));
    // fail always fails; the structured error renders to the message via errorToString.
    assertEquals(
        "Err \"nope\"",
        decode(
            head
                + "result = Result.mapError D.errorToString"
                + " (D.decodeString (D.fail \"nope\") (E.encode 0 (E.int 1)))\n"));
    // lazy forces the thunk and then decodes.
    assertEquals(
        "Ok 5",
        decode(head + "result = D.decodeString (D.lazy (\\_ -> D.int)) (E.encode 0 (E.int 5))\n"));
  }

  @Test
  void errorToStringReportsTheFieldAndIndexPath() {
    String head = "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n";
    // A wrong type two levels deep: errorToString names the json path (json.user.tags[0]).
    String json =
        "json = E.encode 0 (E.object [ ( \"user\", E.object "
            + "[ ( \"tags\", E.list E.int [ 1 ] ) ] ) ])\n";
    String dec = "dec = D.at [ \"user\", \"tags\" ] (D.index 0 D.string)\n";
    String r =
        decode(head + json + dec
            + "result = Result.mapError D.errorToString (D.decodeString dec json)\n");
    assertTrue(r.startsWith("Err"), r);
    assertTrue(r.contains("json.user.tags[0]"), r); // the path is in the message
    assertTrue(r.contains("STRING"), r); // and the underlying failure
  }

  @Test
  void indexOneOfAndAtDecoders() {
    String head = "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n";
    // index reads a positional element of a JSON array.
    assertEquals(
        "Ok 20",
        decode(
            head
                + "result = D.decodeString (D.index 1 D.int) "
                + "(E.encode 0 (E.list E.int [ 10, 20, 30 ]))\n"));
    // oneOf takes the first decoder that succeeds.
    assertEquals(
        "Ok 99",
        decode(
            head
                + "result = D.decodeString (D.oneOf [ D.int, D.null 99 ]) (E.encode 0 E.null)\n"));
    // at drills through nested object fields.
    assertEquals(
        "Ok 7",
        decode(
            head
                + "json = E.encode 0 (E.object [ ( \"a\", E.object [ ( \"b\", E.int 7 ) ] ) ])\n"
                + "result = D.decodeString (D.at [ \"a\", \"b\" ] D.int) json\n"));
  }

  @Test
  void decodeValueDecodesAnAlreadyHeldValue() {
    String head = "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n";
    // decodeValue runs a decoder against a Value you already hold (here one built with Json.Encode),
    // without serializing to a string first.
    assertEquals(
        "Ok 42",
        decode(head + "result = D.decodeValue D.int (E.int 42)\n"));
    assertEquals(
        "Ok [1,2,3]",
        decode(head + "result = D.decodeValue (D.list D.int) (E.list E.int [ 1, 2, 3 ])\n"));
    // A type mismatch is an Err, just like decodeString.
    assertTrue(decode(head + "result = D.decodeValue D.string (E.int 1)\n").startsWith("Err"));
  }

  @Test
  void dictAndKeyValuePairsDecoders() {
    String head = "module M exposing (result)\nimport Json.Decode as D\nimport Json.Encode as E\n";
    // dict reads a JSON object into a Dict String a (ordered by key).
    assertEquals(
        "Ok Dict.fromList [(\"a\",1),(\"b\",2)]",
        decode(head + twoFieldObject() + "result = D.decodeString (D.dict D.int) json\n"));
    // keyValuePairs reads a JSON object into a List ( String, a ), preserving source order.
    assertEquals(
        "Ok [(\"a\",1),(\"b\",2)]",
        decode(head + twoFieldObject() + "result = D.decodeString (D.keyValuePairs D.int) json\n"));
  }
}

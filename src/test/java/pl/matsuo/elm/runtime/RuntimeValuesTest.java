package pl.matsuo.elm.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Value-semantics tests for the runtime value model (equals/hashCode/accessors/conversions). */
class RuntimeValuesTest {

  @Test
  void elmDataEqualityAndAccess() {
    ElmData just5 = new ElmData("Just", new Object[] {5L});
    assertEquals(just5, new ElmData("Just", new Object[] {5L}));
    assertNotEquals(just5, new ElmData("Just", new Object[] {6L}));
    assertNotEquals(just5, new ElmData("Nothing", new Object[0]));
    assertEquals(5L, just5.arg(0));
    assertEquals(just5.hashCode(), new ElmData("Just", new Object[] {5L}).hashCode());
    assertTrue(just5.toString().contains("Just"));
  }

  @Test
  void elmListConversionsAndCons() {
    ElmList xs = ElmList.fromJava(List.of(1L, 2L, 3L));
    assertEquals(List.of(1L, 2L, 3L), xs.toJava());
    assertEquals(List.of(0L, 1L, 2L, 3L), ElmList.cons(0L, xs).toJava());
    assertTrue(ElmList.NIL.isEmpty());
    assertFalse(xs.isEmpty());
    assertEquals(xs, ElmList.fromJava(List.of(1L, 2L, 3L)));
  }

  @Test
  void elmRecordUpdateAndLookup() {
    ElmRecord r = new ElmRecord(Map.of("x", 1L, "y", 2L));
    assertEquals(1L, r.get("x"));
    assertTrue(r.has("y"));
    assertFalse(r.has("z"));
    ElmRecord r2 = r.with("x", 9L);
    assertEquals(9L, r2.get("x"));
    assertEquals(1L, r.get("x")); // original unchanged (immutable)
    assertEquals(2L, r.withAll(Map.of("x", 5L)).get("y"));
    assertEquals(r, new ElmRecord(Map.of("y", 2L, "x", 1L))); // order-independent equality
  }

  @Test
  void elmTupleAccess() {
    ElmTuple t = new ElmTuple(new Object[] {1L, "a", true});
    assertEquals(3, t.size());
    assertEquals("a", t.get(1));
    assertEquals(t, new ElmTuple(new Object[] {1L, "a", true}));
    assertNotEquals(t, new ElmTuple(new Object[] {1L, "b", true}));
  }

  @Test
  void builtinInvokeAndArity() {
    Builtin inc = new Builtin("inc", 1, a -> ((Long) a[0]) + 1);
    assertEquals(1, inc.arity());
    assertEquals("inc", inc.name());
    assertEquals(6L, inc.invoke(new Object[] {5L}));
    assertTrue(inc.toString().contains("inc"));
  }
}

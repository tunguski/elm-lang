package pl.matsuo.elm.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pl.matsuo.elm.bytecode.BytecodeInterpreter;

class DictSetTest {

  private String show(String src) {
    return Show.plain(Interpreter.eval(src));
  }

  @Test
  void dictBasics() {
    assertEquals("Just \"b\"", show("Dict.get 2 (Dict.fromList [(1,\"a\"),(2,\"b\")])"));
    assertEquals("Nothing", show("Dict.get 9 Dict.empty"));
    assertEquals("2", show("Dict.size (Dict.fromList [(1,2),(3,4)])"));
    assertEquals("[(1,10)]", show("Dict.toList (Dict.insert 1 10 Dict.empty)"));
    assertEquals("[1,2,3]", show("Dict.keys (Dict.fromList [(3,0),(1,0),(2,0)])"));
    assertEquals("True", show("Dict.member 2 (Dict.fromList [(1,0),(2,0)])"));
  }

  @Test
  void dictMapFilterFold() {
    assertEquals("[2,4,6]", show("Dict.values (Dict.map (\\k v -> v * 2) (Dict.fromList [(1,1),(2,2),(3,3)]))"));
    assertEquals(
        "[(2,0),(4,0)]",
        show("Dict.toList (Dict.filter (\\k v -> modBy 2 k == 0) (Dict.fromList [(1,0),(2,0),(3,0),(4,0)]))"));
    assertEquals("6", show("Dict.foldl (\\k v acc -> acc + v) 0 (Dict.fromList [(1,1),(2,2),(3,3)])"));
  }

  @Test
  void dictEquality() {
    assertEquals("True", show("Dict.fromList [(1,2),(3,4)] == Dict.fromList [(3,4),(1,2)]"));
    assertEquals("False", show("Dict.fromList [(1,2)] == Dict.fromList [(1,3)]"));
  }

  @Test
  void setBasics() {
    assertEquals("[1,2,3]", show("Set.toList (Set.fromList [3,1,2,1,3])"));
    assertEquals("True", show("Set.member 2 (Set.fromList [1,2,3])"));
    assertEquals("2", show("Set.size (Set.fromList [1,1,2])"));
    assertEquals("[1,2,3,4]", show("Set.toList (Set.union (Set.fromList [1,2]) (Set.fromList [3,4]))"));
    assertEquals("[2]", show("Set.toList (Set.intersect (Set.fromList [1,2]) (Set.fromList [2,3]))"));
  }

  @Test
  void arrayBasics() {
    assertEquals("3", show("Array.length (Array.fromList [1,2,3])"));
    assertEquals("Just 20", show("Array.get 1 (Array.fromList [10,20,30])"));
    assertEquals("Nothing", show("Array.get 9 (Array.fromList [1])"));
    assertEquals("Array.fromList [1,2,99]", show("Array.set 2 99 (Array.fromList [1,2,3])"));
    assertEquals("Array.fromList [1,2,3]", show("Array.push 3 (Array.fromList [1,2])"));
    assertEquals("[1,4,9]", show("Array.toList (Array.map (\\x -> x * x) (Array.fromList [1,2,3]))"));
    assertEquals("6", show("Array.foldl (+) 0 (Array.fromList [1,2,3])"));
    assertEquals("Array.fromList [0,1,2,3]", show("Array.initialize 4 (\\i -> i)"));
  }

  @Test
  void crossBackendDict() {
    String src = "Set.toList (Set.fromList [5,3,5,1])";
    assertEquals(
        Show.plain(Interpreter.eval(src)), Show.plain(BytecodeInterpreter.eval(src)));
  }
}

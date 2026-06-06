package pl.matsuo.elm.codegen.wasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import pl.matsuo.elm.interp.Interpreter;
import pl.matsuo.elm.interp.Show;

/**
 * WASM-backend tests for language features that build/consume heap structures: custom types and
 * pattern matching, records, tail recursion, higher-order functions/closures, large-heap growth and
 * the property-based RNG agreement checks. Shares {@link WasmHeapTestSupport}; split out of
 * {@code WasmHeapTest} to keep both under the line budget.
 *
 * <p>{@code @Execution(CONCURRENT)}: each test compiles to a unique temp {@code .wasm} and runs it in
 * its own {@code node} subprocess, so methods run in parallel to overlap the subprocess waits.
 */
@Execution(ExecutionMode.CONCURRENT)
class WasmLangFeaturesTest extends WasmHeapTestSupport {

  @Test
  void matchesOnACustomTypeTag() throws Exception {
    agrees(
        """
        type Shape = Circle Int | Rect Int Int | Unit
        area s =
            case s of
                Circle r -> r * r * 3
                Rect w h -> w * h
                Unit -> 1
        main = area (Rect 3 4)
        """);
  }

  @Test
  void matchesOnIntegerLiterals() throws Exception {
    // A case over Int literals with a catch-all: an i64.eq if/else chain, no heap involved.
    String classify =
        """
        classify n =
            case n of
                0 -> 100
                1 -> 200
                2 -> 300
                _ -> n
        main = classify (%d)
        """;
    agrees(classify.formatted(0)); // 100
    agrees(classify.formatted(1)); // 200
    agrees(classify.formatted(2)); // 300
    agrees(classify.formatted(7)); // 7 (catch-all binds nothing)
    // A variable catch-all that binds and uses the scrutinee.
    agrees(
        """
        f n =
            case n of
                0 -> 0
                k -> k * k
        main = f 6
        """); // 36
  }

  @Test
  void destructuringParameters() throws Exception {
    // A tuple-destructuring function parameter.
    agrees("fst ( a, b ) = a\nmain = fst ( 3, 4 )\n"); // 3
    // A tuple-destructuring lambda parameter.
    agrees("main = (\\( a, b ) -> a + b) ( 5, 6 )\n"); // 11
    // A constructor-destructuring parameter.
    agrees(
        """
        type Box = Box Int
        unbox (Box n) = n
        main = unbox (Box 7)
        """); // 7
  }

  @Test
  void nestedAndStringCasePatterns() throws Exception {
    // A constructor argument that is itself a constructor pattern (nested, refutable).
    agrees(
        """
        type Pair = Pair Int Int
        type Wrap = Wrap Pair
        unwrap w =
            case w of
                Wrap (Pair a b) -> a + b
        main = unwrap (Wrap (Pair 3 4))
        """); // 7
    // A constructor argument that is a tuple pattern.
    agrees(
        """
        type Holder = Holder ( Int, Int )
        total h =
            case h of
                Holder ( a, b ) -> a + b
        main = total (Holder ( 5, 6 ))
        """); // 11
    // String-literal case patterns with a catch-all.
    agrees(
        """
        classify s =
            case s of
                "yes" -> 1
                "no" -> 2
                _ -> 0
        main = classify "no"
        """); // 2
    // Nested cons: bind the first two elements of a list.
    agrees(
        """
        sumFirstTwo xs =
            case xs of
                a :: b :: _ -> a + b
                _ -> 0
        main = sumFirstTwo [ 10, 20, 30 ]
        """); // 30
    // A tuple case with refutable contents over multiple branches.
    agrees(
        """
        describe pair =
            case pair of
                ( 0, y ) -> y
                ( x, 0 ) -> x
                ( x, y ) -> x + y
        main = describe ( 0, 9 )
        """); // 9
  }

  @Test
  void recursesOverACustomTypeTree() throws Exception {
    // A recursive ADT (binary tree) built and summed entirely in wasm.
    agrees(
        """
        type Tree = Leaf Int | Node Tree Tree
        total t =
            case t of
                Leaf n -> n
                Node l r -> total l + total r
        main = total (Node (Node (Leaf 1) (Leaf 2)) (Leaf 3))
        """);
  }

  @Test
  void buildsARecordAndReadsAFieldByName() throws Exception {
    // A record literal lays its fields out in name-sorted order; the annotated (closed) record type
    // lets the field accesses resolve to the matching offsets.
    agrees(
        """
        type alias Point = { x : Int, y : Int }
        normSq : Point -> Int
        normSq p = p.x * p.x + p.y * p.y
        main = normSq { x = 3, y = 4 }
        """);
  }

  @Test
  void recordFieldOrderDoesNotDependOnLiteralOrder() throws Exception {
    // The literal lists fields out of alphabetical order; sorted layout must still read correctly.
    agrees(
        """
        type alias R = { b : Int, a : Int }
        diff : R -> Int
        diff r = r.a - r.b
        main = diff { b = 10, a = 30 }
        """);
  }

  @Test
  void polymorphicRecordAccessAcrossShapes() throws Exception {
    // `get r = r.x` is row-polymorphic: it knows only the `x` field, yet works on records of
    // different shapes because access looks the field up by name at runtime.
    agrees(
        """
        getX r = r.x
        main = getX { x = 7, y = 99 } + getX { x = 3, name = 5, z = 2 }
        """);
  }

  @Test
  void polymorphicRecordUpdate() throws Exception {
    agrees(
        """
        getX r = r.x
        setX r v = { r | x = v }
        main = getX (setX { x = 1, y = 2 } 100)
        """);
  }

  @Test
  void partiallyAppliedConstructors() throws Exception {
    // A bare constructor passed as a first-class value, then applied through $apply.
    agrees("apply f x = f x\nmain = Maybe.withDefault 0 (apply Just 5)\n"); // 5
    // `List.map Just` maps a bare constructor over a list (the constructor is a closure value).
    agrees("main = List.sum (List.map (\\m -> Maybe.withDefault 0 m) (List.map Just [ 1, 2, 3 ]))\n"); // 6
    // A user constructor partially applied (one of two args), completed via a higher-order call.
    agrees(
        """
        type Pair = Pair Int Int
        fst p =
            case p of
                Pair a b -> a
        apply f x = f x
        main = fst (apply (Pair 3) 4)
        """); // 3
  }

  @Test
  void recordUpdateOnTopLevelValue() throws Exception {
    // The update's base is a top-level (zero-arg) record value, not a local — emitted by calling it.
    agrees(
        """
        origin = { x = 0, y = 7 }
        shifted = { origin | x = 5 }
        main = shifted.x + shifted.y
        """); // 5 + 7 = 12
  }

  @Test
  void updatesARecordImmutably() throws Exception {
    agrees(
        """
        type alias Counter = { count : Int, step : Int }
        bump : Counter -> Counter
        bump c = { c | count = c.count + c.step }
        get : Counter -> Int
        get c = c.count
        main = get (bump (bump { count = 0, step = 5 }))
        """);
  }

  @Test
  void tailRecursionRunsAtGreatDepth() throws Exception {
    // A direct tail call compiles to return_call, reusing the frame — so a million-deep loop runs
    // instead of overflowing the wasm call stack.
    agrees(
        """
        down n = if n == 0 then 0 else down (n - 1)
        main = down 1000000
        """);
    agrees(
        """
        sumTo n acc = if n == 0 then acc else sumTo (n - 1) (acc + n)
        main = sumTo 1000000 0
        """);
  }

  @Test
  void appliesAFunctionPassedAsAValue() throws Exception {
    // A top-level function used as a value (its table index) and invoked via call_indirect.
    agrees(
        """
        inc n = n + 1
        apply f x = f x
        main = apply inc 5
        """);
  }

  @Test
  void higherOrderTwiceComposesAFunctionValue() throws Exception {
    agrees(
        """
        double n = n * 2
        twice f x = f (f x)
        main = twice double 7
        """);
  }

  @Test
  void choosesBetweenFunctionValuesThenCallsIndirectly() throws Exception {
    // The function value is selected at runtime, exercising the indirect dispatch over the table.
    agrees(
        """
        inc n = n + 1
        dec n = n - 1
        pick b = if b == 1 then inc else dec
        run g x = g x
        main = run (pick 1) 41 + run (pick 0) 10
        """);
  }

  @Test
  void partialApplicationOfANamedFunction() throws Exception {
    // `add 1` is a partial application (a closure); applying it later completes the call.
    agrees(
        """
        add a b = a + b
        inc = add 1
        main = inc 41
        """);
  }

  @Test
  void closureCapturesALocal() throws Exception {
    // The returned lambda captures the parameter x.
    agrees(
        """
        adder x = \\y -> x + y
        main = (adder 10) 5
        """);
  }

  @Test
  void closureCapturedThroughALet() throws Exception {
    agrees(
        """
        makeAdder n = \\x -> n + x
        main =
            let
                add5 = makeAdder 5
            in
            add5 100
        """);
  }

  @Test
  void higherOrderWithALambdaArgument() throws Exception {
    agrees(
        """
        twiceApply f x = f (f x)
        main = twiceApply (\\n -> n + 1) 5
        """);
  }

  @Test
  void curriedHelperCapturesAndAccumulates() throws Exception {
    // applyTo holds an arg and applies a function to it; combined with a capturing lambda.
    agrees(
        """
        applyTo x f = f x
        main =
            let
                base = 100
                bump = \\n -> n + base
            in
            applyTo 7 bump
        """);
  }

  @Test
  void growsTheHeapAcrossManyPagesForALargeTree() throws Exception {
    // A depth-13 binary tree is ~16k cells (~6 pages, far past the initial 64 KiB), yet recursion
    // stays shallow (depth 13). It only runs without trapping if the allocator grows memory; the
    // total (2^13 = 8192 leaves) must still match the interpreter.
    agrees(
        """
        type Tree = Leaf Int | Node Tree Tree
        build d =
            if d == 0 then
                Leaf 1
            else
                Node (build (d - 1)) (build (d - 1))
        total t =
            case t of
                Leaf n -> n
                Node l r -> total l + total r
        main = total (build 13)
        """);
  }

  @Test
  void nullaryConstructorsAndDefaultBranch() throws Exception {
    agrees(
        """
        type Color = Red | Green | Blue
        code c =
            case c of
                Red -> 1
                _ -> 0
        main = code Blue
        """);
  }

  @Test
  void randomCustomTypeMatchesAgreeWithInterpreter() throws Exception {
    assumeTrue(NODE, "node not available");
    java.util.Random rng = new java.util.Random(20260530L);
    for (int trial = 0; trial < 20; trial++) {
      int a = rng.nextInt(50);
      int b = rng.nextInt(50);
      String shape =
          switch (trial % 3) {
            case 0 -> "Circle " + a;
            case 1 -> "Rect " + a + " " + b;
            default -> "Empty";
          };
      String source =
          """
          type Shape = Circle Int | Rect Int Int | Empty
          area s =
              case s of
                  Circle r -> r * r
                  Rect w h -> w * h
                  Empty -> 0
          main = area (%s)
          """
              .formatted(shape);
      String expected = Show.plain(Interpreter.load(source).value("main"));
      assertEquals(expected, runMain(source), source);
    }
  }

  @Test
  void randomListFoldsAgreeWithInterpreter() throws Exception {
    assumeTrue(NODE, "node not available");
    // Property: for random integer lists, a recursive sum/length/max in wasm equals the
    // interpreter's — exercising the bump allocator across many shapes and lengths.
    java.util.Random rng = new java.util.Random(20260530L);
    for (int trial = 0; trial < 25; trial++) {
      int n = rng.nextInt(12); // 0..11 elements
      StringBuilder list = new StringBuilder("[");
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          list.append(", ");
        }
        list.append(rng.nextInt(200) - 100); // -100..99
      }
      list.append("]");
      String fold =
          switch (trial % 3) {
            case 0 -> "sum";
            case 1 -> "len";
            default -> "maxOr0";
          };
      String source =
          """
          sum xs = case xs of
              [] -> 0
              h :: t -> h + sum t
          len xs = case xs of
              [] -> 0
              h :: t -> 1 + len t
          maxOr0 xs = case xs of
              [] -> 0
              h :: t -> let m = maxOr0 t in if h > m then h else m
          main = %s %s
          """
              .formatted(fold, list);
      String expected = Show.plain(Interpreter.load(source).value("main"));
      assertEquals(expected, runMain(source), source);
    }
  }
}

module Test exposing (Test, test, describe, concat, fuzz, fuzzWith, fuzz2, fuzz3, only, skip, todo)

{-| A tiny test framework (a subset of elm-explorations/test). Build tests with `test` and group
them with `describe`/`concat`, then expose them as a top-level `Test` value; the `elm test` runner
discovers every top-level `Test` value across the given files, runs each, and reports the results.

    suite : Test
    suite =
        describe "math"
            [ test "adds" (\_ -> Expect.equal 4 (2 + 2))
            , test "multiplies" (\_ -> Expect.equal 6 (2 * 3))
            , fuzz Fuzz.int "negate self-inverts" (\n -> Expect.equal n (negate (negate n)))
            ]
-}

import Expect exposing (Expectation)
import Fuzz exposing (Fuzzer)


{-| A test, or a labelled group of tests. Opaque: build it with `test`/`describe`/`concat`/`fuzz`.
A `FuzzTest` carries a seed-driven thunk the runner replays over many random inputs. -}
type Test
    = UnitTest String (() -> Expectation)
    | Labeled String (List Test)
    | FuzzTest String (Int -> Expectation)
    | FuzzWith String Int (Int -> Expectation)
    | Only Test
    | Skip Test
    | Todo String


{-| A single test: a description and a thunk producing an `Expectation`. -}
test : String -> (() -> Expectation) -> Test
test =
    UnitTest


{-| Groups tests under a description. -}
describe : String -> List Test -> Test
describe =
    Labeled


{-| Combines several tests into one (an unlabelled group). -}
concat : List Test -> Test
concat tests =
    Labeled "" tests


{-| Focus the run on this test (and any other `only`-marked tests): when any `only` is present, the
runner runs only the focused tests and skips the rest. Handy for zeroing in on one failure. -}
only : Test -> Test
only =
    Only


{-| Skip this test (and everything inside it): the runner reports it as skipped and never runs it. -}
skip : Test -> Test
skip =
    Skip


{-| A placeholder for a test not yet written: the runner reports it as a failure ("TODO: …") so it
stays visible (red) until implemented. -}
todo : String -> Test
todo =
    Todo


{-| A property test: the runner draws many random inputs from the `Fuzzer` and fails on the first
one that breaks the expectation. On failure the input is *shrunk* to a minimal counterexample (the
fuzzer's `shrink` candidates are followed greedily for as long as they keep failing), and that
minimal value is what gets reported — far more useful than a random 9-digit integer. -}
fuzz : Fuzzer a -> String -> (a -> Expectation) -> Test
fuzz fuzzer description body =
    FuzzTest description (runProperty fuzzer body)


{-| Like {@link fuzz} but with options — currently the number of random inputs (`runs`) to draw for
this test, overriding the runner's default. -}
fuzzWith : { runs : Int } -> Fuzzer a -> String -> (a -> Expectation) -> Test
fuzzWith options fuzzer description body =
    FuzzWith description options.runs (runProperty fuzzer body)


{-| The seed-driven property the runner replays: pass, or shrink the first failing input. -}
runProperty : Fuzzer a -> (a -> Expectation) -> Int -> Expectation
runProperty fuzzer body seed =
    let
        value =
            fuzzer.gen seed
    in
    case Expect.toFailure (body value) of
        Nothing ->
            Expect.pass

        Just _ ->
            let
                minimal =
                    shrink fuzzer.shrink body value
            in
            Expect.onFail ("Given " ++ Debug.toString minimal ++ "\n\n") (body minimal)


{-| A property test over two independent fuzzed inputs (shrunk together as a pair). -}
fuzz2 : Fuzzer a -> Fuzzer b -> String -> (a -> b -> Expectation) -> Test
fuzz2 fa fb description body =
    fuzz (Fuzz.pair fa fb) description (\( a, b ) -> body a b)


{-| A property test over three independent fuzzed inputs (shrunk together as a triple). -}
fuzz3 : Fuzzer a -> Fuzzer b -> Fuzzer c -> String -> (a -> b -> c -> Expectation) -> Test
fuzz3 fa fb fc description body =
    fuzz (triple fa fb fc) description (\( a, b, c ) -> body a b c)


{-| A 3-tuple fuzzer (each component fed a decorrelated seed), shrinking one component at a time. -}
triple : Fuzzer a -> Fuzzer b -> Fuzzer c -> Fuzzer ( a, b, c )
triple fa fb fc =
    { gen = \seed -> ( fa.gen seed, fb.gen (seed * 7919 + 1), fc.gen (seed * 104729 + 3) )
    , shrink =
        \( a, b, c ) ->
            List.map (\a2 -> ( a2, b, c )) (fa.shrink a)
                ++ List.map (\b2 -> ( a, b2, c )) (fb.shrink b)
                ++ List.map (\c2 -> ( a, b, c2 )) (fc.shrink c)
    }


{-| Greedily reduce a failing input to a simpler one that still fails, following the fuzzer's shrink
candidates. Bounded so a pathological shrinker can't loop forever. -}
shrink : (a -> List a) -> (a -> Expectation) -> a -> a
shrink shrinker body value =
    shrinkHelp shrinker body value 2000


shrinkHelp : (a -> List a) -> (a -> Expectation) -> a -> Int -> a
shrinkHelp shrinker body value budget =
    if budget <= 0 then
        value

    else
        case firstFailing body (shrinker value) of
            Nothing ->
                value

            Just smaller ->
                shrinkHelp shrinker body smaller (budget - 1)


{-| The first candidate that still fails the test, if any. -}
firstFailing : (a -> Expectation) -> List a -> Maybe a
firstFailing body candidates =
    case candidates of
        [] ->
            Nothing

        c :: rest ->
            case Expect.toFailure (body c) of
                Just _ ->
                    Just c

                Nothing ->
                    firstFailing body rest

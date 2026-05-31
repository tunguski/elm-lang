module Test exposing (Test, test, describe, concat, fuzz)

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


{-| A property test: the runner draws many random inputs from the `Fuzzer` and fails on the first
one that breaks the expectation, reporting the offending value. -}
fuzz : Fuzzer a -> String -> (a -> Expectation) -> Test
fuzz fuzzer description body =
    FuzzTest description
        (\seed ->
            let
                value =
                    fuzzer seed
            in
            Expect.onFail ("Given " ++ Debug.toString value ++ "\n\n") (body value)
        )

module Test exposing (Test, test, describe, concat)

{-| A tiny test framework (a subset of elm-explorations/test). Build tests with `test` and group
them with `describe`/`concat`, then expose them as a top-level `Test` value; the `elm test` runner
discovers every top-level `Test` value across the given files, runs each, and reports the results.

    suite : Test
    suite =
        describe "math"
            [ test "adds" (\_ -> Expect.equal 4 (2 + 2))
            , test "multiplies" (\_ -> Expect.equal 6 (2 * 3))
            ]
-}

import Expect exposing (Expectation)


{-| A test, or a labelled group of tests. Opaque: build it with `test`/`describe`/`concat`. -}
type Test
    = UnitTest String (() -> Expectation)
    | Labeled String (List Test)


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

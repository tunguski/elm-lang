module Tests exposing (suite)

{-| An example test suite, run with `elm test example-test`. -}

import Expect
import Fuzz
import Test exposing (Test, describe, fuzz, test)


suite : Test
suite =
    describe "example"
        [ describe "arithmetic"
            [ test "addition" (\_ -> Expect.equal 4 (2 + 2))
            , test "multiplication" (\_ -> Expect.equal 6 (2 * 3))
            , test "precedence" (\_ -> Expect.equal 14 (2 + 3 * 4))
            ]
        , describe "lists"
            [ test "length" (\_ -> Expect.equal 3 (List.length [ 1, 2, 3 ]))
            , test "map" (\_ -> Expect.equal [ 2, 4, 6 ] (List.map (\x -> x * 2) [ 1, 2, 3 ]))
            , test "is non-empty" (\_ -> Expect.isFalse (List.isEmpty [ 1 ]))
            ]
        , test "comparison" (\_ -> Expect.lessThan 10 (3 + 4))
        , describe "properties"
            [ fuzz Fuzz.int "double negation is identity" (\n -> Expect.equal n (negate (negate n)))
            , fuzz (Fuzz.intRange 0 100) "abs is non-negative" (\n -> Expect.atLeast 0 (abs n))
            ]
        ]

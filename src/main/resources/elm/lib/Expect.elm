module Expect exposing
    ( Expectation
    , pass
    , fail
    , equal
    , notEqual
    , lessThan
    , greaterThan
    , atMost
    , atLeast
    , isTrue
    , isFalse
    )

{-| Expectations for the bundled test framework (a small subset of elm-explorations/test's `Expect`).
An `Expectation` is produced inside a test body and reported by the `elm test` runner.
-}


{-| The outcome of a test: a pass, or a failure with a message. Opaque. -}
type Expectation
    = Pass
    | Fail String


{-| Always passes. -}
pass : Expectation
pass =
    Pass


{-| Always fails with the given message. -}
fail : String -> Expectation
fail message =
    Fail message


{-| Passes if the two values are equal. -}
equal : a -> a -> Expectation
equal expected actual =
    if expected == actual then
        Pass

    else
        Fail ("expected " ++ Debug.toString expected ++ "\n     but got " ++ Debug.toString actual)


{-| Passes if the two values are not equal. -}
notEqual : a -> a -> Expectation
notEqual a b =
    if a /= b then
        Pass

    else
        Fail ("expected something other than " ++ Debug.toString a)


{-| Passes if the second argument is less than the first. -}
lessThan : comparable -> comparable -> Expectation
lessThan ceiling actual =
    if actual < ceiling then
        Pass

    else
        Fail (Debug.toString actual ++ " is not less than " ++ Debug.toString ceiling)


{-| Passes if the second argument is greater than the first. -}
greaterThan : comparable -> comparable -> Expectation
greaterThan floor actual =
    if actual > floor then
        Pass

    else
        Fail (Debug.toString actual ++ " is not greater than " ++ Debug.toString floor)


{-| Passes if the second argument is at most (≤) the first. -}
atMost : comparable -> comparable -> Expectation
atMost ceiling actual =
    if actual <= ceiling then
        Pass

    else
        Fail (Debug.toString actual ++ " is greater than " ++ Debug.toString ceiling)


{-| Passes if the second argument is at least (≥) the first. -}
atLeast : comparable -> comparable -> Expectation
atLeast floor actual =
    if actual >= floor then
        Pass

    else
        Fail (Debug.toString actual ++ " is less than " ++ Debug.toString floor)


{-| Passes if the argument is True. -}
isTrue : Bool -> Expectation
isTrue actual =
    if actual then
        Pass

    else
        Fail "expected True"


{-| Passes if the argument is False. -}
isFalse : Bool -> Expectation
isFalse actual =
    if actual then
        Fail "expected False"

    else
        Pass

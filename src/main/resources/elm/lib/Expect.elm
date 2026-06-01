module Expect exposing
    ( Expectation
    , pass
    , fail
    , equal
    , equalLists
    , notEqual
    , lessThan
    , greaterThan
    , atMost
    , atLeast
    , isTrue
    , isFalse
    , within
    , all
    , ok
    , err
    , onFail
    , toFailure
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


{-| Passes if two lists are equal, with a message that calls out a length difference first (clearer
than a plain `equal` on long lists). -}
equalLists : List a -> List a -> Expectation
equalLists expected actual =
    if expected == actual then
        Pass

    else if List.length expected /= List.length actual then
        Fail
            ("list lengths differ: expected "
                ++ String.fromInt (List.length expected)
                ++ " elements but got "
                ++ String.fromInt (List.length actual)
            )

    else
        Fail ("lists differ:\n  expected " ++ Debug.toString expected ++ "\n  but got  " ++ Debug.toString actual)


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


{-| Passes if two Floats are within an absolute `tolerance` of each other (for comparing results of
floating-point arithmetic, which is rarely exactly equal). -}
within : Float -> Float -> Float -> Expectation
within tolerance expected actual =
    if abs (expected - actual) <= tolerance then
        Pass

    else
        Fail (Debug.toString actual ++ " is not within " ++ Debug.toString tolerance ++ " of " ++ Debug.toString expected)


{-| Runs several checks against the same subject, failing with the first that fails. Handy for
asserting many properties of one value: `Expect.all [ Expect.lessThan 10, Expect.greaterThan 0 ] n`. -}
all : List (subject -> Expectation) -> subject -> Expectation
all checks subject =
    List.foldl
        (\check acc ->
            case acc of
                Pass ->
                    check subject

                Fail _ ->
                    acc
        )
        Pass
        checks


{-| Passes if the `Result` is `Ok`. -}
ok : Result e a -> Expectation
ok result =
    case result of
        Ok _ ->
            Pass

        Err e ->
            Fail ("expected Ok but got Err " ++ Debug.toString e)


{-| Passes if the `Result` is `Err`. -}
err : Result e a -> Expectation
err result =
    case result of
        Err _ ->
            Pass

        Ok a ->
            Fail ("expected Err but got Ok " ++ Debug.toString a)


{-| The failure message of an expectation, or `Nothing` if it passed. Used by `Test.fuzz` to drive
shrinking (it needs to know whether a candidate input still fails). -}
toFailure : Expectation -> Maybe String
toFailure expectation =
    case expectation of
        Pass ->
            Nothing

        Fail message ->
            Just message


{-| Prepends context to an expectation's failure message (used by `Test.fuzz` to report the input);
a passing expectation is returned unchanged. -}
onFail : String -> Expectation -> Expectation
onFail context expectation =
    case expectation of
        Pass ->
            Pass

        Fail message ->
            Fail (context ++ message)

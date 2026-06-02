module Fuzz exposing
    ( Fuzzer
    , int
    , intRange
    , bool
    , weightedBool
    , float
    , floatRange
    , percentage
    , char
    , asciiChar
    , string
    , asciiString
    , constant
    , map
    , map2
    , map3
    , map4
    , map5
    , andThen
    , pair
    , triple
    , list
    , listOfLength
    , listOfLengthBetween
    , sequence
    , array
    , maybe
    , result
    , oneOf
    , frequency
    , filter
    )

{-| Random value generators for property-based (`fuzz`) tests — a small subset of
elm-explorations/test's `Fuzz`. A `Fuzzer a` both generates values from a seed and knows how to
*shrink* a value toward simpler ones; the `elm test` runner feeds it many seeds, and on the first
input that breaks an expectation it shrinks that input to a minimal counterexample before reporting
it. Compose with `map`, `pair` and `list`.

    Test.fuzz Fuzz.int "negate is its own inverse" <|
        \n -> Expect.equal n (negate (negate n))

-}


import Array exposing (Array)


{-| A source of pseudo-random values of type `a`. `gen` turns a seed into a value; `shrink` returns
a handful of strictly-simpler values to try when a generated value fails a test (each closer to a
"smallest" value — 0 for numbers, `""`/`[]` for strings/lists). Build with the combinators below. -}
type alias Fuzzer a =
    { gen : Int -> a
    , shrink : a -> List a
    }


{-| A fast deterministic scramble of a seed into a non-negative Int, so nearby seeds give unrelated
values (a linear-congruential step). -}
hash : Int -> Int
hash s =
    abs (modBy 2147483647 (s * 1103515245 + 12345))


{-| Any Int (spread across negative and positive); shrinks toward 0. -}
int : Fuzzer Int
int =
    { gen = \seed -> hash seed - 1073741823
    , shrink = shrinkInt
    }


shrinkInt : Int -> List Int
shrinkInt n =
    if n == 0 then
        []

    else
        -- Candidates strictly closer to 0 (smallest first, so shrinking favours the simplest).
        List.filter (\c -> abs c < abs n) [ 0, n // 2, n - sign n ]


sign : Int -> Int
sign n =
    if n > 0 then
        1

    else if n < 0 then
        -1

    else
        0


{-| An Int in the inclusive range `lo..hi`; shrinks toward `lo`. -}
intRange : Int -> Int -> Fuzzer Int
intRange lo hi =
    { gen =
        \seed ->
            if hi <= lo then
                lo

            else
                lo + modBy (hi - lo + 1) (hash seed)
    , shrink = \n -> List.filter (\c -> c >= lo && c < n) [ lo, (lo + n) // 2, n - 1 ]
    }


{-| True or False; shrinks `True` toward `False`. -}
bool : Fuzzer Bool
bool =
    { gen = \seed -> modBy 2 (hash seed) == 0
    , shrink =
        \b ->
            if b then
                [ False ]

            else
                []
    }


{-| A Float in roughly `-1e6 .. 1e6` (three decimal places); shrinks toward 0 (and toward integers). -}
float : Fuzzer Float
float =
    { gen = \seed -> toFloat (modBy 2000000001 (hash seed) - 1000000000) / 1000.0
    , shrink = \n -> List.filter (\c -> abs c < abs n) [ 0.0, toFloat (truncate n) ]
    }


{-| A Float in the inclusive range `lo..hi`; shrinks toward `lo`. -}
floatRange : Float -> Float -> Fuzzer Float
floatRange lo hi =
    { gen =
        \seed ->
            if hi <= lo then
                lo

            else
                lo + (toFloat (modBy 1000001 (hash seed)) / 1000000.0) * (hi - lo)
    , shrink = \n -> List.filter (\c -> c >= lo && c < n) [ lo, (lo + n) / 2 ]
    }


{-| True with probability `p` (clamped to `0..1`); shrinks `True` toward `False`. -}
weightedBool : Float -> Fuzzer Bool
weightedBool p =
    { gen = \seed -> toFloat (modBy 1000001 (hash seed)) / 1000000.0 < p
    , shrink =
        \b ->
            if b then
                [ False ]

            else
                []
    }


{-| A Float in `0.0 .. 1.0`; shrinks toward 0. -}
percentage : Fuzzer Float
percentage =
    { gen = \seed -> toFloat (modBy 1000001 (hash seed)) / 1000000.0
    , shrink = \n -> List.filter (\c -> c < n) [ 0.0 ]
    }


{-| A lowercase ASCII letter; shrinks toward `'a'`. -}
char : Fuzzer Char
char =
    { gen = \seed -> Char.fromCode (97 + modBy 26 (hash seed))
    , shrink =
        \c ->
            if c == 'a' then
                []

            else
                [ 'a' ]
    }


{-| Any printable ASCII character (codes 32..126); shrinks toward `'a'`. -}
asciiChar : Fuzzer Char
asciiChar =
    { gen = \seed -> Char.fromCode (32 + modBy 95 (hash seed))
    , shrink =
        \c ->
            if c == 'a' then
                []

            else
                [ 'a' ]
    }


{-| A short lowercase string (0..11 letters); shrinks toward `""` (dropping characters). -}
string : Fuzzer String
string =
    { gen =
        \seed ->
            String.fromList
                (List.map (\i -> Char.fromCode (97 + modBy 26 (hash (seed + i))))
                    (List.range 1 (modBy 12 (hash seed)))
                )
    , shrink = shrinkString
    }


{-| A short string of printable ASCII (0..11 characters); shrinks toward `""`. -}
asciiString : Fuzzer String
asciiString =
    { gen =
        \seed ->
            String.fromList
                (List.map (\i -> Char.fromCode (32 + modBy 95 (hash (seed + i))))
                    (List.range 1 (modBy 12 (hash seed)))
                )
    , shrink = shrinkString
    }


shrinkString : String -> List String
shrinkString s =
    if String.isEmpty s then
        []

    else
        List.filter (\c -> String.length c < String.length s)
            [ "", String.dropLeft 1 s, String.dropRight 1 s, String.left (String.length s // 2) s ]


{-| Always the given value (does not shrink). -}
constant : a -> Fuzzer a
constant a =
    { gen = \_ -> a
    , shrink = \_ -> []
    }


{-| Transform every generated value. The mapped fuzzer does not shrink (the function need not be
invertible), so prefer shrinking the source fuzzer where it matters. -}
map : (a -> b) -> Fuzzer a -> Fuzzer b
map f fuzzer =
    { gen = \seed -> f (fuzzer.gen seed)
    , shrink = \_ -> []
    }


{-| Combine two fuzzers with a function (each fed a decorrelated seed). Does not shrink. -}
map2 : (a -> b -> c) -> Fuzzer a -> Fuzzer b -> Fuzzer c
map2 f fa fb =
    { gen = \seed -> f (fa.gen seed) (fb.gen (hash (seed + 7919)))
    , shrink = \_ -> []
    }


{-| Combine three fuzzers with a function (each fed a decorrelated seed). Does not shrink. -}
map3 : (a -> b -> c -> d) -> Fuzzer a -> Fuzzer b -> Fuzzer c -> Fuzzer d
map3 f fa fb fc =
    { gen = \seed -> f (fa.gen seed) (fb.gen (hash (seed + 7919))) (fc.gen (hash (seed + 104729)))
    , shrink = \_ -> []
    }


{-| Combine four fuzzers with a function (each fed a decorrelated seed). Does not shrink. -}
map4 : (a -> b -> c -> d -> e) -> Fuzzer a -> Fuzzer b -> Fuzzer c -> Fuzzer d -> Fuzzer e
map4 f fa fb fc fd =
    { gen =
        \seed ->
            f (fa.gen seed)
                (fb.gen (hash (seed + 7919)))
                (fc.gen (hash (seed + 104729)))
                (fd.gen (hash (seed + 1299709)))
    , shrink = \_ -> []
    }


{-| Combine five fuzzers with a function (each fed a decorrelated seed). Does not shrink. -}
map5 : (a -> b -> c -> d -> e -> g) -> Fuzzer a -> Fuzzer b -> Fuzzer c -> Fuzzer d -> Fuzzer e -> Fuzzer g
map5 f fa fb fc fd fe =
    { gen =
        \seed ->
            f (fa.gen seed)
                (fb.gen (hash (seed + 7919)))
                (fc.gen (hash (seed + 104729)))
                (fd.gen (hash (seed + 1299709)))
                (fe.gen (hash (seed + 15485863)))
    , shrink = \_ -> []
    }


{-| Generates a value, then chooses the next fuzzer from it (a dependent fuzzer). The result does not
shrink (the continuation may pick unrelated fuzzers), so prefer `map`/`pair` when you can. -}
andThen : (a -> Fuzzer b) -> Fuzzer a -> Fuzzer b
andThen f fuzzer =
    { gen = \seed -> (f (fuzzer.gen seed)).gen (hash (seed + 7919))
    , shrink = \_ -> []
    }


{-| `Nothing` about a quarter of the time, otherwise `Just` a value from the inner fuzzer; shrinks a
`Just` toward `Nothing` and toward the inner shrinks. -}
maybe : Fuzzer a -> Fuzzer (Maybe a)
maybe fuzzer =
    { gen =
        \seed ->
            if modBy 4 (hash seed) == 0 then
                Nothing

            else
                Just (fuzzer.gen (hash (seed + 1)))
    , shrink =
        \m ->
            case m of
                Nothing ->
                    []

                Just x ->
                    Nothing :: List.map Just (fuzzer.shrink x)
    }


{-| `Ok` or `Err` with equal chance, from the respective fuzzers; shrinks within the chosen side. -}
result : Fuzzer e -> Fuzzer a -> Fuzzer (Result e a)
result errFuzzer okFuzzer =
    { gen =
        \seed ->
            if modBy 2 (hash seed) == 0 then
                Err (errFuzzer.gen (hash (seed + 1)))

            else
                Ok (okFuzzer.gen (hash (seed + 2)))
    , shrink =
        \r ->
            case r of
                Ok a ->
                    List.map Ok (okFuzzer.shrink a)

                Err e ->
                    List.map Err (errFuzzer.shrink e)
    }


{-| Picks uniformly from a (non-empty) list of fuzzers. Shrinks by trying every alternative's
shrinker on the value (they all produce values of the same type). -}
oneOf : List (Fuzzer a) -> Fuzzer a
oneOf fuzzers =
    case fuzzers of
        [] ->
            Debug.todo "Fuzz.oneOf: needs a non-empty list of fuzzers"

        first :: _ ->
            { gen =
                \seed ->
                    (pick (modBy (List.length fuzzers) (hash seed)) fuzzers first).gen (hash (seed + 104729))
            , shrink = \v -> List.concatMap (\f -> f.shrink v) fuzzers
            }


{-| Picks from a (non-empty) list of `(weight, fuzzer)` pairs in proportion to the weights. -}
frequency : List ( Int, Fuzzer a ) -> Fuzzer a
frequency pairs =
    case pairs of
        [] ->
            Debug.todo "Fuzz.frequency: needs a non-empty list"

        ( _, first ) :: _ ->
            { gen =
                \seed ->
                    (byWeight (modBy (max 1 (List.sum (List.map Tuple.first pairs))) (hash seed)) pairs first).gen
                        (hash (seed + 104729))
            , shrink = \v -> List.concatMap (\( _, f ) -> f.shrink v) pairs
            }


{-| Keeps only generated values satisfying the predicate (re-trying with fresh seeds, bounded);
shrinks are filtered too. The predicate should accept a reasonable fraction of values. -}
filter : (a -> Bool) -> Fuzzer a -> Fuzzer a
filter pred fuzzer =
    { gen = \seed -> filterGen pred fuzzer seed 0
    , shrink = \v -> List.filter pred (fuzzer.shrink v)
    }


pick : Int -> List x -> x -> x
pick i xs default =
    Maybe.withDefault default (List.head (List.drop i xs))


byWeight : Int -> List ( Int, Fuzzer a ) -> Fuzzer a -> Fuzzer a
byWeight n pairs default =
    case pairs of
        [] ->
            default

        ( w, f ) :: rest ->
            if n < w then
                f

            else
                byWeight (n - w) rest default


filterGen : (a -> Bool) -> Fuzzer a -> Int -> Int -> a
filterGen pred fuzzer seed attempts =
    let
        v =
            fuzzer.gen seed
    in
    if pred v || attempts >= 20 then
        v

    else
        filterGen pred fuzzer (hash (seed + 1)) (attempts + 1)


{-| A pair drawn from two fuzzers (each fed a decorrelated seed); shrinks each component in turn. -}
pair : Fuzzer a -> Fuzzer b -> Fuzzer ( a, b )
pair fa fb =
    { gen = \seed -> ( fa.gen seed, fb.gen (hash (seed + 7919)) )
    , shrink =
        \( a, b ) ->
            List.map (\a2 -> ( a2, b )) (fa.shrink a)
                ++ List.map (\b2 -> ( a, b2 )) (fb.shrink b)
    }


{-| A triple drawn from three fuzzers (each fed a decorrelated seed); shrinks one component at a time. -}
triple : Fuzzer a -> Fuzzer b -> Fuzzer c -> Fuzzer ( a, b, c )
triple fa fb fc =
    { gen = \seed -> ( fa.gen seed, fb.gen (hash (seed + 7919)), fc.gen (hash (seed + 104729)) )
    , shrink =
        \( a, b, c ) ->
            List.map (\a2 -> ( a2, b, c )) (fa.shrink a)
                ++ List.map (\b2 -> ( a, b2, c )) (fb.shrink b)
                ++ List.map (\c2 -> ( a, b, c2 )) (fc.shrink c)
    }


{-| A list of 0..9 values from the element fuzzer; shrinks by dropping elements and by shrinking
each element. -}
list : Fuzzer a -> Fuzzer (List a)
list fuzzer =
    { gen =
        \seed ->
            List.map (\i -> fuzzer.gen (hash (seed + i * 31)))
                (List.range 1 (modBy 10 (hash seed)))
    , shrink =
        \xs ->
            case xs of
                [] ->
                    []

                _ ->
                    ([] :: removeEach xs) ++ shrinkEach fuzzer.shrink xs
    }


{-| A list of exactly `n` values from the element fuzzer; shrinks each element (length is fixed). -}
listOfLength : Int -> Fuzzer a -> Fuzzer (List a)
listOfLength n fuzzer =
    { gen = \seed -> List.map (\i -> fuzzer.gen (hash (seed + i * 31))) (List.range 1 (max 0 n))
    , shrink = \xs -> shrinkEach fuzzer.shrink xs
    }


{-| A list whose length is in `lo..hi`, from the element fuzzer; shrinks by dropping elements (never
below `lo`) and by shrinking each element. -}
listOfLengthBetween : Int -> Int -> Fuzzer a -> Fuzzer (List a)
listOfLengthBetween lo hi fuzzer =
    let
        low =
            max 0 lo
    in
    { gen =
        \seed ->
            let
                n =
                    if hi <= low then
                        low

                    else
                        low + modBy (hi - low + 1) (hash seed)
            in
            List.map (\i -> fuzzer.gen (hash (seed + i * 31))) (List.range 1 n)
    , shrink =
        \xs ->
            (if List.length xs > low then
                removeEach xs

             else
                []
            )
                ++ shrinkEach fuzzer.shrink xs
    }


{-| Runs each fuzzer in the list (each fed a decorrelated seed) and collects the results in order.
Does not shrink. -}
sequence : List (Fuzzer a) -> Fuzzer (List a)
sequence fuzzers =
    { gen = \seed -> List.indexedMap (\i f -> f.gen (hash (seed + i * 7919))) fuzzers
    , shrink = \_ -> []
    }


{-| An `Array` of 0..9 values from the element fuzzer (shrinks like the underlying list). -}
array : Fuzzer a -> Fuzzer (Array a)
array fuzzer =
    let
        elems =
            list fuzzer
    in
    { gen = \seed -> Array.fromList (elems.gen seed)
    , shrink = \arr -> List.map Array.fromList (elems.shrink (Array.toList arr))
    }


{-| Every way to drop one element. -}
removeEach : List a -> List (List a)
removeEach xs =
    List.indexedMap (\i _ -> dropIndex i xs) xs


dropIndex : Int -> List a -> List a
dropIndex i xs =
    List.take i xs ++ List.drop (i + 1) xs


{-| Every list obtained by replacing one element with one of its shrinks. -}
shrinkEach : (a -> List a) -> List a -> List (List a)
shrinkEach sh xs =
    List.concat
        (List.indexedMap (\i x -> List.map (\x2 -> setIndex i x2 xs) (sh x)) xs)


setIndex : Int -> a -> List a -> List a
setIndex i v xs =
    List.indexedMap
        (\j x ->
            if i == j then
                v

            else
                x
        )
        xs

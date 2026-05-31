module Fuzz exposing
    ( Fuzzer
    , int
    , intRange
    , bool
    , float
    , percentage
    , char
    , string
    , constant
    , map
    , pair
    , list
    )

{-| Random value generators for property-based (`fuzz`) tests — a small subset of
elm-explorations/test's `Fuzz`. A `Fuzzer a` is a deterministic function from a seed `Int` to a
value; the `elm test` runner feeds it many seeds and fails the test on the first input that breaks
an expectation (reporting the offending value). Compose with `map`, `pair` and `list`.

    Test.fuzz Fuzz.int "negate is its own inverse" <|
        \n -> Expect.equal n (negate (negate n))
-}


{-| A source of pseudo-random values of type `a`, driven by a seed. Opaque in spirit; build with the
combinators below rather than relying on its representation. -}
type alias Fuzzer a =
    Int -> a


{-| A fast deterministic scramble of a seed into a non-negative Int, so nearby seeds give unrelated
values (a linear-congruential step). -}
hash : Int -> Int
hash s =
    abs (modBy 2147483647 (s * 1103515245 + 12345))


{-| Any Int (spread across negative and positive). -}
int : Fuzzer Int
int seed =
    hash seed - 1073741823


{-| An Int in the inclusive range `lo..hi`. -}
intRange : Int -> Int -> Fuzzer Int
intRange lo hi seed =
    if hi <= lo then
        lo

    else
        lo + modBy (hi - lo + 1) (hash seed)


{-| True or False. -}
bool : Fuzzer Bool
bool seed =
    modBy 2 (hash seed) == 0


{-| A Float in roughly `-1e6 .. 1e6` (three decimal places). -}
float : Fuzzer Float
float seed =
    toFloat (intRange -1000000000 1000000000 seed) / 1000.0


{-| A Float in `0.0 .. 1.0`. -}
percentage : Fuzzer Float
percentage seed =
    toFloat (modBy 1000001 (hash seed)) / 1000000.0


{-| A lowercase ASCII letter. -}
char : Fuzzer Char
char seed =
    Char.fromCode (97 + modBy 26 (hash seed))


{-| A short lowercase string (0..11 letters). -}
string : Fuzzer String
string seed =
    String.fromList (List.map (\i -> char (hash (seed + i))) (List.range 1 (modBy 12 (hash seed))))


{-| Always the given value. -}
constant : a -> Fuzzer a
constant a _ =
    a


{-| Transform every generated value. -}
map : (a -> b) -> Fuzzer a -> Fuzzer b
map f fuzzer seed =
    f (fuzzer seed)


{-| A pair drawn from two fuzzers (each fed a decorrelated seed). -}
pair : Fuzzer a -> Fuzzer b -> Fuzzer ( a, b )
pair fa fb seed =
    ( fa seed, fb (hash (seed + 7919)) )


{-| A list of 0..9 values from the element fuzzer. -}
list : Fuzzer a -> Fuzzer (List a)
list fuzzer seed =
    List.map (\i -> fuzzer (hash (seed + i * 31))) (List.range 1 (modBy 10 (hash seed)))

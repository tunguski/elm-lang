module Bytes.Decode exposing
    ( Decoder
    , decode
    , unsignedInt8
    , unsignedInt16
    , unsignedInt32
    , signedInt8
    , signedInt16
    , signedInt32
    , bytes
    , string
    , succeed
    , fail
    , map
    , map2
    , map3
    , map4
    , map5
    , andThen
    )

{-| Read values back out of `Bytes`. A `Decoder a` consumes some leading bytes and yields an `a`;
combine decoders with `map`/`map2`/`andThen` and run one with `decode`. See [`Bytes`](../Bytes.elm).

    import Bytes exposing (Endianness(..))
    import Bytes.Decode as D

    pair : D.Decoder ( Int, Int )
    pair =
        D.map2 Tuple.pair (D.unsignedInt16 BE) (D.unsignedInt8)

    D.decode pair someBytes

-}

import Bytes exposing (Bytes, Endianness(..))


{-| A decoder of an `a`: given the remaining bytes, maybe produce a value and the bytes left. -}
type Decoder a
    = Decoder (List Int -> Maybe ( a, List Int ))


{-| Runs a decoder over `Bytes`, returning `Nothing` if it runs off the end. -}
decode : Decoder a -> Bytes -> Maybe a
decode (Decoder f) input =
    Maybe.map Tuple.first (f (Bytes.toByteValues input))


{-| Reads one unsigned byte. -}
unsignedInt8 : Decoder Int
unsignedInt8 =
    Decoder
        (\bs ->
            case bs of
                b :: rest ->
                    Just ( b, rest )

                [] ->
                    Nothing
        )


{-| Reads an unsigned 16-bit integer in the given byte order. -}
unsignedInt16 : Endianness -> Decoder Int
unsignedInt16 endianness =
    Decoder
        (\bs ->
            case bs of
                b0 :: b1 :: rest ->
                    Just ( combine endianness [ b0, b1 ], rest )

                _ ->
                    Nothing
        )


{-| Reads an unsigned 32-bit integer in the given byte order. -}
unsignedInt32 : Endianness -> Decoder Int
unsignedInt32 endianness =
    Decoder
        (\bs ->
            case bs of
                b0 :: b1 :: b2 :: b3 :: rest ->
                    Just ( combine endianness [ b0, b1, b2, b3 ], rest )

                _ ->
                    Nothing
        )


{-| Reads one signed byte (two's complement, `-128..127`). -}
signedInt8 : Decoder Int
signedInt8 =
    map (signed 8) unsignedInt8


{-| Reads a signed 16-bit integer in the given byte order. -}
signedInt16 : Endianness -> Decoder Int
signedInt16 endianness =
    map (signed 16) (unsignedInt16 endianness)


{-| Reads a signed 32-bit integer in the given byte order. -}
signedInt32 : Endianness -> Decoder Int
signedInt32 endianness =
    map (signed 32) (unsignedInt32 endianness)


{-| Reads the next `n` bytes as a `Bytes` value. -}
bytes : Int -> Decoder Bytes
bytes n =
    Decoder
        (\bs ->
            if List.length bs >= n && n >= 0 then
                Just ( Bytes.fromByteValues (List.take n bs), List.drop n bs )

            else
                Nothing
        )


{-| Reads `n` bytes as a string (each byte a code point — Latin-1/ASCII). -}
string : Int -> Decoder String
string n =
    Decoder
        (\bs ->
            if List.length bs >= n && n >= 0 then
                Just ( String.fromList (List.map Char.fromCode (List.take n bs)), List.drop n bs )

            else
                Nothing
        )


{-| Runs three decoders in sequence and combines their results. -}
map3 : (a -> b -> c -> d) -> Decoder a -> Decoder b -> Decoder c -> Decoder d
map3 f da db dc =
    andThen (\a -> map2 (f a) db dc) da


map4 : (a -> b -> c -> d -> e) -> Decoder a -> Decoder b -> Decoder c -> Decoder d -> Decoder e
map4 f da db dc dd =
    andThen (\a -> map3 (f a) db dc dd) da


map5 : (a -> b -> c -> d -> e -> g) -> Decoder a -> Decoder b -> Decoder c -> Decoder d -> Decoder e -> Decoder g
map5 f da db dc dd de =
    andThen (\a -> map4 (f a) db dc dd de) da


{-| A decoder that consumes nothing and always yields `a`. -}
succeed : a -> Decoder a
succeed a =
    Decoder (\bs -> Just ( a, bs ))


{-| A decoder that always fails. -}
fail : Decoder a
fail =
    Decoder (\_ -> Nothing)


{-| Transforms a decoded value. -}
map : (a -> b) -> Decoder a -> Decoder b
map f (Decoder g) =
    Decoder (\bs -> Maybe.map (\( a, rest ) -> ( f a, rest )) (g bs))


{-| Runs two decoders in sequence and combines their results. -}
map2 : (a -> b -> c) -> Decoder a -> Decoder b -> Decoder c
map2 f da db =
    andThen (\a -> map (f a) db) da


{-| Chooses the next decoder based on the value decoded so far. -}
andThen : (a -> Decoder b) -> Decoder a -> Decoder b
andThen f (Decoder g) =
    Decoder
        (\bs ->
            case g bs of
                Just ( a, rest ) ->
                    let
                        (Decoder h) =
                            f a
                    in
                    h rest

                Nothing ->
                    Nothing
        )



-- INTERNAL


{-| Reinterprets an unsigned `width`-bit integer as two's-complement signed. -}
signed : Int -> Int -> Int
signed width n =
    if n >= 2 ^ (width - 1) then
        n - 2 ^ width

    else
        n


{-| Combines bytes (listed in read order) into an integer, honouring endianness. -}
combine : Endianness -> List Int -> Int
combine endianness readOrder =
    let
        bigEndian =
            case endianness of
                BE ->
                    readOrder

                LE ->
                    List.reverse readOrder
    in
    List.foldl (\b acc -> acc * 256 + b) 0 bigEndian

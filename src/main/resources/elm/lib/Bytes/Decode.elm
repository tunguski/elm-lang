module Bytes.Decode exposing
    ( Decoder
    , decode
    , unsignedInt8
    , unsignedInt16
    , unsignedInt32
    , succeed
    , fail
    , map
    , map2
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

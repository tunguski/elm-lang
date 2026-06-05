module Bytes.Encode exposing
    ( Encoder
    , encode
    , unsignedInt8
    , unsignedInt16
    , unsignedInt32
    , signedInt8
    , signedInt16
    , signedInt32
    , bytes
    , sequence
    )

{-| Build up `Bytes` from fixed-width integers and other byte runs, then turn the `Encoder` into
`Bytes` with `encode`. See [`Bytes`](../Bytes.elm) for the overall picture.

    import Bytes exposing (Endianness(..))
    import Bytes.Encode as E

    E.encode (E.sequence [ E.unsignedInt8 1, E.unsignedInt16 BE 515 ])  -- bytes 01 02 03

-}

import Bytes exposing (Bytes, Endianness(..))


{-| A description of some bytes to produce. -}
type Encoder
    = Encoder (List Int)


{-| Runs an encoder, producing the `Bytes` it describes. -}
encode : Encoder -> Bytes
encode (Encoder values) =
    Bytes.fromByteValues values


{-| One unsigned byte (the low 8 bits of `n`). -}
unsignedInt8 : Int -> Encoder
unsignedInt8 n =
    Encoder [ modBy 256 n ]


{-| One signed byte; negative values wrap into `0..255` (two's complement). -}
signedInt8 : Int -> Encoder
signedInt8 n =
    Encoder [ modBy 256 n ]


{-| Two bytes for an unsigned 16-bit integer, in the given byte order. -}
unsignedInt16 : Endianness -> Int -> Encoder
unsignedInt16 endianness n =
    Encoder (order endianness [ byteAt 1 n, byteAt 0 n ])


{-| Four bytes for an unsigned 32-bit integer, in the given byte order. -}
unsignedInt32 : Endianness -> Int -> Encoder
unsignedInt32 endianness n =
    Encoder (order endianness [ byteAt 3 n, byteAt 2 n, byteAt 1 n, byteAt 0 n ])


{-| Two bytes for a signed 16-bit integer (negatives wrap two's-complement into `0..65535`). -}
signedInt16 : Endianness -> Int -> Encoder
signedInt16 endianness n =
    unsignedInt16 endianness (modBy 65536 n)


{-| Four bytes for a signed 32-bit integer (negatives wrap two's-complement into `0..2^32-1`). -}
signedInt32 : Endianness -> Int -> Encoder
signedInt32 endianness n =
    unsignedInt32 endianness (modBy 4294967296 n)


{-| Re-encodes an existing `Bytes` value. -}
bytes : Bytes -> Encoder
bytes b =
    Encoder (Bytes.toByteValues b)


{-| Concatenates encoders left to right. -}
sequence : List Encoder -> Encoder
sequence encoders =
    Encoder (List.concatMap (\(Encoder vs) -> vs) encoders)



-- INTERNAL


{-| The byte at position `i` (0 = least significant) of `n`. -}
byteAt : Int -> Int -> Int
byteAt i n =
    modBy 256 (n // (256 ^ i))


{-| `bytes` are listed most-significant first; reverse them for little-endian. -}
order : Endianness -> List Int -> List Int
order endianness mostSignificantFirst =
    case endianness of
        BE ->
            mostSignificantFirst

        LE ->
            List.reverse mostSignificantFirst

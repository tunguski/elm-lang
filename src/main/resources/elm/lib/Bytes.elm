module Bytes exposing (Bytes, Endianness(..), width, empty, fromByteValues, toByteValues)

{-| A compact `Bytes` type — a sequence of byte values (each `0..255`) — for binary data, with the
companion [`Bytes.Encode`](Bytes/Encode.elm) and [`Bytes.Decode`](Bytes/Decode.elm) modules. Unlike
elm/bytes this is plain Elm (a list of bytes under the hood), so it works on every backend without a
kernel; the API mirrors the parts you reach for most: fixed-width unsigned ints with an
`Endianness`, sequencing and the usual decoder combinators.

    import Bytes exposing (Endianness(..))
    import Bytes.Encode as E
    import Bytes.Decode as D

    -- encode two unsigned 16-bit big-endian ints, then read the first back
    bytes = E.encode (E.sequence [ E.unsignedInt16 BE 258, E.unsignedInt16 BE 1 ])
    Bytes.width bytes            --> 4
    D.decode (D.unsignedInt16 BE) bytes  --> Just 258

-}


{-| A run of bytes. Opaque; build with `Bytes.Encode.encode` or {@link fromByteValues}. -}
type Bytes
    = Bytes (List Int)


{-| Byte order for multi-byte integers: little-endian or big-endian. -}
type Endianness
    = LE
    | BE


{-| The number of bytes. -}
width : Bytes -> Int
width (Bytes bs) =
    List.length bs


{-| Zero bytes. -}
empty : Bytes
empty =
    Bytes []


{-| Builds `Bytes` from raw byte values, clamping each into `0..255` (the bridge the encoder uses). -}
fromByteValues : List Int -> Bytes
fromByteValues values =
    Bytes (List.map (\n -> modBy 256 n) values)


{-| The raw byte values, in order (the bridge the decoder reads). -}
toByteValues : Bytes -> List Int
toByteValues (Bytes bs) =
    bs

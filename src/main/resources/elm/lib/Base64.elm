module Base64 exposing (encode, decode)

{-| Standard Base64 (RFC 4648) over byte lists — pair with `Bytes.toByteValues` /
`Bytes.fromByteValues` to Base64 arbitrary binary data.

    Base64.encode [ 77, 97, 110 ]   --> "TWFu"

    Base64.decode "TWFu"            --> Ok [ 77, 97, 110 ]

Each byte is an `Int` in 0..255. `encode` pads with `=` so the output length is a multiple of four;
`decode` accepts optional padding and reports the first invalid character.
-}


alphabet : String
alphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"


{-| Encode a list of bytes (each 0..255) as a padded Base64 string. -}
encode : List Int -> String
encode bytes =
    String.concat (encodeGroups bytes)


encodeGroups : List Int -> List String
encodeGroups bytes =
    case bytes of
        b0 :: b1 :: b2 :: rest ->
            quad b0 b1 b2 4 :: encodeGroups rest

        [ b0, b1 ] ->
            [ quad b0 b1 0 3 ]

        [ b0 ] ->
            [ quad b0 0 0 2 ]

        _ ->
            []


{-| One 4-character Base64 group for three bytes, keeping `keep` characters and padding the rest. -}
quad : Int -> Int -> Int -> Int -> String
quad b0 b1 b2 keep =
    let
        n =
            b0 * 65536 + b1 * 256 + b2

        chars =
            [ sixBit (n // 262144)
            , sixBit (modBy 64 (n // 4096))
            , sixBit (modBy 64 (n // 64))
            , sixBit (modBy 64 n)
            ]
    in
    String.fromList (List.take keep chars) ++ String.repeat (4 - keep) "="


sixBit : Int -> Char
sixBit i =
    Maybe.withDefault 'A' (List.head (List.drop i (String.toList alphabet)))


{-| Decode a Base64 string (padding optional) back into a list of bytes. -}
decode : String -> Result String (List Int)
decode s =
    decodeGroups (String.toList (String.filter (\c -> c /= '=' && c /= '\n' && c /= '\u{000D}') s))


decodeGroups : List Char -> Result String (List Int)
decodeGroups chars =
    case chars of
        a :: b :: c :: d :: rest ->
            Result.map2 (++) (bytesOf a b c d 3) (decodeGroups rest)

        [ a, b, c ] ->
            bytesOf a b c 'A' 2

        [ a, b ] ->
            bytesOf a b 'A' 'A' 1

        [] ->
            Ok []

        _ ->
            Err "Base64.decode: invalid length"


{-| The `take` leading bytes packed from four Base64 characters. -}
bytesOf : Char -> Char -> Char -> Char -> Int -> Result String (List Int)
bytesOf a b c d take =
    Result.map4
        (\va vb vc vd ->
            let
                n =
                    va * 262144 + vb * 4096 + vc * 64 + vd
            in
            List.take take [ n // 65536, modBy 256 (n // 256), modBy 256 n ]
        )
        (charValue a)
        (charValue b)
        (charValue c)
        (charValue d)


charValue : Char -> Result String Int
charValue c =
    let
        code =
            Char.toCode c
    in
    if c >= 'A' && c <= 'Z' then
        Ok (code - 65)

    else if c >= 'a' && c <= 'z' then
        Ok (code - 97 + 26)

    else if c >= '0' && c <= '9' then
        Ok (code - 48 + 52)

    else if c == '+' then
        Ok 62

    else if c == '/' then
        Ok 63

    else
        Err ("Base64.decode: invalid character '" ++ String.fromChar c ++ "'")

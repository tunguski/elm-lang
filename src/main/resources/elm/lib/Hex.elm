module Hex exposing (toString, fromString)

{-| Convert between `Int`s and lowercase hexadecimal strings — a small port of
rtfeldman/elm-hex.

    Hex.toString 255       --> "ff"

    Hex.fromString "ff"    --> Ok 255

-}


{-| The lowercase hexadecimal representation of an `Int` (a leading `-` for negatives). -}
toString : Int -> String
toString n =
    if n < 0 then
        "-" ++ toString (negate n)

    else if n < 16 then
        String.fromChar (hexDigit n)

    else
        toString (n // 16) ++ String.fromChar (hexDigit (modBy 16 n))


hexDigit : Int -> Char
hexDigit d =
    if d < 10 then
        Char.fromCode (Char.toCode '0' + d)

    else
        Char.fromCode (Char.toCode 'a' + d - 10)


{-| Parse a hexadecimal string (optionally `-`-prefixed, case-insensitive) into an `Int`, or report
the offending character. -}
fromString : String -> Result String Int
fromString s =
    case String.toList s of
        '-' :: rest ->
            fromDigits rest |> Result.map negate

        chars ->
            fromDigits chars


fromDigits : List Char -> Result String Int
fromDigits chars =
    case chars of
        [] ->
            Err "Hex.fromString: no digits"

        _ ->
            List.foldl step (Ok 0) chars


step : Char -> Result String Int -> Result String Int
step c acc =
    acc |> Result.andThen (\n -> Result.map (\d -> n * 16 + d) (digitValue c))


digitValue : Char -> Result String Int
digitValue c =
    let
        code =
            Char.toCode (Char.toLower c)
    in
    if code >= 48 && code <= 57 then
        Ok (code - 48)

    else if code >= 97 && code <= 102 then
        Ok (code - 87)

    else
        Err ("Hex.fromString: invalid digit '" ++ String.fromChar c ++ "'")

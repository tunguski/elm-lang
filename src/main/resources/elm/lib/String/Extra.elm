module String.Extra exposing
    ( toSentenceCase
    , decapitalize
    , toTitleCase
    , isBlank
    , clean
    , countOccurrences
    , surround
    , unsurround
    , ellipsis
    , nonEmpty
    , nonBlank
    , insertAt
    )

{-| A subset of the popular `elm-community/string-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend.

    import String.Extra as SE

    SE.toSentenceCase "hello"        --> "Hello"
    SE.clean "  a   b  c "           --> "a b c"
    SE.ellipsis 8 "a long string"    --> "a lon..."

-}


{-| Uppercases the first character. -}
toSentenceCase : String -> String
toSentenceCase s =
    case String.uncons s of
        Just ( c, rest ) ->
            String.cons (Char.toUpper c) rest

        Nothing ->
            s


{-| Lowercases the first character. -}
decapitalize : String -> String
decapitalize s =
    case String.uncons s of
        Just ( c, rest ) ->
            String.cons (Char.toLower c) rest

        Nothing ->
            s


{-| Uppercases the first character of each whitespace-separated word. -}
toTitleCase : String -> String
toTitleCase s =
    String.join " " (List.map toSentenceCase (String.words s))


{-| Whether the string is empty or only whitespace. -}
isBlank : String -> Bool
isBlank s =
    String.trim s == ""


{-| Collapses runs of whitespace to single spaces and trims the ends. -}
clean : String -> String
clean s =
    String.join " " (String.words s)


{-| How many (non-overlapping) times `needle` occurs in the string (0 if `needle` is empty). -}
countOccurrences : String -> String -> Int
countOccurrences needle haystack =
    if needle == "" then
        0

    else
        List.length (String.split needle haystack) - 1


{-| Wraps the string in `wrapper` on both sides. -}
surround : String -> String -> String
surround wrapper s =
    wrapper ++ s ++ wrapper


{-| Removes `wrapper` from both ends, if present on both. -}
unsurround : String -> String -> String
unsurround wrapper s =
    if String.startsWith wrapper s && String.endsWith wrapper s && String.length s >= 2 * String.length wrapper then
        String.dropLeft (String.length wrapper) (String.dropRight (String.length wrapper) s)

    else
        s


{-| Truncates to at most `howLong` characters, appending `...` when shortened. -}
ellipsis : Int -> String -> String
ellipsis howLong s =
    if String.length s <= howLong then
        s

    else
        String.left (Basics.max 0 (howLong - 3)) s ++ "..."


{-| `Nothing` for the empty string, otherwise `Just` the string. -}
nonEmpty : String -> Maybe String
nonEmpty s =
    if s == "" then
        Nothing

    else
        Just s


{-| `Nothing` for a blank (empty/whitespace) string, otherwise `Just` the string. -}
nonBlank : String -> Maybe String
nonBlank s =
    if isBlank s then
        Nothing

    else
        Just s


{-| Inserts `sub` at index `i` (clamped to the string's bounds). -}
insertAt : String -> Int -> String -> String
insertAt sub i s =
    String.left i s ++ sub ++ String.dropLeft i s

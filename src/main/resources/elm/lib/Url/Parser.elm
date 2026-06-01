module Url.Parser exposing (Parser, parse, s, int, string, map, oneOf, top, slash, (</>))

{-| A small typed URL router — a subset of elm/url's `Url.Parser`. Match fixed path segments with
`s`, capture an `Int` or `String` segment, and combine with `</>`; `parse` runs a parser against a
record with a `path` field (a `Url`), returning `Just value` on a full match or `Nothing`.

    type Route = Home | User Int | Post String

    route : Parser (Route -> a) a
    route =
        oneOf
            [ map Home top
            , map User (s "user" </> int)
            , map Post (s "post" </> string)
            ]

    parse route someUrl  -- Just (User 42) for "/user/42"

-}


{-| A parser that consumes path segments, turning a value of type `a` into one of type `b`. -}
type Parser a b
    = Parser (State a -> List (State b))


type alias State value =
    { visited : List String
    , unvisited : List String
    , value : value
    }


{-| Sequence two parsers, matching the first then the second. `</>` is the same. -}
slash : Parser a b -> Parser b c -> Parser a c
slash (Parser before) (Parser after) =
    Parser (\state -> List.concatMap after (before state))


infix right 7 (</>) = slash


{-| Match a fixed path segment, e.g. `s "users"`. -}
s : String -> Parser a a
s segment =
    Parser
        (\state ->
            case state.unvisited of
                next :: rest ->
                    if next == segment then
                        [ { visited = next :: state.visited, unvisited = rest, value = state.value } ]

                    else
                        []

                [] ->
                    []
        )


custom : (String -> Maybe a) -> Parser (a -> b) b
custom toValue =
    Parser
        (\state ->
            case state.unvisited of
                next :: rest ->
                    case toValue next of
                        Just v ->
                            [ { visited = next :: state.visited, unvisited = rest, value = state.value v } ]

                        Nothing ->
                            []

                [] ->
                    []
        )


{-| Capture a path segment as an `Int`. -}
int : Parser (Int -> a) a
int =
    custom String.toInt


{-| Capture a path segment as a `String`. -}
string : Parser (String -> a) a
string =
    custom Just


{-| Transform a parser's captured values with a function (often a route constructor). -}
map : a -> Parser a b -> Parser (b -> c) c
map subValue (Parser parseArg) =
    Parser
        (\state ->
            List.map
                (\inner -> { visited = inner.visited, unvisited = inner.unvisited, value = state.value inner.value })
                (parseArg { visited = state.visited, unvisited = state.unvisited, value = subValue })
        )


{-| Try several parsers, taking the first that matches. -}
oneOf : List (Parser a b) -> Parser a b
oneOf parsers =
    Parser (\state -> List.concatMap (\(Parser p) -> p state) parsers)


{-| Match the end of the path (the root route). -}
top : Parser a a
top =
    Parser (\state -> [ state ])


{-| Run a parser against a `Url` (anything with a `path` field). -}
parse (Parser parser) url =
    firstMatch (parser { visited = [], unvisited = prepare url.path, value = identity })


firstMatch : List (State a) -> Maybe a
firstMatch states =
    case states of
        [] ->
            Nothing

        state :: rest ->
            case state.unvisited of
                [] ->
                    Just state.value

                only :: [] ->
                    if only == "" then
                        Just state.value

                    else
                        firstMatch rest

                _ ->
                    firstMatch rest


prepare : String -> List String
prepare path =
    case String.split "/" path of
        "" :: segments ->
            dropTrailingEmpty segments

        segments ->
            dropTrailingEmpty segments


dropTrailingEmpty : List String -> List String
dropTrailingEmpty segments =
    case segments of
        [] ->
            []

        only :: [] ->
            if only == "" then
                []

            else
                [ only ]

        seg :: rest ->
            seg :: dropTrailingEmpty rest

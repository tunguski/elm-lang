module Url.Parser exposing (Parser, parse, s, int, string, map, oneOf, top, slash, fragment, questionMark, (</>), (<?>))

{-| A small typed URL router — a subset of elm/url's `Url.Parser`. Match fixed path segments with
`s`, capture an `Int`/`String` segment, read query parameters with `<?>` (see `Url.Parser.Query`) and
the URL `fragment`, and combine with `</>`; `parse` runs a parser against a record with `path`,
`query` and `fragment` fields (a `Url`).

    type Route = Home | User Int | Search (Maybe String)

    route : Parser (Route -> a) a
    route =
        oneOf
            [ map Home top
            , map User (s "user" </> int)
            , map Search (s "search" <?> Query.string "q")
            ]

    parse route someUrl  -- Just (Search (Just "elm")) for "/search?q=elm"

-}


{-| A parser that consumes path segments (and query/fragment), turning an `a` into a `b`. -}
type Parser a b
    = Parser (State a -> List (State b))


type alias State value =
    { visited : List String
    , unvisited : List String
    , query : List ( String, String )
    , frag : Maybe String
    , value : value
    }


{-| Sequence two parsers, matching the first then the second. `</>` is the same. -}
slash : Parser a b -> Parser b c -> Parser a c
slash (Parser before) (Parser after) =
    Parser (\state -> List.concatMap after (before state))


infix right 7 (</>) = slash


{-| Attach a query parser (from `Url.Parser.Query`) to a path parser. `<?>` is the same. -}
questionMark : Parser a (query -> b) -> (List ( String, String ) -> query) -> Parser a b
questionMark (Parser before) queryParser =
    Parser (\state -> List.map (\s2 -> { s2 | value = s2.value (queryParser s2.query) }) (before state))


infix left 8 (<?>) = questionMark


{-| Match a fixed path segment, e.g. `s "users"`. -}
s : String -> Parser a a
s segment =
    Parser
        (\state ->
            case state.unvisited of
                next :: rest ->
                    if next == segment then
                        [ { state | visited = next :: state.visited, unvisited = rest } ]

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
                            [ { state | visited = next :: state.visited, unvisited = rest, value = state.value v } ]

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


{-| Capture the URL fragment (the part after `#`), as `Maybe String`. -}
fragment : (Maybe String -> frag) -> Parser (frag -> a) a
fragment toFrag =
    Parser (\state -> [ { state | value = state.value (toFrag state.frag) } ])


{-| Transform a parser's captured values with a function (often a route constructor). -}
map : a -> Parser a b -> Parser (b -> c) c
map subValue (Parser parseArg) =
    Parser
        (\state ->
            List.map
                (\inner -> { inner | value = state.value inner.value })
                (parseArg { state | value = subValue })
        )


{-| Try several parsers, taking the first that matches. -}
oneOf : List (Parser a b) -> Parser a b
oneOf parsers =
    Parser (\state -> List.concatMap (\(Parser p) -> p state) parsers)


{-| Match the end of the path (the root route). -}
top : Parser a a
top =
    Parser (\state -> [ state ])


{-| Run a parser against a `Url` (a record with `path`, `query` and `fragment`). -}
parse (Parser parser) url =
    firstMatch
        (parser
            { visited = []
            , unvisited = prepare url.path
            , query = parseQuery url.query
            , frag = url.fragment
            , value = identity
            }
        )


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


{-| Parse a `Maybe` query string ("a=1&b=2") into key/value pairs. -}
parseQuery : Maybe String -> List ( String, String )
parseQuery maybeQuery =
    case maybeQuery of
        Nothing ->
            []

        Just "" ->
            []

        Just q ->
            List.filterMap pair (String.split "&" q)


pair : String -> Maybe ( String, String )
pair part =
    case String.split "=" part of
        key :: value :: [] ->
            Just ( key, value )

        _ ->
            Nothing

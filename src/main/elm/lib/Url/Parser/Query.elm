module Url.Parser.Query exposing (Parser, string, int, enum, map, map2)

{-| Query-string parsers for `Url.Parser`'s `<?>` — read parameters out of a URL's query. A
`Parser a` reads the parsed key/value pairs and produces an `a`.

    Query.string "q"        -- ?q=elm   -> Just "elm"   (else Nothing)
    Query.int "page"        -- ?page=2  -> Just 2
    Query.enum "sort" dict  -- ?sort=asc by a lookup table

-}

import Dict exposing (Dict)


{-| Reads the query's key/value pairs and yields a value. -}
type alias Parser a =
    List ( String, String ) -> a


{-| The (first) value of a query parameter as a `String`, or `Nothing` if absent. -}
string : String -> Parser (Maybe String)
string key params =
    first key params


{-| The value of a query parameter parsed as an `Int`, or `Nothing` if absent or non-numeric. -}
int : String -> Parser (Maybe Int)
int key params =
    Maybe.andThen String.toInt (first key params)


{-| The value of a query parameter looked up in a table of allowed values. -}
enum : String -> Dict String a -> Parser (Maybe a)
enum key table params =
    Maybe.andThen (\v -> Dict.get v table) (first key params)


{-| Transform a query parser's result. -}
map : (a -> b) -> Parser a -> Parser b
map f parser params =
    f (parser params)


{-| Combine two query parsers. -}
map2 : (a -> b -> c) -> Parser a -> Parser b -> Parser c
map2 f a b params =
    f (a params) (b params)


first : String -> List ( String, String ) -> Maybe String
first key params =
    case params of
        [] ->
            Nothing

        ( k, v ) :: rest ->
            if k == key then
                Just v

            else
                first key rest

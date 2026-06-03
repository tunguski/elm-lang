module Url.Builder exposing
    ( QueryParameter
    , absolute
    , relative
    , crossOrigin
    , string
    , int
    , toQuery
    )

{-| Build URLs and query strings safely (a small port of elm/url's `Url.Builder`). Path segments and
query values are percent-encoded for you.

    Url.Builder.absolute [ "search" ] [ Url.Builder.string "q" "a b", Url.Builder.int "page" 2 ]
        --> "/search?q=a%20b&page=2"

-}

import Url


{-| One `key=value` query parameter (already percent-encoded). Build with `string`/`int`. -}
type QueryParameter
    = QueryParameter String String


{-| A query parameter with a string value. -}
string : String -> String -> QueryParameter
string key value =
    QueryParameter (Url.percentEncode key) (Url.percentEncode value)


{-| A query parameter with an integer value. -}
int : String -> Int -> QueryParameter
int key value =
    QueryParameter (Url.percentEncode key) (String.fromInt value)


{-| An absolute path (leading `/`) from segments and query parameters. -}
absolute : List String -> List QueryParameter -> String
absolute path params =
    "/" ++ String.join "/" (List.map Url.percentEncode path) ++ toQuery params


{-| A relative path (no leading `/`) from segments and query parameters. -}
relative : List String -> List QueryParameter -> String
relative path params =
    String.join "/" (List.map Url.percentEncode path) ++ toQuery params


{-| A full URL on another host: a pre-path (e.g. `https://example.com`) then segments and query. -}
crossOrigin : String -> List String -> List QueryParameter -> String
crossOrigin prePath path params =
    prePath ++ "/" ++ String.join "/" (List.map Url.percentEncode path) ++ toQuery params


{-| Render query parameters as a `?key=value&…` string (empty when there are none). -}
toQuery : List QueryParameter -> String
toQuery params =
    case params of
        [] ->
            ""

        _ ->
            "?" ++ String.join "&" (List.map toQueryPair params)


toQueryPair : QueryParameter -> String
toQueryPair (QueryParameter key value) =
    key ++ "=" ++ value

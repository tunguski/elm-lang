module Json.Decode.Extra exposing
    ( andMap
    , withDefault
    , optionalField
    , fromMaybe
    , fromResult
    , parseInt
    , parseFloat
    )

{-| A subset of the popular `elm-community/json-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend. `andMap` is the building block for
pipeline-style record decoding:

    import Json.Decode as Decode
    import Json.Decode.Extra as Extra

    pair =
        Decode.succeed Tuple.pair
            |> Extra.andMap (Decode.field "a" Decode.int)
            |> Extra.andMap (Decode.field "b" Decode.int)

-}

import Json.Decode as Decode exposing (Decoder)


{-| Applies a decoded function to a decoded argument — the pipeline applicative. Built on
`andThen`/`map` (no `map2` needed). -}
andMap : Decoder a -> Decoder (a -> b) -> Decoder b
andMap argDecoder functionDecoder =
    functionDecoder |> Decode.andThen (\f -> Decode.map f argDecoder)


{-| Falls back to `fallback` when `decoder` fails. -}
withDefault : a -> Decoder a -> Decoder a
withDefault fallback decoder =
    Decode.oneOf [ decoder, Decode.succeed fallback ]


{-| `Just` the decoded field when present and decodable, otherwise `Nothing`. -}
optionalField : String -> Decoder a -> Decoder (Maybe a)
optionalField name decoder =
    Decode.maybe (Decode.field name decoder)


{-| Lifts a `Maybe` into a decoder, failing with `error` on `Nothing`. -}
fromMaybe : String -> Maybe a -> Decoder a
fromMaybe error maybe =
    case maybe of
        Just x ->
            Decode.succeed x

        Nothing ->
            Decode.fail error


{-| Lifts a `Result` into a decoder, failing with the `Err` message. -}
fromResult : Result String a -> Decoder a
fromResult result =
    case result of
        Ok x ->
            Decode.succeed x

        Err e ->
            Decode.fail e


{-| Decodes a JSON string and parses it as an `Int`. -}
parseInt : Decoder Int
parseInt =
    Decode.andThen (\s -> fromMaybe ("not an Int: " ++ s) (String.toInt s)) Decode.string


{-| Decodes a JSON string and parses it as a `Float`. -}
parseFloat : Decoder Float
parseFloat =
    Decode.andThen (\s -> fromMaybe ("not a Float: " ++ s) (String.toFloat s)) Decode.string

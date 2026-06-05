module Maybe.Extra exposing
    ( isJust
    , isNothing
    , join
    , or
    , orElse
    , values
    , combine
    , traverse
    , filter
    , unwrap
    , toList
    , oneOf
    , andMap
    )

{-| A subset of the popular `elm-community/maybe-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend.

    import Maybe.Extra as ME

    ME.values [ Just 1, Nothing, Just 3 ]   --> [ 1, 3 ]
    ME.combine [ Just 1, Just 2 ]           --> Just [ 1, 2 ]
    ME.combine [ Just 1, Nothing ]          --> Nothing

-}


{-| `True` for `Just _`. -}
isJust : Maybe a -> Bool
isJust m =
    case m of
        Just _ ->
            True

        Nothing ->
            False


{-| `True` for `Nothing`. -}
isNothing : Maybe a -> Bool
isNothing m =
    case m of
        Just _ ->
            False

        Nothing ->
            True


{-| Flattens nested maybes. -}
join : Maybe (Maybe a) -> Maybe a
join m =
    case m of
        Just inner ->
            inner

        Nothing ->
            Nothing


{-| The first argument if it is `Just`, otherwise the second. -}
or : Maybe a -> Maybe a -> Maybe a
or first second =
    case first of
        Just _ ->
            first

        Nothing ->
            second


{-| `or` with the arguments flipped, so it reads well in a pipeline:
`first |> orElse fallback`. -}
orElse : Maybe a -> Maybe a -> Maybe a
orElse second first =
    or first second


{-| Keeps only the `Just` values. -}
values : List (Maybe a) -> List a
values list =
    List.filterMap identity list


{-| `Just` a list of all the values if every element is `Just`, otherwise `Nothing`. -}
combine : List (Maybe a) -> Maybe (List a)
combine list =
    traverse identity list


{-| Maps each element to a `Maybe` and combines: `Just` the results if all succeed. -}
traverse : (a -> Maybe b) -> List a -> Maybe (List b)
traverse f list =
    case list of
        [] ->
            Just []

        x :: rest ->
            case f x of
                Nothing ->
                    Nothing

                Just y ->
                    Maybe.map (\ys -> y :: ys) (traverse f rest)


{-| Keeps the value only if it satisfies `pred`. -}
filter : (a -> Bool) -> Maybe a -> Maybe a
filter pred m =
    case m of
        Just x ->
            if pred x then
                Just x

            else
                Nothing

        Nothing ->
            Nothing


{-| `unwrap default f` is `Maybe.map f >> Maybe.withDefault default`. -}
unwrap : b -> (a -> b) -> Maybe a -> b
unwrap default f m =
    case m of
        Just x ->
            f x

        Nothing ->
            default


{-| `[ x ]` for `Just x`, `[]` for `Nothing`. -}
toList : Maybe a -> List a
toList m =
    case m of
        Just x ->
            [ x ]

        Nothing ->
            []


{-| The first `Just` in a list, or `Nothing` if there is none. -}
oneOf : List (Maybe a) -> Maybe a
oneOf list =
    case list of
        [] ->
            Nothing

        x :: rest ->
            case x of
                Just _ ->
                    x

                Nothing ->
                    oneOf rest


{-| Applicative apply: `Just f |> andMap (Just x)` is `Just (f x)`. Useful for chaining
`Just f |> andMap arg1 |> andMap arg2`. -}
andMap : Maybe a -> Maybe (a -> b) -> Maybe b
andMap arg fn =
    Maybe.map2 (\f x -> f x) fn arg

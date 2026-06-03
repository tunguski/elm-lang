module Dict.Extra exposing
    ( groupBy
    , fromListBy
    , frequencies
    , mapKeys
    , filterMap
    , removeWhen
    , any
    , find
    , invert
    , unionWith
    , removeMany
    , keepOnly
    )

{-| A subset of the popular `elm-community/dict-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend.

    import Dict.Extra as DE

    DE.frequencies [ "a", "b", "a" ] |> Dict.toList   --> [ ( "a", 2 ), ( "b", 1 ) ]

-}

import Dict exposing (Dict)
import Set exposing (Set)


{-| Groups a list into a `Dict` keyed by `toKey`, each value the list of elements with that key
(original order preserved). -}
groupBy : (a -> comparable) -> List a -> Dict comparable (List a)
groupBy toKey list =
    List.foldr
        (\x acc -> Dict.update (toKey x) (\m -> Just (x :: Maybe.withDefault [] m)) acc)
        Dict.empty
        list


{-| Builds a `Dict` from a list, keyed by `toKey` (later duplicates win). -}
fromListBy : (a -> comparable) -> List a -> Dict comparable a
fromListBy toKey list =
    List.foldl (\x acc -> Dict.insert (toKey x) x acc) Dict.empty list


{-| Counts how often each value occurs. -}
frequencies : List comparable -> Dict comparable Int
frequencies list =
    List.foldl (\x acc -> Dict.update x (\m -> Just (1 + Maybe.withDefault 0 m)) acc) Dict.empty list


{-| Applies `f` to every key. If two keys collide, the later (greater) one wins. -}
mapKeys : (comparable1 -> comparable2) -> Dict comparable1 v -> Dict comparable2 v
mapKeys f dict =
    Dict.foldl (\k v acc -> Dict.insert (f k) v acc) Dict.empty dict


{-| Keeps and transforms only the entries for which `f` returns `Just`. -}
filterMap : (comparable -> a -> Maybe b) -> Dict comparable a -> Dict comparable b
filterMap f dict =
    Dict.foldl
        (\k v acc ->
            case f k v of
                Just b ->
                    Dict.insert k b acc

                Nothing ->
                    acc
        )
        Dict.empty
        dict


{-| Removes the entries for which `pred key value` is `True`. -}
removeWhen : (comparable -> v -> Bool) -> Dict comparable v -> Dict comparable v
removeWhen pred dict =
    Dict.filter (\k v -> not (pred k v)) dict


{-| Whether any entry satisfies `pred`. -}
any : (comparable -> v -> Bool) -> Dict comparable v -> Bool
any pred dict =
    Dict.foldl (\k v acc -> acc || pred k v) False dict


{-| The first entry (in key order) satisfying `pred`, or `Nothing`. -}
find : (comparable -> v -> Bool) -> Dict comparable v -> Maybe ( comparable, v )
find pred dict =
    Dict.foldl
        (\k v acc ->
            case acc of
                Just _ ->
                    acc

                Nothing ->
                    if pred k v then
                        Just ( k, v )

                    else
                        Nothing
        )
        Nothing
        dict


{-| Swaps keys and values (later collisions on a value win). -}
invert : Dict comparable1 comparable2 -> Dict comparable2 comparable1
invert dict =
    Dict.foldl (\k v acc -> Dict.insert v k acc) Dict.empty dict


{-| Unions two dicts, combining the values of keys present in both with `combine left right`. -}
unionWith : (v -> v -> v) -> Dict comparable v -> Dict comparable v -> Dict comparable v
unionWith combine left right =
    Dict.foldl
        (\k lv acc ->
            Dict.update k
                (\m ->
                    Just
                        (case m of
                            Just rv ->
                                combine lv rv

                            Nothing ->
                                lv
                        )
                )
                acc
        )
        right
        left


{-| Removes every key in the set. -}
removeMany : Set comparable -> Dict comparable v -> Dict comparable v
removeMany keys dict =
    Set.foldl (\k acc -> Dict.remove k acc) dict keys


{-| Keeps only the entries whose key is in the set. -}
keepOnly : Set comparable -> Dict comparable v -> Dict comparable v
keepOnly keys dict =
    Dict.filter (\k _ -> Set.member k keys) dict

module Set.Extra exposing
    ( toggle
    , concatMap
    , filterMap
    , areDisjoint
    , symmetricDifference
    )

{-| A subset of the popular `elm-community/set-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend.

    import Set.Extra as SE

    SE.toggle 2 (Set.fromList [ 1, 2, 3 ]) |> Set.toList   --> [ 1, 3 ]

-}

import Set exposing (Set)


{-| Removes `x` if present, otherwise inserts it. -}
toggle : comparable -> Set comparable -> Set comparable
toggle x set =
    if Set.member x set then
        Set.remove x set

    else
        Set.insert x set


{-| Maps each element to a set and unions the results. -}
concatMap : (comparable -> Set comparable2) -> Set comparable -> Set comparable2
concatMap f set =
    Set.foldl (\x acc -> Set.union (f x) acc) Set.empty set


{-| Keeps and transforms only the elements for which `f` returns `Just`. -}
filterMap : (comparable -> Maybe comparable2) -> Set comparable -> Set comparable2
filterMap f set =
    Set.foldl
        (\x acc ->
            case f x of
                Just y ->
                    Set.insert y acc

                Nothing ->
                    acc
        )
        Set.empty
        set


{-| Whether two sets share no elements. -}
areDisjoint : Set comparable -> Set comparable -> Bool
areDisjoint a b =
    Set.isEmpty (Set.intersect a b)


{-| The elements in exactly one of the two sets. -}
symmetricDifference : Set comparable -> Set comparable -> Set comparable
symmetricDifference a b =
    Set.union (Set.diff a b) (Set.diff b a)

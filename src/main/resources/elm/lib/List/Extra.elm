module List.Extra exposing
    ( last
    , init
    , getAt
    , setAt
    , updateAt
    , removeAt
    , find
    , findIndex
    , elemIndex
    , count
    , splitAt
    , takeWhile
    , dropWhile
    , span
    , unique
    , uniqueBy
    , groupsOf
    , scanl1
    , foldl1
    , maximumBy
    , minimumBy
    , zip
    , unzip3
    , interweave
    , notMember
    )

{-| A subset of the popular `elm-community/list-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend. Indexing helpers (`getAt`, `setAt`,
`removeAt`), search (`find`, `findIndex`, `elemIndex`, `count`), slicing (`splitAt`, `takeWhile`,
`dropWhile`, `span`, `groupsOf`), de-duplication (`unique`, `uniqueBy`), folds/scans without a base
(`foldl1`, `scanl1`), keyed extrema (`maximumBy`, `minimumBy`) and a few combiners.

    import List.Extra as LE

    LE.getAt 1 [ "a", "b", "c" ]   --> Just "b"
    LE.unique [ 1, 1, 2, 3, 2 ]    --> [ 1, 2, 3 ]
    LE.groupsOf 2 [ 1, 2, 3, 4, 5 ]  --> [ [ 1, 2 ], [ 3, 4 ], [ 5 ] ]

-}


{-| The last element, or `Nothing` if empty. -}
last : List a -> Maybe a
last list =
    case list of
        [] ->
            Nothing

        [ x ] ->
            Just x

        _ :: rest ->
            last rest


{-| All elements but the last, or `Nothing` if empty. -}
init : List a -> Maybe (List a)
init list =
    case list of
        [] ->
            Nothing

        _ ->
            Just (List.take (List.length list - 1) list)


{-| The element at index `n` (0-based), or `Nothing` if out of range. -}
getAt : Int -> List a -> Maybe a
getAt n list =
    if n < 0 then
        Nothing

    else
        List.head (List.drop n list)


{-| Replaces the element at index `n` with `value` (no-op if out of range). -}
setAt : Int -> a -> List a -> List a
setAt n value list =
    updateAt n (\_ -> value) list


{-| Applies `f` to the element at index `n` (no-op if out of range). -}
updateAt : Int -> (a -> a) -> List a -> List a
updateAt n f list =
    List.indexedMap
        (\i x ->
            if i == n then
                f x

            else
                x
        )
        list


{-| Removes the element at index `n` (no-op if out of range). -}
removeAt : Int -> List a -> List a
removeAt n list =
    List.take n list ++ List.drop (n + 1) list


{-| The first element matching `pred`, or `Nothing`. -}
find : (a -> Bool) -> List a -> Maybe a
find pred list =
    case list of
        [] ->
            Nothing

        x :: rest ->
            if pred x then
                Just x

            else
                find pred rest


{-| The index of the first element matching `pred`, or `Nothing`. -}
findIndex : (a -> Bool) -> List a -> Maybe Int
findIndex pred list =
    findIndexHelp 0 pred list


findIndexHelp : Int -> (a -> Bool) -> List a -> Maybe Int
findIndexHelp i pred list =
    case list of
        [] ->
            Nothing

        x :: rest ->
            if pred x then
                Just i

            else
                findIndexHelp (i + 1) pred rest


{-| The index of the first element equal to `value`, or `Nothing`. -}
elemIndex : a -> List a -> Maybe Int
elemIndex value list =
    findIndex (\x -> x == value) list


{-| How many elements satisfy `pred`. -}
count : (a -> Bool) -> List a -> Int
count pred list =
    List.length (List.filter pred list)


{-| Splits a list into the first `n` elements and the rest. -}
splitAt : Int -> List a -> ( List a, List a )
splitAt n list =
    ( List.take n list, List.drop n list )


{-| The longest prefix of elements satisfying `pred`. -}
takeWhile : (a -> Bool) -> List a -> List a
takeWhile pred list =
    case list of
        [] ->
            []

        x :: rest ->
            if pred x then
                x :: takeWhile pred rest

            else
                []


{-| The list after the longest prefix satisfying `pred`. -}
dropWhile : (a -> Bool) -> List a -> List a
dropWhile pred list =
    case list of
        [] ->
            []

        x :: rest ->
            if pred x then
                dropWhile pred rest

            else
                list


{-| `( takeWhile pred, dropWhile pred )`. -}
span : (a -> Bool) -> List a -> ( List a, List a )
span pred list =
    ( takeWhile pred list, dropWhile pred list )


{-| Removes duplicate elements, keeping the first occurrence (order preserved). -}
unique : List a -> List a
unique list =
    uniqueBy identity list


{-| Like {@link unique} but comparing by the result of `key`. -}
uniqueBy : (a -> b) -> List a -> List a
uniqueBy key list =
    uniqueByHelp key [] list


uniqueByHelp : (a -> b) -> List b -> List a -> List a
uniqueByHelp key seen list =
    case list of
        [] ->
            []

        x :: rest ->
            let
                k =
                    key x
            in
            if List.member k seen then
                uniqueByHelp key seen rest

            else
                x :: uniqueByHelp key (k :: seen) rest


{-| Splits into chunks of `size` (the last chunk may be shorter). -}
groupsOf : Int -> List a -> List (List a)
groupsOf size list =
    if size <= 0 || List.isEmpty list then
        []

    else
        List.take size list :: groupsOf size (List.drop size list)


{-| Like `List.foldl` but using the first element as the initial accumulator. -}
foldl1 : (a -> a -> a) -> List a -> Maybe a
foldl1 f list =
    case list of
        [] ->
            Nothing

        x :: rest ->
            Just (List.foldl f x rest)


{-| A left scan using the first element as the seed (so the result starts with it). -}
scanl1 : (a -> a -> a) -> List a -> List a
scanl1 f list =
    case list of
        [] ->
            []

        x :: rest ->
            List.reverse (List.foldl (\e acc -> f e (firstOf acc) :: acc) [ x ] rest)


firstOf : List a -> a
firstOf list =
    case list of
        x :: _ ->
            x

        [] ->
            -- unreachable: the accumulator always starts non-empty
            firstOf list


{-| The element with the largest `key`, or `Nothing`. -}
maximumBy : (a -> comparable) -> List a -> Maybe a
maximumBy key list =
    foldl1
        (\x best ->
            if key x > key best then
                x

            else
                best
        )
        list


{-| The element with the smallest `key`, or `Nothing`. -}
minimumBy : (a -> comparable) -> List a -> Maybe a
minimumBy key list =
    foldl1
        (\x best ->
            if key x < key best then
                x

            else
                best
        )
        list


{-| Pairs up two lists, stopping at the shorter one. -}
zip : List a -> List b -> List ( a, b )
zip xs ys =
    List.map2 Tuple.pair xs ys


{-| Splits a list of triples into three lists. -}
unzip3 : List ( a, b, c ) -> ( List a, List b, List c )
unzip3 triples =
    ( List.map (\( a, _, _ ) -> a) triples
    , List.map (\( _, b, _ ) -> b) triples
    , List.map (\( _, _, c ) -> c) triples
    )


{-| Alternates elements from two lists; once one runs out, the rest of the other follows. -}
interweave : List a -> List a -> List a
interweave xs ys =
    case ( xs, ys ) of
        ( x :: xrest, y :: yrest ) ->
            x :: y :: interweave xrest yrest

        ( [], _ ) ->
            ys

        ( _, [] ) ->
            xs


{-| Whether `value` is not in the list. -}
notMember : a -> List a -> Bool
notMember value list =
    not (List.member value list)

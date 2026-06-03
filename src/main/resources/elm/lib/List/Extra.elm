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
    , foldr1
    , maximumBy
    , minimumBy
    , zip
    , unzip3
    , interweave
    , notMember
    , intercalate
    , transpose
    , group
    , cartesianProduct
    , andMap
    , iterate
    , remove
    , swapAt
    , isPrefixOf
    , isSuffixOf
    , stripPrefix
    , groupWhile
    , findMap
    , zip3
    , indexedFoldl
    , unfoldr
    , scanl
    , splitWhen
    , mapAccuml
    , updateIf
    , setIf
    , lift2
    , minimumWith
    , maximumWith
    , takeWhileRight
    , dropWhileRight
    , gatherEquals
    , gatherWith
    , tails
    , inits
    , isInfixOf
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


{-| Like {@link foldl1} but from the right (`f a (f b (… z))`), or `Nothing` if empty. -}
foldr1 : (a -> a -> a) -> List a -> Maybe a
foldr1 f list =
    case List.reverse list of
        [] ->
            Nothing

        seed :: rest ->
            Just (List.foldl f seed rest)


{-| Concatenates a list of lists, inserting `sep` between each. -}
intercalate : List a -> List (List a) -> List a
intercalate sep lists =
    List.concat (List.intersperse sep lists)


{-| Transposes rows into columns; rows that run out early are dropped. -}
transpose : List (List a) -> List (List a)
transpose listOfLists =
    let
        heads =
            List.filterMap List.head listOfLists

        tails =
            List.map (List.drop 1) listOfLists
    in
    if List.isEmpty heads then
        []

    else
        heads :: transpose tails


{-| Groups consecutive equal elements into sublists. -}
group : List a -> List (List a)
group list =
    case list of
        [] ->
            []

        x :: _ ->
            takeWhile (\y -> y == x) list :: group (dropWhile (\y -> y == x) list)


{-| Every way to pick one element from each list, in order. -}
cartesianProduct : List (List a) -> List (List a)
cartesianProduct lists =
    case lists of
        [] ->
            [ [] ]

        first :: rest ->
            List.concatMap (\x -> List.map (\combo -> x :: combo) (cartesianProduct rest)) first


{-| Applies a list of functions to a list of arguments pairwise (stops at the shorter). -}
andMap : List a -> List (a -> b) -> List b
andMap args functions =
    List.map2 (\f x -> f x) functions args


{-| Repeatedly applies `f`, collecting each value, until it returns `Nothing`. -}
iterate : (a -> Maybe a) -> a -> List a
iterate f x =
    x
        :: (case f x of
                Just next ->
                    iterate f next

                Nothing ->
                    []
           )


{-| Removes the first element equal to `value`. -}
remove : a -> List a -> List a
remove value list =
    case list of
        [] ->
            []

        x :: rest ->
            if x == value then
                rest

            else
                x :: remove value rest


{-| Swaps the elements at indices `i` and `j` (no-op if either is out of range). -}
swapAt : Int -> Int -> List a -> List a
swapAt i j list =
    case ( getAt i list, getAt j list ) of
        ( Just a, Just b ) ->
            setAt j a (setAt i b list)

        _ ->
            list


{-| Whether the list starts with `prefix`. -}
isPrefixOf : List a -> List a -> Bool
isPrefixOf prefix list =
    List.take (List.length prefix) list == prefix


{-| Whether the list ends with `suffix`. -}
isSuffixOf : List a -> List a -> Bool
isSuffixOf suffix list =
    isPrefixOf (List.reverse suffix) (List.reverse list)


{-| Drops `prefix` from the front, or `Nothing` if the list doesn't start with it. -}
stripPrefix : List a -> List a -> Maybe (List a)
stripPrefix prefix list =
    if isPrefixOf prefix list then
        Just (List.drop (List.length prefix) list)

    else
        Nothing


{-| Groups runs of adjacent elements for which `test prev next` holds. -}
groupWhile : (a -> a -> Bool) -> List a -> List (List a)
groupWhile test list =
    case list of
        [] ->
            []

        x :: rest ->
            groupWhileHelp test x [ x ] rest


groupWhileHelp : (a -> a -> Bool) -> a -> List a -> List a -> List (List a)
groupWhileHelp test prev current list =
    case list of
        [] ->
            [ List.reverse current ]

        y :: rest ->
            if test prev y then
                groupWhileHelp test y (y :: current) rest

            else
                List.reverse current :: groupWhileHelp test y [ y ] rest


{-| The first non-`Nothing` result of `f`, or `Nothing`. -}
findMap : (a -> Maybe b) -> List a -> Maybe b
findMap f list =
    case list of
        [] ->
            Nothing

        x :: rest ->
            case f x of
                Just y ->
                    Just y

                Nothing ->
                    findMap f rest


{-| Zips three lists into triples, stopping at the shortest. -}
zip3 : List a -> List b -> List c -> List ( a, b, c )
zip3 xs ys zs =
    List.map3 (\a b c -> ( a, b, c )) xs ys zs


{-| `List.foldl` with the element index passed to the step function. -}
indexedFoldl : (Int -> a -> b -> b) -> b -> List a -> b
indexedFoldl f acc list =
    indexedFoldlHelp f 0 acc list


indexedFoldlHelp : (Int -> a -> b -> b) -> Int -> b -> List a -> b
indexedFoldlHelp f i acc list =
    case list of
        [] ->
            acc

        x :: rest ->
            indexedFoldlHelp f (i + 1) (f i x acc) rest


{-| Builds a list from a seed: `f` returns `Just (element, nextSeed)` or `Nothing` to stop. -}
unfoldr : (b -> Maybe ( a, b )) -> b -> List a
unfoldr f seed =
    case f seed of
        Just ( a, next ) ->
            a :: unfoldr f next

        Nothing ->
            []


{-| A left scan: every intermediate accumulator, starting with the seed. -}
scanl : (a -> b -> b) -> b -> List a -> List b
scanl f acc list =
    acc
        :: (case list of
                [] ->
                    []

                x :: rest ->
                    scanl f (f x acc) rest
           )


{-| Splits at the first element satisfying `pred` (that element starts the second part); `Nothing`
if none match. -}
splitWhen : (a -> Bool) -> List a -> Maybe ( List a, List a )
splitWhen pred list =
    case findIndex pred list of
        Just i ->
            Just (splitAt i list)

        Nothing ->
            Nothing


{-| A stateful map: threads an accumulator left to right, returning the final state and mapped list. -}
mapAccuml : (s -> a -> ( s, b )) -> s -> List a -> ( s, List b )
mapAccuml f acc list =
    case list of
        [] ->
            ( acc, [] )

        x :: rest ->
            let
                step =
                    f acc x

                recur =
                    mapAccuml f (Tuple.first step) rest
            in
            ( Tuple.first recur, Tuple.second step :: Tuple.second recur )


{-| Applies `f` to every element satisfying `pred`. -}
updateIf : (a -> Bool) -> (a -> a) -> List a -> List a
updateIf pred f list =
    List.map
        (\x ->
            if pred x then
                f x

            else
                x
        )
        list


{-| Replaces every element satisfying `pred` with `value`. -}
setIf : (a -> Bool) -> a -> List a -> List a
setIf pred value list =
    updateIf pred (\_ -> value) list


{-| Applies `f` to every pair from the two lists (the cartesian combination). -}
lift2 : (a -> b -> c) -> List a -> List b -> List c
lift2 f xs ys =
    List.concatMap (\x -> List.map (\y -> f x y) ys) xs


{-| The element that is smallest by the given comparator, or `Nothing`. -}
minimumWith : (a -> a -> Order) -> List a -> Maybe a
minimumWith cmp list =
    foldl1
        (\x best ->
            if cmp x best == LT then
                x

            else
                best
        )
        list


{-| The element that is largest by the given comparator, or `Nothing`. -}
maximumWith : (a -> a -> Order) -> List a -> Maybe a
maximumWith cmp list =
    foldl1
        (\x best ->
            if cmp x best == GT then
                x

            else
                best
        )
        list


{-| The longest suffix of elements satisfying `pred`. -}
takeWhileRight : (a -> Bool) -> List a -> List a
takeWhileRight pred list =
    List.reverse (takeWhile pred (List.reverse list))


{-| The list with the longest suffix satisfying `pred` removed. -}
dropWhileRight : (a -> Bool) -> List a -> List a
dropWhileRight pred list =
    List.reverse (dropWhile pred (List.reverse list))


{-| Groups equal elements: each distinct element paired with the (later) elements equal to it. -}
gatherEquals : List a -> List ( a, List a )
gatherEquals list =
    gatherWith (\a b -> a == b) list


{-| Like {@link gatherEquals} but with a custom equality test. -}
gatherWith : (a -> a -> Bool) -> List a -> List ( a, List a )
gatherWith test list =
    case list of
        [] ->
            []

        x :: rest ->
            ( x, List.filter (test x) rest )
                :: gatherWith test (List.filter (\y -> not (test x y)) rest)


{-| Every suffix of the list, longest first, ending with `[]`. -}
tails : List a -> List (List a)
tails list =
    case list of
        [] ->
            [ [] ]

        _ :: rest ->
            list :: tails rest


{-| Every prefix of the list, shortest first, starting with `[]`. -}
inits : List a -> List (List a)
inits list =
    [] :: (case list of
            [] ->
                []

            x :: rest ->
                List.map (\p -> x :: p) (inits rest)
          )


{-| Whether `infixList` appears as a contiguous run within the list. -}
isInfixOf : List a -> List a -> Bool
isInfixOf infixList list =
    List.any (\suffix -> isPrefixOf infixList suffix) (tails list)

module Result.Extra exposing
    ( isOk
    , isErr
    , combine
    , partition
    , mapBoth
    , merge
    , or
    , orElse
    , unwrap
    , extract
    )

{-| A subset of the popular `elm-community/result-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend.

    import Result.Extra as RE

    RE.combine [ Ok 1, Ok 2 ]        --> Ok [ 1, 2 ]
    RE.combine [ Ok 1, Err "x" ]     --> Err "x"
    RE.merge (Ok 3)                  --> 3

-}


{-| `True` for `Ok _`. -}
isOk : Result e a -> Bool
isOk r =
    case r of
        Ok _ ->
            True

        Err _ ->
            False


{-| `True` for `Err _`. -}
isErr : Result e a -> Bool
isErr r =
    case r of
        Ok _ ->
            False

        Err _ ->
            True


{-| `Ok` a list of all values if every element is `Ok`, otherwise the first `Err`. -}
combine : List (Result e a) -> Result e (List a)
combine list =
    case list of
        [] ->
            Ok []

        r :: rest ->
            case r of
                Err e ->
                    Err e

                Ok x ->
                    Result.map (\xs -> x :: xs) (combine rest)


{-| Splits results into the `Ok` values and the `Err` values. -}
partition : List (Result e a) -> ( List a, List e )
partition list =
    case list of
        [] ->
            ( [], [] )

        r :: rest ->
            let
                rec =
                    partition rest
            in
            case r of
                Ok x ->
                    ( x :: Tuple.first rec, Tuple.second rec )

                Err e ->
                    ( Tuple.first rec, e :: Tuple.second rec )


{-| Maps the `Err` with one function and the `Ok` with another. -}
mapBoth : (e -> f) -> (a -> b) -> Result e a -> Result f b
mapBoth onErr onOk r =
    case r of
        Ok x ->
            Ok (onOk x)

        Err e ->
            Err (onErr e)


{-| Collapses a `Result a a` (both branches the same type) to its value. -}
merge : Result a a -> a
merge r =
    case r of
        Ok x ->
            x

        Err x ->
            x


{-| The first argument if it is `Ok`, otherwise the second. -}
or : Result x a -> Result x a -> Result x a
or first second =
    case first of
        Ok _ ->
            first

        Err _ ->
            second


{-| `or` with the arguments flipped, for pipelines: `first |> orElse fallback`. -}
orElse : Result x a -> Result x a -> Result x a
orElse second first =
    or first second


{-| `unwrap default f` is `Result.map f >> Result.withDefault default`. -}
unwrap : b -> (a -> b) -> Result e a -> b
unwrap default f r =
    case r of
        Ok x ->
            f x

        Err _ ->
            default


{-| The `Ok` value, or recover one from the `Err` with the given function. -}
extract : (e -> a) -> Result e a -> a
extract recover r =
    case r of
        Ok x ->
            x

        Err e ->
            recover e

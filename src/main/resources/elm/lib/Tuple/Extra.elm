module Tuple.Extra exposing
    ( apply
    , swap
    , sequenceMaybe
    , sequenceFirstMaybe
    , sequenceSecondMaybe
    )

{-| A subset of the popular `elm-community/tuple-extra` helpers — the ones reached for most often —
implemented in plain Elm so they work on every backend.

    import Tuple.Extra as TE

    TE.apply (+) ( 3, 4 )                       --> 7
    TE.sequenceMaybe ( Just 1, Just 2 )         --> Just ( 1, 2 )

-}


{-| Applies a two-argument function to the pair (the "uncurry"). -}
apply : (a -> b -> c) -> ( a, b ) -> c
apply f ( a, b ) =
    f a b


{-| Swaps the two elements. -}
swap : ( a, b ) -> ( b, a )
swap ( a, b ) =
    ( b, a )


{-| `Just` the pair if both elements are `Just`, otherwise `Nothing`. -}
sequenceMaybe : ( Maybe a, Maybe b ) -> Maybe ( a, b )
sequenceMaybe ( ma, mb ) =
    Maybe.map2 (\a b -> ( a, b )) ma mb


{-| `Just` the pair if the first element is `Just`. -}
sequenceFirstMaybe : ( Maybe a, b ) -> Maybe ( a, b )
sequenceFirstMaybe ( ma, b ) =
    Maybe.map (\a -> ( a, b )) ma


{-| `Just` the pair if the second element is `Just`. -}
sequenceSecondMaybe : ( a, Maybe b ) -> Maybe ( a, b )
sequenceSecondMaybe ( a, mb ) =
    Maybe.map (\b -> ( a, b )) mb

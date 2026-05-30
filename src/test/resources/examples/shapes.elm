import Svg exposing (..)
import Svg.Attributes exposing (..)


main =
  svg
    [ viewBox "0 0 400 400"
    , width "400"
    , height "400"
    ]
    [ circle
        [ cx "100"
        , cy "100"
        , r "75"
        , fill "#0B79CE"
        ]
        []
    , rect
        [ x "200"
        , y "10"
        , width "100"
        , height "100"
        , fill "#85B53F"
        ]
        []
    ]

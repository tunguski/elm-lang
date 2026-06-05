module RTS.Main exposing (main)

{-| The RTS frontend: a `Browser.element` program wiring the model, the pure `update` from
`RTS.Logic`, the SVG/HTML `view` from `RTS.View`, and a real-time clock that fires `Tick` five times
a second. Compile it with `elm make src/main/elm/rts/Main.elm --project src/main/elm/rts -o rts.html`.
-}

import Browser
import RTS.Logic as Logic
import RTS.Model exposing (..)
import RTS.View as View
import Time


main : Program () Model Msg
main =
    Browser.element
        { init = \_ -> ( Logic.init, Cmd.none )
        , update = \msg model -> ( Logic.update msg model, Cmd.none )
        , view = View.view
        , subscriptions = \_ -> Time.every 200 (\_ -> Tick)
        }

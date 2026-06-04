module Element exposing (main)

import Browser
import Html exposing (button, div, text)
import Html.Events exposing (onClick)

main = Browser.element { init = init, update = update, view = view, subscriptions = subs }

init flags = ( 0, Cmd.none )

update msg model =
    case msg of
        Bump ->
            ( model + 1, Cmd.none )

subs model = Sub.none

view model =
    div []
        [ button [ onClick Bump ] [ text "bump" ]
        , div [] [ text (String.fromInt model) ]
        ]

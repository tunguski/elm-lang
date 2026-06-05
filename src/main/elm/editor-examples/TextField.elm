module TextField exposing (main)

import Browser
import Html exposing (div, input, text)
import Html.Events exposing (onInput)

main = Browser.sandbox { init = init, update = update, view = view }

init = ""

update msg model =
    case msg of
        SetText s ->
            s

view model =
    div []
        [ input [ onInput SetText ] []
        , div [] [ text ("You typed: " ++ model) ]
        ]

main = Browser.sandbox { init = init, update = update, view = view }

init = False

update msg model =
    case msg of
        Toggle ->
            not model

view model =
    div []
        [ button [ onClick Toggle ] [ text "toggle " ]
        , text (if model then "ON" else "OFF")
        ]

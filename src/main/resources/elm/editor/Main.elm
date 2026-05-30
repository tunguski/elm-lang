module Main exposing (main)

{-| The hosted instance of the reusable {@link Editor}: it only supplies the set of example files.
The editor itself (file browser, source editing, and live rendering of each file's `main`) lives in
the `Editor` module, so it can be embedded elsewhere with a different file list. -}

import Editor


main : Program () Editor.Model Editor.Msg
main =
    Editor.program examples


{-| The examples shown in the editor. Each is an independent program whose `main` the editor renders
— a static view, an interactive Browser.sandbox app, or a computed value. They stay within the
in-browser interpreter's supported subset (Html, records, lists, recursion, sandbox apps). -}
examples : List ( String, String )
examples =
    [ ( "Buttons.elm", buttons )
    , ( "Hello.elm", hello )
    , ( "Greeting.elm", greeting )
    , ( "Factorial.elm", factorial )
    , ( "ListSum.elm", listSum )
    , ( "Squares.elm", squares )
    , ( "Toggle.elm", toggle )
    ]


buttons : String
buttons =
    """module Main exposing (main)

import Browser
import Html exposing (Html, button, div, text)
import Html.Events exposing (onClick)

main = Browser.sandbox { init = init, update = update, view = view }

init = 0

update msg model =
    case msg of
        Increment ->
            model + 1

        Decrement ->
            model - 1

view model =
    div []
        [ button [ onClick Decrement ] [ text "-" ]
        , div [] [ text (String.fromInt model) ]
        , button [ onClick Increment ] [ text "+" ]
        ]
"""


hello : String
hello =
    """main =
    div []
        [ text "Hello, Elm!" ]
"""


greeting : String
greeting =
    """main = text (greet "world")

greet name =
    "Hello, " ++ name ++ "!"
"""


factorial : String
factorial =
    """main = text (String.fromInt (fact 5))

fact n =
    if n <= 1 then
        1

    else
        n * fact (n - 1)
"""


listSum : String
listSum =
    """main = text (String.fromInt (List.sum (List.range 1 100)))
"""


squares : String
squares =
    """main = div [] (List.map square (List.range 1 5))

square n =
    div [] [ text (String.fromInt (n * n)) ]
"""


toggle : String
toggle =
    """main = Browser.sandbox { init = init, update = update, view = view }

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
"""

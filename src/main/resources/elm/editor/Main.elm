module Main exposing (main)

{-| The hosted instance of the reusable {@link Editor}: it only names the example files to offer.
The editor fetches each over HTTP at startup (relative to the page) and presents them as editable
files; the editor shell (file browser, source editing, live rendering of each file's `main`) lives
in the `Editor` module, so it can be embedded elsewhere with a different list. -}

import Editor


main : Program () Editor.Model Editor.Msg
main =
    Editor.program exampleUrls


{-| The example files the editor loads at startup, served by the gallery under `examples/`. The
first group are tailored to the in-browser interpreter's subset (they render live); the rest are the
full elm-lang.org gallery examples — editable and viewable here, though many use features (SVG,
WebGL, HTTP, …) the small interpreter can't run, so their result pane shows what it can. -}
exampleUrls : List String
exampleUrls =
    [ "examples/Buttons.elm"
    , "examples/TextField.elm"
    , "examples/Element.elm"
    , "examples/Hello.elm"
    , "examples/Greeting.elm"
    , "examples/Factorial.elm"
    , "examples/ListSum.elm"
    , "examples/Squares.elm"
    , "examples/Toggle.elm"
    , "examples/todomvc.elm"
    ]
        ++ List.map (\slug -> "examples/" ++ slug ++ ".elm")
            [ "hello", "groceries", "shapes", "buttons", "text-fields", "forms", "numbers", "cards"
            , "positions", "book", "quotes", "time", "clock", "upload", "drag-and-drop"
            , "image-previews", "triangle", "cube", "crate", "thwomp", "first-person", "picture"
            , "animation", "mouse", "keyboard", "turtle", "mario", "life"
            ]

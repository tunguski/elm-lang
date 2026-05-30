module Main exposing (main)

{-| The Ellie-style multi-file editor UI (The Elm Architecture). It edits a set of files, evaluates
an entry expression across them via the interpreter (`Eval.evalProject`), and drives a time-travel
debugger (`Eval.debugSteps`). The interpreter itself lives in the Lang/Lexer/Parser/Eval modules.
-}

import Browser
import Eval exposing (debugSteps, evalProject, lookup)
import Html exposing (Html, button, div, h1, h2, h3, input, li, p, pre, span, text, textarea, ul)
import Html.Attributes exposing (placeholder, style, value)
import Html.Events exposing (onClick, onInput)


type alias Model =
    { files : List ( String, String )
    , active : String
    , entry : String
    , messages : String
    , step : Int
    , newName : String
    }


init : Model
init =
    { files =
        [ ( "Counter.elm", "init = 0\n\nupdate msg model =\n    case msg of\n        Inc -> model + 1 ;\n        Dec -> model - 1 ;\n        _ -> model\n\nview model = \"count = \" ++ toString model" )
        , ( "Main.elm", "main = greet \"world\"\n\ngreet name = \"Hello, \" ++ name ++ \"!\"" )
        ]
    , active = "Main.elm"
    , entry = "main"
    , messages = "Inc\nInc\nDec\nInc"
    , step = 0
    , newName = ""
    }


type Msg
    = Select String
    | Edit String
    | SetEntry String
    | SetMessages String
    | SetStep String
    | SetNewName String
    | AddFile
    | RemoveFile String


update : Msg -> Model -> Model
update msg model =
    case msg of
        Select name ->
            { model | active = name }

        Edit content ->
            { model | files = setFile model.active content model.files }

        SetEntry e ->
            { model | entry = e }

        SetMessages m ->
            { model | messages = m, step = 0 }

        SetStep s ->
            { model | step = Maybe.withDefault 0 (String.toInt s) }

        SetNewName n ->
            { model | newName = n }

        AddFile ->
            let
                name =
                    if String.endsWith ".elm" model.newName then
                        model.newName

                    else
                        model.newName ++ ".elm"
            in
            if model.newName == "" || hasFile name model.files then
                model

            else
                { model | files = model.files ++ [ ( name, "" ) ], active = name, newName = "" }

        RemoveFile name ->
            let
                remaining =
                    List.filter (\f -> Tuple.first f /= name) model.files
            in
            { model
                | files = remaining
                , active =
                    if model.active == name then
                        List.head remaining |> Maybe.map Tuple.first |> Maybe.withDefault ""

                    else
                        model.active
            }


setFile : String -> String -> List ( String, String ) -> List ( String, String )
setFile name content files =
    List.map
        (\f ->
            if Tuple.first f == name then
                ( name, content )

            else
                f
        )
        files


hasFile : String -> List ( String, String ) -> Bool
hasFile name files =
    List.any (\f -> Tuple.first f == name) files


activeContent : Model -> String
activeContent model =
    lookup model.active model.files |> Maybe.withDefault ""



-- VIEW


view : Model -> Html Msg
view model =
    let
        steps =
            debugSteps model.files (String.lines model.messages)

        clampedStep =
            Basics.max 0 (Basics.min model.step (List.length steps - 1))

        currentStep =
            steps |> List.drop clampedStep |> List.head |> Maybe.withDefault ""
    in
    div [ style "font-family" "system-ui, sans-serif", style "max-width" "1000px", style "margin" "20px auto", style "color" "#0f1720" ]
        [ h1 [] [ text "Elm-in-Elm — multi-file editor" ]
        , p [] [ text "A tiny Elm-like language interpreted in the browser. Edit files on the left; results and the time-travel debugger update live." ]
        , div [ style "display" "flex", style "gap" "16px", style "align-items" "flex-start" ]
            [ fileSidebar model
            , div [ style "flex" "2" ]
                [ h3 [] [ text model.active ]
                , textarea
                    [ onInput Edit
                    , value (activeContent model)
                    , style "width" "100%"
                    , style "height" "260px"
                    , style "font-family" "monospace"
                    , style "font-size" "13px"
                    , style "padding" "10px"
                    , style "box-sizing" "border-box"
                    ]
                    []
                , resultPane model
                ]
            ]
        , debuggerPane steps clampedStep currentStep
        ]


fileSidebar : Model -> Html Msg
fileSidebar model =
    div [ style "flex" "1", style "min-width" "180px" ]
        [ h3 [] [ text "Files" ]
        , ul [ style "list-style" "none", style "padding" "0", style "margin" "0" ]
            (List.map (fileRow model.active) model.files)
        , div [ style "display" "flex", style "gap" "4px", style "margin-top" "8px" ]
            [ input [ placeholder "New.elm", value model.newName, onInput SetNewName, style "flex" "1", style "min-width" "0" ] []
            , button [ onClick AddFile ] [ text "+" ]
            ]
        ]


fileRow : String -> ( String, String ) -> Html Msg
fileRow active file =
    let
        name =
            Tuple.first file

        selected =
            name == active
    in
    li [ style "display" "flex", style "align-items" "center", style "gap" "4px", style "margin" "2px 0" ]
        [ button
            [ onClick (Select name)
            , style "flex" "1"
            , style "text-align" "left"
            , style "font-weight"
                (if selected then
                    "bold"

                 else
                    "normal"
                )
            , style "background"
                (if selected then
                    "#dbeeff"

                 else
                    "#f4f4f4"
                )
            ]
            [ text name ]
        , button [ onClick (RemoveFile name), style "color" "#a00" ] [ text "×" ]
        ]


resultPane : Model -> Html Msg
resultPane model =
    div [ style "margin-top" "12px" ]
        [ div [ style "display" "flex", style "gap" "8px", style "align-items" "center" ]
            [ h3 [ style "margin" "0" ] [ text "Result of " ]
            , input [ value model.entry, onInput SetEntry, style "font-family" "monospace" ] []
            ]
        , pre [ style "background" "#0f1720", style "color" "#e6edf3", style "padding" "12px", style "border-radius" "8px", style "white-space" "pre-wrap" ]
            [ text (evalProject model.files model.entry) ]
        ]


debuggerPane : List String -> Int -> String -> Html Msg
debuggerPane steps clampedStep currentStep =
    div [ style "margin-top" "16px", style "border-top" "1px solid #ccc", style "padding-top" "12px" ]
        [ h2 [] [ text "Time-travel debugger" ]
        , p [] [ text "Define top-level init / update / view, then list messages (one per line) to step through:" ]
        , textarea
            [ onInput SetMessages
            , placeholder "Inc\nInc\nDec"
            , style "width" "100%"
            , style "height" "70px"
            , style "font-family" "monospace"
            , style "box-sizing" "border-box"
            ]
            []
        , div [ style "margin" "8px 0" ]
            [ text ("step " ++ String.fromInt clampedStep ++ " / " ++ String.fromInt (List.length steps - 1) ++ "   ")
            , input
                [ Html.Attributes.type_ "range"
                , Html.Attributes.min "0"
                , Html.Attributes.max (String.fromInt (Basics.max 0 (List.length steps - 1)))
                , value (String.fromInt clampedStep)
                , onInput SetStep
                , style "width" "300px"
                ]
                []
            ]
        , pre [ style "background" "#0f1720", style "color" "#e6edf3", style "padding" "12px", style "border-radius" "8px", style "white-space" "pre-wrap" ]
            [ text currentStep ]
        , h3 [] [ text "All steps" ]
        , ul [ style "font-family" "monospace", style "font-size" "12px" ]
            (List.indexedMap (\i s -> li [ style "color" (if i == clampedStep then "#0a7" else "#555") ] [ text s ]) steps)
        ]


main : Program () Model Msg
main =
    Browser.sandbox { init = init, update = update, view = view }

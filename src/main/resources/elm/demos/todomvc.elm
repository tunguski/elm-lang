module Main exposing (main)

{-| A TodoMVC-style app in The Elm Architecture — the flagship showcase that the interpreter and the
JavaScript backend run end to end (add / toggle / delete / clear-completed, with a live "items left"
count). It exercises records, custom types, lists, `List.map`/`List.filter`, record update, string
ops, `onInput`/`onClick` and nested views together. -}

import Browser
import Html exposing (Html, button, div, h1, input, label, li, span, text, ul)
import Html.Attributes exposing (placeholder, value)
import Html.Events exposing (onClick, onInput)


type alias Entry =
    { description : String, completed : Bool, id : Int }


type alias Model =
    { entries : List Entry, field : String, uid : Int }


init : Model
init =
    { entries = [], field = "", uid = 0 }


type Msg
    = UpdateField String
    | Add
    | Toggle Int
    | Delete Int
    | ClearCompleted


update : Msg -> Model -> Model
update msg model =
    case msg of
        UpdateField s ->
            { model | field = s }

        Add ->
            if model.field == "" then
                model

            else
                let
                    id =
                        model.uid + 1
                in
                { model
                    | uid = id
                    , field = ""
                    , entries = model.entries ++ [ Entry model.field False id ]
                }

        Toggle id ->
            { model | entries = List.map (toggle id) model.entries }

        Delete id ->
            { model | entries = List.filter (\e -> e.id /= id) model.entries }

        ClearCompleted ->
            { model | entries = List.filter (\e -> not e.completed) model.entries }


toggle : Int -> Entry -> Entry
toggle id e =
    if e.id == id then
        { e | completed = not e.completed }

    else
        e


remaining : Model -> Int
remaining model =
    List.length (List.filter (\e -> not e.completed) model.entries)


view : Model -> Html Msg
view model =
    div []
        [ h1 [] [ text "todos" ]
        , input [ placeholder "What needs to be done?", value model.field, onInput UpdateField ] []
        , button [ onClick Add ] [ text "Add" ]
        , ul [] (List.map viewEntry model.entries)
        , span [] [ text (String.fromInt (remaining model) ++ " items left") ]
        , button [ onClick ClearCompleted ] [ text "Clear completed" ]
        ]


viewEntry : Entry -> Html Msg
viewEntry e =
    li []
        [ button [ onClick (Toggle e.id) ]
            [ text
                (if e.completed then
                    "[x]"

                 else
                    "[ ]"
                )
            ]
        , label [] [ text e.description ]
        , button [ onClick (Delete e.id) ] [ text "x" ]
        ]


main : Program () Model Msg
main =
    Browser.sandbox { init = init, update = update, view = view }

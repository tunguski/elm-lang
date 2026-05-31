module Main exposing (main)

{-| A TodoMVC-style app in The Elm Architecture — the flagship showcase that the interpreter and the
JavaScript backend run end to end (add / toggle / delete / clear-completed, with a live "items left"
count). It exercises records, custom types, lists, `List.map`/`List.filter`, record update, string
ops, `onInput`/`onClick` and nested views together. -}

import Browser
import Html exposing (Html, button, div, h1, input, label, li, span, text, ul)
import Html.Attributes exposing (placeholder, style, value)
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
    div
        [ style "min-height" "100vh"
        , style "background" "#f5f5f5"
        , style "padding" "40px 0"
        , style "font-family" "system-ui, -apple-system, Segoe UI, sans-serif"
        ]
        [ div
            [ style "max-width" "480px"
            , style "margin" "0 auto"
            , style "background" "#fff"
            , style "border-radius" "10px"
            , style "box-shadow" "0 12px 28px rgba(0,0,0,0.12)"
            , style "overflow" "hidden"
            ]
            [ h1
                [ style "text-align" "center"
                , style "font-size" "70px"
                , style "font-weight" "200"
                , style "color" "rgba(175,47,47,0.4)"
                , style "margin" "0"
                , style "padding" "18px 0 6px"
                ]
                [ text "todos" ]
            , div [ style "display" "flex", style "padding" "0 16px 12px" ]
                [ input
                    [ placeholder "What needs to be done?"
                    , value model.field
                    , onInput UpdateField
                    , style "flex" "1"
                    , style "font-size" "18px"
                    , style "padding" "12px 14px"
                    , style "border" "1px solid #ddd"
                    , style "border-radius" "6px 0 0 6px"
                    , style "outline" "none"
                    ]
                    []
                , button
                    [ onClick Add
                    , style "padding" "0 18px"
                    , style "font-size" "16px"
                    , style "border" "none"
                    , style "background" "#af2f2f"
                    , style "color" "#fff"
                    , style "border-radius" "0 6px 6px 0"
                    , style "cursor" "pointer"
                    ]
                    [ text "Add" ]
                ]
            , ul [ style "list-style" "none", style "margin" "0", style "padding" "0" ]
                (List.map viewEntry model.entries)
            , div
                [ style "display" "flex"
                , style "justify-content" "space-between"
                , style "align-items" "center"
                , style "padding" "12px 16px"
                , style "color" "#777"
                , style "font-size" "14px"
                , style "border-top" "1px solid #ededed"
                ]
                [ span [] [ text (String.fromInt (remaining model) ++ " items left") ]
                , button
                    [ onClick ClearCompleted
                    , style "border" "none"
                    , style "background" "none"
                    , style "color" "#777"
                    , style "cursor" "pointer"
                    , style "text-decoration" "underline"
                    ]
                    [ text "Clear completed" ]
                ]
            ]
        ]


viewEntry : Entry -> Html Msg
viewEntry e =
    li
        [ style "display" "flex"
        , style "align-items" "center"
        , style "gap" "10px"
        , style "padding" "12px 16px"
        , style "border-top" "1px solid #ededed"
        , style "font-size" "18px"
        ]
        [ button
            [ onClick (Toggle e.id)
            , style "border" "none"
            , style "background" "none"
            , style "font-size" "18px"
            , style "cursor" "pointer"
            , style "color"
                (if e.completed then
                    "#5dc2af"

                 else
                    "#ccc"
                )
            ]
            [ text
                (if e.completed then
                    "[x]"

                 else
                    "[ ]"
                )
            ]
        , label
            [ style "flex" "1"
            , style "color"
                (if e.completed then
                    "#d9d9d9"

                 else
                    "#484848"
                )
            , style "text-decoration"
                (if e.completed then
                    "line-through"

                 else
                    "none"
                )
            ]
            [ text e.description ]
        , button
            [ onClick (Delete e.id)
            , style "border" "none"
            , style "background" "none"
            , style "color" "#cc9a9a"
            , style "font-size" "20px"
            , style "cursor" "pointer"
            ]
            [ text "x" ]
        ]


main : Program () Model Msg
main =
    Browser.sandbox { init = init, update = update, view = view }

port module Ports exposing (..)

import Browser
import Html exposing (Html, button, div, text)
import Html.Events exposing (onClick)


port toJs : String -> Cmd msg


port fromJs : (String -> msg) -> Sub msg


type alias Model =
    String


type Msg
    = Send
    | Got String


main : Program () Model Msg
main =
    Browser.element
        { init = \_ -> ( "waiting", Cmd.none )
        , update = update
        , view = view
        , subscriptions = \_ -> fromJs Got
        }


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        Send ->
            ( model, toJs "ping" )

        Got text ->
            ( text, Cmd.none )


view : Model -> Html Msg
view model =
    div []
        [ button [ onClick Send ] [ text "send" ]
        , text model
        ]

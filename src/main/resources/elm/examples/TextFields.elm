module TextFields exposing (main)

import Browser
import Html exposing (Html, Attribute, div, input, text)
import Html.Attributes exposing (..)
import Html.Events exposing (onInput)


main =
  Browser.sandbox { init = init, update = update, view = view }


type alias Model =
  { content : String
  }


init =
  { content = "" }


type Msg
  = Change String


update msg model =
  case msg of
    Change newContent ->
      { model | content = newContent }


view model =
  div []
    [ input [ placeholder "Text to reverse", value model.content, onInput Change ] []
    , div [] [ text (String.reverse model.content) ]
    ]

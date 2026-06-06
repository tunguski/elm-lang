module Life exposing (main)

-- Conway's Game of Life, with a small control form.
--
-- Use the form to set the grid size (cols × rows), the cell size in pixels, the step interval, and
-- which starting pattern to drop in (a glider, a lightweight spaceship, some oscillators, …). Press
-- Pause to freeze the simulation and Reset to reload the chosen pattern. The grid wraps at the edges.
--
-- Learn more about Conway's Game of Life:
--   https://en.wikipedia.org/wiki/Conway%27s_Game_of_Life

import Browser
import Html exposing (Html, button, div, input, label, option, select, span, text)
import Html.Attributes exposing (style, type_, value)
import Html.Events exposing (onClick, onInput)
import Time



-- MODEL


type alias Model =
  { cols : Int
  , rows : Int
  , cell : Int            -- cell size in pixels
  , stepEvery : Int       -- milliseconds between generations
  , pattern : String      -- key of the starting pattern (see `patterns`)
  , cells : List ( Int, Int )
  , gen : Int
  , running : Bool
  }


defaults : Model
defaults =
  reload
    { cols = 24
    , rows = 16
    , cell = 22
    , stepEvery = 120
    , pattern = "glider"
    , cells = []
    , gen = 0
    , running = True
    }


main =
  Browser.element
    { init = init
    , update = update
    , view = view
    , subscriptions = subscriptions
    }


init _ =
  ( defaults, Cmd.none )


{-| Drop the current pattern onto the grid and reset the generation counter. -}
reload : Model -> Model
reload model =
  { model | cells = setup model.pattern, gen = 0 }


{-| While running, tick one generation every `stepEvery` milliseconds. -}
subscriptions : Model -> Sub Msg
subscriptions model =
  if model.running then
    Time.every (toFloat model.stepEvery) (\_ -> Tick)

  else
    Sub.none



-- UPDATE


type Msg
  = Tick
  | TogglePlay
  | Reset
  | SetPattern String
  | SetCols String
  | SetRows String
  | SetCell String
  | SetStepEvery String


update msg model =
  case msg of
    Tick ->
      ( { model | cells = step model.cols model.rows model.cells, gen = model.gen + 1 }, Cmd.none )

    TogglePlay ->
      ( { model | running = not model.running }, Cmd.none )

    Reset ->
      ( reload model, Cmd.none )

    SetPattern p ->
      ( reload { model | pattern = p }, Cmd.none )

    SetCols s ->
      ( { model | cols = parseInt 3 80 model.cols s }, Cmd.none )

    SetRows s ->
      ( { model | rows = parseInt 3 80 model.rows s }, Cmd.none )

    SetCell s ->
      ( { model | cell = parseInt 4 60 model.cell s }, Cmd.none )

    SetStepEvery s ->
      ( { model | stepEvery = parseInt 20 2000 model.stepEvery s }, Cmd.none )


{-| Parse a form field to an int, clamped to [lo, hi]; keep the old value if it isn't a number. -}
parseInt : Int -> Int -> Int -> String -> Int
parseInt lo hi current s =
  clamp lo hi (Maybe.withDefault current (String.toInt s))



-- STARTING PATTERNS (each a list of (col, row) cells, offset from an origin)


{-| The choices offered by the pattern <select>: (value, label). -}
patterns : List ( String, String )
patterns =
  [ ( "glider", "Glider" )
  , ( "lwss", "Lightweight spaceship" )
  , ( "oscillators", "Oscillators" )
  , ( "pulsar", "Pulsar" )
  , ( "rpentomino", "R-pentomino" )
  ]


setup : String -> List ( Int, Int )
setup pattern =
  if pattern == "lwss" then
    lwss 3 5

  else if pattern == "oscillators" then
    blinker 4 4 ++ toad 13 4 ++ beacon 5 10

  else if pattern == "pulsar" then
    pulsar 5 1

  else if pattern == "rpentomino" then
    rPentomino 11 7

  else
    glider 1 1


at ox oy coords =
  List.map (\( x, y ) -> ( ox + x, oy + y )) coords


glider ox oy =
  at ox oy [ ( 1, 0 ), ( 2, 1 ), ( 0, 2 ), ( 1, 2 ), ( 2, 2 ) ]


lwss ox oy =
  at ox oy [ ( 1, 0 ), ( 2, 0 ), ( 3, 0 ), ( 4, 0 ), ( 0, 1 ), ( 4, 1 ), ( 4, 2 ), ( 0, 3 ), ( 3, 3 ) ]


blinker ox oy =
  at ox oy [ ( 0, 0 ), ( 1, 0 ), ( 2, 0 ) ]


toad ox oy =
  at ox oy [ ( 1, 0 ), ( 2, 0 ), ( 3, 0 ), ( 0, 1 ), ( 1, 1 ), ( 2, 1 ) ]


beacon ox oy =
  at ox oy [ ( 0, 0 ), ( 1, 0 ), ( 0, 1 ), ( 3, 2 ), ( 2, 3 ), ( 3, 3 ) ]


rPentomino ox oy =
  at ox oy [ ( 1, 0 ), ( 2, 0 ), ( 0, 1 ), ( 1, 1 ), ( 1, 2 ) ]


pulsar ox oy =
  at ox oy
    [ ( 2, 0 ), ( 3, 0 ), ( 4, 0 ), ( 8, 0 ), ( 9, 0 ), ( 10, 0 )
    , ( 0, 2 ), ( 5, 2 ), ( 7, 2 ), ( 12, 2 )
    , ( 0, 3 ), ( 5, 3 ), ( 7, 3 ), ( 12, 3 )
    , ( 0, 4 ), ( 5, 4 ), ( 7, 4 ), ( 12, 4 )
    , ( 2, 5 ), ( 3, 5 ), ( 4, 5 ), ( 8, 5 ), ( 9, 5 ), ( 10, 5 )
    , ( 2, 7 ), ( 3, 7 ), ( 4, 7 ), ( 8, 7 ), ( 9, 7 ), ( 10, 7 )
    , ( 0, 8 ), ( 5, 8 ), ( 7, 8 ), ( 12, 8 )
    , ( 0, 9 ), ( 5, 9 ), ( 7, 9 ), ( 12, 9 )
    , ( 0, 10 ), ( 5, 10 ), ( 7, 10 ), ( 12, 10 )
    , ( 2, 12 ), ( 3, 12 ), ( 4, 12 ), ( 8, 12 ), ( 9, 12 ), ( 10, 12 )
    ]



-- ONE CONWAY STEP ON THE WRAPPED GRID


step cols rows cells =
  List.filter (\c -> survives cols rows c cells) (allCells cols rows)


allCells cols rows =
  List.concatMap
    (\y -> List.map (\x -> ( x, y )) (List.range 0 (cols - 1)))
    (List.range 0 (rows - 1))


survives cols rows ( x, y ) cells =
  let
    n =
      neighbors cols rows x y cells
  in
  if List.member ( x, y ) cells then
    n == 2 || n == 3

  else
    n == 3


neighbors cols rows x y cells =
  List.length
    (List.filter
      (\( dx, dy ) -> List.member ( wrap cols (x + dx), wrap rows (y + dy) ) cells)
      deltas
    )


deltas =
  [ ( -1, -1 ), ( 0, -1 ), ( 1, -1 ), ( -1, 0 ), ( 1, 0 ), ( -1, 1 ), ( 0, 1 ), ( 1, 1 ) ]


wrap m v =
  modBy m (v + m)



-- VIEW


view : Model -> Html Msg
view model =
  div
    [ style "font-family" "system-ui, -apple-system, Segoe UI, sans-serif"
    , style "color" "#e6edf3"
    , style "background" "#0b1020"
    , style "padding" "16px"
    ]
    [ controls model
    , grid model
    ]


controls : Model -> Html Msg
controls model =
  div
    [ style "display" "flex"
    , style "flex-wrap" "wrap"
    , style "gap" "12px"
    , style "align-items" "flex-end"
    , style "margin-bottom" "12px"
    ]
    [ numberField "Cols" model.cols SetCols
    , numberField "Rows" model.rows SetRows
    , numberField "Cell (px)" model.cell SetCell
    , numberField "Step every (ms)" model.stepEvery SetStepEvery
    , patternField
    , button (onClick TogglePlay :: btnAttrs)
        [ text
            (if model.running then
              "Pause"

             else
              "Play"
            )
        ]
    , button (onClick Reset :: btnAttrs) [ text "Reset" ]
    , span [ style "opacity" "0.7", style "font-size" "12px" ]
        [ text ("generation " ++ String.fromInt model.gen) ]
    ]


numberField : String -> Int -> (String -> Msg) -> Html Msg
numberField name v toMsg =
  label fieldAttrs
    [ span labelAttrs [ text name ]
    , input ([ type_ "number", value (String.fromInt v), onInput toMsg, style "width" "84px" ] ++ inputAttrs) []
    ]


patternField : Html Msg
patternField =
  label fieldAttrs
    [ span labelAttrs [ text "Pattern" ]
    , select (onInput SetPattern :: style "width" "190px" :: inputAttrs)
        (List.map patternOption patterns)
    ]


patternOption : ( String, String ) -> Html Msg
patternOption ( val, lbl ) =
  option [ value val ] [ text lbl ]


grid : Model -> Html Msg
grid model =
  div
    [ style "position" "relative"
    , style "width" (px (model.cols * model.cell))
    , style "height" (px (model.rows * model.cell))
    , style "background" "#0c0c1c"
    , style "overflow" "hidden"
    , style "border" "1px solid #2a3550"
    , style "border-radius" "6px"
    ]
    (List.map (cellView model.cell) model.cells)


cellView : Int -> ( Int, Int ) -> Html Msg
cellView cell ( x, y ) =
  div
    [ style "position" "absolute"
    , style "left" (px (x * cell))
    , style "top" (px (y * cell))
    , style "width" (px (cell - 1))
    , style "height" (px (cell - 1))
    , style "background" "#50dc8c"
    ]
    []


px : Int -> String
px n =
  String.fromInt n ++ "px"



-- STYLES (inline, since a gallery example has no stylesheet)


fieldAttrs : List (Html.Attribute msg)
fieldAttrs =
  [ style "display" "flex", style "flex-direction" "column", style "gap" "3px", style "font-size" "12px" ]


labelAttrs : List (Html.Attribute msg)
labelAttrs =
  [ style "opacity" "0.7" ]


inputAttrs : List (Html.Attribute msg)
inputAttrs =
  [ style "padding" "5px 7px"
  , style "border-radius" "5px"
  , style "border" "1px solid #2a3550"
  , style "background" "#0f1730"
  , style "color" "#e6edf3"
  ]


btnAttrs : List (Html.Attribute msg)
btnAttrs =
  [ style "padding" "7px 14px"
  , style "border-radius" "6px"
  , style "border" "1px solid #2a3550"
  , style "background" "#1b2747"
  , style "color" "#e6edf3"
  , style "cursor" "pointer"
  ]

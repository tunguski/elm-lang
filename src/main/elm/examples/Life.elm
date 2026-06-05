module Life exposing (main)

-- Conway's Game of Life, in the elm-playground style.
--
-- Press a number key to load a starting pattern:
--   1  Glider        2  Oscillators
--   3  Pulsar        4  R-pentomino
-- Hold SPACE to pause. The grid wraps around at the edges.
--
-- Learn more about the playground here:
--   https://package.elm-lang.org/packages/evancz/elm-playground/latest/

import Playground exposing (..)


cols =
  24


rows =
  16


cell =
  22


stepEvery =
  5


main =
  game view update (load 1)


load n =
  { cells = setup n, gen = 0, sub = 0 }


setup n =
  if n == 2 then
    blinker 4 4 ++ toad 13 4 ++ beacon 5 10

  else if n == 3 then
    pulsar 5 1

  else if n == 4 then
    rPentomino 11 7

  else
    glider 1 1



-- PATTERNS (each a list of (col, row) cells, offset from an origin)


at ox oy coords =
  List.map (\( x, y ) -> ( ox + x, oy + y )) coords


glider ox oy =
  at ox oy [ ( 1, 0 ), ( 2, 1 ), ( 0, 2 ), ( 1, 2 ), ( 2, 2 ) ]


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


step cells =
  List.filter (\c -> survives c cells) allCells


allCells =
  List.concatMap
    (\y -> List.map (\x -> ( x, y )) (List.range 0 (cols - 1)))
    (List.range 0 (rows - 1))


survives ( x, y ) cells =
  let
    n =
      neighbors x y cells
  in
  if List.member ( x, y ) cells then
    n == 2 || n == 3

  else
    n == 3


neighbors x y cells =
  List.length
    (List.filter
      (\( dx, dy ) -> List.member ( wrap cols (x + dx), wrap rows (y + dy) ) cells)
      deltas
    )


deltas =
  [ ( -1, -1 ), ( 0, -1 ), ( 1, -1 ), ( -1, 0 ), ( 1, 0 ), ( -1, 1 ), ( 0, 1 ), ( 1, 1 ) ]


wrap m v =
  modBy m (v + m)



-- UPDATE: number keys load a pattern, SPACE pauses, otherwise step every few frames


update computer mem =
  if List.member "1" computer.keyboard.keys then
    load 1

  else if List.member "2" computer.keyboard.keys then
    load 2

  else if List.member "3" computer.keyboard.keys then
    load 3

  else if List.member "4" computer.keyboard.keys then
    load 4

  else if computer.keyboard.space then
    mem

  else if mem.sub + 1 >= stepEvery then
    { mem | cells = step mem.cells, gen = mem.gen + 1, sub = 0 }

  else
    { mem | sub = mem.sub + 1 }



-- VIEW


view computer mem =
  rectangle (rgb 12 12 28) computer.screen.width computer.screen.height
    :: List.map cellShape mem.cells


cellShape ( x, y ) =
  square (rgb 80 220 140) cell
    |> move (px x) (py y)


px x =
  toFloat x * cell - toFloat cols * cell / 2 + cell / 2


py y =
  toFloat rows * cell / 2 - toFloat y * cell - cell / 2

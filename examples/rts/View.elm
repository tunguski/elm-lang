module RTS.View exposing (view)

{-| Renders the RTS `Model`: the tile map (with fog of war), buildings and units as SVG, and a side
HUD (resources, build/train buttons, a legend and the current message) as HTML. Pure — it only reads
the model and emits `Html Msg`.
-}

import Html exposing (Html, button, div, span, text)
import Html.Attributes as HA
import Html.Events exposing (onClick)
import RTS.Model exposing (..)
import Svg exposing (circle, rect, svg)
import Svg.Attributes as SA


view : Model -> Html Msg
view model =
    div
        [ HA.style "display" "flex"
        , HA.style "gap" "16px"
        , HA.style "font-family" "system-ui, -apple-system, Segoe UI, sans-serif"
        , HA.style "padding" "16px"
        , HA.style "background" "#0f172a"
        , HA.style "color" "#e2e8f0"
        , HA.style "min-height" "100vh"
        ]
        [ mapView model
        , hud model
        ]



-- THE MAP ----------------------------------------------------------------------------------------


mapView : Model -> Html Msg
mapView model =
    svg
        [ SA.width (px (mapWidth * tileSize))
        , SA.height (px (mapHeight * tileSize))
        , HA.style "background" "#020617"
        , HA.style "border-radius" "8px"
        , HA.style "box-shadow" "0 6px 20px rgba(0,0,0,0.4)"
        ]
        (List.map tileRect model.map
            ++ List.map buildingRect model.buildings
            ++ List.map (unitCircle model) model.units
        )


tileRect : Tile -> Html Msg
tileRect t =
    rect
        [ SA.x (px (t.x * tileSize))
        , SA.y (px (t.y * tileSize))
        , SA.width (px tileSize)
        , SA.height (px tileSize)
        , SA.fill
            (if t.visible then
                terrainColor t.terrain

             else
                "#0b1220"
            )
        , SA.stroke "#0b1220"
        , SA.strokeWidth "1"
        , onClick (ClickTile t.x t.y)
        ]
        []


buildingRect : Building -> Html Msg
buildingRect b =
    rect
        [ SA.x (px (b.x * tileSize + 3))
        , SA.y (px (b.y * tileSize + 3))
        , SA.width (px (tileSize - 6))
        , SA.height (px (tileSize - 6))
        , SA.rx "4"
        , SA.fill (buildingColor b.kind)
        , SA.stroke "#e2e8f0"
        , SA.strokeWidth "2"
        , onClick (ClickTile b.x b.y)
        ]
        []


unitCircle : Model -> Unit -> Html Msg
unitCircle model u =
    circle
        [ SA.cx (px (round (u.x * toFloat tileSize + toFloat tileSize / 2)))
        , SA.cy (px (round (u.y * toFloat tileSize + toFloat tileSize / 2)))
        , SA.r (px (tileSize // 3))
        , SA.fill (unitColor u.kind)
        , SA.stroke
            (if isSelected model u then
                "#fde047"

             else
                "#0b1220"
            )
        , SA.strokeWidth
            (if isSelected model u then
                "3"

             else
                "1"
            )
        , onClick (SelectUnit u.id)
        ]
        []



-- THE HUD ----------------------------------------------------------------------------------------


hud : Model -> Html Msg
hud model =
    div [ HA.style "min-width" "240px", HA.style "display" "flex", HA.style "flex-direction" "column", HA.style "gap" "8px" ]
        [ div [ HA.style "font-size" "20px", HA.style "font-weight" "700" ] [ text "RTS Mini" ]
        , resourceRow "Gold" model.gold "#facc15"
        , resourceRow "Wood" model.wood "#84cc16"
        , div [ HA.style "color" "#94a3b8", HA.style "font-size" "12px" ] [ text ("Tick " ++ String.fromInt model.tick) ]
        , divider
        , btn "Train Worker (50g)" TrainWorker
        , btn "Train Soldier (60g)" TrainSoldier
        , btn "Build Barracks (120g)" StartBarracks
        , btn "Build Farm (120g)" StartFarm
        , btn "Cancel / Deselect" Cancel
        , divider
        , div [ HA.style "font-size" "13px" ] [ text (modeLine model) ]
        , div
            [ HA.style "min-height" "34px"
            , HA.style "font-size" "13px"
            , HA.style "color"
                (if model.explored then
                    "#4ade80"

                 else
                    "#cbd5e1"
                )
            , HA.style "font-weight"
                (if model.explored then
                    "700"

                 else
                    "400"
                )
            ]
            [ text model.message ]
        , divider
        , div [ HA.style "font-weight" "600", HA.style "color" "#cbd5e1", HA.style "font-size" "12px" ] [ text "Minimap" ]
        , minimap model
        , divider
        , legend
        ]


{-| A small overview: explored terrain in colour, fog dark, buildings and units as dots. -}
minimap : Model -> Html Msg
minimap model =
    let
        scale =
            10
    in
    svg
        [ SA.width (px (mapWidth * scale))
        , SA.height (px (mapHeight * scale))
        , HA.style "background" "#020617"
        , HA.style "border-radius" "4px"
        ]
        (List.map (miniTile scale) model.map
            ++ List.map (miniDot scale "#e2e8f0") (List.map buildingPos model.buildings)
            ++ List.map (miniDot scale "#fde047") (List.map unitPos model.units)
        )


miniTile : Int -> Tile -> Html Msg
miniTile scale t =
    rect
        [ SA.x (px (t.x * scale))
        , SA.y (px (t.y * scale))
        , SA.width (px scale)
        , SA.height (px scale)
        , SA.fill
            (if t.visible then
                terrainColor t.terrain

             else
                "#0b1220"
            )
        ]
        []


miniDot : Int -> String -> ( Int, Int ) -> Html Msg
miniDot scale color ( x, y ) =
    circle
        [ SA.cx (px (x * scale + scale // 2))
        , SA.cy (px (y * scale + scale // 2))
        , SA.r (px (scale // 2))
        , SA.fill color
        ]
        []


buildingPos : Building -> ( Int, Int )
buildingPos b =
    ( b.x, b.y )


unitPos : Unit -> ( Int, Int )
unitPos u =
    ( round u.x, round u.y )


resourceRow : String -> Int -> String -> Html Msg
resourceRow label amount color =
    div [ HA.style "display" "flex", HA.style "justify-content" "space-between", HA.style "font-size" "15px" ]
        [ span [ HA.style "color" color, HA.style "font-weight" "600" ] [ text label ]
        , span [] [ text (String.fromInt amount) ]
        ]


btn : String -> Msg -> Html Msg
btn label msg =
    button
        [ onClick msg
        , HA.style "padding" "8px 10px"
        , HA.style "border" "none"
        , HA.style "border-radius" "6px"
        , HA.style "background" "#1e293b"
        , HA.style "color" "#e2e8f0"
        , HA.style "cursor" "pointer"
        , HA.style "text-align" "left"
        , HA.style "font-size" "13px"
        ]
        [ text label ]


legend : Html Msg
legend =
    div [ HA.style "font-size" "12px", HA.style "color" "#94a3b8", HA.style "display" "flex", HA.style "flex-direction" "column", HA.style "gap" "3px" ]
        [ div [ HA.style "font-weight" "600", HA.style "color" "#cbd5e1" ] [ text "Legend" ]
        , swatch (terrainColor Grass) "Grass"
        , swatch (terrainColor Forest) "Forest (wood)"
        , swatch (terrainColor GoldMine) "Gold mine (gold)"
        , swatch (terrainColor Water) "Water (impassable)"
        , swatch (buildingColor Base) "Base — trains workers"
        , swatch (unitColor Worker) "Worker — gathers on resources"
        , swatch (unitColor Soldier) "Soldier"
        ]


swatch : String -> String -> Html Msg
swatch color label =
    div [ HA.style "display" "flex", HA.style "align-items" "center", HA.style "gap" "6px" ]
        [ span
            [ HA.style "width" "12px"
            , HA.style "height" "12px"
            , HA.style "border-radius" "3px"
            , HA.style "display" "inline-block"
            , HA.style "background" color
            ]
            []
        , text label
        ]


divider : Html Msg
divider =
    div [ HA.style "height" "1px", HA.style "background" "#1e293b", HA.style "margin" "4px 0" ] []



-- HELPERS ----------------------------------------------------------------------------------------


modeLine : Model -> String
modeLine model =
    case model.mode of
        Placing kind ->
            "Placing: " ++ buildingLabel kind ++ " — click a tile"

        Normal ->
            case model.selected of
                Just _ ->
                    "A unit is selected — click a tile to move it"

                Nothing ->
                    "Click a unit to select it"


isSelected : Model -> Unit -> Bool
isSelected model u =
    case model.selected of
        Just id ->
            id == u.id

        Nothing ->
            False


unitColor : UnitKind -> String
unitColor kind =
    case kind of
        Worker ->
            "#fcd34d"

        Soldier ->
            "#f87171"


px : Int -> String
px n =
    String.fromInt n

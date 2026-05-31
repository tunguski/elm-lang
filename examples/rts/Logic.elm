module RTS.Logic exposing (init, update)

{-| Game rules for the RTS: the initial world, and a pure `update` that advances it for each message
(including the real-time `Tick`). No rendering and no effects — `RTS.Main` wraps this in a
`Browser.element` and `RTS.View` draws the resulting `Model`.
-}

import RTS.Model exposing (..)


{-| The starting world: a generated map, a base with one worker, and some gold to spend. -}
init : Model
init =
    let
        tiles =
            List.concatMap
                (\y -> List.map (\x -> { x = x, y = y, terrain = terrainAt x y, visible = False }) (List.range 0 (mapWidth - 1)))
                (List.range 0 (mapHeight - 1))

        base =
            { x = 2, y = 2, kind = Base }

        worker =
            { id = 1, x = 2, y = 3, tx = 2, ty = 3, kind = Worker }

        start =
            { map = tiles
            , units = [ worker ]
            , buildings = [ base ]
            , gold = 150
            , wood = 0
            , selected = Just 1
            , nextId = 2
            , mode = Normal
            , tick = 0
            , explored = False
            , message = "Send workers onto gold/forest to gather; explore the whole map to win."
            }
    in
    { start | map = revealAround start }


{-| Deterministic terrain so the map is interesting without needing randomness. -}
terrainAt : Int -> Int -> Terrain
terrainAt x y =
    if x >= 8 && x <= 10 && y >= 4 && y <= 7 then
        Water

    else if (x == 14 && y == 2) || (x == 16 && y == 9) || (x == 5 && y == 11) then
        GoldMine

    else if modBy 7 (x * 3 + y * 5) == 0 && (x > 3 || y > 3) then
        Rock

    else if modBy 3 (x + y * 2) == 0 && (x > 4 || y > 4) then
        Forest

    else
        Grass


update : Msg -> Model -> Model
update msg model =
    case msg of
        Tick ->
            tickGame model

        SelectUnit id ->
            { model | selected = Just id, message = "Unit selected — click a tile to move it." }

        ClickTile x y ->
            clickTile x y model

        TrainWorker ->
            trainWorker model

        TrainSoldier ->
            trainSoldier model

        StartBarracks ->
            startPlacing Barracks model

        StartFarm ->
            startPlacing Farm model

        Cancel ->
            { model | mode = Normal, selected = Nothing, message = "" }



-- THE REAL-TIME STEP -----------------------------------------------------------------------------


tickGame : Model -> Model
tickGame model =
    let
        moved =
            List.map moveUnit model.units

        isIncomeTick =
            modBy 5 model.tick == 0

        gained =
            { model
                | units = moved
                , gold = model.gold + (if isIncomeTick then countWorkersOn isGold model else 0)
                , wood = model.wood + (if isIncomeTick then countWorkersOn isForest model else 0)
                , tick = model.tick + 1
            }

        revealed =
            revealAround gained

        allVisible =
            List.all (\t -> t.visible) revealed
    in
    { gained
        | map = revealed
        , explored = allVisible
        , message =
            if allVisible && not model.explored then
                "Victory — the whole map is explored!"

            else
                gained.message
    }


{-| Moves a unit a small step toward its target tile (straight line; obstacles are avoided by simply
not letting the player target impassable tiles). -}
moveUnit : Unit -> Unit
moveUnit u =
    let
        dx =
            u.tx - u.x

        dy =
            u.ty - u.y

        dist =
            sqrt (dx * dx + dy * dy)

        speed =
            0.2
    in
    if dist <= speed then
        { u | x = u.tx, y = u.ty }

    else
        { u | x = u.x + speed * dx / dist, y = u.y + speed * dy / dist }


{-| Clears the fog around every unit and building (Chebyshev distance ≤ `revealRadius`). -}
revealAround : Model -> List Tile
revealAround model =
    let
        sources =
            List.map (\b -> ( b.x, b.y )) model.buildings
                ++ List.map (\u -> ( round u.x, round u.y )) model.units

        near t ( sx, sy ) =
            abs (t.x - sx) <= revealRadius && abs (t.y - sy) <= revealRadius
    in
    List.map
        (\t ->
            if t.visible || List.any (near t) sources then
                { t | visible = True }

            else
                t
        )
        model.map



-- CLICKS & COMMANDS ------------------------------------------------------------------------------


clickTile : Int -> Int -> Model -> Model
clickTile x y model =
    case model.mode of
        Placing kind ->
            placeBuilding kind x y model

        Normal ->
            case model.selected of
                Just id ->
                    if passableAt x y model then
                        { model
                            | units =
                                List.map
                                    (\u ->
                                        if u.id == id then
                                            { u | tx = toFloat x, ty = toFloat y }

                                        else
                                            u
                                    )
                                    model.units
                            , message = ""
                        }

                    else
                        { model | message = "That tile is impassable." }

                Nothing ->
                    { model | message = "Select a unit first." }


placeBuilding : BuildingKind -> Int -> Int -> Model -> Model
placeBuilding kind x y model =
    if model.gold < barracksCost then
        { model | mode = Normal, message = "Not enough gold to build." }

    else if not (passableAt x y model) || occupied x y model then
        { model | message = "Pick a clear, passable tile." }

    else
        { model
            | buildings = { x = x, y = y, kind = kind } :: model.buildings
            , gold = model.gold - barracksCost
            , mode = Normal
            , message = buildingLabel kind ++ " built."
        }


startPlacing : BuildingKind -> Model -> Model
startPlacing kind model =
    if model.gold < barracksCost then
        { model | message = "Need " ++ String.fromInt barracksCost ++ " gold for a " ++ buildingLabel kind ++ "." }

    else
        { model | mode = Placing kind, message = "Click a clear tile to place the " ++ buildingLabel kind ++ "." }


trainWorker : Model -> Model
trainWorker model =
    spawnUnit Worker workerCost isBase "Worker" model


trainSoldier : Model -> Model
trainSoldier model =
    spawnUnit Soldier soldierCost isBarracks "Soldier" model


{-| Spawns a unit next to the first building matching `atBuilding`, if affordable. -}
spawnUnit : UnitKind -> Int -> (BuildingKind -> Bool) -> String -> Model -> Model
spawnUnit kind cost atBuilding name model =
    case findBuilding atBuilding model of
        Nothing ->
            { model | message = "Build the right structure first to train a " ++ name ++ "." }

        Just ( bx, by ) ->
            if model.gold < cost then
                { model | message = "Need " ++ String.fromInt cost ++ " gold for a " ++ name ++ "." }

            else
                let
                    sx =
                        toFloat bx

                    sy =
                        toFloat (by + 1)
                in
                { model
                    | units = { id = model.nextId, x = sx, y = sy, tx = sx, ty = sy, kind = kind } :: model.units
                    , gold = model.gold - cost
                    , nextId = model.nextId + 1
                    , selected = Just model.nextId
                    , message = name ++ " trained."
                }



-- QUERIES ----------------------------------------------------------------------------------------


passableAt : Int -> Int -> Model -> Bool
passableAt x y model =
    case lookupTerrain x y model of
        Just Water ->
            False

        Just Rock ->
            False

        Just _ ->
            True

        Nothing ->
            False


occupied : Int -> Int -> Model -> Bool
occupied x y model =
    List.any (\b -> b.x == x && b.y == y) model.buildings


lookupTerrain : Int -> Int -> Model -> Maybe Terrain
lookupTerrain x y model =
    List.head
        (List.filterMap
            (\t ->
                if t.x == x && t.y == y then
                    Just t.terrain

                else
                    Nothing
            )
            model.map
        )


countWorkersOn : (Terrain -> Bool) -> Model -> Int
countWorkersOn isKind model =
    List.length
        (List.filter
            (\u ->
                case lookupTerrain (round u.x) (round u.y) model of
                    Just terrain ->
                        isWorker u.kind && isKind terrain

                    Nothing ->
                        False
            )
            model.units
        )


findBuilding : (BuildingKind -> Bool) -> Model -> Maybe ( Int, Int )
findBuilding pred model =
    List.head
        (List.filterMap
            (\b ->
                if pred b.kind then
                    Just ( b.x, b.y )

                else
                    Nothing
            )
            model.buildings
        )



-- Custom-type predicates (kept as case-matches rather than `==` on tagged values) -----------------


isGold : Terrain -> Bool
isGold t =
    case t of
        GoldMine ->
            True

        _ ->
            False


isForest : Terrain -> Bool
isForest t =
    case t of
        Forest ->
            True

        _ ->
            False


isWorker : UnitKind -> Bool
isWorker k =
    case k of
        Worker ->
            True

        _ ->
            False


isBase : BuildingKind -> Bool
isBase k =
    case k of
        Base ->
            True

        _ ->
            False


isBarracks : BuildingKind -> Bool
isBarracks k =
    case k of
        Barracks ->
            True

        _ ->
            False

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
            { id = 1, x = 2, y = 3, tx = 2, ty = 3, kind = Worker, carrying = 0, assigned = Nothing }

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
            , status = Playing
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
    case model.status of
        Playing ->
            tickPlaying model

        _ ->
            model


tickPlaying : Model -> Model
tickPlaying model =
    let
        moved =
            List.map (moveUnit model) model.units

        ( workedUnits, goldGain, woodGain ) =
            List.foldr (gatherStep model) ( [], 0, 0 ) moved

        gained =
            { model
                | units = workedUnits
                , gold = model.gold + goldGain
                , wood = model.wood + woodGain
                , tick = model.tick + 1
            }

        revealed =
            revealAround gained

        allVisible =
            List.all (\t -> t.visible) revealed

        status =
            if allVisible then
                Won

            else if gained.tick >= tickLimit then
                Lost

            else
                Playing
    in
    { gained
        | map = revealed
        , status = status
        , message =
            case status of
                Won ->
                    "Victory — the whole map is explored!"

                Lost ->
                    "Out of time — explore faster next run!"

                Playing ->
                    gained.message
    }


{-| Moves a unit a small step toward its target. While it's still in a different tile from the goal
it heads toward the next tile on a breadth-first path that routes *around* impassable water/rock;
once in the goal tile it homes in on the exact target point. -}
moveUnit : Model -> Unit -> Unit
moveUnit model u =
    let
        dist =
            distance u.x u.y u.tx u.ty
    in
    if dist <= speed then
        { u | x = u.tx, y = u.ty }

    else
        let
            cur =
                ( round u.x, round u.y )

            goal =
                ( round u.tx, round u.ty )

            ( gx, gy ) =
                if cur == goal then
                    ( u.tx, u.ty )

                else
                    case nextStep model cur goal of
                        Just ( sx, sy ) ->
                            ( toFloat sx, toFloat sy )

                        Nothing ->
                            ( u.x, u.y )

            step =
                distance u.x u.y gx gy
        in
        if step <= speed then
            { u | x = gx, y = gy }

        else
            { u | x = u.x + speed * (gx - u.x) / step, y = u.y + speed * (gy - u.y) / step }


speed : Float
speed =
    0.2


distance : Float -> Float -> Float -> Float -> Float
distance ax ay bx by =
    sqrt ((bx - ax) * (bx - ax) + (by - ay) * (by - ay))


{-| The next tile to step to on a shortest passable path from `start` to `goal` (4-directional BFS),
or `Nothing` if no path exists. -}
nextStep : Model -> ( Int, Int ) -> ( Int, Int ) -> Maybe ( Int, Int )
nextStep model start goal =
    if start == goal then
        Just goal

    else
        let
            ring =
                passableNeighbors model goal start
        in
        bfs model goal (List.map (\n -> ( n, n )) ring) (start :: ring)


bfs : Model -> ( Int, Int ) -> List ( ( Int, Int ), ( Int, Int ) ) -> List ( Int, Int ) -> Maybe ( Int, Int )
bfs model goal queue visited =
    case queue of
        [] ->
            Nothing

        ( tile, first ) :: rest ->
            if tile == goal then
                Just first

            else
                let
                    ns =
                        List.filter (\n -> not (List.member n visited)) (passableNeighbors model goal tile)
                in
                bfs model goal (rest ++ List.map (\n -> ( n, first )) ns) (visited ++ ns)


{-| In-bounds 4-neighbours that are passable (the `goal` is always allowed, so a worker can step onto
a resource tile even though it's the destination). -}
passableNeighbors : Model -> ( Int, Int ) -> ( Int, Int ) -> List ( Int, Int )
passableNeighbors model goal ( x, y ) =
    List.filter
        (\( nx, ny ) ->
            nx >= 0 && nx < mapWidth && ny >= 0 && ny < mapHeight && (( nx, ny ) == goal || passableAt nx ny model)
        )
        [ ( x + 1, y ), ( x - 1, y ), ( x, y + 1 ), ( x, y - 1 ) ]


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
                                            retarget x y model u

                                        else
                                            u
                                    )
                                    model.units
                            , message =
                                if isResourceAt x y model then
                                    "Worker assigned to gather — it will haul loads back to the base."

                                else
                                    ""
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
                    | units = { id = model.nextId, x = sx, y = sy, tx = sx, ty = sy, kind = kind, carrying = 0, assigned = Nothing } :: model.units
                    , gold = model.gold - cost
                    , nextId = model.nextId + 1
                    , selected = Just model.nextId
                    , message = name ++ " trained."
                }



-- QUERIES ----------------------------------------------------------------------------------------


{-| Points a unit at a tile; a worker sent to a resource tile is `assigned` to gather it, while a
worker sent elsewhere (or any soldier) just walks there. -}
retarget : Int -> Int -> Model -> Unit -> Unit
retarget x y model u =
    case u.kind of
        Worker ->
            { u
                | tx = toFloat x
                , ty = toFloat y
                , assigned =
                    if isResourceAt x y model then
                        Just ( x, y )

                    else
                        Nothing
            }

        Soldier ->
            { u | tx = toFloat x, ty = toFloat y }


isResourceAt : Int -> Int -> Model -> Bool
isResourceAt x y model =
    case lookupTerrain x y model of
        Just terrain ->
            isGold terrain || isForest terrain

        Nothing ->
            False


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


{-| Advances a single unit's gather loop, threading the gold/wood deposited this tick:

  - on the base with a load: deposit it (into gold or wood, per the assigned resource) and head back;
  - full: walk to the base to unload;
  - standing on its assigned resource: keep filling up;
  - otherwise: nothing.

Soldiers pass through unchanged. -}
gatherStep : Model -> Unit -> ( List Unit, Int, Int ) -> ( List Unit, Int, Int )
gatherStep model u ( us, g, w ) =
    case u.kind of
        Soldier ->
            ( u :: us, g, w )

        Worker ->
            if atBase u model && u.carrying > 0 then
                let
                    ( dg, dw ) =
                        deposit u model
                in
                ( resume u :: us, g + dg, w + dw )

            else if u.carrying >= carryCap then
                ( sendHome u model :: us, g, w )

            else if onAssignedResource u model then
                ( { u | carrying = min carryCap (u.carrying + gatherRate) } :: us, g, w )

            else
                ( u :: us, g, w )


{-| A worker's load as (gold, wood): gold from a mine, wood from a forest (gold by default). -}
deposit : Unit -> Model -> ( Int, Int )
deposit u model =
    case u.assigned of
        Just ( ax, ay ) ->
            if isForestAt ax ay model then
                ( 0, u.carrying )

            else
                ( u.carrying, 0 )

        Nothing ->
            ( u.carrying, 0 )


isForestAt : Int -> Int -> Model -> Bool
isForestAt x y model =
    case lookupTerrain x y model of
        Just terrain ->
            isForest terrain

        Nothing ->
            False


{-| Clears the load and walks back to the assigned resource (or waits if none). -}
resume : Unit -> Unit
resume u =
    case u.assigned of
        Just ( ax, ay ) ->
            { u | carrying = 0, tx = toFloat ax, ty = toFloat ay }

        Nothing ->
            { u | carrying = 0 }


{-| Targets the base so a full worker returns to unload. -}
sendHome : Unit -> Model -> Unit
sendHome u model =
    case findBuilding isBase model of
        Just ( bx, by ) ->
            { u | tx = toFloat bx, ty = toFloat (by + 1) }

        Nothing ->
            u


atBase : Unit -> Model -> Bool
atBase u model =
    case findBuilding isBase model of
        Just ( bx, by ) ->
            abs (round u.x - bx) <= 1 && abs (round u.y - by) <= 1

        Nothing ->
            False


onAssignedResource : Unit -> Model -> Bool
onAssignedResource u model =
    case u.assigned of
        Just ( ax, ay ) ->
            round u.x == ax && round u.y == ay

        Nothing ->
            False


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

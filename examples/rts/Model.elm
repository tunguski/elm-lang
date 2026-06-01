module RTS.Model exposing
    ( Model
    , Msg(..)
    , Tile
    , Terrain(..)
    , Unit
    , UnitKind(..)
    , Building
    , BuildingKind(..)
    , Mode(..)
    , Status(..)
    , mapWidth
    , mapHeight
    , tickLimit
    , tileSize
    , revealRadius
    , workerCost
    , barracksCost
    , soldierCost
    , carryCap
    , gatherRate
    , buildingLabel
    , buildingColor
    , terrainColor
    )

{-| Data model for the little real-time strategy game: the tile map, units, buildings, resources and
the UI mode, plus the message type and a handful of shared constants. Pure data only — game rules
live in `RTS.Logic` and rendering in `RTS.View`.
-}


{-| The whole game state. The map is a flat list of tiles (row-major); units and buildings are lists
keyed by their integer ids / grid positions. -}
type alias Model =
    { map : List Tile
    , units : List Unit
    , buildings : List Building
    , gold : Int
    , wood : Int
    , selected : Maybe Int
    , nextId : Int
    , mode : Mode
    , tick : Int
    , status : Status
    , message : String
    }


{-| How the game is going: still playing, won by exploring the whole map, or lost by running out of
time before doing so. -}
type Status
    = Playing
    | Won
    | Lost


{-| What a click on the map should do. -}
type Mode
    = Normal
    | Placing BuildingKind


type Msg
    = Tick
    | ClickTile Int Int
    | SelectUnit Int
    | TrainWorker
    | TrainSoldier
    | StartBarracks
    | StartFarm
    | Cancel


{-| One map cell. `visible` flips from `False` (fog) to `True` once a unit/building reveals it. -}
type alias Tile =
    { x : Int
    , y : Int
    , terrain : Terrain
    , visible : Bool
    }


type Terrain
    = Grass
    | Forest
    | GoldMine
    | Water
    | Rock


{-| A movable unit. `tx`/`ty` is the move target (in tile coordinates). A worker `assigned` to a
resource tile runs a gather loop: walk there, fill up to `carryCap`, haul the load back to the base,
deposit it and return for more. -}
type alias Unit =
    { id : Int
    , x : Float
    , y : Float
    , tx : Float
    , ty : Float
    , kind : UnitKind
    , carrying : Int
    , assigned : Maybe ( Int, Int )
    }


type UnitKind
    = Worker
    | Soldier


type alias Building =
    { x : Int
    , y : Int
    , kind : BuildingKind
    }


type BuildingKind
    = Base
    | Barracks
    | Farm


mapWidth : Int
mapWidth =
    20


mapHeight : Int
mapHeight =
    14


tileSize : Int
tileSize =
    32


{-| Reveal the whole map within this many ticks to win; exceed it and the game is lost. -}
tickLimit : Int
tickLimit =
    600


{-| How far (in tiles, Chebyshev distance) a unit or building clears the fog. -}
revealRadius : Int
revealRadius =
    3


workerCost : Int
workerCost =
    50


barracksCost : Int
barracksCost =
    120


soldierCost : Int
soldierCost =
    60


{-| How much a worker can carry before it must return to base to deposit. -}
carryCap : Int
carryCap =
    10


{-| How much a worker gathers per tick while standing on its assigned resource. -}
gatherRate : Int
gatherRate =
    2


buildingLabel : BuildingKind -> String
buildingLabel kind =
    case kind of
        Base ->
            "Base"

        Barracks ->
            "Barracks"

        Farm ->
            "Farm"


buildingColor : BuildingKind -> String
buildingColor kind =
    case kind of
        Base ->
            "#3b82f6"

        Barracks ->
            "#b91c1c"

        Farm ->
            "#65a30d"


terrainColor : Terrain -> String
terrainColor terrain =
    case terrain of
        Grass ->
            "#4d7c3f"

        Forest ->
            "#1f5130"

        GoldMine ->
            "#caa72b"

        Water ->
            "#2b6cb0"

        Rock ->
            "#6b7280"

module RTS.Backend exposing (main, handle)

{-| The RTS backend: a server that shares the game's `RTS.Model` constants with the frontend (one
source of truth for the map size and unit/building costs). It serves a landing page, a health check
and a JSON description of the world (the pure `handle`), and — as a *stateful* `Server.Program` —
saves and loads a game state in memory at `/api/save` and `/api/load`. Demonstrating server-side Elm
that the frontend can persist to.

Run it via the `server` command (with the rts modules on the path), or unit-test `handle`/onRequest
directly (both are pure functions).
-}

import RTS.Model exposing (..)
import Server exposing (..)


{-| A stateful server holding the most recently saved game state (an opaque JSON string). -}
main : Program String
main =
    program
        { init = ""
        , onRequest = onRequest
        , onTick = \saved -> saved
        , tickMillis = 0
        }


onRequest : Request -> String -> ( String, Response )
onRequest req saved =
    case ( req.method, segments req ) of
        ( "POST", [ "api", "save" ] ) ->
            -- Persist the posted game state and acknowledge.
            ( req.body, json "{\"saved\":true}" )

        ( _, [ "api", "load" ] ) ->
            -- Return the saved state (or `null` if nothing has been saved yet).
            ( saved
            , json
                (if saved == "" then
                    "null"

                 else
                    saved
                )
            )

        _ ->
            ( saved, handle req )


{-| The stateless routes (also usable on their own via the simpler `handle` server form). -}
handle : Request -> Response
handle req =
    case segments req of
        [] ->
            html landingPage

        [ "ping" ] ->
            text "pong"

        [ "api", "map" ] ->
            json mapJson

        _ ->
            notFound


{-| A JSON description of the world, built from the shared model constants and terrain palette. -}
mapJson : String
mapJson =
    "{\"width\":"
        ++ String.fromInt mapWidth
        ++ ",\"height\":"
        ++ String.fromInt mapHeight
        ++ ",\"costs\":{\"worker\":"
        ++ String.fromInt workerCost
        ++ ",\"soldier\":"
        ++ String.fromInt soldierCost
        ++ ",\"barracks\":"
        ++ String.fromInt barracksCost
        ++ "},\"terrain\":["
        ++ String.join "," (List.map terrainEntry [ Grass, Forest, GoldMine, Water, Rock ])
        ++ "]}"


terrainEntry : Terrain -> String
terrainEntry t =
    "{\"name\":\"" ++ terrainName t ++ "\",\"color\":\"" ++ terrainColor t ++ "\"}"


terrainName : Terrain -> String
terrainName t =
    case t of
        Grass ->
            "grass"

        Forest ->
            "forest"

        GoldMine ->
            "gold"

        Water ->
            "water"

        Rock ->
            "rock"


landingPage : String
landingPage =
    String.join "\n"
        [ "<!doctype html><html><head><meta charset=\"utf-8\"><title>RTS Mini</title>"
        , "<style>body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;max-width:680px;margin:40px auto;padding:0 16px;line-height:1.6}code{background:#1e293b;padding:2px 6px;border-radius:4px}a{color:#60a5fa}</style></head><body>"
        , "<h1>RTS Mini</h1>"
        , "<p>A tiny real-time strategy game written in Elm. Build buildings, train units, gather"
        , "resources and uncover the whole " ++ String.fromInt mapWidth ++ "&times;" ++ String.fromInt mapHeight ++ " map to win.</p>"
        , "<h2>How to play</h2>"
        , "<ul>"
        , "<li>Click a unit to select it, then click a tile to move it.</li>"
        , "<li>Move <b>workers</b> onto a gold mine or forest to gather gold/wood.</li>"
        , "<li>Train a worker at the base (" ++ String.fromInt workerCost ++ " gold).</li>"
        , "<li>Build a barracks (" ++ String.fromInt barracksCost ++ " gold), then train soldiers (" ++ String.fromInt soldierCost ++ " gold).</li>"
        , "<li>Reveal every tile to win — there is no enemy AI.</li>"
        , "</ul>"
        , "<p>Build the playable client with"
        , "<code>elm make examples/rts/Main.elm --project examples/rts -o rts.html</code>.</p>"
        , "<p>Machine-readable world data: <a href=\"/api/map\">/api/map</a>. Health check:"
        , "<a href=\"/ping\">/ping</a>.</p>"
        , "</body></html>"
        ]

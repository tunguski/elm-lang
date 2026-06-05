module Main exposing (main)

{-| A full-stack stateful server written in Elm, run by `elm server live-dashboard`.

It keeps an **in-memory database** (a rolling time series) that a **server-side randomized process**
advances on every tick (a seeded random walk — no external randomness, fully deterministic-yet-
varying). It serves its own client: an HTML page, a stylesheet and a small JavaScript that polls
`/api/series` once a second and draws the series as an SVG line graph. So the page, CSS, JS and the
live data are all served by this one Elm program.

Routes: `/` (page), `/style.css`, `/app.js`, `/api/series` (JSON of the current series).
-}

import Server exposing (..)


type alias Model =
    { seed : Int
    , series : List Int
    }


main : Program Model
main =
    program
        { init = { seed = 1, series = [ 50 ] }
        , onRequest = onRequest
        , onTick = onTick
        , tickMillis = 1000
        }



-- SERVER-SIDE RANDOMIZED PROCESS (a seeded random walk over the in-memory series)


onTick : Model -> Model
onTick model =
    let
        seed =
            modBy 2147483647 (model.seed * 48271 + 1)

        step =
            modBy 11 seed - 5

        last =
            List.reverse model.series |> List.head |> Maybe.withDefault 50

        next =
            clampInt 0 100 (last + step)

        grown =
            model.series ++ [ next ]

        series =
            if List.length grown > 60 then
                List.drop (List.length grown - 60) grown

            else
                grown
    in
    { seed = seed, series = series }


clampInt : Int -> Int -> Int -> Int
clampInt lo hi x =
    if x < lo then
        lo

    else if x > hi then
        hi

    else
        x



-- REQUESTS (read the in-memory model; the model is advanced by the tick, not by requests)


onRequest : Request -> Model -> ( Model, Response )
onRequest req model =
    case segments req of
        [] ->
            ( model, html page )

        [ "style.css" ] ->
            ( model, css styles )

        [ "app.js" ] ->
            ( model, javascript client )

        [ "api", "series" ] ->
            ( model, json ("[" ++ String.join "," (List.map String.fromInt model.series) ++ "]") )

        _ ->
            ( model, notFound )



-- THE CLIENT, SERVED BY THE SERVER


page : String
page =
    """<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <title>Live dashboard (server-side Elm)</title>
  <link rel="stylesheet" href="/style.css">
</head>
<body>
  <h1>Server-side random walk</h1>
  <p>The graph below is a time series maintained in memory on the server and advanced once a
     second by a seeded random process. This page polls <code>/api/series</code> every second.</p>
  <p>latest value: <span id="latest">…</span></p>
  <div id="chart"></div>
  <script src="/app.js"></script>
</body>
</html>
"""


styles : String
styles =
    """body { font-family: system-ui, sans-serif; max-width: 720px; margin: 32px auto; color: #0f1720; }
h1 { color: #5fabdc; }
#chart { background: #0f1720; border-radius: 8px; padding: 8px; }
code { background: #eef; padding: 1px 4px; border-radius: 3px; }
"""


client : String
client =
    """var W = 680, H = 220;
function draw(data) {
  document.getElementById('latest').textContent = data.length ? data[data.length - 1] : '-';
  var n = Math.max(1, data.length - 1);
  var pts = data.map(function(v, i) {
    var x = i * (W / n);
    var y = H - (v / 100) * H;
    return x.toFixed(1) + ',' + y.toFixed(1);
  }).join(' ');
  document.getElementById('chart').innerHTML =
    '<svg width="' + W + '" height="' + H + '">' +
    '<polyline fill="none" stroke="#5fabdc" stroke-width="2" points="' + pts + '"/>' +
    '</svg>';
}
function poll() {
  fetch('/api/series').then(function(r) { return r.json(); }).then(draw).catch(function(){});
}
setInterval(poll, 1000);
poll();
"""

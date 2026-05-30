# Server-side Elm

`elm server <file.elm> [--port N] [--static DIR]` runs an Elm file as an HTTP server on the JIT
interpreter. There are two shapes of server:

- **Stateless** — expose `handle : Request -> Response`, a pure function from request to response.
- **Stateful** — expose `main : Program model`, a small record bundling an in-memory model, a
  request handler, and a background "tick" that mutates the model on a timer.

Both use the bundled [`Server`](../src/main/resources/elm/lib/Server.elm) module.

## Requests and responses

```elm
type alias Request =
    { method : String, path : String, query : List ( String, String ), body : String }

type alias Response =
    { status : Int, contentType : String, body : String }
```

Response builders cover the common content types, and two request helpers parse the path and query:

| Helper | Type | Purpose |
|---|---|---|
| `text` | `String -> Response` | `text/plain` body. |
| `html` | `String -> Response` | `text/html` body. |
| `json` | `String -> Response` | `application/json` body. |
| `css` | `String -> Response` | `text/css` body. |
| `javascript` | `String -> Response` | `application/javascript` body. |
| `notFound` | `Response` | A `404`. |
| `segments` | `Request -> List String` | Path split on `/`, empties dropped. |
| `param` | `String -> Request -> Maybe String` | Look up a query parameter. |

## A stateless server

The bundled [`simple-server-showcase.elm`](../src/main/resources/elm/demos/simple-server-showcase.elm)
routes on the path segments and echoes query parameters:

```elm
module Main exposing (handle)

import Server exposing (..)

handle : Request -> Response
handle req =
    case segments req of
        [] ->
            html "<h1>Hello from Elm</h1>"

        [ "ping" ] ->
            text "pong"

        [ "greet" ] ->
            text ("Hello, " ++ Maybe.withDefault "world" (param "name" req) ++ "!")

        _ ->
            notFound
```

```sh
elm server simple-server-showcase --port 8080
curl localhost:8080/ping              # -> pong
curl 'localhost:8080/greet?name=Ada'  # -> Hello, Ada!
```

`simple-server-showcase` resolves to the bundled demo; you can also pass a path to your own file.

## A stateful server

A stateful program holds a model in memory, updates it on every request, and advances it on a timer
— so the page changes over time without any client action:

```elm
type alias Program model =
    { init : model
    , onRequest : Request -> model -> ( model, Response )
    , onTick : model -> model
    , tickMillis : Int
    }
```

Model access is serialized, so `onRequest` and `onTick` never interleave. The bundled
[`live-dashboard.elm`](../src/main/resources/elm/demos/live-dashboard.elm) is a complete example: an
in-memory time series advanced by a seeded random walk on every tick, with the server itself serving

- `/` — an HTML page with a little Elm-rendered shell,
- `/style.css` and `/app.js` — the page's CSS and a vanilla-JS poller that redraws an SVG chart,
- `/api/series` — the current series as JSON, polled by the client every second.

```sh
elm server live-dashboard --port 8080
# open http://localhost:8080 — the chart updates on its own as the server ticks
```

## Serving static files

Pass `--static DIR` to serve text assets (HTML/CSS/JS/JSON/SVG) from a directory *before* the
handler runs; path traversal outside the directory is refused. This lets a single command serve a
built front-end plus a dynamic API from the same Elm app.

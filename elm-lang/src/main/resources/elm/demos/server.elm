module Main exposing (handle)

{-| An example HTTP server written in Elm, run by `elm server server.elm`. It routes on the request
path and method, echoes POST bodies, and returns 404 for unknown routes — a small but complete
request handler. `handle` is a pure function, so it is trivial to unit-test.
-}

import Server exposing (..)


handle : Request -> Response
handle req =
    case req.path of
        "/" ->
            html "<h1>Hello from Elm</h1><p>Try /ping, /echo (POST) or /json.</p>"

        "/ping" ->
            text "pong"

        "/json" ->
            json "{\"message\":\"hello\",\"lang\":\"elm\"}"

        "/echo" ->
            if req.method == "POST" then
                text ("you said: " ++ req.body)

            else
                response 405 "text/plain" "Use POST to /echo"

        _ ->
            notFound

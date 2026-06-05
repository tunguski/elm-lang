module Main exposing (handle)

{-| An example HTTP server written in Elm, run by `elm server server.elm`. It routes on the path
segments (path parameters), reads a query parameter, echoes POST bodies, and returns 404 otherwise —
a small but complete request handler. `handle` is a pure function, so it is trivial to unit-test.
-}

import Server exposing (..)


handle : Request -> Response
handle req =
    case segments req of
        [] ->
            html "<h1>Hello from Elm</h1><p>Try /ping, /hello?name=you, /users/7 or /echo (POST).</p>"

        [ "ping" ] ->
            text "pong"

        [ "json" ] ->
            json "{\"message\":\"hello\",\"lang\":\"elm\"}"

        [ "hello" ] ->
            case param "name" req of
                Just name ->
                    text ("Hello, " ++ name ++ "!")

                Nothing ->
                    text "Hello!"

        [ "users", id ] ->
            json ("{\"id\":\"" ++ id ++ "\"}")

        [ "echo" ] ->
            if req.method == "POST" then
                text ("you said: " ++ req.body)

            else
                response 405 "text/plain" "Use POST to /echo"

        _ ->
            notFound

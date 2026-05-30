module Server exposing
    ( Request
    , Response
    , text
    , html
    , json
    , response
    , notFound
    )

{-| A tiny HTTP server API for writing Elm programs that handle requests server-side, run by
`elm server <file.elm>`. The application exposes a pure handler:

    import Server exposing (..)

    handle : Request -> Response
    handle req =
        if req.path == "/" then
            html "<h1>Hello from Elm</h1>"

        else
            notFound

The runner builds a `Request` for each incoming HTTP request, applies `handle`, and writes the
`Response` back. Handlers are pure functions, so they are trivial to unit-test.

-}


{-| An incoming HTTP request: the method (e.g. "GET"), the path (e.g. "/users"), and the body. -}
type alias Request =
    { method : String
    , path : String
    , body : String
    }


{-| The response to send: an HTTP status, a Content-Type, and a body. -}
type alias Response =
    { status : Int
    , contentType : String
    , body : String
    }


{-| A 200 text/plain response. -}
text : String -> Response
text body =
    { status = 200, contentType = "text/plain", body = body }


{-| A 200 text/html response. -}
html : String -> Response
html body =
    { status = 200, contentType = "text/html", body = body }


{-| A 200 application/json response. -}
json : String -> Response
json body =
    { status = 200, contentType = "application/json", body = body }


{-| A response with an explicit status, Content-Type and body. -}
response : Int -> String -> String -> Response
response status contentType body =
    { status = status, contentType = contentType, body = body }


{-| A 404 text/plain "Not Found" response. -}
notFound : Response
notFound =
    { status = 404, contentType = "text/plain", body = "Not Found" }

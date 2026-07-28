module Server exposing
    ( Request
    , Response
    , text
    , html
    , json
    , css
    , javascript
    , response
    , notFound
    , param
    , header
    , segments
    , withHeaders
    , cors
    , Program
    , program
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


{-| An incoming HTTP request: the method (e.g. "GET"), the path (e.g. "/users/7"), the parsed
query parameters, the request headers (name/value pairs, names lower-cased), and the body. Decode a
JSON body with the `Json.Decode` module; read a header with [`header`](#header).
-}
type alias Request =
    { method : String
    , path : String
    , query : List ( String, String )
    , headers : List ( String, String )
    , body : String
    }


{-| The response to send: an HTTP status, a Content-Type, a body, and any extra response headers
(e.g. `Location` for a redirect, `Cache-Control`, or CORS headers). Build the common cases with the
helpers below and add headers with [`withHeaders`](#withHeaders) / [`cors`](#cors).
-}
type alias Response =
    { status : Int
    , contentType : String
    , body : String
    , headers : List ( String, String )
    }


{-| A 200 text/plain response. -}
text : String -> Response
text body =
    { status = 200, contentType = "text/plain", body = body, headers = [] }


{-| A 200 text/html response. -}
html : String -> Response
html body =
    { status = 200, contentType = "text/html", body = body, headers = [] }


{-| A 200 application/json response. -}
json : String -> Response
json body =
    { status = 200, contentType = "application/json", body = body, headers = [] }


{-| A 200 text/css response. -}
css : String -> Response
css body =
    { status = 200, contentType = "text/css", body = body, headers = [] }


{-| A 200 application/javascript response. -}
javascript : String -> Response
javascript body =
    { status = 200, contentType = "application/javascript", body = body, headers = [] }


{-| A response with an explicit status, Content-Type and body. -}
response : Int -> String -> String -> Response
response status contentType body =
    { status = status, contentType = contentType, body = body, headers = [] }


{-| A 404 text/plain "Not Found" response. -}
notFound : Response
notFound =
    { status = 404, contentType = "text/plain", body = "Not Found", headers = [] }


{-| Adds (appends) response headers, e.g. `text "hi" |> withHeaders [ ( "Cache-Control", "no-store" ) ]`. -}
withHeaders : List ( String, String ) -> Response -> Response
withHeaders extra resp =
    { resp | headers = resp.headers ++ extra }


{-| Adds permissive CORS headers so a browser page from any origin — including a `file://` page,
whose origin is the opaque `"null"` — may call this server from JavaScript. Handy for APIs consumed
by standalone/local HTML apps. Pair it with answering `OPTIONS` preflight requests:

    handle req =
        if req.method == "OPTIONS" then
            cors (response 204 "text/plain" "")

        else
            cors (json "{}")

-}
cors : Response -> Response
cors resp =
    withHeaders
        [ ( "Access-Control-Allow-Origin", "*" )
        , ( "Access-Control-Allow-Methods", "GET, POST, OPTIONS" )
        , ( "Access-Control-Allow-Headers", "Content-Type" )
        , ( "Access-Control-Max-Age", "86400" )
        ]
        resp


{-| Looks up a query parameter by name, e.g. `param "name" req` for `?name=…`. -}
param : String -> Request -> Maybe String
param name req =
    req.query
        |> List.filter (\pair -> Tuple.first pair == name)
        |> List.head
        |> Maybe.map Tuple.second


{-| Looks up a request header by name, case-insensitively, e.g. `header "authorization" req`. Header
names in the `Request` are lower-cased, so pass a lower-case name.
-}
header : String -> Request -> Maybe String
header name req =
    let
        wanted =
            String.toLower name
    in
    req.headers
        |> List.filter (\pair -> String.toLower (Tuple.first pair) == wanted)
        |> List.head
        |> Maybe.map Tuple.second


{-| The non-empty path segments, for routing: `/users/7` -> `[ "users", "7" ]`. Match with `case`:

    case segments req of
        [ "users", id ] ->
            json ("{\"id\":\"" ++ id ++ "\"}")

-}
segments : Request -> List String
segments req =
    List.filter (\s -> s /= "") (String.split "/" req.path)



-- STATEFUL SERVERS


{-| A stateful server program holding an in-memory `model`:

  - `init` — the initial model;
  - `onRequest` — handles a request against the current model, returning an updated model and a
    response;
  - `onTick` — a background step run every `tickMillis` milliseconds (e.g. to advance a simulation
    or expire data), returning the next model;
  - `tickMillis` — the tick interval (use 0 to disable ticking).

Expose it as `main : Server.Program Model` and the runner holds the model for you. (A simpler
stateless server instead exposes `handle : Request -> Response`.)

-}
type alias Program model =
    { init : model
    , onRequest : Request -> model -> ( model, Response )
    , onTick : model -> model
    , tickMillis : Int
    }


{-| Builds a stateful server program (currently the identity — provided for readable `main =`). -}
program : Program model -> Program model
program config =
    config

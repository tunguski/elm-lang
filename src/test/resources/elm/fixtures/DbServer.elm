module DbServer exposing (handle, schema)

{-| Test fixture for ServerDbTest: a database-backed HTTP handler (`handle : Request -> Db Response`)
built on Server + Db. Loaded by the test and dispatched through ServerRunner against in-memory H2,
exactly as `elm server --db` would run it. -}

import Db exposing (..)
import Server exposing (Request, Response)


schema : Db (Result String Int)
schema =
    execute "CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR)" []
        |> andThen (\_ -> execute "INSERT INTO items VALUES (1, 'sock'), (2, 'shoe'), (3, 'hat')" [])


handle : Request -> Db Response
handle req =
    case Server.segments req of
        [ "count" ] ->
            queryWith "SELECT COUNT(*) FROM items" [] (row identity |> andMap intColumn)
                |> map
                    (\result ->
                        case result of
                            Ok (n :: _) ->
                                Server.text (String.fromInt n)

                            _ ->
                                Server.response 500 "text/plain" "no count"
                    )

        [ "item", id ] ->
            queryWith "SELECT name FROM items WHERE id = ?"
                [ int (Maybe.withDefault 0 (String.toInt id)) ]
                (row identity |> andMap textColumn)
                |> map
                    (\result ->
                        case result of
                            Ok (name :: _) ->
                                Server.json ("{\"name\":\"" ++ name ++ "\"}")

                            _ ->
                                Server.notFound
                    )

        _ ->
            succeed Server.notFound

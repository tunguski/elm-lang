module DbApp exposing (..)

{-| Test fixture for DbRunnerTest: a set of named `Db` values that exercise the typed JDBC layer
(typed parameters, typed row decoding, NULL handling, parameterised-query safety, and SQL errors).
Loaded by the test and run against in-memory H2. -}

import Db exposing (..)


schema : Db (Result String Int)
schema =
    execute "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR, score DOUBLE, active BOOLEAN, note VARCHAR)" []
        |> andThen (\_ -> insert 1 "Ada" 9.5 True (Just "first"))
        |> andThen (\_ -> insert 2 "Linus" 7.0 False Nothing)
        |> andThen (\_ -> insert 3 "Grace" 9.0 True Nothing)


insert : Int -> String -> Float -> Bool -> Maybe String -> Db (Result String Int)
insert id name score active note =
    execute "INSERT INTO users (id, name, score, active, note) VALUES (?, ?, ?, ?, ?)"
        [ int id, text name, real score, bool active, maybeText note ]


maybeText : Maybe String -> Value
maybeText m =
    case m of
        Just s ->
            text s

        Nothing ->
            null


type alias User =
    { id : Int, name : String, score : Float, active : Bool, note : Maybe String }


userDecoder : RowDecoder User
userDecoder =
    row User
        |> andMap intColumn
        |> andMap textColumn
        |> andMap realColumn
        |> andMap boolColumn
        |> andMap (nullable textColumn)


allUsers : Db (Result String (List User))
allUsers =
    queryWith "SELECT id, name, score, active, note FROM users ORDER BY id" [] userDecoder


activeNames : Db (Result String (List String))
activeNames =
    queryWith "SELECT name FROM users WHERE active = ? ORDER BY name"
        [ bool True ]
        (row identity |> andMap textColumn)


countWhereName : String -> Db (Result String (List Int))
countWhereName name =
    queryWith "SELECT COUNT(*) FROM users WHERE name = ?" [ text name ] (row identity |> andMap intColumn)


badQuery : Db (Result String (List Row))
badQuery =
    query "SELECT * FROM nonexistent_table" []

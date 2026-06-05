module Db exposing
    ( Db, succeed, andThen, map, map2, map3, sequence
    , Value(..), int, text, real, bool, null
    , Row, query, execute, queryWith
    , RowDecoder, row, andMap, column, intColumn, textColumn, realColumn, boolColumn, nullable
    , decodeRow, decodeRows
    , toInt, toText, toReal, toBool
    )

{-| A typed JDBC layer for Elm server apps run by `elm server --db <jdbc-url>`.

Rather than splicing values into SQL strings, you bind **typed parameters** (`int`, `text`, …) to
`?` placeholders and decode result rows through a **typed `RowDecoder`** — so a query is checked
against the shape you expect, and user input can never be interpreted as SQL.

A database interaction is a `Db a`: a description of queries to run, sequenced with `andThen`,
ending in a value of type `a`. The server runner executes that description against a real JDBC
connection (any driver on the classpath; H2 ships by default) and never exposes the connection to
your pure Elm code. A handler that talks to the database therefore has type
`Request -> Db Response`:

    import Db exposing (..)
    import Server exposing (Request, Response, json, notFound)

    type alias User =
        { id : Int, name : String }

    userDecoder : RowDecoder User
    userDecoder =
        row User
            |> andMap intColumn
            |> andMap textColumn

    handle : Request -> Db Response
    handle req =
        case Server.segments req of
            [ "users", id ] ->
                queryWith "SELECT id, name FROM users WHERE id = ?" [ int (String.toInt id |> Maybe.withDefault 0) ] userDecoder
                    |> map
                        (\result ->
                            case result of
                                Ok (user :: _) ->
                                    json ("{\"name\":\"" ++ user.name ++ "\"}")

                                _ ->
                                    notFound
                        )

            _ ->
                succeed notFound

@docs Db, succeed, andThen, map, map2, map3, sequence
@docs Value, int, text, real, bool, null
@docs Row, query, execute, queryWith
@docs RowDecoder, row, andMap, column, intColumn, textColumn, realColumn, boolColumn, nullable
@docs decodeRow, decodeRows
@docs toInt, toText, toReal, toBool

-}


{-| A description of database work that ultimately produces an `a`. Build one with `query`/`execute`
and sequence with `andThen`; the `elm server` runner interprets it against a JDBC connection. The
constructors are private — `Db` is an opaque effect type, like `Cmd` in a normal Elm program.
-}
type Db a
    = Done a
    | Query String (List Value) (Result String (List Row) -> Db a)
    | Execute String (List Value) (Result String Int -> Db a)


{-| A `Db` that runs no queries and yields the given value. -}
succeed : a -> Db a
succeed value =
    Done value


{-| Run a `Db`, then feed its result into a function that produces the next `Db`. This is how you
sequence dependent queries (run one, then use its rows to decide the next).
-}
andThen : (a -> Db b) -> Db a -> Db b
andThen f db =
    case db of
        Done value ->
            f value

        Query sql params next ->
            Query sql params (\result -> andThen f (next result))

        Execute sql params next ->
            Execute sql params (\result -> andThen f (next result))


{-| Transform the value a `Db` produces. -}
map : (a -> b) -> Db a -> Db b
map f db =
    andThen (\a -> succeed (f a)) db


{-| Combine the results of two independent `Db` steps (run in order). -}
map2 : (a -> b -> c) -> Db a -> Db b -> Db c
map2 f da db =
    da |> andThen (\a -> db |> andThen (\b -> succeed (f a b)))


{-| Combine the results of three independent `Db` steps (run in order). -}
map3 : (a -> b -> c -> d) -> Db a -> Db b -> Db c -> Db d
map3 f da db dc =
    da |> andThen (\a -> map2 (f a) db dc)


{-| Run a list of `Db` steps in order and collect their results. -}
sequence : List (Db a) -> Db (List a)
sequence steps =
    case steps of
        [] ->
            succeed []

        first :: rest ->
            first |> andThen (\a -> sequence rest |> andThen (\as_ -> succeed (a :: as_)))



-- TYPED VALUES


{-| A typed SQL value: a bound parameter you pass to a query, or a cell you read back from a row.
Build parameters with `int`/`text`/`real`/`bool`/`null`; read cells with a `RowDecoder` (or pattern
-match the constructors directly).
-}
type Value
    = VInt Int
    | VText String
    | VReal Float
    | VBool Bool
    | VNull


{-| An integer parameter. -}
int : Int -> Value
int =
    VInt


{-| A text parameter. -}
text : String -> Value
text =
    VText


{-| A floating-point parameter. -}
real : Float -> Value
real =
    VReal


{-| A boolean parameter. -}
bool : Bool -> Value
bool =
    VBool


{-| A SQL NULL parameter. -}
null : Value
null =
    VNull



-- QUERIES


{-| One row of a result set: its cells, left to right, as typed `Value`s. -}
type alias Row =
    List Value


{-| Run a `SELECT` (or any row-returning statement), binding `params` to the `?` placeholders in
order. The result is `Ok rows` or `Err message` if the database rejects the statement. Prefer
`queryWith`, which also decodes the rows into your own type.
-}
query : String -> List Value -> Db (Result String (List Row))
query sql params =
    Query sql params Done


{-| Run an `INSERT`/`UPDATE`/`DELETE` (or DDL), binding `params` to the `?` placeholders. The result
is `Ok n` with the number of affected rows, or `Err message`.
-}
execute : String -> List Value -> Db (Result String Int)
execute sql params =
    Execute sql params Done


{-| Run a query and decode every row with the given `RowDecoder`. A decode failure (wrong column
type or arity) turns the whole result into `Err`.
-}
queryWith : String -> List Value -> RowDecoder a -> Db (Result String (List a))
queryWith sql params decoder =
    query sql params
        |> map (\result -> Result.andThen (decodeRows decoder) result)



-- ROW DECODERS


{-| Decodes one result `Row` into a value of type `a` by consuming its columns left to right. Build
one applicatively, mirroring the `SELECT` list:

    row (\id name active -> { id = id, name = name, active = active })
        |> andMap intColumn
        |> andMap textColumn
        |> andMap boolColumn

-}
type RowDecoder a
    = RowDecoder (List Value -> Result String ( a, List Value ))


{-| A decoder that consumes no columns and yields the given value — the start of a pipeline. -}
row : a -> RowDecoder a
row value =
    RowDecoder (\cols -> Ok ( value, cols ))


{-| Apply the next column decoder in a pipeline (applicative `andMap`). -}
andMap : RowDecoder a -> RowDecoder (a -> b) -> RowDecoder b
andMap (RowDecoder consume) (RowDecoder consumeFn) =
    RowDecoder
        (\cols ->
            consumeFn cols
                |> Result.andThen
                    (\( f, rest ) ->
                        consume rest |> Result.map (\( a, rest2 ) -> ( f a, rest2 ))
                    )
        )


{-| Consume the next column, decoding its `Value` with the given function. The primitive behind
`intColumn`/`textColumn`/…; use it for custom cell decoding.
-}
column : (Value -> Result String a) -> RowDecoder a
column decode =
    RowDecoder
        (\cols ->
            case cols of
                [] ->
                    Err "row has fewer columns than the decoder expects"

                value :: rest ->
                    decode value |> Result.map (\a -> ( a, rest ))
        )


{-| Decode the next column as an `Int`. -}
intColumn : RowDecoder Int
intColumn =
    column toInt


{-| Decode the next column as a `String`. -}
textColumn : RowDecoder String
textColumn =
    column toText


{-| Decode the next column as a `Float`. -}
realColumn : RowDecoder Float
realColumn =
    column toReal


{-| Decode the next column as a `Bool`. -}
boolColumn : RowDecoder Bool
boolColumn =
    column toBool


{-| Make a column decoder tolerate NULL: a NULL cell decodes to `Nothing`, anything else to
`Just` of the wrapped decoder's value.
-}
nullable : RowDecoder a -> RowDecoder (Maybe a)
nullable (RowDecoder consume) =
    RowDecoder
        (\cols ->
            case cols of
                VNull :: rest ->
                    Ok ( Nothing, rest )

                _ ->
                    consume cols |> Result.map (\( a, rest ) -> ( Just a, rest ))
        )


{-| Decode a single row, requiring the decoder to consume every column. -}
decodeRow : RowDecoder a -> Row -> Result String a
decodeRow (RowDecoder consume) cols =
    consume cols
        |> Result.andThen
            (\( a, rest ) ->
                if List.isEmpty rest then
                    Ok a

                else
                    Err "row has more columns than the decoder consumed"
            )


{-| Decode every row in a result set, failing on the first row that does not decode. -}
decodeRows : RowDecoder a -> List Row -> Result String (List a)
decodeRows decoder rows =
    case rows of
        [] ->
            Ok []

        first :: rest ->
            decodeRow decoder first
                |> Result.andThen (\a -> decodeRows decoder rest |> Result.map (\others -> a :: others))



-- CELL ACCESSORS


{-| Read a cell as an `Int`, failing on any other type. -}
toInt : Value -> Result String Int
toInt value =
    case value of
        VInt n ->
            Ok n

        _ ->
            Err ("expected an integer column, got " ++ describe value)


{-| Read a cell as a `String`, failing on any other type. -}
toText : Value -> Result String String
toText value =
    case value of
        VText s ->
            Ok s

        _ ->
            Err ("expected a text column, got " ++ describe value)


{-| Read a cell as a `Float`, accepting integer cells too. -}
toReal : Value -> Result String Float
toReal value =
    case value of
        VReal f ->
            Ok f

        VInt n ->
            Ok (toFloat n)

        _ ->
            Err ("expected a numeric column, got " ++ describe value)


{-| Read a cell as a `Bool`, failing on any other type. -}
toBool : Value -> Result String Bool
toBool value =
    case value of
        VBool b ->
            Ok b

        _ ->
            Err ("expected a boolean column, got " ++ describe value)


describe : Value -> String
describe value =
    case value of
        VInt _ ->
            "an integer"

        VText _ ->
            "text"

        VReal _ ->
            "a number"

        VBool _ ->
            "a boolean"

        VNull ->
            "NULL"

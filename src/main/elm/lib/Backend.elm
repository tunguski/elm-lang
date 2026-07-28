module Backend exposing
    ( Actor, uid
    , dispatch, documentRoutes
    , ok, err
    )

{-| **Backend** — batteries for `Db`-backed HTTP servers: user accounts, password login with server
sessions, request/login auditing, and a generic per-user document store with visibility (private /
public) and sharing. Built on [`Server`](Server) + [`Db`](Db); bundled by `elm server` so a server
app can just `import Backend`.

All crypto is done in H2 SQL — passwords are salted and stretched with `HASH('SHA-256', …, 100000)`,
session tokens and salts come from `RANDOM_UUID()` — so no host crypto primitive is needed.

An app wires it in one line:

    import Backend exposing (Actor)
    import Server exposing (Request, Response)
    import Db exposing (Db)

    handle : Request -> Db Response
    handle =
        Backend.dispatch "notes" router

    router : Actor -> Request -> Db Response
    router actor req =
        case Backend.documentRoutes "notes" actor req of
            Just done -> done                       -- the standard CRUD / share / search endpoints
            Nothing   -> ...app-specific routes...

`dispatch` creates the schema (idempotent), records the request in the audit log, resolves the caller
(a session token in `Authorization: Bearer …`, or an `X-User-Id` uuid for an unauthenticated
file-first client), handles the `/api/auth/*` endpoints, and hands the rest to `router`.

-}

import Db exposing (Db, andMap, andThen, boolColumn, execute, map, query, queryWith, row, succeed, textColumn)
import Json.Decode as D
import Json.Encode as E
import Server exposing (Request, Response)



-- ACTOR


{-| The resolved caller: `uid` is their user uuid, or `""` if the request carried no valid identity.
-}
type Actor
    = Actor String


{-| The acting user's uuid (`""` when unauthenticated). -}
uid : Actor -> String
uid (Actor u) =
    u



-- DISPATCH


{-| The entry point: ensure the schema, resolve the caller, handle `/api/auth/*`, else delegate to
the app's `router`, and record the request in the audit log.
-}
dispatch : String -> (Actor -> Request -> Db Response) -> Request -> Db Response
dispatch app router req =
    ensureSchema
        |> andThen (\_ -> resolveActor req)
        |> andThen
            (\actor ->
                case authRoutes actor req of
                    Just done ->
                        done

                    Nothing ->
                        router actor req
            )
        |> andThen (\resp -> auditRequest app req resp)


{-| Build a 200 JSON response (with CORS) from a JSON string. -}
ok : String -> Response
ok body =
    Server.cors (Server.json body)


{-| Build an error JSON response (with CORS). -}
err : Int -> String -> Response
err status message =
    Server.cors (Server.response status "application/json" (E.encode 0 (E.object [ ( "error", E.string message ) ])))



-- SCHEMA


ensureSchema : Db ()
ensureSchema =
    ddl
        [ "CREATE TABLE IF NOT EXISTS users (uuid VARCHAR PRIMARY KEY, login VARCHAR, pw_hash VARBINARY, pw_salt VARCHAR, created TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        , "CREATE TABLE IF NOT EXISTS sessions (token VARCHAR PRIMARY KEY, uuid VARCHAR NOT NULL, created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, expires TIMESTAMP NOT NULL)"
        , "CREATE TABLE IF NOT EXISTS audit_requests (ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, app VARCHAR, uid VARCHAR, method VARCHAR, path VARCHAR, status INT)"
        , "CREATE TABLE IF NOT EXISTS audit_logins (ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, login VARCHAR, uid VARCHAR, success BOOLEAN, note VARCHAR)"
        , "CREATE TABLE IF NOT EXISTS documents (id VARCHAR PRIMARY KEY, app VARCHAR NOT NULL, owner VARCHAR NOT NULL, visibility VARCHAR NOT NULL DEFAULT 'private', title VARCHAR, body CLOB, updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
        , "CREATE TABLE IF NOT EXISTS shares (doc_id VARCHAR NOT NULL, principal VARCHAR NOT NULL, access VARCHAR NOT NULL DEFAULT 'read', PRIMARY KEY (doc_id, principal))"
        ]


ddl : List String -> Db ()
ddl statements =
    case statements of
        [] ->
            succeed ()

        sql :: rest ->
            execute sql [] |> andThen (\_ -> ddl rest)



-- AUDIT


auditRequest : String -> Request -> Response -> Db Response
auditRequest app req resp =
    execute
        "INSERT INTO audit_requests (app, uid, method, path, status) VALUES (?, ?, ?, ?, ?)"
        [ Db.text app, Db.text (uidFromReq req), Db.text req.method, Db.text req.path, Db.int resp.status ]
        |> map (\_ -> resp)


{-| A best-effort uid for the audit row: the X-User-Id header (unauthenticated) — the session case is
already covered by the login audit and we don't want to re-query per request. -}
uidFromReq : Request -> String
uidFromReq req =
    Maybe.withDefault "" (Server.header "x-user-id" req)



-- ACTOR RESOLUTION


resolveActor : Request -> Db Actor
resolveActor req =
    case bearer req of
        Just token ->
            queryWith
                "SELECT uuid FROM sessions WHERE token = ? AND expires > CURRENT_TIMESTAMP"
                [ Db.text token ]
                (row identity |> andMap textColumn)
                |> map (\res -> Actor (firstOr "" res))

        Nothing ->
            succeed (Actor (Maybe.withDefault "" (Server.header "x-user-id" req)))


bearer : Request -> Maybe String
bearer req =
    Server.header "authorization" req
        |> Maybe.andThen
            (\h ->
                if String.startsWith "Bearer " h then
                    Just (String.trim (String.dropLeft 7 h))

                else
                    Nothing
            )



-- AUTH ROUTES  (/api/auth/register, /login, /logout, /me)


authRoutes : Actor -> Request -> Maybe (Db Response)
authRoutes actor req =
    case ( req.method, Server.segments req ) of
        ( "POST", [ "api", "auth", "register" ] ) ->
            Just (register actor req)

        ( "POST", [ "api", "auth", "login" ] ) ->
            Just (login req)

        ( "POST", [ "api", "auth", "logout" ] ) ->
            Just (logout req)

        ( "GET", [ "api", "auth", "me" ] ) ->
            Just (me actor)

        _ ->
            Nothing


type alias Creds =
    { login : String, password : String }


credsDecoder : D.Decoder Creds
credsDecoder =
    D.map2 Creds
        (D.field "login" D.string)
        (D.field "password" D.string)


register : Actor -> Request -> Db Response
register actor req =
    case D.decodeString credsDecoder req.body of
        Ok c ->
            if uid actor == "" then
                succeed (err 401 "no identity — send X-User-Id first")

            else if String.length (String.trim c.login) < 3 || String.length c.password < 6 then
                succeed (err 400 "login must be ≥3 chars and password ≥6")

            else
                queryWith "SELECT COUNT(*) FROM users WHERE login = ? AND uuid <> ?"
                    [ Db.text c.login, Db.text (uid actor) ]
                    (row identity |> andMap Db.intColumn)
                    |> andThen
                        (\res ->
                            if firstOr 1 res > 0 then
                                succeed (err 409 "that login is taken")

                            else
                                setPassword (uid actor) c
                                    |> andThen (\_ -> issueSession (uid actor))
                                    |> map (\token -> ok (tokenJson token (uid actor)))
                        )

        Err _ ->
            succeed (err 400 "expected {login, password}")


{-| Attach a login + salted, stretched password hash to a user row (creating it if needed). -}
setPassword : String -> Creds -> Db ()
setPassword userId c =
    execute "MERGE INTO users (uuid) KEY (uuid) VALUES (?)" [ Db.text userId ]
        |> andThen (\_ -> newUuid)
        |> andThen
            (\salt ->
                execute
                    "UPDATE users SET login = ?, pw_salt = ?, pw_hash = HASH('SHA-256', STRINGTOUTF8(CONCAT(?, ?)), 100000) WHERE uuid = ?"
                    [ Db.text c.login, Db.text salt, Db.text c.password, Db.text salt, Db.text userId ]
                    |> map (\_ -> ())
            )


login : Request -> Db Response
login req =
    case D.decodeString credsDecoder req.body of
        Ok c ->
            queryWith
                "SELECT uuid, (pw_hash = HASH('SHA-256', STRINGTOUTF8(CONCAT(?, pw_salt)), 100000)) FROM users WHERE login = ? AND pw_hash IS NOT NULL"
                [ Db.text c.password, Db.text c.login ]
                (row Tuple.pair |> andMap textColumn |> andMap boolColumn)
                |> andThen
                    (\res ->
                        case res of
                            Ok (( userId, True ) :: _) ->
                                auditLogin c.login userId True "ok"
                                    |> andThen (\_ -> issueSession userId)
                                    |> map (\token -> ok (tokenJson token userId))

                            _ ->
                                auditLogin c.login "" False "bad credentials"
                                    |> map (\_ -> err 401 "invalid login or password")
                    )

        Err _ ->
            succeed (err 400 "expected {login, password}")


logout : Request -> Db Response
logout req =
    case bearer req of
        Just token ->
            execute "DELETE FROM sessions WHERE token = ?" [ Db.text token ]
                |> map (\_ -> ok "{}")

        Nothing ->
            succeed (ok "{}")


me : Actor -> Db Response
me actor =
    if uid actor == "" then
        succeed (ok "{\"uid\":\"\",\"login\":null}")

    else
        queryWith "SELECT COALESCE(login, '') FROM users WHERE uuid = ?"
            [ Db.text (uid actor) ]
            (row identity |> andMap textColumn)
            |> map
                (\res ->
                    let
                        lg =
                            firstOr "" res
                    in
                    ok
                        ("{\"uid\":"
                            ++ jstr (uid actor)
                            ++ ",\"login\":"
                            ++ (if lg == "" then
                                    "null"

                                else
                                    jstr lg
                               )
                            ++ "}"
                        )
                )


issueSession : String -> Db String
issueSession userId =
    newUuid
        |> andThen
            (\token ->
                execute
                    "INSERT INTO sessions (token, uuid, expires) VALUES (?, ?, DATEADD('DAY', 30, CURRENT_TIMESTAMP))"
                    [ Db.text token, Db.text userId ]
                    |> map (\_ -> token)
            )


auditLogin : String -> String -> Bool -> String -> Db ()
auditLogin lg userId success note =
    execute "INSERT INTO audit_logins (login, uid, success, note) VALUES (?, ?, ?, ?)"
        [ Db.text lg, Db.text userId, Db.bool success, Db.text note ]
        |> map (\_ -> ())


newUuid : Db String
newUuid =
    queryWith "SELECT CAST(RANDOM_UUID() AS VARCHAR)" [] (row identity |> andMap textColumn)
        |> map (firstOr "")


tokenJson : String -> String -> String
tokenJson token userId =
    "{\"token\":" ++ jstr token ++ ",\"uid\":" ++ jstr userId ++ "}"



-- DOCUMENT ROUTES  (/api/docs, /api/docs/<id>, /api/docs/<id>/share, /api/search)


{-| The standard per-user document endpoints, scoped to `app`. Returns `Nothing` for paths it does
not own, so the app can add its own routes.

  - `GET    /api/docs`            — the caller's documents (owned + shared with them)
  - `GET    /api/docs/<id>`       — one document (if owned, shared, or public)
  - `PUT    /api/docs/<id>`       — create/update (owner only) `{title, visibility, body}`
  - `DELETE /api/docs/<id>`       — delete (owner only)
  - `POST   /api/docs/<id>/share` — share with another user `{login, access}` (owner only)
  - `GET    /api/search?q=…`      — public documents whose title matches (across all users)

-}
documentRoutes : String -> Actor -> Request -> Maybe (Db Response)
documentRoutes app actor req =
    case ( req.method, Server.segments req ) of
        ( "OPTIONS", _ ) ->
            Just (succeed (Server.cors (Server.response 204 "text/plain" "")))

        ( "GET", [ "api", "docs" ] ) ->
            Just (listDocs app actor)

        ( "GET", [ "api", "docs", id ] ) ->
            Just (getDoc app actor id)

        ( "PUT", [ "api", "docs", id ] ) ->
            Just (putDoc app actor id req)

        ( "DELETE", [ "api", "docs", id ] ) ->
            Just (deleteDoc actor id)

        ( "POST", [ "api", "docs", id, "share" ] ) ->
            Just (shareDoc actor id req)

        ( "GET", [ "api", "search" ] ) ->
            Just (searchDocs app (Maybe.withDefault "" (Server.param "q" req)))

        _ ->
            Nothing


requireUser : Actor -> (String -> Db Response) -> Db Response
requireUser actor f =
    if uid actor == "" then
        succeed (err 401 "not authenticated")

    else
        f (uid actor)


docSelect : String
docSelect =
    "SELECT id, COALESCE(title,''), visibility, owner, CAST(updated AS VARCHAR), COALESCE(NULLIF(CAST(body AS VARCHAR), ''), 'null') FROM documents"


type alias DocRow =
    { id : String, title : String, visibility : String, owner : String, updated : String, body : String }


docRowDecoder : Db.RowDecoder DocRow
docRowDecoder =
    row DocRow
        |> andMap textColumn
        |> andMap textColumn
        |> andMap textColumn
        |> andMap textColumn
        |> andMap textColumn
        |> andMap textColumn


listDocs : String -> Actor -> Db Response
listDocs app actor =
    requireUser actor
        (\me_ ->
            queryWith
                (docSelect ++ " WHERE app = ? AND (owner = ? OR id IN (SELECT doc_id FROM shares WHERE principal = ?)) ORDER BY updated DESC")
                [ Db.text app, Db.text me_, Db.text me_ ]
                docRowDecoder
                |> map (\res -> ok (docsJson (resultList res)))
        )


getDoc : String -> Actor -> String -> Db Response
getDoc app actor id =
    queryWith
        (docSelect ++ " WHERE app = ? AND id = ? AND (owner = ? OR visibility = 'public' OR id IN (SELECT doc_id FROM shares WHERE principal = ?))")
        [ Db.text app, Db.text id, Db.text (uid actor), Db.text (uid actor) ]
        docRowDecoder
        |> map
            (\res ->
                case resultList res of
                    d :: _ ->
                        ok (docJson d)

                    [] ->
                        err 404 "not found"
            )


type alias DocInput =
    { title : String, visibility : String, body : String }


docInputDecoder : D.Decoder DocInput
docInputDecoder =
    D.map3 DocInput
        (D.oneOf [ D.field "title" D.string, D.succeed "" ])
        (D.oneOf [ D.field "visibility" D.string, D.succeed "private" ])
        (D.field "body" D.value |> D.map (E.encode 0))


putDoc : String -> Actor -> String -> Request -> Db Response
putDoc app actor id req =
    requireUser actor
        (\me_ ->
            case D.decodeString docInputDecoder req.body of
                Ok input ->
                    ownerOf id
                        |> andThen
                            (\owner ->
                                case owner of
                                    Nothing ->
                                        execute
                                            "INSERT INTO documents (id, app, owner, visibility, title, body, updated) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
                                            [ Db.text id, Db.text app, Db.text me_, Db.text (vis input.visibility), Db.text input.title, Db.text input.body ]
                                            |> andThen (\_ -> getDoc app actor id)

                                    Just o ->
                                        if o == me_ then
                                            execute
                                                "UPDATE documents SET title = ?, visibility = ?, body = ?, updated = CURRENT_TIMESTAMP WHERE id = ?"
                                                [ Db.text input.title, Db.text (vis input.visibility), Db.text input.body, Db.text id ]
                                                |> andThen (\_ -> getDoc app actor id)

                                        else
                                            succeed (err 403 "not the owner")
                            )

                Err _ ->
                    succeed (err 400 "expected {title, visibility, body}")
        )


deleteDoc : Actor -> String -> Db Response
deleteDoc actor id =
    requireUser actor
        (\me_ ->
            execute "DELETE FROM documents WHERE id = ? AND owner = ?" [ Db.text id, Db.text me_ ]
                |> andThen (\_ -> execute "DELETE FROM shares WHERE doc_id = ?" [ Db.text id ])
                |> map (\_ -> ok "{}")
        )


shareDoc : Actor -> String -> Request -> Db Response
shareDoc actor id req =
    requireUser actor
        (\me_ ->
            case D.decodeString shareDecoder req.body of
                Ok s ->
                    ownerOf id
                        |> andThen
                            (\owner ->
                                if owner /= Just me_ then
                                    succeed (err 403 "not the owner")

                                else
                                    uuidForLogin s.login
                                        |> andThen
                                            (\principal ->
                                                case principal of
                                                    Nothing ->
                                                        succeed (err 404 "no such user")

                                                    Just p ->
                                                        execute
                                                            "MERGE INTO shares (doc_id, principal, access) KEY (doc_id, principal) VALUES (?, ?, ?)"
                                                            [ Db.text id, Db.text p, Db.text (access s.access) ]
                                                            |> map (\_ -> ok "{}")
                                            )
                            )

                Err _ ->
                    succeed (err 400 "expected {login, access}")
        )


type alias ShareInput =
    { login : String, access : String }


shareDecoder : D.Decoder ShareInput
shareDecoder =
    D.map2 ShareInput
        (D.field "login" D.string)
        (D.oneOf [ D.field "access" D.string, D.succeed "read" ])


searchDocs : String -> String -> Db Response
searchDocs app q =
    queryWith
        (docSelect ++ " WHERE app = ? AND visibility = 'public' AND LOWER(COALESCE(title,'')) LIKE ? ORDER BY updated DESC LIMIT 50")
        [ Db.text app, Db.text ("%" ++ String.toLower q ++ "%") ]
        docRowDecoder
        |> map (\res -> ok (docsJson (resultList res)))



-- DOCUMENT HELPERS


ownerOf : String -> Db (Maybe String)
ownerOf id =
    queryWith "SELECT owner FROM documents WHERE id = ?" [ Db.text id ] (row identity |> andMap textColumn)
        |> map (\res -> List.head (resultList res))


uuidForLogin : String -> Db (Maybe String)
uuidForLogin lg =
    queryWith "SELECT uuid FROM users WHERE login = ?" [ Db.text lg ] (row identity |> andMap textColumn)
        |> map (\res -> List.head (resultList res))


vis : String -> String
vis v =
    if v == "public" then
        "public"

    else
        "private"


access : String -> String
access a =
    if a == "write" then
        "write"

    else
        "read"


docsJson : List DocRow -> String
docsJson docs =
    "[" ++ String.join "," (List.map docJson docs) ++ "]"


docJson : DocRow -> String
docJson d =
    "{\"id\":"
        ++ jstr d.id
        ++ ",\"title\":"
        ++ jstr d.title
        ++ ",\"visibility\":"
        ++ jstr d.visibility
        ++ ",\"owner\":"
        ++ jstr d.owner
        ++ ",\"updated\":"
        ++ jstr d.updated
        ++ ",\"body\":"
        ++ d.body
        ++ "}"



-- SMALL HELPERS


identity : a -> a
identity a =
    a


firstOr : a -> Result String (List a) -> a
firstOr default res =
    Maybe.withDefault default (List.head (resultList res))


resultList : Result String (List a) -> List a
resultList res =
    Result.withDefault [] res


jstr : String -> String
jstr s =
    E.encode 0 (E.string s)

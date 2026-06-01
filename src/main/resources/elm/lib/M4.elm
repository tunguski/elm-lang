module M4 exposing
    ( expand
    , expandWith
    , Macro
    , define
    )

{-| A small **m4**-style macro processor in Elm — define macros and expand them in text, with
positional arguments and quoting, following <https://mbreen.com/m4.html>.

The processor scans text left to right. A word that names a macro is replaced by the macro's body,
in which `$0` is the macro name, `$1 … $9` are the call's arguments (`name(a, b, …)`), `$#` is the
argument count and `$*` is the comma-joined arguments; the replacement is then **re-scanned**, so
macros may expand to further macro calls. Quotes — `` ` `` … `'` — protect text from expansion:
the outer pair is stripped and the contents are emitted verbatim. The `define`, `dnl` and `ifelse`
builtins are recognised.

    import M4 exposing (..)

    -- define(greet, Hello $1!)greet(world)  ==>  "Hello world!"
    out : String
    out =
        expand "define(greet, Hello $1!)greet(world)"

`expand` starts with no macros (definitions come from `define(...)` in the text); `expandWith`
pre-defines some.

-}


{-| A predefined macro: a name and its replacement body (with `$1`… placeholders). -}
type alias Macro =
    { name : String, body : String }


{-| Builds a predefined macro (for `expandWith`). -}
define : String -> String -> Macro
define name body =
    { name = name, body = body }


{-| Expands the macros in `text`, starting with no definitions (so any macros must be introduced by
`define(...)` in the text itself). -}
expand : String -> String
expand text =
    expandWith [] text


{-| Like {@link expand} but with `macros` already defined. -}
expandWith : List Macro -> String -> String
expandWith macros text =
    scan 10000 (List.map (\m -> ( m.name, m.body )) macros) (String.toList text)



-- SCANNER ----------------------------------------------------------------


{-| The core left-to-right scan. `fuel` bounds total expansions (so a recursive macro can't loop
forever); `env` is the current definitions (most-recent first); the output is built as we go. -}
scan : Int -> List ( String, String ) -> List Char -> String
scan fuel env chars =
    case chars of
        [] ->
            ""

        '`' :: rest ->
            -- A quoted region: strip the outer ` ' pair and emit the contents without expanding them.
            let
                ( inner, after ) =
                    readQuote 1 rest []
            in
            inner ++ scan fuel env after

        c :: rest ->
            if isIdentStart c then
                let
                    ( name, afterName ) =
                        readIdent chars []
                in
                processName fuel env name afterName

            else
                String.fromChar c ++ scan fuel env rest


{-| Handles an identifier read from the input: if it names a builtin or a defined macro, collect its
arguments (when followed by `(`) and expand; otherwise emit it literally. -}
processName : Int -> List ( String, String ) -> String -> List Char -> String
processName fuel env name afterName =
    let
        known =
            name == "define" || name == "dnl" || name == "ifelse" || hasMacro name env

        ( args, afterArgs ) =
            if known then
                case afterName of
                    '(' :: _ ->
                        readArgs afterName

                    _ ->
                        ( [], afterName )

            else
                ( [], afterName )
    in
    if not known then
        name ++ scan fuel env afterName

    else if fuel <= 0 then
        -- Out of expansion budget: stop expanding and emit the rest verbatim.
        name ++ scan 0 env afterArgs

    else if name == "dnl" then
        -- Delete to (and including) the next newline.
        scan fuel env (dropThroughNewline afterArgs)

    else if name == "define" then
        case args of
            n :: b :: _ ->
                scan (fuel - 1) (( n, b ) :: env) afterArgs

            n :: [] ->
                scan (fuel - 1) (( n, "" ) :: env) afterArgs

            [] ->
                scan fuel env afterArgs

    else if name == "ifelse" then
        scan (fuel - 1) env (String.toList (ifelse args) ++ afterArgs)

    else
        case lookupMacro name env of
            Just body ->
                -- Substitute the arguments into the body and re-scan the result.
                scan (fuel - 1) env (String.toList (substitute name args body) ++ afterArgs)

            Nothing ->
                name ++ scan fuel env afterArgs


{-| m4's `ifelse(a, b, t)` / `ifelse(a, b, t, e)`: `t` if `a == b`, else `e` (or `""`). -}
ifelse : List String -> String
ifelse args =
    case args of
        a :: b :: t :: rest ->
            if a == b then
                t

            else
                case rest of
                    e :: _ ->
                        e

                    [] ->
                        ""

        _ ->
            ""



-- READERS ----------------------------------------------------------------


{-| Reads a maximal identifier (`[A-Za-z_][A-Za-z0-9_]*`) from the front of the input. -}
readIdent : List Char -> List Char -> ( String, List Char )
readIdent chars acc =
    case chars of
        c :: rest ->
            if isIdentChar c then
                readIdent rest (c :: acc)

            else
                ( String.fromList (List.reverse acc), chars )

        [] ->
            ( String.fromList (List.reverse acc), [] )


{-| Reads a quoted region given the opening `` ` `` was already consumed. Tracks nesting so inner
`` ` `` … `'` pairs are kept (one outer level is stripped), returning the contents and the rest. -}
readQuote : Int -> List Char -> List Char -> ( String, List Char )
readQuote depth chars acc =
    case chars of
        [] ->
            ( String.fromList (List.reverse acc), [] )

        '`' :: rest ->
            readQuote (depth + 1) rest ('`' :: acc)

        '\'' :: rest ->
            if depth == 1 then
                ( String.fromList (List.reverse acc), rest )

            else
                readQuote (depth - 1) rest ('\'' :: acc)

        c :: rest ->
            readQuote depth rest (c :: acc)


{-| Reads a macro argument list, given the opening `(` is at the front. Splits on top-level commas
(respecting nested parens and quotes), strips one quote level and leading unquoted whitespace from
each argument, and returns the arguments and the input after the matching `)`. -}
readArgs : List Char -> ( List String, List Char )
readArgs chars =
    case chars of
        '(' :: rest ->
            readArgsHelp rest 0 "" []

        _ ->
            ( [], chars )


readArgsHelp : List Char -> Int -> String -> List String -> ( List String, List Char )
readArgsHelp chars depth current done =
    case chars of
        [] ->
            ( List.reverse (stripArg current :: done), [] )

        ')' :: rest ->
            if depth == 0 then
                ( List.reverse (stripArg current :: done), rest )

            else
                readArgsHelp rest (depth - 1) (current ++ ")") done

        '(' :: rest ->
            readArgsHelp rest (depth + 1) (current ++ "(") done

        ',' :: rest ->
            if depth == 0 then
                readArgsHelp rest depth "" (stripArg current :: done)

            else
                readArgsHelp rest depth (current ++ ",") done

        '`' :: rest ->
            let
                ( inner, after ) =
                    readQuote 1 rest []
            in
            readArgsHelp after depth (current ++ inner) done

        c :: rest ->
            readArgsHelp rest depth (current ++ String.fromChar c) done


{-| Strips the leading (unquoted) whitespace m4 ignores at the start of each argument. -}
stripArg : String -> String
stripArg s =
    String.trimLeft s



-- SUBSTITUTION -----------------------------------------------------------


{-| Replaces the `$…` placeholders in a macro body: `$0` is the macro name, `$1 … $9` the arguments,
`$#` the argument count and `$*` the comma-joined arguments. -}
substitute : String -> List String -> String -> String
substitute name args body =
    subst name args (String.toList body) []


subst : String -> List String -> List Char -> List Char -> String
subst name args chars acc =
    case chars of
        '$' :: d :: rest ->
            if Char.isDigit d then
                let
                    i =
                        Char.toCode d - Char.toCode '0'

                    value =
                        if i == 0 then
                            name

                        else
                            nth (i - 1) args |> Maybe.withDefault ""
                in
                subst name args rest (prepend value acc)

            else if d == '#' then
                subst name args rest (prepend (String.fromInt (List.length args)) acc)

            else if d == '*' then
                subst name args rest (prepend (String.join "," args) acc)

            else
                subst name args (d :: rest) ('$' :: acc)

        c :: rest ->
            subst name args rest (c :: acc)

        [] ->
            String.fromList (List.reverse acc)


{-| Prepends a string's characters (reversed) onto the reversed-accumulator. -}
prepend : String -> List Char -> List Char
prepend s acc =
    List.foldl (\c a -> c :: a) acc (String.toList s)



-- HELPERS ----------------------------------------------------------------


isIdentStart : Char -> Bool
isIdentStart c =
    Char.isAlpha c || c == '_'


isIdentChar : Char -> Bool
isIdentChar c =
    Char.isAlphaNum c || c == '_'


hasMacro : String -> List ( String, String ) -> Bool
hasMacro name env =
    case lookupMacro name env of
        Just _ ->
            True

        Nothing ->
            False


lookupMacro : String -> List ( String, String ) -> Maybe String
lookupMacro name env =
    case env of
        ( n, b ) :: rest ->
            if n == name then
                Just b

            else
                lookupMacro name rest

        [] ->
            Nothing


nth : Int -> List a -> Maybe a
nth i xs =
    List.head (List.drop i xs)


dropThroughNewline : List Char -> List Char
dropThroughNewline chars =
    case chars of
        [] ->
            []

        '\n' :: rest ->
            rest

        _ :: rest ->
            dropThroughNewline rest

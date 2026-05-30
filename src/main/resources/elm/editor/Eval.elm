module Eval exposing (eval, evalProject, debugSteps, lookup, renderValue, appInit, appUpdate, appView, hasApp, renderProgram)

{-| The evaluator for the interpreted language. Global (top-level) definitions are threaded through
evaluation so all definitions across the project's files form one mutually-recursive scope. Public
entry points: `eval` (one expression), `evalProject` (entry expression against all files) and
`debugSteps` (fold messages through update for the time-travel debugger). -}

import Lang exposing (Decl, Env, Expr(..), Globals, Pattern(..), Value(..))
import Lexer exposing (tokenize)
import Parser exposing (parse, parseProject)


{-| Native builtins available to interpreted programs (resolved when a name is in neither the local
scope nor the project's top-level definitions). Includes Html element/attribute constructors so The
Elm Architecture programs (Browser.sandbox apps) can be rendered live by the editor. -}
builtins : List String
builtins =
    htmlTags ++ [ "text", "onClick", "style", "toString", "negate", "not", "String.fromInt", "String.fromFloat", "Browser.sandbox" ]


{-| The Html element builtins (each takes a list of attributes then a list of children). -}
htmlTags : List String
htmlTags =
    [ "div", "button", "p", "span", "h1", "h2", "h3", "h4", "ul", "ol", "li", "pre", "code", "input", "textarea", "label", "a", "section", "strong", "em", "br", "img", "table", "tr", "td", "th" ]


{-| How many arguments a builtin consumes before it runs. -}
arity : String -> Int
arity name =
    if List.member name [ "text", "onClick", "toString", "negate", "not", "String.fromInt", "String.fromFloat", "Browser.sandbox" ] then
        1

    else
        2


evalExpr : Globals -> Env -> Expr -> Result String Value
evalExpr globals env expr =
    case expr of
        Num n ->
            Ok (VNum n)

        Str s ->
            Ok (VStr s)

        Boolean b ->
            Ok (VBool b)

        Var name ->
            case lookup name env of
                Just v ->
                    Ok v

                Nothing ->
                    case lookup name globals of
                        Just decl ->
                            if List.isEmpty decl.params then
                                evalExpr globals [] decl.body

                            else
                                Ok (VClosure decl.params decl.body [])

                        Nothing ->
                            if List.member name builtins then
                                Ok (VBuiltin name [])

                            else
                                Err ("undefined variable: " ++ name)

        Ctor name ->
            Ok (VCtor name [])

        Case subject branches ->
            evalExpr globals env subject
                |> Result.andThen (\v -> evalCase globals env v branches)

        ListE items ->
            evalList globals env items []

        Neg inner ->
            evalExpr globals env inner
                |> Result.andThen
                    (\v ->
                        case v of
                            VNum n ->
                                Ok (VNum (negate n))

                            _ ->
                                Err "cannot negate a non-number"
                    )

        If cond then_ else_ ->
            evalExpr globals env cond
                |> Result.andThen
                    (\v ->
                        case v of
                            VBool True ->
                                evalExpr globals env then_

                            VBool False ->
                                evalExpr globals env else_

                            _ ->
                                Err "if condition must be a Bool"
                    )

        Let name boundExpr body ->
            case boundExpr of
                Lam params lamBody ->
                    evalExpr globals (( name, VRec name params lamBody env ) :: env) body

                _ ->
                    evalExpr globals env boundExpr
                        |> Result.andThen (\v -> evalExpr globals (( name, v ) :: env) body)

        Lam params body ->
            Ok (VClosure params body env)

        App fn arg ->
            evalExpr globals env fn
                |> Result.andThen
                    (\fv ->
                        evalExpr globals env arg
                            |> Result.andThen (\av -> applyValue globals fv av)
                    )

        BinOp op l r ->
            evalExpr globals env l
                |> Result.andThen
                    (\lv ->
                        evalExpr globals env r
                            |> Result.andThen (\rv -> applyOp op lv rv)
                    )

        RecordLit fields ->
            evalFields globals env fields []

        RecordGet target field ->
            case target of
                -- A qualified name like `String.fromInt` parses as RecordGet (Ctor "String")
                -- "fromInt"; resolve it to the matching builtin when there is one.
                Ctor moduleName ->
                    let
                        qualified =
                            moduleName ++ "." ++ field
                    in
                    if List.member qualified builtins then
                        Ok (VBuiltin qualified [])

                    else
                        Err ("unknown qualified name: " ++ qualified)

                _ ->
                    evalExpr globals env target
                        |> Result.andThen
                            (\v ->
                                case v of
                                    VRecord fs ->
                                        case lookup field fs of
                                            Just x ->
                                                Ok x

                                            Nothing ->
                                                Err ("record has no field ." ++ field)

                                    _ ->
                                        Err ("." ++ field ++ " needs a record")
                            )

        RecordUpdate name fields ->
            evalExpr globals env (Var name)
                |> Result.andThen
                    (\v ->
                        case v of
                            VRecord base ->
                                evalFields globals env fields []
                                    |> Result.andThen
                                        (\nv ->
                                            case nv of
                                                VRecord updates ->
                                                    Ok (VRecord (mergeFields base updates))

                                                _ ->
                                                    Err "internal: record update"
                                        )

                            _ ->
                                Err ("cannot update " ++ name ++ ": not a record")
                    )


evalFields : Globals -> Env -> List ( String, Expr ) -> List ( String, Value ) -> Result String Value
evalFields globals env fields acc =
    case fields of
        [] ->
            Ok (VRecord (List.reverse acc))

        ( name, expr ) :: rest ->
            evalExpr globals env expr
                |> Result.andThen (\v -> evalFields globals env rest (( name, v ) :: acc))


{-| Returns `base` with each field of `updates` replaced (or appended if new). -}
mergeFields : List ( String, Value ) -> List ( String, Value ) -> List ( String, Value )
mergeFields base updates =
    let
        replaced =
            List.map
                (\pair ->
                    case lookup (Tuple.first pair) updates of
                        Just v ->
                            ( Tuple.first pair, v )

                        Nothing ->
                            pair
                )
                base

        added =
            List.filter (\u -> lookup (Tuple.first u) base == Nothing) updates
    in
    replaced ++ added


evalList : Globals -> Env -> List Expr -> List Value -> Result String Value
evalList globals env items acc =
    case items of
        [] ->
            Ok (VList (List.reverse acc))

        x :: rest ->
            evalExpr globals env x |> Result.andThen (\v -> evalList globals env rest (v :: acc))


applyValue : Globals -> Value -> Value -> Result String Value
applyValue globals fn arg =
    case fn of
        VClosure params body closedEnv ->
            applyClosure globals params body closedEnv arg

        VRec name params body closedEnv ->
            applyClosure globals params body (( name, fn ) :: closedEnv) arg

        VCtor name args ->
            Ok (VCtor name (args ++ [ arg ]))

        VBuiltin name args ->
            let
                collected =
                    args ++ [ arg ]
            in
            if List.length collected >= arity name then
                runBuiltin name collected

            else
                Ok (VBuiltin name collected)

        _ ->
            Err "cannot apply a non-function value"


{-| Runs a fully-applied builtin. Html element/attribute builtins produce a structured `Value` tree
(VCtor "Html.node"/"Html.text"/"Html.on"/"Html.style") the editor renders to live Html. -}
runBuiltin : String -> List Value -> Result String Value
runBuiltin name args =
    if List.member name htmlTags then
        case args of
            [ attrs, children ] ->
                Ok (VCtor "Html.node" [ VStr name, attrs, children ])

            _ ->
                Err (name ++ " needs attributes and children")

    else
        case ( name, args ) of
            ( "text", [ v ] ) ->
                Ok (VCtor "Html.text" [ v ])

            ( "onClick", [ msg ] ) ->
                Ok (VCtor "Html.on" [ VStr "click", msg ])

            ( "style", [ k, v ] ) ->
                Ok (VCtor "Html.style" [ k, v ])

            ( "toString", [ VStr s ] ) ->
                Ok (VStr s)

            ( "toString", [ v ] ) ->
                Ok (VStr (renderValue v))

            ( "negate", [ VNum n ] ) ->
                Ok (VNum (negate n))

            ( "not", [ VBool b ] ) ->
                Ok (VBool (not b))

            ( "String.fromInt", [ VNum n ] ) ->
                Ok (VStr (String.fromInt (round n)))

            ( "String.fromFloat", [ VNum n ] ) ->
                Ok (VStr (String.fromFloat n))

            ( "Browser.sandbox", [ config ] ) ->
                -- The editor drives init/update/view directly; evaluating `main` just yields the config.
                Ok config

            _ ->
                Err ("bad arguments to " ++ name)


applyClosure : Globals -> List String -> Expr -> Env -> Value -> Result String Value
applyClosure globals params body closedEnv arg =
    case params of
        [] ->
            Err "cannot apply a non-function"

        p :: [] ->
            evalExpr globals (( p, arg ) :: closedEnv) body

        p :: more ->
            Ok (VClosure more body (( p, arg ) :: closedEnv))


evalCase : Globals -> Env -> Value -> List ( Pattern, Expr ) -> Result String Value
evalCase globals env subject branches =
    case branches of
        [] ->
            Err "no matching case branch"

        ( pat, body ) :: rest ->
            case matchPattern pat subject of
                Just bindings ->
                    evalExpr globals (bindings ++ env) body

                Nothing ->
                    evalCase globals env subject rest


matchPattern : Pattern -> Value -> Maybe (List ( String, Value ))
matchPattern pat value =
    case ( pat, value ) of
        ( PWild, _ ) ->
            Just []

        ( PVar name, _ ) ->
            Just [ ( name, value ) ]

        ( PInt x, VNum y ) ->
            if x == y then
                Just []

            else
                Nothing

        ( PBool x, VBool y ) ->
            if x == y then
                Just []

            else
                Nothing

        ( PStr x, VStr y ) ->
            if x == y then
                Just []

            else
                Nothing

        ( PNil, VList [] ) ->
            Just []

        ( PCons hp tp, VList (h :: t) ) ->
            matchPattern hp h
                |> Maybe.andThen (\hb -> matchPattern tp (VList t) |> Maybe.map (\tb -> hb ++ tb))

        ( PCtor name pats, VCtor vname vargs ) ->
            if name == vname && List.length pats == List.length vargs then
                matchAll pats vargs

            else
                Nothing

        _ ->
            Nothing


matchAll : List Pattern -> List Value -> Maybe (List ( String, Value ))
matchAll pats values =
    case ( pats, values ) of
        ( [], [] ) ->
            Just []

        ( p :: ps, v :: vs ) ->
            matchPattern p v
                |> Maybe.andThen (\b -> matchAll ps vs |> Maybe.map (\rest -> b ++ rest))

        _ ->
            Nothing


applyOp : String -> Value -> Value -> Result String Value
applyOp op a b =
    if op == "::" then
        case b of
            VList xs ->
                Ok (VList (a :: xs))

            _ ->
                Err ":: needs a list on the right"

    else if op == "++" then
        case ( a, b ) of
            ( VStr x, VStr y ) ->
                Ok (VStr (x ++ y))

            ( VList x, VList y ) ->
                Ok (VList (x ++ y))

            _ ->
                Err "++ needs two Strings or two Lists"

    else if op == "&&" || op == "||" then
        case ( a, b ) of
            ( VBool x, VBool y ) ->
                Ok (VBool (if op == "&&" then x && y else x || y))

            _ ->
                Err "&& and || need Bools"

    else if List.member op [ "==", "/=" ] then
        Ok (VBool (if op == "==" then valueEq a b else not (valueEq a b)))

    else
        case ( a, b ) of
            ( VNum x, VNum y ) ->
                arithOrCompare op x y

            _ ->
                Err (op ++ " needs two numbers")


arithOrCompare : String -> Float -> Float -> Result String Value
arithOrCompare op x y =
    if op == "+" then
        Ok (VNum (x + y))

    else if op == "-" then
        Ok (VNum (x - y))

    else if op == "*" then
        Ok (VNum (x * y))

    else if op == "/" then
        if y == 0 then
            Err "division by zero"

        else
            Ok (VNum (x / y))

    else if op == "//" then
        if y == 0 then
            Err "division by zero"

        else
            Ok (VNum (toFloat (truncate (x / y))))

    else if op == "<" then
        Ok (VBool (x < y))

    else if op == "<=" then
        Ok (VBool (x <= y))

    else if op == ">" then
        Ok (VBool (x > y))

    else if op == ">=" then
        Ok (VBool (x >= y))

    else
        Err ("unknown operator: " ++ op)


valueEq : Value -> Value -> Bool
valueEq a b =
    case ( a, b ) of
        ( VNum x, VNum y ) ->
            x == y

        ( VBool x, VBool y ) ->
            x == y

        ( VStr x, VStr y ) ->
            x == y

        ( VList x, VList y ) ->
            listEq x y

        ( VCtor n1 a1, VCtor n2 a2 ) ->
            n1 == n2 && listEq a1 a2

        ( VRecord f1, VRecord f2 ) ->
            List.length f1 == List.length f2 && List.all (fieldMatches f2) f1

        _ ->
            False


fieldMatches : List ( String, Value ) -> ( String, Value ) -> Bool
fieldMatches other pair =
    case lookup (Tuple.first pair) other of
        Just v ->
            valueEq (Tuple.second pair) v

        Nothing ->
            False


listEq : List Value -> List Value -> Bool
listEq xs ys =
    case ( xs, ys ) of
        ( [], [] ) ->
            True

        ( x :: xrest, y :: yrest ) ->
            valueEq x y && listEq xrest yrest

        _ ->
            False


lookup : String -> List ( String, a ) -> Maybe a
lookup name pairs =
    case pairs of
        [] ->
            Nothing

        ( k, v ) :: rest ->
            if k == name then
                Just v

            else
                lookup name rest



-- RENDERING


renderValue : Value -> String
renderValue v =
    case v of
        VNum n ->
            String.fromFloat n

        VBool b ->
            if b then
                "True"

            else
                "False"

        VStr s ->
            "\"" ++ s ++ "\""

        VList items ->
            "[" ++ String.join ", " (List.map renderValue items) ++ "]"

        VCtor name args ->
            if List.isEmpty args then
                name

            else
                name ++ " " ++ String.join " " (List.map renderValueAtom args)

        VRecord fields ->
            if List.isEmpty fields then
                "{}"

            else
                "{ " ++ String.join ", " (List.map (\f -> Tuple.first f ++ " = " ++ renderValue (Tuple.second f)) fields) ++ " }"

        VClosure _ _ _ ->
            "<function>"

        VRec _ _ _ _ ->
            "<function>"

        VBuiltin name _ ->
            "<" ++ name ++ ">"


renderValueAtom : Value -> String
renderValueAtom v =
    case v of
        VCtor _ args ->
            if List.isEmpty args then
                renderValue v

            else
                "(" ++ renderValue v ++ ")"

        _ ->
            renderValue v



-- PUBLIC ENTRY POINTS


{-| Evaluates a single expression in an empty scope (used for messages and the simple REPL). -}
eval : String -> String
eval src =
    case tokenize src |> Result.andThen parse |> Result.andThen (evalExpr [] []) of
        Ok v ->
            renderValue v

        Err e ->
            "Error: " ++ e


{-| Evaluates the entry expression against the top-level definitions of all files. -}
evalProject : List ( String, String ) -> String -> String
evalProject files entry =
    case parseProject files of
        Err e ->
            "Parse error: " ++ e

        Ok globals ->
            case tokenize entry |> Result.andThen parse of
                Err e ->
                    "Error: " ++ e

                Ok expr ->
                    case evalExpr globals [] expr of
                        Ok v ->
                            renderValue v

                        Err e ->
                            "Error: " ++ e


{-| Folds the message expressions through `update`, returning, per step, the message text and the
rendered model and view — the data behind the time-travel debugger. Step 0 is the initial model. -}
debugSteps : List ( String, String ) -> List String -> List String
debugSteps files messageLines =
    case parseProject files of
        Err e ->
            [ "Parse error: " ++ e ]

        Ok globals ->
            case ( evalGlobal globals "init", findDecl globals "update" ) of
                ( Ok initModel, True ) ->
                    let
                        msgs =
                            List.filter (\s -> String.trim s /= "") messageLines
                    in
                    stepFold globals initModel msgs [ formatStep globals "(init)" initModel ]

                _ ->
                    [ "Define top-level `init`, `update` and `view` to use the debugger." ]


stepFold : Globals -> Value -> List String -> List String -> List String
stepFold globals model msgs acc =
    case msgs of
        [] ->
            List.reverse acc

        line :: rest ->
            case tokenize line |> Result.andThen parse |> Result.andThen (evalExpr globals []) of
                Err e ->
                    List.reverse (("✗ " ++ line ++ " -> " ++ e) :: acc)

                Ok msg ->
                    case applyUpdate globals msg model of
                        Err e ->
                            List.reverse (("✗ " ++ line ++ " -> " ++ e) :: acc)

                        Ok next ->
                            stepFold globals next rest (formatStep globals line next :: acc)


applyUpdate : Globals -> Value -> Value -> Result String Value
applyUpdate globals msg model =
    evalExpr globals [] (Var "update")
        |> Result.andThen (\u -> applyValue globals u msg)
        |> Result.andThen (\u1 -> applyValue globals u1 model)


formatStep : Globals -> String -> Value -> String
formatStep globals label model =
    let
        viewText =
            case evalGlobal globals "view" of
                Ok _ ->
                    case evalExpr globals [] (Var "view") |> Result.andThen (\f -> applyValue globals f model) of
                        Ok v ->
                            "  view: " ++ renderValue v

                        Err _ ->
                            ""

                Err _ ->
                    ""
    in
    label ++ "  =>  model: " ++ renderValue model ++ viewText


evalGlobal : Globals -> String -> Result String Value
evalGlobal globals name =
    if findDecl globals name then
        evalExpr globals [] (Var name)

    else
        Err ("missing " ++ name)


findDecl : Globals -> String -> Bool
findDecl globals name =
    case lookup name globals of
        Just _ ->
            True

        Nothing ->
            False



-- LIVE APP (The Elm Architecture): drive a Browser.sandbox-style init/update/view interactively.


{-| Whether the project defines the `init`, `update` and `view` of a runnable app. -}
hasApp : List ( String, String ) -> Bool
hasApp files =
    case parseProject files of
        Ok globals ->
            findDecl globals "init" && findDecl globals "update" && findDecl globals "view"

        Err _ ->
            False


{-| The app's initial model value. -}
appInit : List ( String, String ) -> Result String Value
appInit files =
    parseProject files |> Result.andThen (\globals -> evalGlobal globals "init")


{-| Runs `update msg model`, producing the next model value. -}
appUpdate : List ( String, String ) -> Value -> Value -> Result String Value
appUpdate files msg model =
    parseProject files |> Result.andThen (\globals -> applyUpdate globals msg model)


{-| Evaluates `view model` to the Html `Value` tree the editor renders to live Html. -}
appView : List ( String, String ) -> Value -> Result String Value
appView files model =
    parseProject files
        |> Result.andThen
            (\globals ->
                evalExpr globals [] (Var "view")
                    |> Result.andThen (\f -> applyValue globals f model)
            )


{-| Headless render of a single-file app's initial view to an HTML string (used in tests and as a
quick non-DOM preview): runs `init` then `view`, serialising the Html `Value` tree. -}
renderProgram : String -> String
renderProgram source =
    let
        files =
            [ ( "Main.elm", source ) ]
    in
    case appInit files of
        Err e ->
            "init error: " ++ e

        Ok model ->
            case appView files model of
                Ok html ->
                    htmlToString html

                Err e ->
                    "view error: " ++ e


htmlToString : Value -> String
htmlToString v =
    case v of
        VCtor "Html.text" [ VStr s ] ->
            s

        VCtor "Html.text" [ other ] ->
            renderValue other

        VCtor "Html.node" [ VStr tag, VList attrs, VList children ] ->
            "<" ++ tag ++ attrsToString attrs ++ ">" ++ String.concat (List.map htmlToString children) ++ "</" ++ tag ++ ">"

        _ ->
            renderValue v


attrsToString : List Value -> String
attrsToString attrs =
    String.concat (List.map attrToString attrs)


attrToString : Value -> String
attrToString v =
    case v of
        VCtor "Html.on" [ VStr ev, msg ] ->
            " on" ++ ev ++ "=" ++ renderValue msg

        VCtor "Html.style" [ VStr k, VStr val ] ->
            " style=" ++ k ++ ":" ++ val

        _ ->
            ""

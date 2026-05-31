module Eval exposing (eval, evalProject, debugSteps, lookup, renderValue, appInit, appUpdate, appView, hasApp, renderProgram, mainValue, applyHandler, appInitCmd, appUpdateCmd, appSubscription, randomCmd, applyMsgIn)

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
    htmlTags
        ++ htmlStringAttrs
        ++ htmlBoolAttrs
        ++ [ "text", "onClick", "onInput", "style", "toString", "negate", "not", "String.fromInt", "String.fromFloat" ]
        ++ [ "Browser.sandbox", "Browser.element" ]
        ++ [ "List.range", "List.map", "List.length", "List.sum", "String.join", "Maybe.withDefault" ]
        ++ [ "String.reverse", "String.length", "String.toUpper", "String.toLower", "String.trim" ]
        ++ [ "cos", "sin", "tan", "sqrt", "toFloat", "round", "floor", "ceiling", "truncate", "abs" ]
        ++ [ "Time.millisToPosix", "Time.posixToMillis", "Time.toHour", "Time.toMinute", "Time.toSecond", "Time.every" ]
        ++ [ "Random.int", "Random.float", "Random.uniform", "Random.generate" ]
        ++ playgroundNames


{-| evancz/elm-playground builtins: shape constructors, transforms, colours and the `picture`/
`animation` entry points, implemented natively in the editor (rendering to SVG `Value` trees). The
`circle`/`polygon` names overlap with the Svg builtins but are disambiguated at run time by the
argument types (`circle color radius` vs `circle attrs children`). -}
playgroundNames : List String
playgroundNames =
    [ "picture", "animation", "oval", "rectangle", "square", "triangle", "pentagon", "hexagon", "octagon", "words" ]
        ++ [ "move", "moveUp", "moveDown", "moveLeft", "moveRight", "moveX", "moveY", "rotate", "scale", "fade" ]
        ++ [ "rgb", "spin", "wave", "zigzag" ]


{-| The Html (and inline SVG) element builtins (each takes a list of attributes then a list of
children). Inline SVG renders directly in the browser, so `svg`/`circle`/… serialize like any node. -}
htmlTags : List String
htmlTags =
    [ "div", "button", "p", "span", "h1", "h2", "h3", "h4", "ul", "ol", "li", "pre", "code", "input", "textarea", "label", "a", "section", "strong", "em", "br", "img", "table", "tr", "td", "th" ]
        ++ [ "svg", "circle", "rect", "line", "ellipse", "polygon", "polyline", "path", "g", "text_", "defs", "stop", "linearGradient", "radialGradient" ]


{-| `Html.Attributes` / `Svg.Attributes` taking a single string, rendered as `key=value`. (`type_`
maps to `type`; the camelCase SVG names map to their hyphenated attribute — see `attrKey`.) -}
htmlStringAttrs : List String
htmlStringAttrs =
    [ "placeholder", "value", "type_", "class", "id", "href", "src", "title", "alt", "name", "for", "target", "rel", "width", "height", "rows", "cols", "autocomplete", "step" ]
        ++ [ "viewBox", "cx", "cy", "r", "x", "y", "x1", "y1", "x2", "y2", "rx", "ry", "fill", "stroke", "points", "d", "transform", "offset", "opacity" ]
        ++ [ "strokeWidth", "strokeLinecap", "strokeDasharray", "fillOpacity", "stopColor", "textAnchor", "fontSize", "fontFamily", "gradientUnits" ]


{-| `Html.Attributes` taking a single bool, rendered as a bare `key` when `True`. -}
htmlBoolAttrs : List String
htmlBoolAttrs =
    [ "checked", "disabled", "selected", "readonly", "autofocus", "hidden" ]


{-| How many arguments a builtin consumes before it runs. -}
arity : String -> Int
arity name =
    if List.member name [ "text", "onClick", "onInput", "toString", "negate", "not", "String.fromInt", "String.fromFloat", "String.reverse", "String.length", "String.toUpper", "String.toLower", "String.trim", "Browser.sandbox", "Browser.element", "List.length", "List.sum" ] then
        1

    else if List.member name [ "cos", "sin", "tan", "sqrt", "toFloat", "round", "floor", "ceiling", "truncate", "abs", "Time.millisToPosix", "Time.posixToMillis", "picture", "animation" ] then
        1

    else if List.member name [ "oval", "rectangle", "move", "rgb" ] then
        3

    else if List.member name [ "wave", "zigzag" ] then
        4

    else if List.member name htmlStringAttrs || List.member name htmlBoolAttrs then
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
                            if name == "pi" then
                                Ok (VNum pi)

                            else if name == "e" then
                                Ok (VNum e)

                            else
                                case playgroundColor name of
                                    Just hex ->
                                        Ok (VStr hex)

                                    Nothing ->
                                        if List.member name builtins then
                                            Ok (VBuiltin name [])

                                        else
                                            Err ("undefined variable: " ++ name)

        Ctor name ->
            -- A `type alias` record constructor is registered as a global; everything else
            -- (custom-type constructors) builds a tagged value.
            case lookup name globals of
                Just decl ->
                    if List.isEmpty decl.params then
                        evalExpr globals [] decl.body

                    else
                        Ok (VClosure decl.params decl.body [])

                Nothing ->
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
                    if moduleName == "Cmd" || moduleName == "Sub" || moduleName == "Task" then
                        -- Effects are opaque no-ops in the editor (Cmd.none, Sub.none, Task.perform …).
                        Ok (VCtor moduleName [])

                    else if qualified == "Time.here" || qualified == "Time.now" then
                        -- These effectful Time values are opaque (they only feed a discarded Cmd).
                        Ok (VCtor "Cmd" [])

                    else if qualified == "Time.utc" then
                        Ok (VNum 0)
                        -- a Zone, modelled as a 0 offset

                    else if List.member qualified builtins then
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

        Tup items ->
            evalTupleItems globals env items []


evalTupleItems : Globals -> Env -> List Expr -> List Value -> Result String Value
evalTupleItems globals env items acc =
    case items of
        [] ->
            Ok (VTup (List.reverse acc))

        x :: rest ->
            evalExpr globals env x |> Result.andThen (\v -> evalTupleItems globals env rest (v :: acc))


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
                runBuiltin globals name collected

            else
                Ok (VBuiltin name collected)

        _ ->
            Err "cannot apply a non-function value"


{-| Runs a fully-applied builtin. Html element/attribute builtins produce a structured `Value` tree
(VCtor "Html.node"/"Html.text"/"Html.on"/"Html.style") the editor renders to live Html. Threads
`globals` so higher-order builtins (List.map) can apply the function value they're given. -}
runBuiltin : Globals -> String -> List Value -> Result String Value
runBuiltin globals name args =
    if name == "circle" && playgroundCircle args then
        -- Playground `circle color radius` (Svg `circle attrs children` falls through below).
        Ok (mkShape (VCtor "PCircle" args))

    else if List.member name playgroundNames then
        runPlayground globals name args

    else if List.member name htmlTags then
        case args of
            [ attrs, children ] ->
                Ok (VCtor "Html.node" [ VStr name, attrs, children ])

            _ ->
                Err (name ++ " needs attributes and children")

    else if List.member name htmlStringAttrs || List.member name htmlBoolAttrs then
        case args of
            [ v ] ->
                Ok (VCtor "Html.attr" [ VStr (attrKey name), v ])

            _ ->
                Err (name ++ " needs a value")

    else
        case ( name, args ) of
            ( "text", [ v ] ) ->
                Ok (VCtor "Html.text" [ v ])

            ( "onClick", [ msg ] ) ->
                Ok (VCtor "Html.on" [ VStr "click", msg ])

            ( "onInput", [ handler ] ) ->
                -- The handler (e.g. a Msg constructor) is applied to the input string at event time.
                Ok (VCtor "Html.on" [ VStr "input", handler ])

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

            ( "String.reverse", [ VStr s ] ) ->
                Ok (VStr (String.reverse s))

            ( "String.length", [ VStr s ] ) ->
                Ok (VNum (toFloat (String.length s)))

            ( "String.toUpper", [ VStr s ] ) ->
                Ok (VStr (String.toUpper s))

            ( "String.toLower", [ VStr s ] ) ->
                Ok (VStr (String.toLower s))

            ( "String.trim", [ VStr s ] ) ->
                Ok (VStr (String.trim s))

            ( "Browser.sandbox", [ config ] ) ->
                -- The editor drives init/update/view directly; evaluating `main` just yields the config.
                Ok config

            ( "Browser.element", [ config ] ) ->
                Ok config

            ( "List.length", [ VList xs ] ) ->
                Ok (VNum (toFloat (List.length xs)))

            ( "List.sum", [ VList xs ] ) ->
                Ok (VNum (List.sum (List.filterMap asNum xs)))

            ( "List.range", [ VNum a, VNum b ] ) ->
                Ok (VList (List.map (\n -> VNum (toFloat n)) (List.range (round a) (round b))))

            ( "List.map", [ f, VList xs ] ) ->
                mapValues globals f xs |> Result.map VList

            ( "String.join", [ VStr sep, VList xs ] ) ->
                Ok (VStr (String.join sep (List.map renderStr xs)))

            ( "Maybe.withDefault", [ dflt, v ] ) ->
                case v of
                    VCtor "Just" [ x ] ->
                        Ok x

                    VCtor "Nothing" [] ->
                        Ok dflt

                    _ ->
                        Ok dflt

            ( "cos", [ VNum n ] ) ->
                Ok (VNum (cos n))

            ( "sin", [ VNum n ] ) ->
                Ok (VNum (sin n))

            ( "tan", [ VNum n ] ) ->
                Ok (VNum (tan n))

            ( "sqrt", [ VNum n ] ) ->
                Ok (VNum (sqrt n))

            ( "toFloat", [ VNum n ] ) ->
                Ok (VNum n)

            ( "round", [ VNum n ] ) ->
                Ok (VNum (toFloat (round n)))

            ( "floor", [ VNum n ] ) ->
                Ok (VNum (toFloat (floor n)))

            ( "ceiling", [ VNum n ] ) ->
                Ok (VNum (toFloat (ceiling n)))

            ( "truncate", [ VNum n ] ) ->
                Ok (VNum (toFloat (truncate n)))

            ( "abs", [ VNum n ] ) ->
                Ok (VNum (abs n))

            -- Time: a Posix is modelled as its milliseconds (a VNum); the Zone is ignored (UTC).
            ( "Time.millisToPosix", [ VNum n ] ) ->
                Ok (VNum n)

            ( "Time.posixToMillis", [ VNum n ] ) ->
                Ok (VNum n)

            ( "Time.toHour", [ _, VNum ms ] ) ->
                Ok (VNum (toFloat (modBy 24 (round ms // 3600000))))

            ( "Time.toMinute", [ _, VNum ms ] ) ->
                Ok (VNum (toFloat (modBy 60 (round ms // 60000))))

            ( "Time.toSecond", [ _, VNum ms ] ) ->
                Ok (VNum (toFloat (modBy 60 (round ms // 1000))))

            -- `Time.every interval toMsg` is a subscription the editor inspects to drive a live tick.
            ( "Time.every", [ VNum interval, toMsg ] ) ->
                Ok (VCtor "Sub.every" [ VNum interval, toMsg ])

            -- Random generators carry their spec so the editor can sample them with its own seed.
            ( "Random.int", [ VNum lo, VNum hi ] ) ->
                Ok (VCtor "Random.Gen" [ VStr "int", VNum lo, VNum hi ])

            ( "Random.float", [ VNum lo, VNum hi ] ) ->
                Ok (VCtor "Random.Gen" [ VStr "float", VNum lo, VNum hi ])

            ( "Random.uniform", [ first, VList rest ] ) ->
                Ok (VCtor "Random.Gen" [ VStr "uniform", VList (first :: rest) ])

            ( "Random.generate", [ toMsg, gen ] ) ->
                Ok (VCtor "Cmd.random" [ toMsg, gen ])

            _ ->
                Err ("bad arguments to " ++ name)


asNum : Value -> Maybe Float
asNum v =
    case v of
        VNum n ->
            Just n

        _ ->
            Nothing


renderStr : Value -> String
renderStr v =
    case v of
        VStr s ->
            s

        _ ->
            renderValue v


{-| Maps a function value over a list, short-circuiting on the first error. -}
mapValues : Globals -> Value -> List Value -> Result String (List Value)
mapValues globals f xs =
    case xs of
        [] ->
            Ok []

        x :: rest ->
            applyValue globals f x
                |> Result.andThen (\y -> mapValues globals f rest |> Result.map (\ys -> y :: ys))


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

        ( PTup pats, VTup vs ) ->
            if List.length pats == List.length vs then
                matchAll pats vs

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

        ( VTup x, VTup y ) ->
            listEq x y

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

        VTup items ->
            "(" ++ String.join ", " (List.map renderValue items) ++ ")"

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


{-| The app's initial model value. For a Browser.element program `init` is `flags -> (model, cmd)`,
so it is applied to unit flags and the model taken from the tuple; for Browser.sandbox `init` is the
model directly. -}
appInit : List ( String, String ) -> Result String Value
appInit files =
    parseProject files
        |> Result.andThen
            (\globals ->
                evalGlobal globals "init"
                    |> Result.andThen
                        (\initVal ->
                            case initVal of
                                VClosure _ _ _ ->
                                    applyValue globals initVal (VTup []) |> Result.map modelOf

                                VRec _ _ _ _ ->
                                    applyValue globals initVal (VTup []) |> Result.map modelOf

                                _ ->
                                    Ok (modelOf initVal)
                        )
            )


{-| Runs `update msg model`, producing the next model value (unwrapping a Browser.element
`(model, cmd)` tuple to just the model). -}
appUpdate : List ( String, String ) -> Value -> Value -> Result String Value
appUpdate files msg model =
    parseProject files
        |> Result.andThen (\globals -> applyUpdate globals msg model |> Result.map modelOf)


{-| The model out of an init/update result: the first element of a `(model, Cmd)` tuple, else the
value itself (a Browser.sandbox model). -}
modelOf : Value -> Value
modelOf v =
    case v of
        VTup (m :: _) ->
            m

        _ ->
            v


-- Cmd/Sub-aware variants the editor uses to run effects (Random) and subscriptions (Time.every).


{-| A no-op command (e.g. `Cmd.none`, or a sandbox update with no command). -}
noCmd : Value
noCmd =
    VCtor "Cmd" []


{-| Splits an init/update result into (model, command). -}
splitMC : Value -> ( Value, Value )
splitMC v =
    case v of
        VTup (m :: c :: _) ->
            ( m, c )

        VTup (m :: _) ->
            ( m, noCmd )

        _ ->
            ( v, noCmd )


{-| Like {@link appInit} but also returns the initial command. -}
appInitCmd : List ( String, String ) -> Result String ( Value, Value )
appInitCmd files =
    parseProject files
        |> Result.andThen
            (\globals ->
                evalGlobal globals "init"
                    |> Result.andThen
                        (\initVal ->
                            case initVal of
                                VClosure _ _ _ ->
                                    applyValue globals initVal (VTup []) |> Result.map splitMC

                                VRec _ _ _ _ ->
                                    applyValue globals initVal (VTup []) |> Result.map splitMC

                                _ ->
                                    Ok ( initVal, noCmd )
                        )
            )


{-| Like {@link appUpdate} but also returns the command produced by `update`. -}
appUpdateCmd : List ( String, String ) -> Value -> Value -> Result String ( Value, Value )
appUpdateCmd files msg model =
    parseProject files
        |> Result.andThen (\globals -> applyUpdate globals msg model |> Result.map splitMC)


{-| Applies a message-producing function (a `Random.generate`/`Time.every` constructor) to a value. -}
applyMsgIn : List ( String, String ) -> Value -> Value -> Result String Value
applyMsgIn files fn arg =
    parseProject files
        |> Result.andThen (\globals -> applyValue globals fn arg)


{-| If the app subscribes via `Time.every`, the (interval-ms, toMsg) the editor wires to a tick. -}
appSubscription : List ( String, String ) -> Value -> Maybe ( Int, Value )
appSubscription files model =
    case parseProject files of
        Ok globals ->
            case evalGlobal globals "subscriptions" |> Result.andThen (\f -> applyValue globals f model) of
                Ok (VCtor "Sub.every" [ VNum interval, toMsg ]) ->
                    Just ( round interval, toMsg )

                _ ->
                    Nothing

        Err _ ->
            Nothing


{-| Resolves a `Random.generate` command: samples its generator with the editor's `seed` and applies
the message constructor, yielding the message to dispatch and the next seed. -}
randomCmd : List ( String, String ) -> Int -> Value -> Maybe ( Value, Int )
randomCmd files seed cmd =
    case cmd of
        VCtor "Cmd.random" [ toMsg, gen ] ->
            let
                ( v, seed2 ) =
                    sampleGen seed gen
            in
            case applyMsgIn files toMsg v of
                Ok msg ->
                    Just ( msg, seed2 )

                Err _ ->
                    Nothing

        _ ->
            Nothing


{-| Samples a generator with a linear-congruential step of the seed, returning (value, next seed). -}
sampleGen : Int -> Value -> ( Value, Int )
sampleGen seed gen =
    let
        s =
            abs (modBy 2147483647 (seed * 1103515245 + 12345))
    in
    case gen of
        VCtor "Random.Gen" [ VStr "int", VNum lo, VNum hi ] ->
            ( VNum (toFloat (round lo + modBy (round hi - round lo + 1) s)), s )

        VCtor "Random.Gen" [ VStr "float", VNum lo, VNum hi ] ->
            ( VNum (lo + (hi - lo) * (toFloat s / 2147483647)), s )

        VCtor "Random.Gen" [ VStr "uniform", VList xs ] ->
            ( listGet (modBy (max 1 (List.length xs)) s) xs, s )

        _ ->
            ( VNum 0, s )


listGet : Int -> List Value -> Value
listGet n xs =
    case xs of
        [] ->
            VCtor "Nothing" []

        x :: rest ->
            if n <= 0 then
                x

            else
                listGet (n - 1) rest


{-| Evaluates `view model` to the Html `Value` tree the editor renders to live Html. -}
appView : List ( String, String ) -> Value -> Result String Value
appView files model =
    parseProject files
        |> Result.andThen
            (\globals ->
                evalExpr globals [] (Var "view")
                    |> Result.andThen (\f -> applyValue globals f model)
            )


{-| Evaluates the project's `main` to a value (e.g. a static Html tree, a Browser.sandbox config, or
a plain value) — what the editor renders for the selected file. -}
mainValue : List ( String, String ) -> Result String Value
mainValue files =
    parseProject files |> Result.andThen (\globals -> evalExpr globals [] (Var "main"))


{-| Applies an event handler (e.g. an `onInput` message constructor) to the event's string payload,
producing the message value to dispatch. -}
applyHandler : List ( String, String ) -> Value -> String -> Result String Value
applyHandler files handler payload =
    parseProject files
        |> Result.andThen (\globals -> applyValue globals handler (VStr payload))


{-| Headless render of a single-file app's initial view to an HTML string (used in tests and as a
quick non-DOM preview): runs `init` then `view`, serialising the Html `Value` tree. -}
renderProgram : String -> String
renderProgram source =
    let
        files =
            [ ( "Main.elm", source ) ]
    in
    if hasApp files then
        -- A Browser.sandbox-style app: render the initial view (init |> view).
        case appInit files |> Result.andThen (appView files) of
            Ok html ->
                htmlToString html

            Err e ->
                "app error: " ++ e

    else
        -- A static program: render `main` (a Html value or a plain value) directly.
        case mainValue files of
            Ok v ->
                htmlToString v

            Err e ->
                "main error: " ++ e


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

        VCtor "Html.attr" [ VStr k, VStr val ] ->
            " " ++ k ++ "=" ++ val

        VCtor "Html.attr" [ VStr k, VBool b ] ->
            if b then
                " " ++ k

            else
                ""

        VCtor "Html.attr" [ VStr k, other ] ->
            " " ++ k ++ "=" ++ renderValue other

        _ ->
            ""


{-| Maps an attribute builtin name to its rendered key (`type_` is a keyword-avoiding alias). -}
attrKey : String -> String
attrKey name =
    if name == "type_" then
        "type"

    else if name == "strokeWidth" then
        "stroke-width"

    else if name == "strokeLinecap" then
        "stroke-linecap"

    else if name == "strokeDasharray" then
        "stroke-dasharray"

    else if name == "fillOpacity" then
        "fill-opacity"

    else if name == "stopColor" then
        "stop-color"

    else if name == "textAnchor" then
        "text-anchor"

    else if name == "fontSize" then
        "font-size"

    else if name == "fontFamily" then
        "font-family"

    else if name == "gradientUnits" then
        "gradientUnits"

    else
        name



-- PLAYGROUND (evancz/elm-playground) ---------------------------------------


{-| Whether `circle`'s arguments are the Playground form (a colour string and a numeric radius)
rather than the Svg element form (an attribute list and a child list). -}
playgroundCircle : List Value -> Bool
playgroundCircle args =
    case args of
        [ VStr _, VNum _ ] ->
            True

        _ ->
            False


{-| A fresh shape at the origin: PShape form x y angle scale alpha. -}
mkShape : Value -> Value
mkShape form =
    VCtor "PShape" [ form, VNum 0, VNum 0, VNum 0, VNum 1, VNum 1 ]


{-| Rebuilds a shape from its updated transform fields. -}
withShape : Value -> (Value -> Float -> Float -> Float -> Float -> Float -> Value) -> Result String Value
withShape shape f =
    case shape of
        VCtor "PShape" [ form, VNum x, VNum y, VNum a, VNum sc, VNum al ] ->
            Ok (f form x y a sc al)

        _ ->
            Err "expected a shape"


runPlayground : Globals -> String -> List Value -> Result String Value
runPlayground globals name args =
    case ( name, args ) of
        ( "picture", [ VList shapes ] ) ->
            Ok (pictureSvg shapes)

        ( "animation", [ view ] ) ->
            -- The editor is a one-shot renderer: draw the initial frame (time 0).
            applyValue globals view (VNum 0)
                |> Result.andThen
                    (\frame ->
                        case frame of
                            VList shapes ->
                                Ok (pictureSvg shapes)

                            _ ->
                                Err "animation view must return a list of shapes"
                    )

        ( "rectangle", [ color, VNum w, VNum h ] ) ->
            Ok (mkShape (VCtor "PRect" [ color, VNum w, VNum h ]))

        ( "square", [ color, VNum s ] ) ->
            Ok (mkShape (VCtor "PRect" [ color, VNum s, VNum s ]))

        ( "oval", [ color, VNum w, VNum h ] ) ->
            Ok (mkShape (VCtor "POval" [ color, VNum w, VNum h ]))

        ( "triangle", [ color, VNum r ] ) ->
            Ok (mkShape (VCtor "PNgon" [ color, VNum 3, VNum r ]))

        ( "pentagon", [ color, VNum r ] ) ->
            Ok (mkShape (VCtor "PNgon" [ color, VNum 5, VNum r ]))

        ( "hexagon", [ color, VNum r ] ) ->
            Ok (mkShape (VCtor "PNgon" [ color, VNum 6, VNum r ]))

        ( "octagon", [ color, VNum r ] ) ->
            Ok (mkShape (VCtor "PNgon" [ color, VNum 8, VNum r ]))

        ( "words", [ color, VStr s ] ) ->
            Ok (mkShape (VCtor "PWords" [ color, VStr s ]))

        ( "move", [ VNum dx, VNum dy, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum (x + dx), VNum (y + dy), VNum a, VNum sc, VNum al ])

        ( "moveUp", [ VNum d, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum x, VNum (y + d), VNum a, VNum sc, VNum al ])

        ( "moveDown", [ VNum d, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum x, VNum (y - d), VNum a, VNum sc, VNum al ])

        ( "moveLeft", [ VNum d, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum (x - d), VNum y, VNum a, VNum sc, VNum al ])

        ( "moveRight", [ VNum d, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum (x + d), VNum y, VNum a, VNum sc, VNum al ])

        ( "moveX", [ VNum d, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum (x + d), VNum y, VNum a, VNum sc, VNum al ])

        ( "moveY", [ VNum d, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum x, VNum (y + d), VNum a, VNum sc, VNum al ])

        ( "rotate", [ VNum da, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum x, VNum y, VNum (a + da), VNum sc, VNum al ])

        ( "scale", [ VNum k, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum x, VNum y, VNum a, VNum (sc * k), VNum al ])

        ( "fade", [ VNum o, shape ] ) ->
            withShape shape (\f x y a sc al -> VCtor "PShape" [ f, VNum x, VNum y, VNum a, VNum sc, VNum o ])

        ( "rgb", [ VNum r, VNum g, VNum b ] ) ->
            Ok (VStr ("rgb(" ++ ic r ++ "," ++ ic g ++ "," ++ ic b ++ ")"))

        ( "spin", [ VNum period, VNum time ] ) ->
            Ok (VNum (360 * frac period time))

        ( "wave", [ VNum lo, VNum hi, VNum period, VNum time ] ) ->
            Ok (VNum (lo + (hi - lo) * (1 + sin (2 * pi * frac period time)) / 2))

        ( "zigzag", [ VNum lo, VNum hi, VNum period, VNum time ] ) ->
            Ok (VNum (lo + (hi - lo) * abs (2 * frac period time - 1)))

        _ ->
            Err ("bad arguments to Playground." ++ name)


{-| The fractional position (0..1) through a `period`-second cycle at the given time (ms). -}
frac : Float -> Float -> Float
frac period time =
    let
        q =
            time / (period * 1000)
    in
    q - toFloat (floor q)


ic : Float -> String
ic n =
    String.fromInt (round n)


ff : Float -> String
ff x =
    String.fromFloat x


attrS : String -> String -> Value
attrS k v =
    VCtor "Html.attr" [ VStr k, VStr v ]


{-| Wraps rendered shapes in a centred SVG canvas (y-axis points up, as in Playground). -}
pictureSvg : List Value -> Value
pictureSvg shapes =
    VCtor "Html.node"
        [ VStr "svg"
        , VList [ attrS "viewBox" "-320 -240 640 480", attrS "width" "640", attrS "height" "480" ]
        , VList (List.map renderShape shapes)
        ]


renderShape : Value -> Value
renderShape shape =
    case shape of
        VCtor "PShape" [ form, VNum x, VNum y, VNum a, VNum sc, VNum al ] ->
            VCtor "Html.node"
                [ VStr "g"
                , VList [ attrS "transform" (transformStr x y a sc), attrS "opacity" (ff al) ]
                , VList [ renderForm form ]
                ]

        _ ->
            VCtor "Html.text" [ VStr "" ]


transformStr : Float -> Float -> Float -> Float -> String
transformStr x y a sc =
    "translate(" ++ ff x ++ " " ++ ff (negate y) ++ ") rotate(" ++ ff (negate a) ++ ") scale(" ++ ff sc ++ ")"


renderForm : Value -> Value
renderForm form =
    case form of
        VCtor "PCircle" [ VStr color, VNum r ] ->
            VCtor "Html.node" [ VStr "ellipse", VList [ attrS "cx" "0", attrS "cy" "0", attrS "rx" (ff r), attrS "ry" (ff r), attrS "fill" color ], VList [] ]

        VCtor "POval" [ VStr color, VNum w, VNum h ] ->
            VCtor "Html.node" [ VStr "ellipse", VList [ attrS "cx" "0", attrS "cy" "0", attrS "rx" (ff (w / 2)), attrS "ry" (ff (h / 2)), attrS "fill" color ], VList [] ]

        VCtor "PRect" [ VStr color, VNum w, VNum h ] ->
            VCtor "Html.node" [ VStr "rect", VList [ attrS "x" (ff (negate (w / 2))), attrS "y" (ff (negate (h / 2))), attrS "width" (ff w), attrS "height" (ff h), attrS "fill" color ], VList [] ]

        VCtor "PNgon" [ VStr color, VNum n, VNum r ] ->
            VCtor "Html.node" [ VStr "path", VList [ attrS "d" (ngonPath n r), attrS "fill" color ], VList [] ]

        VCtor "PWords" [ VStr color, VStr s ] ->
            VCtor "Html.node" [ VStr "text_", VList [ attrS "x" "0", attrS "y" "0", attrS "text-anchor" "middle", attrS "fill" color ], VList [ VCtor "Html.text" [ VStr s ] ] ]

        _ ->
            VCtor "Html.text" [ VStr "" ]


ngonPath : Float -> Float -> String
ngonPath n r =
    let
        pts =
            List.map
                (\i ->
                    let
                        ang =
                            2 * pi * toFloat i / n - pi / 2
                    in
                    { px = r * cos ang, py = r * sin ang }
                )
                (List.range 0 (round n - 1))
    in
    case pts of
        [] ->
            ""

        p0 :: rest ->
            "M " ++ ff p0.px ++ " " ++ ff p0.py ++ String.join "" (List.map (\p -> " L " ++ ff p.px ++ " " ++ ff p.py) rest) ++ " Z"


{-| The Playground named colours (approximate hex). -}
playgroundColor : String -> Maybe String
playgroundColor name =
    case name of
        "red" ->
            Just "#cc0000"

        "orange" ->
            Just "#f57900"

        "yellow" ->
            Just "#edd400"

        "green" ->
            Just "#4e9a06"

        "blue" ->
            Just "#3465a4"

        "purple" ->
            Just "#75507b"

        "brown" ->
            Just "#8f5902"

        "black" ->
            Just "#000000"

        "white" ->
            Just "#ffffff"

        "lightGray" ->
            Just "#d3d7cf"

        "gray" ->
            Just "#babdb6"

        "darkGray" ->
            Just "#888a85"

        "charcoal" ->
            Just "#2e3436"

        "lightBlue" ->
            Just "#729fcf"

        "lightGreen" ->
            Just "#8ae234"

        "lightYellow" ->
            Just "#fce94f"

        "darkRed" ->
            Just "#a40000"

        "darkGreen" ->
            Just "#4e9a06"

        "darkBlue" ->
            Just "#204a87"

        _ ->
            Nothing

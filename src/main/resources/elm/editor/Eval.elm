module Eval exposing (eval, evalProject, debugSteps, lookup, renderValue, appInit, appUpdate, appView, hasApp, renderProgram, mainValue, applyHandler, appInitCmd, appUpdateCmd, appSubscription, randomCmd, applyMsgIn, gameInitMem, gameView, gameStep, httpCmd, httpResult)

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
        ++ [ "Http.get", "Http.expectString", "Http.expectJson" ]
        ++ [ "field", "map2", "map3", "map4", "map5", "map6", "map7", "map8", "succeed", "list", "andThen", "oneOf", "nullable" ]
        ++ [ "Encode.string", "Encode.int", "Encode.float", "Encode.bool", "Encode.object", "Encode.list", "Encode.encode" ]
        ++ playgroundNames


{-| evancz/elm-playground builtins: shape constructors, transforms, colours and the `picture`/
`animation` entry points, implemented natively in the editor (rendering to SVG `Value` trees). The
`circle`/`polygon` names overlap with the Svg builtins but are disambiguated at run time by the
argument types (`circle color radius` vs `circle attrs children`). -}
playgroundNames : List String
playgroundNames =
    [ "picture", "animation", "game", "oval", "rectangle", "square", "triangle", "pentagon", "hexagon", "octagon", "words", "image" ]
        ++ [ "move", "moveUp", "moveDown", "moveLeft", "moveRight", "moveX", "moveY", "rotate", "scale", "fade" ]
        ++ [ "rgb", "spin", "wave", "zigzag", "toX", "toY", "degrees" ]


{-| The Html (and inline SVG) element builtins (each takes a list of attributes then a list of
children). Inline SVG renders directly in the browser, so `svg`/`circle`/… serialize like any node. -}
htmlTags : List String
htmlTags =
    [ "div", "button", "p", "span", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "pre", "code", "input", "textarea", "label", "a", "section", "strong", "em", "br", "img", "table", "tr", "td", "th", "blockquote", "cite", "hr", "nav", "header", "footer" ]
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

    else if List.member name [ "cos", "sin", "tan", "sqrt", "toFloat", "round", "floor", "ceiling", "truncate", "abs", "Time.millisToPosix", "Time.posixToMillis", "picture", "animation", "Http.get", "Http.expectString", "succeed", "list", "oneOf", "nullable", "Encode.string", "Encode.int", "Encode.float", "Encode.bool", "Encode.object" ] then
        1

    else if List.member name [ "toX", "toY", "degrees" ] then
        1

    else if List.member name [ "oval", "rectangle", "move", "rgb", "game", "image", "map2" ] then
        3

    else if List.member name [ "wave", "zigzag", "map3" ] then
        4

    else if name == "map4" then
        5

    else if name == "map5" then
        6

    else if name == "map6" then
        7

    else if name == "map7" then
        8

    else if name == "map8" then
        9

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

                            else if name == "Encode.null" then
                                Ok (VCtor "Null" [])

                            else if List.member name [ "string", "int", "float", "bool" ] then
                                -- Json.Decode primitive decoders (exposed unqualified by the quotes
                                -- example); locals/globals are checked first, so a same-named binding
                                -- still shadows them.
                                Ok (VCtor ("Dec." ++ name) [])

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

            ( "Http.expectString", [ toMsg ] ) ->
                Ok (VCtor "Http.expect" [ toMsg ])

            ( "Http.expectJson", [ toMsg, decoder ] ) ->
                Ok (VCtor "Http.expectJson" [ toMsg, decoder ])

            ( "Http.get", [ VRecord fields ] ) ->
                -- A GET command the editor issues for real, feeding the response back via `expect`.
                case ( lookup "url" fields, lookup "expect" fields ) of
                    ( Just (VStr url), Just expect ) ->
                        Ok (VCtor "Cmd.http" [ VStr url, expect ])

                    _ ->
                        Err "Http.get needs { url : String, expect : … }"

            ( "field", [ VStr name2, decoder ] ) ->
                Ok (VCtor "Dec.field" [ VStr name2, decoder ])

            ( "succeed", [ v ] ) ->
                Ok (VCtor "Dec.succeed" [ v ])

            ( "map2", [ f, a, b ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b ])

            ( "map3", [ f, a, b, c ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b, c ])

            ( "map4", [ f, a, b, c, d ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b, c, d ])

            ( "map5", [ f, a, b, c, d, e ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b, c, d, e ])

            ( "map6", [ f, a, b, c, d, e, g ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b, c, d, e, g ])

            ( "map7", [ f, a, b, c, d, e, g, h ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b, c, d, e, g, h ])

            ( "map8", [ f, a, b, c, d, e, g, h, i ] ) ->
                Ok (VCtor "Dec.map" [ f, a, b, c, d, e, g, h, i ])

            ( "list", [ dec ] ) ->
                Ok (VCtor "Dec.list" [ dec ])

            ( "andThen", [ f, dec ] ) ->
                Ok (VCtor "Dec.andThen" [ f, dec ])

            ( "oneOf", [ decs ] ) ->
                Ok (VCtor "Dec.oneOf" [ decs ])

            ( "nullable", [ dec ] ) ->
                Ok (VCtor "Dec.nullable" [ dec ])

            ( "Encode.int", [ v ] ) ->
                Ok v

            ( "Encode.float", [ v ] ) ->
                Ok v

            ( "Encode.string", [ v ] ) ->
                Ok v

            ( "Encode.bool", [ v ] ) ->
                Ok v

            ( "Encode.object", [ pairs ] ) ->
                Ok (encodeObject pairs)

            ( "Encode.list", [ f, xs ] ) ->
                encodeList globals f xs

            ( "Encode.encode", [ _, value ] ) ->
                Ok (VStr (jsonEncode value))

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


{-| If the command is an `Http.get`, the (url, expect) the editor needs to issue a real request and
build the response message. The `expect` carries the message constructor (and, for JSON, a decoder). -}
httpCmd : Value -> Maybe ( String, Value )
httpCmd cmd =
    case cmd of
        VCtor "Cmd.http" [ VStr url, expect ] ->
            Just ( url, expect )

        _ ->
            Nothing


{-| Builds the message to dispatch when an HTTP request finishes. For `expectString` it is
`toMsg (Ok body)`; for `expectJson` the body is parsed and run through the decoder, giving
`toMsg (Ok value)` (or an `Err` on a network/decode failure). -}
httpResult : List ( String, String ) -> Value -> Maybe String -> Result String Value
httpResult files expect body =
    parseProject files |> Result.andThen (\globals -> httpResultIn globals expect body)


httpResultIn : Globals -> Value -> Maybe String -> Result String Value
httpResultIn globals expect body =
    case expect of
        VCtor "Http.expect" [ toMsg ] ->
            applyValue globals toMsg (okOrErr body)

        VCtor "Http.expectJson" [ toMsg, decoder ] ->
            case body of
                Just text ->
                    case parseJson text |> Result.andThen (\json -> runDecoder globals decoder json) of
                        Ok v ->
                            applyValue globals toMsg (VCtor "Ok" [ v ])

                        Err _ ->
                            applyValue globals toMsg (VCtor "Err" [ VCtor "BadBody" [] ])

                Nothing ->
                    applyValue globals toMsg (VCtor "Err" [ VCtor "NetworkError" [] ])

        _ ->
            Err "unknown Http expect"


okOrErr : Maybe String -> Value
okOrErr body =
    case body of
        Just text ->
            VCtor "Ok" [ VStr text ]

        Nothing ->
            VCtor "Err" [ VCtor "NetworkError" [] ]


{-| Runs an interpreted decoder (a `Dec.*` value) against a parsed JSON `Value`. -}
runDecoder : Globals -> Value -> Value -> Result String Value
runDecoder globals decoder json =
    case decoder of
        VCtor "Dec.string" [] ->
            case json of
                VStr s ->
                    Ok (VStr s)

                _ ->
                    Err "expected a string"

        VCtor "Dec.int" [] ->
            case json of
                VNum n ->
                    Ok (VNum n)

                _ ->
                    Err "expected an int"

        VCtor "Dec.float" [] ->
            case json of
                VNum n ->
                    Ok (VNum n)

                _ ->
                    Err "expected a float"

        VCtor "Dec.bool" [] ->
            case json of
                VBool b ->
                    Ok (VBool b)

                _ ->
                    Err "expected a bool"

        VCtor "Dec.field" [ VStr name, dec ] ->
            case json of
                VRecord fs ->
                    case lookup name fs of
                        Just v ->
                            runDecoder globals dec v

                        Nothing ->
                            Err ("no field: " ++ name)

                _ ->
                    Err "expected an object"

        VCtor "Dec.succeed" [ v ] ->
            Ok v

        VCtor "Dec.map" (f :: decs) ->
            decodeAll globals decs json [] |> Result.andThen (\vals -> applyAll globals f vals)

        VCtor "Dec.list" [ dec ] ->
            case json of
                VList items ->
                    decodeEach globals dec items []

                _ ->
                    Err "expected a list"

        VCtor "Dec.andThen" [ f, dec ] ->
            runDecoder globals dec json
                |> Result.andThen
                    (\v ->
                        applyValue globals f v
                            |> Result.andThen (\next -> runDecoder globals next json)
                    )

        VCtor "Dec.oneOf" [ VList decs ] ->
            tryDecoders globals decs json

        VCtor "Dec.nullable" [ dec ] ->
            case json of
                VCtor "Null" [] ->
                    Ok (VCtor "Nothing" [])

                _ ->
                    runDecoder globals dec json |> Result.map (\v -> VCtor "Just" [ v ])

        _ ->
            Err "unsupported decoder"


{-| Runs `dec` against each element of a JSON array, collecting the decoded values into a `VList`. -}
decodeEach : Globals -> Value -> List Value -> List Value -> Result String Value
decodeEach globals dec items acc =
    case items of
        [] ->
            Ok (VList (List.reverse acc))

        x :: rest ->
            runDecoder globals dec x |> Result.andThen (\v -> decodeEach globals dec rest (v :: acc))


{-| Tries each decoder in turn (for `oneOf`), returning the first success or the last error. -}
tryDecoders : Globals -> List Value -> Value -> Result String Value
tryDecoders globals decs json =
    case decs of
        [] ->
            Err "oneOf: no decoder succeeded"

        d :: rest ->
            case runDecoder globals d json of
                Ok v ->
                    Ok v

                Err e ->
                    if List.isEmpty rest then
                        Err e

                    else
                        tryDecoders globals rest json


decodeAll : Globals -> List Value -> Value -> List Value -> Result String (List Value)
decodeAll globals decs json acc =
    case decs of
        [] ->
            Ok (List.reverse acc)

        d :: rest ->
            runDecoder globals d json |> Result.andThen (\v -> decodeAll globals rest json (v :: acc))


applyAll : Globals -> Value -> List Value -> Result String Value
applyAll globals f vals =
    case vals of
        [] ->
            Ok f

        v :: rest ->
            applyValue globals f v |> Result.andThen (\f2 -> applyAll globals f2 rest)


{-| `Json.Encode.object`: a list of `( key, value )` tuples becomes a `VRecord`. -}
encodeObject : Value -> Value
encodeObject pairs =
    case pairs of
        VList items ->
            VRecord (List.filterMap pairToField items)

        _ ->
            pairs


pairToField : Value -> Maybe ( String, Value )
pairToField v =
    case v of
        VTup [ VStr k, val ] ->
            Just ( k, val )

        _ ->
            Nothing


{-| `Json.Encode.list f xs`: encode each element with `f`, collecting a `VList`. -}
encodeList : Globals -> Value -> Value -> Result String Value
encodeList globals f xs =
    case xs of
        VList items ->
            encodeEach globals f items []

        _ ->
            Err "Encode.list expects a list"


encodeEach : Globals -> Value -> List Value -> List Value -> Result String Value
encodeEach globals f items acc =
    case items of
        [] ->
            Ok (VList (List.reverse acc))

        x :: rest ->
            applyValue globals f x |> Result.andThen (\v -> encodeEach globals f rest (v :: acc))


{-| Serialises an encoded `Value` to a compact JSON string (`Json.Encode.encode`). -}
jsonEncode : Value -> String
jsonEncode v =
    case v of
        VStr s ->
            "\"" ++ jsonEscape s ++ "\""

        VBool b ->
            if b then
                "true"

            else
                "false"

        VNum n ->
            if n == toFloat (round n) then
                String.fromInt (round n)

            else
                String.fromFloat n

        VCtor "Null" [] ->
            "null"

        VList items ->
            "[" ++ String.join "," (List.map jsonEncode items) ++ "]"

        VRecord fields ->
            "{" ++ String.join "," (List.map (\( k, val ) -> "\"" ++ jsonEscape k ++ "\":" ++ jsonEncode val) fields) ++ "}"

        _ ->
            "null"


jsonEscape : String -> String
jsonEscape s =
    s |> String.replace "\\" "\\\\" |> String.replace "\"" "\\\""


{-| A small JSON parser producing an interpreted `Value` (object→VRecord, array→VList, …). -}
parseJson : String -> Result String Value
parseJson s =
    jsonValue (skipWs (String.toList s)) |> Result.map Tuple.first


jsonValue : List Char -> Result String ( Value, List Char )
jsonValue chars =
    case chars of
        '"' :: rest ->
            jsonString rest ""

        '{' :: rest ->
            jsonObject (skipWs rest) []

        '[' :: rest ->
            jsonArray (skipWs rest) []

        't' :: 'r' :: 'u' :: 'e' :: rest ->
            Ok ( VBool True, rest )

        'f' :: 'a' :: 'l' :: 's' :: 'e' :: rest ->
            Ok ( VBool False, rest )

        'n' :: 'u' :: 'l' :: 'l' :: rest ->
            Ok ( VCtor "Null" [], rest )

        c :: _ ->
            if c == '-' || Char.isDigit c then
                jsonNumber chars ""

            else
                Err "unexpected character in JSON"

        [] ->
            Err "unexpected end of JSON"


jsonString : List Char -> String -> Result String ( Value, List Char )
jsonString chars acc =
    case chars of
        '"' :: rest ->
            Ok ( VStr acc, rest )

        '\\' :: c :: rest ->
            jsonString rest (acc ++ escape c)

        c :: rest ->
            jsonString rest (acc ++ String.fromChar c)

        [] ->
            Err "unterminated JSON string"


escape : Char -> String
escape c =
    case c of
        'n' ->
            "\n"

        't' ->
            "\t"

        'r' ->
            "\u{000D}"

        _ ->
            String.fromChar c


jsonNumber : List Char -> String -> Result String ( Value, List Char )
jsonNumber chars acc =
    case chars of
        c :: rest ->
            if Char.isDigit c || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' then
                jsonNumber rest (acc ++ String.fromChar c)

            else
                finishNumber acc chars

        [] ->
            finishNumber acc []


finishNumber : String -> List Char -> Result String ( Value, List Char )
finishNumber acc rest =
    case String.toFloat acc of
        Just n ->
            Ok ( VNum n, rest )

        Nothing ->
            Err ("bad JSON number: " ++ acc)


jsonObject : List Char -> List ( String, Value ) -> Result String ( Value, List Char )
jsonObject chars acc =
    case chars of
        '}' :: rest ->
            Ok ( VRecord (List.reverse acc), rest )

        '"' :: rest ->
            jsonString rest ""
                |> Result.andThen
                    (\( key, afterKey ) ->
                        case skipWs afterKey of
                            ':' :: afterColon ->
                                jsonValue (skipWs afterColon)
                                    |> Result.andThen
                                        (\( v, afterVal ) ->
                                            let
                                                pair =
                                                    ( valueToKey key, v )
                                            in
                                            case skipWs afterVal of
                                                ',' :: more ->
                                                    jsonObject (skipWs more) (pair :: acc)

                                                '}' :: more ->
                                                    Ok ( VRecord (List.reverse (pair :: acc)), more )

                                                _ ->
                                                    Err "expected ',' or '}' in object"
                                        )

                            _ ->
                                Err "expected ':' in object"
                    )

        _ ->
            Err "expected a key or '}' in object"


valueToKey : Value -> String
valueToKey v =
    case v of
        VStr s ->
            s

        _ ->
            ""


jsonArray : List Char -> List Value -> Result String ( Value, List Char )
jsonArray chars acc =
    case chars of
        ']' :: rest ->
            Ok ( VList (List.reverse acc), rest )

        _ ->
            jsonValue chars
                |> Result.andThen
                    (\( v, afterVal ) ->
                        case skipWs afterVal of
                            ',' :: more ->
                                jsonArray (skipWs more) (v :: acc)

                            ']' :: more ->
                                Ok ( VList (List.reverse (v :: acc)), more )

                            _ ->
                                Err "expected ',' or ']' in array"
                    )


skipWs : List Char -> List Char
skipWs chars =
    case chars of
        c :: rest ->
            if c == ' ' || c == '\n' || c == '\t' || c == '\u{000D}' then
                skipWs rest

            else
                chars

        [] ->
            []


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
            Ok (VCtor "Playground.game" [ _, _, mem ]) ->
                -- A `game`: draw its initial frame (no keys, time 0).
                case gameView files [] 0 mem of
                    Ok html ->
                        htmlToString html

                    Err e ->
                        "game error: " ++ e

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

        ( "game", [ view, update, mem ] ) ->
            -- Preserve the parts so the editor can drive the game (keyboard/frames); a static
            -- render (tests / renderProgram) draws the initial frame via gameInitialView.
            Ok (VCtor "Playground.game" [ view, update, mem ])

        ( "image", [ VNum w, VNum h, VStr url ] ) ->
            Ok (mkShape (VCtor "PImage" [ VNum w, VNum h, VStr url ]))

        ( "degrees", [ VNum d ] ) ->
            Ok (VNum (d * pi / 180))

        ( "toX", [ kb ] ) ->
            Ok (VNum (boolField "right" kb - boolField "left" kb))

        ( "toY", [ kb ] ) ->
            Ok (VNum (boolField "up" kb - boolField "down" kb))

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

        VCtor "PImage" [ VNum w, VNum h, VStr url ] ->
            VCtor "Html.node" [ VStr "image", VList [ attrS "x" (ff (negate (w / 2))), attrS "y" (ff (negate (h / 2))), attrS "width" (ff w), attrS "height" (ff h), attrS "href" url ], VList [] ]

        _ ->
            VCtor "Html.text" [ VStr "" ]


-- elm-playground `game`: a Computer-driven loop the editor renders and steps.


{-| A boolean keyboard field as 0.0/1.0 (used by `toX`/`toY`). -}
boolField : String -> Value -> Float
boolField name kb =
    case kb of
        VRecord fields ->
            case lookup name fields of
                Just (VBool True) ->
                    1

                _ ->
                    0

        _ ->
            0


{-| The `Computer` a game's `view`/`update` receive: mouse, keyboard, screen and time. The keyboard
flags are derived from the set of currently-pressed key names; time is milliseconds. -}
computerValue : List String -> Float -> Value
computerValue keys time =
    let
        down k =
            VBool (List.member k keys)

        on names =
            VBool (List.any (\k -> List.member k keys) names)
    in
    VRecord
        [ ( "mouse", VRecord [ ( "x", VNum 0 ), ( "y", VNum 0 ), ( "down", VBool False ) ] )
        , ( "keyboard"
          , VRecord
                [ ( "up", on [ "ArrowUp", "w", "W" ] )
                , ( "down", on [ "ArrowDown", "s", "S" ] )
                , ( "left", on [ "ArrowLeft", "a", "A" ] )
                , ( "right", on [ "ArrowRight", "d", "D" ] )
                , ( "space", down " " )
                , ( "enter", down "Enter" )
                , ( "shift", down "Shift" )
                , ( "keys", VList (List.map VStr keys) )
                ]
          )
        , ( "screen"
          , VRecord
                [ ( "width", VNum 640 ), ( "height", VNum 480 ), ( "top", VNum 240 ), ( "bottom", VNum -240 ), ( "left", VNum -320 ), ( "right", VNum 320 ) ]
          )
        , ( "time", VNum time )
        ]


{-| Extracts a game's (view, update, memory) from the project's `main`, if it is a `game`. -}
gameOf : List ( String, String ) -> Maybe ( Value, Value, Value )
gameOf files =
    case mainValue files of
        Ok (VCtor "Playground.game" [ view, update, mem ]) ->
            Just ( view, update, mem )

        _ ->
            Nothing


{-| A game's initial memory (the third argument to `game`), if the project is a game. -}
gameInitMem : List ( String, String ) -> Maybe Value
gameInitMem files =
    gameOf files |> Maybe.map (\( _, _, mem ) -> mem)


{-| Renders a game's `view computer memory` to an SVG value, for the given keys and time. -}
gameView : List ( String, String ) -> List String -> Float -> Value -> Result String Value
gameView files keys time mem =
    case ( parseProject files, gameOf files ) of
        ( Ok globals, Just ( view, _, _ ) ) ->
            applyValue globals view (computerValue keys time)
                |> Result.andThen (\f -> applyValue globals f mem)
                |> Result.andThen
                    (\shapes ->
                        case shapes of
                            VList ss ->
                                Ok (pictureSvg ss)

                            _ ->
                                Err "game view must return a list of shapes"
                    )

        _ ->
            Err "not a game"


{-| Steps a game's `update computer memory`, for the given keys and time, to the next memory. -}
gameStep : List ( String, String ) -> List String -> Float -> Value -> Result String Value
gameStep files keys time mem =
    case ( parseProject files, gameOf files ) of
        ( Ok globals, Just ( _, update, _ ) ) ->
            applyValue globals update (computerValue keys time)
                |> Result.andThen (\f -> applyValue globals f mem)

        _ ->
            Err "not a game"


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

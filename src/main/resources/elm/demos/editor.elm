module Main exposing (main, eval)

{-| An Elm-in-Elm interpreter (tokenizer + recursive-descent parser with precedence climbing +
an evaluator over a `Value` ADT with closures) wrapped in an Ellie-style live editor. The
interpreter is itself written in Elm and runs — compiled by this project's JS backend — in the
browser.

Supported language: integers and floats, string literals, booleans, lists; the operators
`+ - * / //`, comparison (`== /= < <= > >=`), logic (`&& ||`) and append (`++`); `if/then/else`,
`let NAME = E in BODY`, multi-argument lambdas (`\x y -> …`) with closures, application, and
variables. Numbers are evaluated as `Float`.
-}

import Browser
import Html exposing (Html, div, h1, h2, p, pre, text, textarea)
import Html.Attributes exposing (style, value)
import Html.Events exposing (onInput)



-- VALUES & EXPRESSIONS


type Value
    = VNum Float
    | VBool Bool
    | VStr String
    | VList (List Value)
    | VClosure (List String) Expr (List ( String, Value ))


type Expr
    = Num Float
    | Str String
    | Boolean Bool
    | ListE (List Expr)
    | Var String
    | Neg Expr
    | BinOp String Expr Expr
    | If Expr Expr Expr
    | Lam (List String) Expr
    | App Expr Expr
    | Let String Expr Expr



-- TOKENIZER


type Token
    = TNum Float
    | TStr String
    | TId String
    | TOp String
    | TLParen
    | TRParen
    | TLBracket
    | TRBracket
    | TComma
    | TLambda
    | TArrow
    | TEquals


tokenize : String -> Result String (List Token)
tokenize src =
    tokenizeHelp (String.toList src) []


tokenizeHelp : List Char -> List Token -> Result String (List Token)
tokenizeHelp chars acc =
    case chars of
        [] ->
            Ok (List.reverse acc)

        c :: rest ->
            if c == ' ' || c == '\n' || c == '\t' then
                tokenizeHelp rest acc

            else if c == '(' then
                tokenizeHelp rest (TLParen :: acc)

            else if c == ')' then
                tokenizeHelp rest (TRParen :: acc)

            else if c == '[' then
                tokenizeHelp rest (TLBracket :: acc)

            else if c == ']' then
                tokenizeHelp rest (TRBracket :: acc)

            else if c == ',' then
                tokenizeHelp rest (TComma :: acc)

            else if c == '\\' then
                tokenizeHelp rest (TLambda :: acc)

            else if c == '"' then
                let
                    taken =
                        takeString rest ""
                in
                tokenizeHelp (Tuple.second taken) (TStr (Tuple.first taken) :: acc)

            else if isOpChar c then
                let
                    taken =
                        takeWhile isOpChar chars ""
                in
                case classifyOp (Tuple.first taken) of
                    Ok tok ->
                        tokenizeHelp (Tuple.second taken) (tok :: acc)

                    Err e ->
                        Err e

            else if Char.isDigit c then
                let
                    taken =
                        takeWhile isNumChar chars ""
                in
                case String.toFloat (Tuple.first taken) of
                    Just n ->
                        tokenizeHelp (Tuple.second taken) (TNum n :: acc)

                    Nothing ->
                        Err ("bad number: " ++ Tuple.first taken)

            else if Char.isAlpha c || c == '_' then
                let
                    taken =
                        takeWhile isIdChar chars ""
                in
                tokenizeHelp (Tuple.second taken) (TId (Tuple.first taken) :: acc)

            else
                Err ("unexpected character: " ++ String.fromChar c)


isOpChar : Char -> Bool
isOpChar c =
    c == '+' || c == '-' || c == '*' || c == '/' || c == '=' || c == '<' || c == '>' || c == '&' || c == '|'


isNumChar : Char -> Bool
isNumChar c =
    Char.isDigit c || c == '.'


isIdChar : Char -> Bool
isIdChar c =
    Char.isAlphaNum c || c == '_'


takeWhile : (Char -> Bool) -> List Char -> String -> ( String, List Char )
takeWhile pred chars acc =
    case chars of
        c :: rest ->
            if pred c then
                takeWhile pred rest (acc ++ String.fromChar c)

            else
                ( acc, chars )

        [] ->
            ( acc, chars )


takeString : List Char -> String -> ( String, List Char )
takeString chars acc =
    case chars of
        '"' :: rest ->
            ( acc, rest )

        c :: rest ->
            takeString rest (acc ++ String.fromChar c)

        [] ->
            ( acc, [] )


classifyOp : String -> Result String Token
classifyOp s =
    if s == "->" then
        Ok TArrow

    else if s == "=" then
        Ok TEquals

    else if List.member s [ "+", "-", "*", "/", "//", "==", "/=", "<", "<=", ">", ">=", "&&", "||", "++" ] then
        Ok (TOp s)

    else
        Err ("unknown operator: " ++ s)



-- PARSER (precedence climbing)


parse : List Token -> Result String Expr
parse tokens =
    parseExpr tokens
        |> Result.andThen
            (\r ->
                if List.isEmpty (Tuple.second r) then
                    Ok (Tuple.first r)

                else
                    Err "unexpected trailing input"
            )


parseExpr : List Token -> Result String ( Expr, List Token )
parseExpr tokens =
    parseBinary 0 tokens


opPrec : String -> Int
opPrec op =
    if op == "||" then
        2

    else if op == "&&" then
        3

    else if List.member op [ "==", "/=", "<", "<=", ">", ">=" ] then
        4

    else if op == "++" then
        5

    else if op == "+" || op == "-" then
        6

    else
        7


parseBinary : Int -> List Token -> Result String ( Expr, List Token )
parseBinary minPrec tokens =
    parseUnary tokens
        |> Result.andThen (\r -> climb minPrec (Tuple.first r) (Tuple.second r))


climb : Int -> Expr -> List Token -> Result String ( Expr, List Token )
climb minPrec left tokens =
    case tokens of
        (TOp op) :: rest ->
            if opPrec op >= minPrec then
                parseBinary (opPrec op + 1) rest
                    |> Result.andThen (\r -> climb minPrec (BinOp op left (Tuple.first r)) (Tuple.second r))

            else
                Ok ( left, tokens )

        _ ->
            Ok ( left, tokens )


parseUnary : List Token -> Result String ( Expr, List Token )
parseUnary tokens =
    case tokens of
        (TOp "-") :: rest ->
            parseUnary rest |> Result.map (\r -> ( Neg (Tuple.first r), Tuple.second r ))

        _ ->
            parseApp tokens


parseApp : List Token -> Result String ( Expr, List Token )
parseApp tokens =
    parseAtom tokens
        |> Result.andThen (\r -> appTail (Tuple.first r) (Tuple.second r))


appTail : Expr -> List Token -> Result String ( Expr, List Token )
appTail fn tokens =
    if startsAtom tokens then
        parseAtom tokens
            |> Result.andThen (\r -> appTail (App fn (Tuple.first r)) (Tuple.second r))

    else
        Ok ( fn, tokens )


startsAtom : List Token -> Bool
startsAtom tokens =
    case tokens of
        (TNum _) :: _ ->
            True

        (TStr _) :: _ ->
            True

        TLParen :: _ ->
            True

        TLBracket :: _ ->
            True

        (TId name) :: _ ->
            not (List.member name [ "then", "else", "in" ])

        _ ->
            False


parseAtom : List Token -> Result String ( Expr, List Token )
parseAtom tokens =
    case tokens of
        (TNum n) :: rest ->
            Ok ( Num n, rest )

        (TStr s) :: rest ->
            Ok ( Str s, rest )

        (TId "True") :: rest ->
            Ok ( Boolean True, rest )

        (TId "False") :: rest ->
            Ok ( Boolean False, rest )

        (TId "if") :: rest ->
            parseIf rest

        (TId "let") :: rest ->
            parseLet rest

        (TId name) :: rest ->
            Ok ( Var name, rest )

        TLambda :: rest ->
            parseLambda rest []

        TLParen :: rest ->
            parseExpr rest
                |> Result.andThen
                    (\r ->
                        case Tuple.second r of
                            TRParen :: rest2 ->
                                Ok ( Tuple.first r, rest2 )

                            _ ->
                                Err "expected a closing )"
                    )

        TLBracket :: rest ->
            parseListItems rest []

        _ ->
            Err "expected an expression"


parseIf : List Token -> Result String ( Expr, List Token )
parseIf tokens =
    parseExpr tokens
        |> Result.andThen
            (\rc ->
                case Tuple.second rc of
                    (TId "then") :: afterThen ->
                        parseExpr afterThen
                            |> Result.andThen
                                (\rt ->
                                    case Tuple.second rt of
                                        (TId "else") :: afterElse ->
                                            parseExpr afterElse
                                                |> Result.map
                                                    (\re ->
                                                        ( If (Tuple.first rc) (Tuple.first rt) (Tuple.first re)
                                                        , Tuple.second re
                                                        )
                                                    )

                                        _ ->
                                            Err "expected 'else'"
                                )

                    _ ->
                        Err "expected 'then'"
            )


parseLet : List Token -> Result String ( Expr, List Token )
parseLet tokens =
    case tokens of
        (TId name) :: TEquals :: afterEq ->
            parseExpr afterEq
                |> Result.andThen
                    (\rv ->
                        case Tuple.second rv of
                            (TId "in") :: afterIn ->
                                parseExpr afterIn
                                    |> Result.map (\rb -> ( Let name (Tuple.first rv) (Tuple.first rb), Tuple.second rb ))

                            _ ->
                                Err "expected 'in'"
                    )

        _ ->
            Err "expected 'NAME =' after let"


parseLambda : List Token -> List String -> Result String ( Expr, List Token )
parseLambda tokens params =
    case tokens of
        (TId name) :: rest ->
            parseLambda rest (params ++ [ name ])

        TArrow :: rest ->
            if List.isEmpty params then
                Err "lambda needs a parameter"

            else
                parseExpr rest |> Result.map (\r -> ( Lam params (Tuple.first r), Tuple.second r ))

        _ ->
            Err "expected lambda parameters then '->'"


parseListItems : List Token -> List Expr -> Result String ( Expr, List Token )
parseListItems tokens acc =
    case tokens of
        TRBracket :: rest ->
            Ok ( ListE acc, rest )

        _ ->
            parseExpr tokens
                |> Result.andThen
                    (\r ->
                        case Tuple.second r of
                            TComma :: rest2 ->
                                parseListItems rest2 (acc ++ [ Tuple.first r ])

                            TRBracket :: rest2 ->
                                Ok ( ListE (acc ++ [ Tuple.first r ]), rest2 )

                            _ ->
                                Err "expected ',' or ']' in list"
                    )



-- EVALUATOR


evalExpr : List ( String, Value ) -> Expr -> Result String Value
evalExpr env expr =
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
                    Err ("undefined variable: " ++ name)

        ListE items ->
            evalList env items []

        Neg inner ->
            evalExpr env inner
                |> Result.andThen
                    (\v ->
                        case v of
                            VNum n ->
                                Ok (VNum (negate n))

                            _ ->
                                Err "cannot negate a non-number"
                    )

        If cond then_ else_ ->
            evalExpr env cond
                |> Result.andThen
                    (\v ->
                        case v of
                            VBool True ->
                                evalExpr env then_

                            VBool False ->
                                evalExpr env else_

                            _ ->
                                Err "if condition must be a Bool"
                    )

        Let name boundExpr body ->
            evalExpr env boundExpr
                |> Result.andThen (\v -> evalExpr (( name, v ) :: env) body)

        Lam params body ->
            Ok (VClosure params body env)

        App fn arg ->
            evalExpr env fn
                |> Result.andThen
                    (\fv ->
                        evalExpr env arg
                            |> Result.andThen (\av -> applyValue fv av)
                    )

        BinOp op l r ->
            evalExpr env l
                |> Result.andThen
                    (\lv ->
                        evalExpr env r
                            |> Result.andThen (\rv -> applyOp op lv rv)
                    )


evalList : List ( String, Value ) -> List Expr -> List Value -> Result String Value
evalList env items acc =
    case items of
        [] ->
            Ok (VList (List.reverse acc))

        x :: rest ->
            evalExpr env x |> Result.andThen (\v -> evalList env rest (v :: acc))


applyValue : Value -> Value -> Result String Value
applyValue fn arg =
    case fn of
        VClosure params body closedEnv ->
            case params of
                [] ->
                    Err "cannot apply a non-function"

                p :: [] ->
                    evalExpr (( p, arg ) :: closedEnv) body

                p :: more ->
                    Ok (VClosure more body (( p, arg ) :: closedEnv))

        _ ->
            Err "cannot apply a non-function value"


applyOp : String -> Value -> Value -> Result String Value
applyOp op a b =
    if op == "++" then
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

        _ ->
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


lookup : String -> List ( String, Value ) -> Maybe Value
lookup name env =
    case env of
        [] ->
            Nothing

        ( k, v ) :: rest ->
            if k == name then
                Just v

            else
                lookup name rest



-- PUBLIC: parse + evaluate + render


eval : String -> String
eval src =
    case tokenize src |> Result.andThen parse |> Result.andThen (evalExpr []) of
        Ok v ->
            renderValue v

        Err e ->
            "Error: " ++ e


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

        VClosure _ _ _ ->
            "<function>"



-- EDITOR (The Elm Architecture)


type alias Model =
    { source : String }


init : Model
init =
    { source = "let double = \\x -> x * 2 in double 21" }


type Msg
    = Change String


update : Msg -> Model -> Model
update msg model =
    case msg of
        Change s ->
            { model | source = s }


view : Model -> Html Msg
view model =
    div [ style "font-family" "system-ui, sans-serif", style "max-width" "820px", style "margin" "24px auto" ]
        [ h1 [] [ text "Elm-in-Elm — interpreter editor" ]
        , p [] [ text "An interpreter written in Elm, evaluating your input live (numbers, strings, bools, lists, if/let/lambdas)." ]
        , div [ style "display" "flex", style "gap" "16px" ]
            [ textarea
                [ onInput Change
                , value model.source
                , style "flex" "1"
                , style "height" "140px"
                , style "font-family" "monospace"
                , style "font-size" "14px"
                , style "padding" "10px"
                ]
                []
            , div [ style "flex" "1" ]
                [ h2 [] [ text "Result" ]
                , pre
                    [ style "background" "#0f1720"
                    , style "color" "#e6edf3"
                    , style "padding" "12px"
                    , style "border-radius" "8px"
                    ]
                    [ text (eval model.source) ]
                ]
            ]
        ]


main : Program () Model Msg
main =
    Browser.sandbox { init = init, update = update, view = view }

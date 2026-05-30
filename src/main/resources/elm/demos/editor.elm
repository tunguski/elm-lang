module Main exposing (main, eval, evalProject, debugSteps)

{-| An Ellie-style multi-file editor for a small Elm-like language, with a built-in interpreter
(written in Elm) and a time-travel debugger — all running in the browser via this project's JS
backend.

Features:

  - **multiple files** you can add, remove and switch between (a file-structure sidebar);
  - a **shared interpreter** that gathers the top-level definitions of *all* files into one mutually
    recursive scope and evaluates an entry expression (default `main`);
  - a **time-travel debugger**: if the project defines `init`/`update`/`view`, you give a list of
    messages (one per line) and step through every intermediate model and its rendered view.

The interpreted language: integers/floats, strings, booleans, lists; `+ - * / //`, comparison,
logic (`&& ||`), append (`++`); `if`, `let … in`, multi-argument lambdas with closures; custom-type
constructors (any Capitalised name) and `case … of P -> E ; …` (`;`-separated branches); and
top-level definitions `name args = body` shared across files.
-}

import Browser
import Html exposing (Html, button, div, h1, h2, h3, input, li, p, pre, span, text, textarea, ul)
import Html.Attributes exposing (disabled, placeholder, style, value)
import Html.Events exposing (onClick, onInput)



-- VALUES, EXPRESSIONS, DECLARATIONS


type Value
    = VNum Float
    | VBool Bool
    | VStr String
    | VList (List Value)
    | VCtor String (List Value)
    | VClosure (List String) Expr (List ( String, Value ))
    | VRec String (List String) Expr (List ( String, Value ))
    | VBuiltin String


type Expr
    = Num Float
    | Str String
    | Boolean Bool
    | ListE (List Expr)
    | Var String
    | Ctor String
    | Neg Expr
    | BinOp String Expr Expr
    | If Expr Expr Expr
    | Lam (List String) Expr
    | App Expr Expr
    | Let String Expr Expr
    | Case Expr (List ( Pattern, Expr ))


type Pattern
    = PVar String
    | PWild
    | PInt Float
    | PBool Bool
    | PStr String
    | PCtor String (List Pattern)
    | PNil
    | PCons Pattern Pattern


{-| A top-level definition `name args = body`. -}
type alias Decl =
    { name : String
    , params : List String
    , body : Expr
    }


type alias Globals =
    List ( String, Decl )



-- TOKENIZER


type Token
    = TNum Float
    | TStr String
    | TId String
    | TUpper String
    | TOp String
    | TLParen
    | TRParen
    | TLBracket
    | TRBracket
    | TComma
    | TSemi
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
            if c == ' ' || c == '\n' || c == '\t' || c == '\u{000D}' then
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

            else if c == ';' then
                tokenizeHelp rest (TSemi :: acc)

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

                    word =
                        Tuple.first taken

                    token =
                        if Char.isUpper c then
                            TUpper word

                        else
                            TId word
                in
                tokenizeHelp (Tuple.second taken) (token :: acc)

            else
                Err ("unexpected character: " ++ String.fromChar c)


isOpChar : Char -> Bool
isOpChar c =
    c == '+' || c == '-' || c == '*' || c == '/' || c == '=' || c == '<' || c == '>' || c == '&' || c == '|' || c == ':'


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

    else if List.member s [ "+", "-", "*", "/", "//", "==", "/=", "<", "<=", ">", ">=", "&&", "||", "++", "::" ] then
        Ok (TOp s)

    else
        Err ("unknown operator: " ++ s)



-- EXPRESSION PARSER (precedence climbing)


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

    else if op == "++" || op == "::" then
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

        (TUpper _) :: _ ->
            True

        (TId name) :: _ ->
            not (List.member name [ "then", "else", "in", "of", "case" ])

        _ ->
            False


parseAtom : List Token -> Result String ( Expr, List Token )
parseAtom tokens =
    case tokens of
        (TNum n) :: rest ->
            Ok ( Num n, rest )

        (TStr s) :: rest ->
            Ok ( Str s, rest )

        (TUpper "True") :: rest ->
            Ok ( Boolean True, rest )

        (TUpper "False") :: rest ->
            Ok ( Boolean False, rest )

        (TUpper name) :: rest ->
            Ok ( Ctor name, rest )

        (TId "if") :: rest ->
            parseIf rest

        (TId "let") :: rest ->
            parseLet rest

        (TId "case") :: rest ->
            parseCase rest

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


parseCase : List Token -> Result String ( Expr, List Token )
parseCase tokens =
    parseExpr tokens
        |> Result.andThen
            (\rs ->
                case Tuple.second rs of
                    (TId "of") :: afterOf ->
                        parseBranches afterOf []
                            |> Result.map (\rb -> ( Case (Tuple.first rs) (Tuple.first rb), Tuple.second rb ))

                    _ ->
                        Err "expected 'of' after case subject"
            )


parseBranches : List Token -> List ( Pattern, Expr ) -> Result String ( List ( Pattern, Expr ), List Token )
parseBranches tokens acc =
    parsePattern tokens
        |> Result.andThen
            (\rp ->
                case Tuple.second rp of
                    TArrow :: afterArrow ->
                        parseExpr afterArrow
                            |> Result.andThen
                                (\rb ->
                                    let
                                        branches =
                                            acc ++ [ ( Tuple.first rp, Tuple.first rb ) ]
                                    in
                                    case Tuple.second rb of
                                        TSemi :: afterSemi ->
                                            parseBranches afterSemi branches

                                        rest ->
                                            Ok ( branches, rest )
                                )

                    _ ->
                        Err "expected '->' in case branch"
            )


parsePattern : List Token -> Result String ( Pattern, List Token )
parsePattern tokens =
    parsePatternApp tokens
        |> Result.andThen
            (\r ->
                case Tuple.second r of
                    (TOp "::") :: rest ->
                        parsePattern rest
                            |> Result.map (\r2 -> ( PCons (Tuple.first r) (Tuple.first r2), Tuple.second r2 ))

                    _ ->
                        Ok r
            )


parsePatternApp : List Token -> Result String ( Pattern, List Token )
parsePatternApp tokens =
    case tokens of
        (TUpper name) :: rest ->
            parsePatternArgs rest []
                |> Result.map (\r -> ( PCtor name (Tuple.first r), Tuple.second r ))

        _ ->
            parsePatternAtom tokens


parsePatternArgs : List Token -> List Pattern -> Result String ( List Pattern, List Token )
parsePatternArgs tokens acc =
    if startsPatternAtom tokens then
        parsePatternAtom tokens
            |> Result.andThen (\r -> parsePatternArgs (Tuple.second r) (acc ++ [ Tuple.first r ]))

    else
        Ok ( acc, tokens )


startsPatternAtom : List Token -> Bool
startsPatternAtom tokens =
    case tokens of
        (TId _) :: _ ->
            True

        (TUpper _) :: _ ->
            True

        (TNum _) :: _ ->
            True

        (TStr _) :: _ ->
            True

        TLParen :: _ ->
            True

        TLBracket :: _ ->
            True

        _ ->
            False


parsePatternAtom : List Token -> Result String ( Pattern, List Token )
parsePatternAtom tokens =
    case tokens of
        (TId "_") :: rest ->
            Ok ( PWild, rest )

        (TUpper "True") :: rest ->
            Ok ( PBool True, rest )

        (TUpper "False") :: rest ->
            Ok ( PBool False, rest )

        (TUpper name) :: rest ->
            Ok ( PCtor name [], rest )

        (TId name) :: rest ->
            Ok ( PVar name, rest )

        (TNum n) :: rest ->
            Ok ( PInt n, rest )

        (TStr s) :: rest ->
            Ok ( PStr s, rest )

        TLBracket :: TRBracket :: rest ->
            Ok ( PNil, rest )

        TLParen :: rest ->
            parsePattern rest
                |> Result.andThen
                    (\r ->
                        case Tuple.second r of
                            TRParen :: rest2 ->
                                Ok ( Tuple.first r, rest2 )

                            _ ->
                                Err "expected ')' in pattern"
                    )

        _ ->
            Err "expected a pattern"



-- MODULE PARSER: top-level definitions, split by column-0 lines (layout-lite)


parseProject : List ( String, String ) -> Result String Globals
parseProject files =
    List.foldl
        (\file acc ->
            acc |> Result.andThen (\defs -> parseModule (Tuple.second file) |> Result.map (\d -> defs ++ d))
        )
        (Ok [])
        files


parseModule : String -> Result String Globals
parseModule source =
    chunk (String.lines source) [] []
        |> List.filter (\c -> c /= "")
        |> List.foldl
            (\c acc -> acc |> Result.andThen (\defs -> parseDecl c |> Result.map (\md -> defs ++ md)))
            (Ok [])


{-| Groups source lines into top-level chunks: a new chunk starts at a non-blank line whose first
character is not whitespace; indented/blank lines continue the current chunk. -}
chunk : List String -> List String -> List String -> List String
chunk lines current done =
    case lines of
        [] ->
            List.reverse (flush current done)

        line :: rest ->
            if startsTopLevel line then
                chunk rest [ line ] (flush current done)

            else
                chunk rest (current ++ [ line ]) done


flush : List String -> List String -> List String
flush current done =
    if List.isEmpty current then
        done

    else
        String.join "\n" current :: done


startsTopLevel : String -> Bool
startsTopLevel line =
    case String.toList line of
        [] ->
            False

        c :: _ ->
            not (c == ' ' || c == '\t')


{-| Parses one top-level chunk into a (possibly empty) list of declarations. Module/import/type
headers and bare type annotations are ignored; a `name params = body` becomes a Decl. -}
parseDecl : String -> Result String Globals
parseDecl source =
    let
        trimmed =
            String.trimLeft source

        firstWord =
            trimmed |> String.split " " |> List.head |> Maybe.withDefault ""
    in
    if List.member firstWord [ "module", "import", "type", "port", "" ] || String.startsWith "--" trimmed then
        Ok []

    else
        case tokenize source of
            Err _ ->
                Ok []

            Ok tokens ->
                case tokens of
                    (TId name) :: rest ->
                        parseDeclParams name rest []

                    _ ->
                        Ok []


parseDeclParams : String -> List Token -> List String -> Result String Globals
parseDeclParams name tokens params =
    case tokens of
        (TId p) :: rest ->
            parseDeclParams name rest (params ++ [ p ])

        TEquals :: rest ->
            parse rest |> Result.map (\body -> [ ( name, { name = name, params = params, body = body } ) ])

        _ ->
            -- not a value/function definition (e.g. an annotation `name : Type`): ignore
            Ok []



-- EVALUATOR (globals are threaded so all top-level definitions are mutually recursive)


type alias Env =
    List ( String, Value )


{-| Native one-argument builtins available to interpreted programs (resolved when a name is in
neither the local scope nor the project's top-level definitions). -}
builtins : List String
builtins =
    [ "toString", "negate", "not" ]


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
                                Ok (VBuiltin name)

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

        VBuiltin name ->
            applyBuiltin name arg

        _ ->
            Err "cannot apply a non-function value"


applyBuiltin : String -> Value -> Result String Value
applyBuiltin name arg =
    case name of
        "toString" ->
            case arg of
                VStr s ->
                    Ok (VStr s)

                _ ->
                    Ok (VStr (renderValue arg))

        "negate" ->
            case arg of
                VNum n ->
                    Ok (VNum (negate n))

                _ ->
                    Err "negate needs a number"

        "not" ->
            case arg of
                VBool b ->
                    Ok (VBool (not b))

                _ ->
                    Err "not needs a Bool"

        _ ->
            Err ("unknown builtin: " ++ name)


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

        VClosure _ _ _ ->
            "<function>"

        VRec _ _ _ _ ->
            "<function>"

        VBuiltin name ->
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



-- EDITOR (The Elm Architecture)


type alias Model =
    { files : List ( String, String )
    , active : String
    , entry : String
    , messages : String
    , step : Int
    , newName : String
    }


init : Model
init =
    { files =
        [ ( "Counter.elm", "init = 0\n\nupdate msg model =\n    case msg of\n        Inc -> model + 1 ;\n        Dec -> model - 1 ;\n        _ -> model\n\nview model = \"count = \" ++ toString model" )
        , ( "Main.elm", "main = greet \"world\"\n\ngreet name = \"Hello, \" ++ name ++ \"!\"" )
        ]
    , active = "Main.elm"
    , entry = "main"
    , messages = "Inc\nInc\nDec\nInc"
    , step = 0
    , newName = ""
    }


type Msg
    = Select String
    | Edit String
    | SetEntry String
    | SetMessages String
    | SetStep String
    | SetNewName String
    | AddFile
    | RemoveFile String


update : Msg -> Model -> Model
update msg model =
    case msg of
        Select name ->
            { model | active = name }

        Edit content ->
            { model | files = setFile model.active content model.files }

        SetEntry e ->
            { model | entry = e }

        SetMessages m ->
            { model | messages = m, step = 0 }

        SetStep s ->
            { model | step = Maybe.withDefault 0 (String.toInt s) }

        SetNewName n ->
            { model | newName = n }

        AddFile ->
            let
                name =
                    if String.endsWith ".elm" model.newName then
                        model.newName

                    else
                        model.newName ++ ".elm"
            in
            if model.newName == "" || hasFile name model.files then
                model

            else
                { model | files = model.files ++ [ ( name, "" ) ], active = name, newName = "" }

        RemoveFile name ->
            let
                remaining =
                    List.filter (\f -> Tuple.first f /= name) model.files
            in
            { model
                | files = remaining
                , active =
                    if model.active == name then
                        List.head remaining |> Maybe.map Tuple.first |> Maybe.withDefault ""

                    else
                        model.active
            }


setFile : String -> String -> List ( String, String ) -> List ( String, String )
setFile name content files =
    List.map
        (\f ->
            if Tuple.first f == name then
                ( name, content )

            else
                f
        )
        files


hasFile : String -> List ( String, String ) -> Bool
hasFile name files =
    List.any (\f -> Tuple.first f == name) files


activeContent : Model -> String
activeContent model =
    lookup model.active model.files |> Maybe.withDefault ""



-- VIEW


view : Model -> Html Msg
view model =
    let
        steps =
            debugSteps model.files (String.lines model.messages)

        clampedStep =
            Basics.max 0 (Basics.min model.step (List.length steps - 1))

        currentStep =
            steps |> List.drop clampedStep |> List.head |> Maybe.withDefault ""
    in
    div [ style "font-family" "system-ui, sans-serif", style "max-width" "1000px", style "margin" "20px auto", style "color" "#0f1720" ]
        [ h1 [] [ text "Elm-in-Elm — multi-file editor" ]
        , p [] [ text "A tiny Elm-like language interpreted in the browser. Edit files on the left; results and the time-travel debugger update live." ]
        , div [ style "display" "flex", style "gap" "16px", style "align-items" "flex-start" ]
            [ fileSidebar model
            , div [ style "flex" "2" ]
                [ h3 [] [ text model.active ]
                , textarea
                    [ onInput Edit
                    , value (activeContent model)
                    , style "width" "100%"
                    , style "height" "260px"
                    , style "font-family" "monospace"
                    , style "font-size" "13px"
                    , style "padding" "10px"
                    , style "box-sizing" "border-box"
                    ]
                    []
                , resultPane model
                ]
            ]
        , debuggerPane steps clampedStep currentStep
        ]


fileSidebar : Model -> Html Msg
fileSidebar model =
    div [ style "flex" "1", style "min-width" "180px" ]
        [ h3 [] [ text "Files" ]
        , ul [ style "list-style" "none", style "padding" "0", style "margin" "0" ]
            (List.map (fileRow model.active) model.files)
        , div [ style "display" "flex", style "gap" "4px", style "margin-top" "8px" ]
            [ input [ placeholder "New.elm", value model.newName, onInput SetNewName, style "flex" "1", style "min-width" "0" ] []
            , button [ onClick AddFile ] [ text "+" ]
            ]
        ]


fileRow : String -> ( String, String ) -> Html Msg
fileRow active file =
    let
        name =
            Tuple.first file

        selected =
            name == active
    in
    li [ style "display" "flex", style "align-items" "center", style "gap" "4px", style "margin" "2px 0" ]
        [ button
            [ onClick (Select name)
            , style "flex" "1"
            , style "text-align" "left"
            , style "font-weight"
                (if selected then
                    "bold"

                 else
                    "normal"
                )
            , style "background"
                (if selected then
                    "#dbeeff"

                 else
                    "#f4f4f4"
                )
            ]
            [ text name ]
        , button [ onClick (RemoveFile name), style "color" "#a00" ] [ text "×" ]
        ]


resultPane : Model -> Html Msg
resultPane model =
    div [ style "margin-top" "12px" ]
        [ div [ style "display" "flex", style "gap" "8px", style "align-items" "center" ]
            [ h3 [ style "margin" "0" ] [ text "Result of " ]
            , input [ value model.entry, onInput SetEntry, style "font-family" "monospace" ] []
            ]
        , pre [ style "background" "#0f1720", style "color" "#e6edf3", style "padding" "12px", style "border-radius" "8px", style "white-space" "pre-wrap" ]
            [ text (evalProject model.files model.entry) ]
        ]


debuggerPane : List String -> Int -> String -> Html Msg
debuggerPane steps clampedStep currentStep =
    div [ style "margin-top" "16px", style "border-top" "1px solid #ccc", style "padding-top" "12px" ]
        [ h2 [] [ text "Time-travel debugger" ]
        , p [] [ text "Define top-level init / update / view, then list messages (one per line) to step through:" ]
        , textarea
            [ onInput SetMessages
            , placeholder "Inc\nInc\nDec"
            , style "width" "100%"
            , style "height" "70px"
            , style "font-family" "monospace"
            , style "box-sizing" "border-box"
            ]
            []
        , div [ style "margin" "8px 0" ]
            [ text ("step " ++ String.fromInt clampedStep ++ " / " ++ String.fromInt (List.length steps - 1) ++ "   ")
            , input
                [ Html.Attributes.type_ "range"
                , Html.Attributes.min "0"
                , Html.Attributes.max (String.fromInt (Basics.max 0 (List.length steps - 1)))
                , value (String.fromInt clampedStep)
                , onInput SetStep
                , style "width" "300px"
                ]
                []
            ]
        , pre [ style "background" "#0f1720", style "color" "#e6edf3", style "padding" "12px", style "border-radius" "8px", style "white-space" "pre-wrap" ]
            [ text currentStep ]
        , h3 [] [ text "All steps" ]
        , ul [ style "font-family" "monospace", style "font-size" "12px" ]
            (List.indexedMap (\i s -> li [ style "color" (if i == clampedStep then "#0a7" else "#555") ] [ text s ]) steps)
        ]


main : Program () Model Msg
main =
    Browser.sandbox { init = init, update = update, view = view }

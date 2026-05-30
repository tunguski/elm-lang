module Parser exposing (parse, parseProject)

{-| Parses tokens into expressions (precedence-climbing) and a project's source files into a
mutually-recursive set of top-level declarations (a column-0 "layout-lite" chunker). -}

import Lang exposing (Decl, Expr(..), Globals, Pattern(..))
import Lexer exposing (Token(..), cookLayout, tokenize)



-- EXPRESSION PARSER (precedence climbing)


parse : List Token -> Result String Expr
parse rawTokens =
    parseExpr (cookLayout rawTokens)
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
    parseAccess tokens
        |> Result.andThen (\r -> appTail (Tuple.first r) (Tuple.second r))


appTail : Expr -> List Token -> Result String ( Expr, List Token )
appTail fn tokens =
    if startsAtom tokens then
        parseAccess tokens
            |> Result.andThen (\r -> appTail (App fn (Tuple.first r)) (Tuple.second r))

    else
        Ok ( fn, tokens )


{-| An atom followed by zero or more `.field` accesses (`record.field.sub`). -}
parseAccess : List Token -> Result String ( Expr, List Token )
parseAccess tokens =
    parseAtom tokens
        |> Result.andThen (\r -> accessTail (Tuple.first r) (Tuple.second r))


accessTail : Expr -> List Token -> Result String ( Expr, List Token )
accessTail e tokens =
    case tokens of
        TDot :: (TId field) :: rest ->
            accessTail (RecordGet e field) rest

        _ ->
            Ok ( e, tokens )


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

        TLBrace :: _ ->
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

        TLBrace :: rest ->
            parseRecord rest

        _ ->
            Err "expected an expression"


{-| A record literal `{ a = e, … }`, an update `{ r | a = e, … }`, or the empty record `{}`. -}
parseRecord : List Token -> Result String ( Expr, List Token )
parseRecord tokens =
    case tokens of
        TRBrace :: rest ->
            Ok ( RecordLit [], rest )

        (TId name) :: TPipe :: afterPipe ->
            parseFields afterPipe []
                |> Result.map (\r -> ( RecordUpdate name (Tuple.first r), Tuple.second r ))

        _ ->
            parseFields tokens []
                |> Result.map (\r -> ( RecordLit (Tuple.first r), Tuple.second r ))


parseFields : List Token -> List ( String, Expr ) -> Result String ( List ( String, Expr ), List Token )
parseFields tokens acc =
    case tokens of
        (TId name) :: TEquals :: afterEq ->
            parseExpr afterEq
                |> Result.andThen
                    (\r ->
                        case Tuple.second r of
                            TComma :: rest2 ->
                                parseFields rest2 (acc ++ [ ( name, Tuple.first r ) ])

                            TRBrace :: rest2 ->
                                Ok ( acc ++ [ ( name, Tuple.first r ) ], rest2 )

                            _ ->
                                Err "expected ',' or '}' in record"
                    )

        _ ->
            Err "expected 'field = value' in record"


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

            Ok rawTokens ->
                case cookLayout rawTokens of
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

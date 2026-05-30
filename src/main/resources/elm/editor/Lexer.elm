module Lexer exposing (Token(..), tokenize)

{-| The tokenizer for the interpreted language: turns source text into a flat list of tokens. -}


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
    | TLBrace
    | TRBrace
    | TDot
    | TPipe
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

            else if c == '{' then
                tokenizeHelp rest (TLBrace :: acc)

            else if c == '}' then
                tokenizeHelp rest (TRBrace :: acc)

            else if c == '.' then
                tokenizeHelp rest (TDot :: acc)

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

    else if s == "|" then
        Ok TPipe

    else if List.member s [ "+", "-", "*", "/", "//", "==", "/=", "<", "<=", ">", ">=", "&&", "||", "++", "::" ] then
        Ok (TOp s)

    else
        Err ("unknown operator: " ++ s)

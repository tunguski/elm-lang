module Lexer exposing (Token(..), tokenize, cookLayout)

{-| The tokenizer for the interpreted language: turns source text into a flat list of tokens. It
keeps layout by emitting a `TLine indent` marker at the start of each non-blank line; `cookLayout`
then turns indentation into the `;` branch separators the parser expects (so real Elm `case`
expressions, which use layout rather than explicit separators, parse correctly). -}


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
    | TLine Int


tokenize : String -> Result String (List Token)
tokenize src =
    tokenizeLines (String.lines src) []


{-| Tokenizes line by line, prefixing each non-blank line's tokens with its indentation marker. -}
tokenizeLines : List String -> List Token -> Result String (List Token)
tokenizeLines lines acc =
    case lines of
        [] ->
            Ok acc

        line :: rest ->
            if String.trim line == "" then
                tokenizeLines rest acc

            else
                case tokenizeHelp (String.toList line) [] of
                    Ok toks ->
                        tokenizeLines rest (acc ++ (TLine (indentOf line) :: toks))

                    Err e ->
                        Err e


indentOf : String -> Int
indentOf line =
    String.length line - String.length (String.trimLeft line)


{-| Resolves layout: drops the `TLine` markers, inserting a `TSemi` between sibling `case` branches
and `let` bindings (lines at the branch/binding indentation). Operates per top-level chunk, so the
only column-0 line is the chunk header — branch indentation is always deeper, which keeps the rule
simple and safe. -}
cookLayout : List Token -> List Token
cookLayout toks =
    cook toks [] [] False


cook : List Token -> List Token -> List Int -> Bool -> List Token
cook toks out stack afterOf =
    case toks of
        [] ->
            List.reverse out

        (TLine col) :: rest ->
            if afterOf then
                cook rest out (col :: stack) False

            else
                let
                    popped =
                        dropWhileGreater col stack
                in
                case popped of
                    h :: _ ->
                        if h == col then
                            case out of
                                TSemi :: _ ->
                                    cook rest out popped False

                                _ ->
                                    cook rest (TSemi :: out) popped False

                        else
                            cook rest out popped False

                    [] ->
                        cook rest out popped False

        (TId "of") :: rest ->
            cook rest (TId "of" :: out) stack True

        (TId "let") :: rest ->
            -- like `of`: the next line establishes the binding column, so sibling
            -- bindings at that column get a `TSemi`; the dedent at `in` pops it back.
            cook rest (TId "let" :: out) stack True

        t :: rest ->
            cook rest (t :: out) stack afterOf


dropWhileGreater : Int -> List Int -> List Int
dropWhileGreater col stack =
    case stack of
        h :: rest ->
            if h > col then
                dropWhileGreater col rest

            else
                stack

        [] ->
            []


tokenizeHelp : List Char -> List Token -> Result String (List Token)
tokenizeHelp chars acc =
    case chars of
        [] ->
            Ok (List.reverse acc)

        c :: rest ->
            if c == ' ' || c == '\n' || c == '\t' || c == '\u{000D}' then
                tokenizeHelp rest acc

            else if c == '-' && List.head rest == Just '-' then
                -- line comment: drop the rest of the line
                Ok (List.reverse acc)

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

    else if List.member s [ "+", "-", "*", "/", "//", "==", "/=", "<", "<=", ">", ">=", "&&", "||", "++", "::", "|>", "<|", ">>", "<<" ] then
        Ok (TOp s)

    else
        Err ("unknown operator: " ++ s)

module Main exposing (main, run)

{-| An Elm-in-Elm expression interpreter (tokenizer + recursive-descent parser + evaluator) wrapped
in an Ellie-style editor: edit the expression on the left, see it evaluated live on the right. The
interpreter itself is written in Elm and runs (compiled by this project's JS backend) in the
browser. Scope: the arithmetic expression subset (+ - * / // , parentheses, unary minus, numbers).
-}

import Browser
import Html exposing (Html, div, h1, h2, p, pre, text, textarea)
import Html.Attributes exposing (style, value)
import Html.Events exposing (onInput)


-- TOKENIZER


type Token
    = TNum Float
    | TPlus
    | TMinus
    | TStar
    | TSlash
    | TSlashSlash
    | TLParen
    | TRParen


tokenize : String -> Result String (List Token)
tokenize src =
    tokenizeHelp (String.toList src) []


tokenizeHelp : List Char -> List Token -> Result String (List Token)
tokenizeHelp chars acc =
    case chars of
        [] ->
            Ok (List.reverse acc)

        c :: rest ->
            if c == ' ' then
                tokenizeHelp rest acc

            else if c == '+' then
                tokenizeHelp rest (TPlus :: acc)

            else if c == '-' then
                tokenizeHelp rest (TMinus :: acc)

            else if c == '*' then
                tokenizeHelp rest (TStar :: acc)

            else if c == '/' then
                case rest of
                    '/' :: more ->
                        tokenizeHelp more (TSlashSlash :: acc)

                    _ ->
                        tokenizeHelp rest (TSlash :: acc)

            else if c == '(' then
                tokenizeHelp rest (TLParen :: acc)

            else if c == ')' then
                tokenizeHelp rest (TRParen :: acc)

            else if Char.isDigit c then
                let
                    taken =
                        takeNumber chars ""
                in
                case String.toFloat (Tuple.first taken) of
                    Just n ->
                        tokenizeHelp (Tuple.second taken) (TNum n :: acc)

                    Nothing ->
                        Err ("bad number: " ++ Tuple.first taken)

            else
                Err ("unexpected character: " ++ String.fromChar c)


takeNumber : List Char -> String -> ( String, List Char )
takeNumber chars acc =
    case chars of
        c :: rest ->
            if Char.isDigit c || c == '.' then
                takeNumber rest (acc ++ String.fromChar c)

            else
                ( acc, chars )

        [] ->
            ( acc, chars )



-- PARSER + EVALUATOR (recursive descent over the token list)


run : String -> Result String Float
run src =
    tokenize src
        |> Result.andThen
            (\toks ->
                parseExpr toks
                    |> Result.andThen
                        (\result ->
                            if List.isEmpty (Tuple.second result) then
                                Ok (Tuple.first result)

                            else
                                Err "unexpected trailing input"
                        )
            )


parseExpr : List Token -> Result String ( Float, List Token )
parseExpr tokens =
    parseTerm tokens |> Result.andThen (\r -> parseExprTail (Tuple.first r) (Tuple.second r))


parseExprTail : Float -> List Token -> Result String ( Float, List Token )
parseExprTail left tokens =
    case tokens of
        TPlus :: rest ->
            parseTerm rest |> Result.andThen (\r -> parseExprTail (left + Tuple.first r) (Tuple.second r))

        TMinus :: rest ->
            parseTerm rest |> Result.andThen (\r -> parseExprTail (left - Tuple.first r) (Tuple.second r))

        _ ->
            Ok ( left, tokens )


parseTerm : List Token -> Result String ( Float, List Token )
parseTerm tokens =
    parseFactor tokens |> Result.andThen (\r -> parseTermTail (Tuple.first r) (Tuple.second r))


parseTermTail : Float -> List Token -> Result String ( Float, List Token )
parseTermTail left tokens =
    case tokens of
        TStar :: rest ->
            parseFactor rest |> Result.andThen (\r -> parseTermTail (left * Tuple.first r) (Tuple.second r))

        TSlash :: rest ->
            parseFactor rest
                |> Result.andThen
                    (\r ->
                        if Tuple.first r == 0 then
                            Err "division by zero"

                        else
                            parseTermTail (left / Tuple.first r) (Tuple.second r)
                    )

        TSlashSlash :: rest ->
            parseFactor rest
                |> Result.andThen
                    (\r ->
                        if Tuple.first r == 0 then
                            Err "division by zero"

                        else
                            parseTermTail (toFloat (truncate (left / Tuple.first r))) (Tuple.second r)
                    )

        _ ->
            Ok ( left, tokens )


parseFactor : List Token -> Result String ( Float, List Token )
parseFactor tokens =
    case tokens of
        (TNum n) :: rest ->
            Ok ( n, rest )

        TMinus :: rest ->
            parseFactor rest |> Result.andThen (\r -> Ok ( negate (Tuple.first r), Tuple.second r ))

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

        _ ->
            Err "expected a number or ("



-- EDITOR (The Elm Architecture)


type alias Model =
    { source : String }


init : Model
init =
    { source = "2 + 3 * (4 - 1) // 2" }


type Msg
    = Change String


update : Msg -> Model -> Model
update msg model =
    case msg of
        Change s ->
            { model | source = s }


view : Model -> Html Msg
view model =
    div [ style "font-family" "system-ui, sans-serif", style "max-width" "780px", style "margin" "24px auto" ]
        [ h1 [] [ text "Elm-in-Elm — expression editor" ]
        , p [] [ text "An interpreter written in Elm, evaluating your input live (arithmetic subset)." ]
        , div [ style "display" "flex", style "gap" "16px" ]
            [ textarea
                [ onInput Change
                , value model.source
                , style "flex" "1"
                , style "height" "120px"
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
                    [ text (render (run model.source)) ]
                ]
            ]
        ]


render : Result String Float -> String
render result =
    case result of
        Ok n ->
            "= " ++ String.fromFloat n

        Err e ->
            "Error: " ++ e


main : Program () Model Msg
main =
    Browser.sandbox { init = init, update = update, view = view }

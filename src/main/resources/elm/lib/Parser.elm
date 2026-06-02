module Parser exposing
    ( Parser
    , run
    , succeed
    , problem
    , map
    , andThen
    , keep
    , ignore
    , (|=)
    , (|.)
    , oneOf
    , symbol
    , keyword
    , token
    , int
    , float
    , spaces
    , chompWhile
    , chompIf
    , chompUntil
    , getChompedString
    , backtrackable
    , lazy
    , Step(..)
    , loop
    , end
    )

{-| A small parser-combinator library in the spirit of elm/parser: build parsers from `succeed`,
`int`, `symbol`, `spaces`, … and combine them with the keep (`|=`) and ignore (`|.`) operators.

    point : Parser ( Int, Int )
    point =
        succeed (\x y -> ( x, y ))
            |. symbol "("
            |. spaces
            |= int
            |. spaces
            |. symbol ","
            |. spaces
            |= int
            |. spaces
            |. symbol ")"

    run point "( 3 , 4 )" == Ok ( 3, 4 )

-}


{-| The parser state: the whole input and the current offset into it. -}
type alias State =
    { src : String, offset : Int }


{-| A parser producing an `a` (or a `String` error). -}
type Parser a
    = Parser (State -> Result String ( a, State ))


{-| Runs a parser over an input string, returning `Ok value` or `Err message`. -}
run : Parser a -> String -> Result String a
run (Parser p) src =
    case p { src = src, offset = 0 } of
        Ok ( a, _ ) ->
            Ok a

        Err e ->
            Err e


{-| A parser that always succeeds with the given value, consuming nothing. -}
succeed : a -> Parser a
succeed a =
    Parser (\s -> Ok ( a, s ))


{-| A parser that always fails with the given message. -}
problem : String -> Parser a
problem msg =
    Parser (\_ -> Err msg)


map : (a -> b) -> Parser a -> Parser b
map f (Parser p) =
    Parser
        (\s ->
            case p s of
                Ok ( a, s2 ) ->
                    Ok ( f a, s2 )

                Err e ->
                    Err e
        )


andThen : (a -> Parser b) -> Parser a -> Parser b
andThen f (Parser p) =
    Parser
        (\s ->
            case p s of
                Ok ( a, s2 ) ->
                    case f a of
                        Parser q ->
                            q s2

                Err e ->
                    Err e
        )


{-| Keep: applies the function a parser produced to the value of the next parser (the `|=` operator). -}
keep : Parser (a -> b) -> Parser a -> Parser b
keep (Parser pf) (Parser pa) =
    Parser
        (\s ->
            case pf s of
                Ok ( f, s2 ) ->
                    case pa s2 of
                        Ok ( a, s3 ) ->
                            Ok ( f a, s3 )

                        Err e ->
                            Err e

                Err e ->
                    Err e
        )


{-| Ignore: runs the next parser but keeps the previous value (the `|.` operator). -}
ignore : Parser a -> Parser b -> Parser a
ignore (Parser pk) (Parser pj) =
    Parser
        (\s ->
            case pk s of
                Ok ( k, s2 ) ->
                    case pj s2 of
                        Ok ( _, s3 ) ->
                            Ok ( k, s3 )

                        Err e ->
                            Err e

                Err e ->
                    Err e
        )


(|=) : Parser (a -> b) -> Parser a -> Parser b
(|=) =
    keep


(|.) : Parser a -> Parser b -> Parser a
(|.) =
    ignore


{-| Tries each parser in turn (no backtracking cost is modelled), returning the first that succeeds. -}
oneOf : List (Parser a) -> Parser a
oneOf parsers =
    Parser (\s -> oneOfHelp parsers s)


oneOfHelp : List (Parser a) -> State -> Result String ( a, State )
oneOfHelp parsers s =
    case parsers of
        (Parser p) :: rest ->
            case p s of
                Ok r ->
                    Ok r

                Err _ ->
                    oneOfHelp rest s

        [] ->
            Err "oneOf: no alternative matched"


{-| Matches an exact string at the current offset. -}
symbol : String -> Parser ()
symbol str =
    Parser
        (\s ->
            if String.startsWith str (String.dropLeft s.offset s.src) then
                Ok ( (), { s | offset = s.offset + String.length str } )

            else
                Err ("expected \"" ++ str ++ "\"")
        )


{-| Like `symbol` (this small library does not enforce a word boundary). -}
keyword : String -> Parser ()
keyword =
    symbol


{-| Matches an exact string (elm/parser's general `token`); here a synonym for `symbol`. -}
token : String -> Parser ()
token =
    symbol


{-| Consumes one character if it satisfies the predicate, failing otherwise. -}
chompIf : (Char -> Bool) -> Parser ()
chompIf pred =
    Parser
        (\s ->
            case String.uncons (String.dropLeft s.offset s.src) of
                Just ( c, _ ) ->
                    if pred c then
                        Ok ( (), { s | offset = s.offset + 1 } )

                    else
                        Err "chompIf: unexpected character"

                Nothing ->
                    Err "chompIf: end of input"
        )


{-| In this simple library every failure already backtracks (there is no committed state), so this
is the identity — provided for `elm/parser` API compatibility. -}
backtrackable : Parser a -> Parser a
backtrackable p =
    p


{-| Consumes zero or more characters while the predicate holds. -}
{-| Defers building a parser until it runs, so a parser can refer to itself (recursive grammars).

    term = lazy (\_ -> oneOf [ int, parens expr ])

-}
lazy : (() -> Parser a) -> Parser a
lazy thunk =
    Parser
        (\s ->
            case thunk () of
                Parser p ->
                    p s
        )


{-| Chomp zero or more characters until the given (non-empty) substring is next, leaving the cursor
just before it; fails if the substring never appears. -}
chompUntil : String -> Parser ()
chompUntil str =
    Parser
        (\s ->
            case List.head (String.indexes str (String.dropLeft s.offset s.src)) of
                Just i ->
                    Ok ( (), { s | offset = s.offset + i } )

                Nothing ->
                    Err ("expected to find " ++ str)
        )


chompWhile : (Char -> Bool) -> Parser ()
chompWhile pred =
    Parser
        (\s ->
            let
                taken =
                    countWhile pred (String.toList (String.dropLeft s.offset s.src))
            in
            Ok ( (), { s | offset = s.offset + taken } )
        )


countWhile : (Char -> Bool) -> List Char -> Int
countWhile pred chars =
    case chars of
        c :: cs ->
            if pred c then
                1 + countWhile pred cs

            else
                0

        [] ->
            0


{-| The substring a parser consumed. -}
getChompedString : Parser a -> Parser String
getChompedString (Parser p) =
    Parser
        (\s ->
            case p s of
                Ok ( _, s2 ) ->
                    Ok ( String.slice s.offset s2.offset s.src, s2 )

                Err e ->
                    Err e
        )


{-| A non-negative integer. -}
int : Parser Int
int =
    getChompedString (chompWhile Char.isDigit)
        |> andThen
            (\str ->
                case String.toInt str of
                    Just n ->
                        succeed n

                    Nothing ->
                        problem "expected an integer"
            )


{-| A number with an optional fractional part, e.g. `3`, `3.14`. -}
float : Parser Float
float =
    getChompedString (chompWhile (\c -> Char.isDigit c || c == '.'))
        |> andThen
            (\str ->
                case String.toFloat str of
                    Just n ->
                        succeed n

                    Nothing ->
                        problem "expected a float"
            )


{-| One step of a {@link loop}: keep going with new state, or finish with a result. -}
type Step state a
    = Loop state
    | Done a


{-| Repeats a parser, threading state, until it returns `Done` — for parsing sequences without
manual recursion. The callback must make progress (consume input) on each `Loop` to terminate. -}
loop : state -> (state -> Parser (Step state a)) -> Parser a
loop initial callback =
    Parser (\s -> loopHelp initial callback s)


loopHelp : state -> (state -> Parser (Step state a)) -> State -> Result String ( a, State )
loopHelp state callback s =
    case callback state of
        Parser p ->
            case p s of
                Ok ( Loop newState, s2 ) ->
                    loopHelp newState callback s2

                Ok ( Done result, s2 ) ->
                    Ok ( result, s2 )

                Err e ->
                    Err e


{-| Consumes any run of spaces, tabs and newlines. -}
spaces : Parser ()
spaces =
    chompWhile (\c -> c == ' ' || c == '\n' || c == '\t' || c == '\u{000D}')


{-| Succeeds only at the end of the input. -}
end : Parser ()
end =
    Parser
        (\s ->
            if s.offset >= String.length s.src then
                Ok ( (), s )

            else
                Err "expected end of input"
        )

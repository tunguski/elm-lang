module Awk exposing
    ( Rule
    , begin
    , end
    , on
    , matchLine
    , eachLine
    , program
    , oneLiner
    , invocation
    , print
    , printf
    , assign
    , addTo
    , field
    , nf
    , nr
    , var
    , str
    , num
    , call
    , matches
    )

{-| Build **awk** program text in Elm, to embed in a generated shell script or pass to `awk` as a
command argument — this does **not** run awk, it composes the source you hand to it.

An awk program is a list of `pattern { action }` rules; `program` renders them, `oneLiner` wraps the
result in single quotes for a shell command line, and `invocation` builds the `awk` argument list.
The helpers produce awk expression/statement text: `field 1` is `$1`, `nf`/`nr` are `NF`/`NR`,
`print`/`printf`/`assign` are statements, `call` is a function call (`length`, `substr`, `toupper`,
`gsub`, …).

    import Awk exposing (..)
    import Bash

    -- awk 'BEGIN { FS="," } { sum += $2 } END { print sum }' data.csv
    total : List String
    total =
        invocation
            [ begin [ assign "FS" (str ",") ]
            , eachLine [ addTo "sum" (field 2) ]
            , end [ print [ var "sum" ] ]
            ]
            [ "data.csv" ]

    runIt : Bash.Io
    runIt =
        Bash.exec "awk" total (\_ -> Bash.done)

-}


{-| One awk rule: a pattern (empty = every line; `"BEGIN"`/`"END"`; a condition; or `/re/`) and the
statements of its action block. -}
type alias Rule =
    { pattern : String, action : List String }


{-| `BEGIN { … }`. -}
begin : List String -> Rule
begin action =
    { pattern = "BEGIN", action = action }


{-| `END { … }`. -}
end : List String -> Rule
end action =
    { pattern = "END", action = action }


{-| `cond { … }` — run the action on lines where the awk condition holds. -}
on : String -> List String -> Rule
on cond action =
    { pattern = cond, action = action }


{-| `/re/ { … }` — run the action on lines matching the regular expression. -}
matchLine : String -> List String -> Rule
matchLine re action =
    { pattern = "/" ++ re ++ "/", action = action }


{-| `{ … }` — run the action on every line. -}
eachLine : List String -> Rule
eachLine action =
    { pattern = "", action = action }


{-| Renders the rules to awk program text (one rule per line). -}
program : List Rule -> String
program rules =
    String.join "\n" (List.map renderRule rules)


renderRule : Rule -> String
renderRule rule =
    let
        block =
            "{ " ++ String.join "; " rule.action ++ " }"
    in
    if rule.pattern == "" then
        block

    else
        rule.pattern ++ " " ++ block


{-| The program as a single shell-quoted argument: `'…'` (newlines flattened to `; `). Embed it in a
generated bash script or hand it to `awk`. -}
oneLiner : List Rule -> String
oneLiner rules =
    "'" ++ String.replace "\n" " " (program rules) ++ "'"


{-| The `awk` argument list: the program followed by the file arguments — `Bash.exec "awk" …`. -}
invocation : List Rule -> List String -> List String
invocation rules files =
    program rules :: files



-- STATEMENTS --------------------------------------------------------------


{-| `print a, b, …`. -}
print : List String -> String
print exprs =
    "print " ++ String.join ", " exprs


{-| `printf fmt, a, b, …`. -}
printf : String -> List String -> String
printf fmt exprs =
    case exprs of
        [] ->
            "printf " ++ str fmt

        _ ->
            "printf " ++ str fmt ++ ", " ++ String.join ", " exprs


{-| `name = expr`. -}
assign : String -> String -> String
assign name expr =
    name ++ " = " ++ expr


{-| `name += expr`. -}
addTo : String -> String -> String
addTo name expr =
    name ++ " += " ++ expr



-- EXPRESSIONS -------------------------------------------------------------


{-| A field reference: `field 0` is `$0` (the whole line), `field n` is `$n`. -}
field : Int -> String
field n =
    "$" ++ String.fromInt n


{-| `NF` — the field count. -}
nf : String
nf =
    "NF"


{-| `NR` — the record number. -}
nr : String
nr =
    "NR"


{-| A bare variable/identifier reference. -}
var : String -> String
var name =
    name


{-| An awk string literal: `"…"` (embedded quotes/backslashes escaped). -}
str : String -> String
str s =
    "\"" ++ String.replace "\"" "\\\"" (String.replace "\\" "\\\\" s) ++ "\""


{-| A numeric literal. -}
num : Float -> String
num n =
    String.fromFloat n


{-| A function call: `call "substr" [ field 1, "1", "3" ]` is `substr($1, 1, 3)`. -}
call : String -> List String -> String
call fn args =
    fn ++ "(" ++ String.join ", " args ++ ")"


{-| A regex match test: `matches (field 1) "^[0-9]+$"` is `$1 ~ /^[0-9]+$/`. -}
matches : String -> String -> String
matches expr re =
    expr ++ " ~ /" ++ re ++ "/"

module Awk exposing
    ( Record
    , run
    , runWith
    , Program
    , field
    , nf
    , nr
    , fields
    , toRecord
    , column
    , columns
    , matching
    , sumColumn
    , length
    , substr
    , substrFrom
    , index
    , split
    , toupper
    , tolower
    )

{-| A tiny **awk** in Elm: process text a line (record) at a time, split each into fields, and run an
action that prints output lines — the awk model from <https://www.grymoire.com/Unix/Awk.html>.

awk's core nouns map directly: a `Record` is the current line with its split `fields`, `nr` is the
record number (awk's `NR`), `nf` the field count (`NF`), `field 0` is the whole line (`$0`) and
`field n` the nth field (`$n`, 1-based). An action returns the lines it "prints"; `run` joins them.

    import Awk exposing (..)

    -- like `awk '{ print $1 }'`
    firstColumn : String -> String
    firstColumn =
        run (\r -> [ field 1 r ])

    -- like `awk 'NR % 2 == 1'` (print odd-numbered lines)
    oddLines : String -> String
    oddLines =
        run (\r -> if modBy 2 (nr r) == 1 then [ field 0 r ] else [])

The default field separator is whitespace (runs of spaces/tabs, like awk); `runWith` takes an
explicit separator plus `BEGIN`/`END` blocks.

-}


{-| One input line: its raw text, its split fields, and its 1-based record number (awk's `NR`). -}
type alias Record =
    { line : String
    , fields : List String
    , nr : Int
    }


{-| A full awk program: the field separator (`" "` means awk's default whitespace splitting), a
`begin` block (lines printed before any input), the per-record `action`, and an `end` block. -}
type alias Program =
    { fs : String
    , begin : List String
    , action : Record -> List String
    , end : List String
    }


{-| Splits a line into fields the way awk does: on `fs`, or — when `fs` is `" "` — on runs of
whitespace, ignoring leading/trailing whitespace. -}
splitFields : String -> String -> List String
splitFields fs line =
    if fs == " " then
        String.words line

    else
        String.split fs line


{-| Builds the `Record` for `line` (the nth input line) under field separator `fs`. -}
toRecord : String -> Int -> String -> Record
toRecord fs n line =
    { line = line, fields = splitFields fs line, nr = n }


{-| The field at `n`: `field 0` is the whole line (`$0`), `field n` the nth field (1-based, `$n`),
and `""` when `n` is out of range — just like an unset awk field. -}
field : Int -> Record -> String
field n record =
    if n == 0 then
        record.line

    else
        nth (n - 1) record.fields |> Maybe.withDefault ""


{-| The number of fields in the record (awk's `NF`). -}
nf : Record -> Int
nf record =
    List.length record.fields


{-| The record's number (awk's `NR`, 1-based). -}
nr : Record -> Int
nr record =
    record.nr


{-| The record's fields (`$1 … $NF`). -}
fields : Record -> List String
fields record =
    record.fields


{-| Runs `action` over every line of the input (whitespace field separator), concatenating the lines
each action prints — `awk '{ … }' input`. -}
run : (Record -> List String) -> String -> String
run action input =
    runWith { fs = " ", begin = [], action = action, end = [] } input


{-| Runs a full program: `begin` lines, then each record's action output, then `end` lines, all
joined by newlines. `end` runs with no record in scope (awk's `END`), so it sees only what the action
accumulated via its own closure. -}
runWith : Program -> String -> String
runWith program input =
    let
        records =
            List.indexedMap (\i line -> toRecord program.fs (i + 1) line) (inputLines input)

        body =
            List.concatMap program.action records
    in
    String.join "\n" (program.begin ++ body ++ program.end)


{-| Splits the input into lines, dropping the trailing empty line a final newline produces (so
`"a\nb\n"` is two records, not three). -}
inputLines : String -> List String
inputLines input =
    let
        parts =
            String.split "\n" input
    in
    if String.endsWith "\n" input then
        List.take (List.length parts - 1) parts

    else
        parts


{-| `awk '{ print $n }'`: print the nth field of every line. -}
column : Int -> String -> String
column n input =
    run (\r -> [ field n r ]) input


{-| Print several columns of every line, space-joined — `awk '{ print $a, $b, … }'`. -}
columns : List Int -> String -> String
columns ns input =
    run (\r -> [ String.join " " (List.map (\n -> field n r) ns) ]) input


{-| `awk '/needle/'`: print only the lines containing `needle`. -}
matching : String -> String -> String
matching needle input =
    run
        (\r ->
            if String.contains needle r.line then
                [ r.line ]

            else
                []
        )
        input


{-| `awk '{ s += $n } END { print s }'`: the sum of the nth column as a Float. -}
sumColumn : Int -> String -> Float
sumColumn n input =
    inputLines input
        |> List.indexedMap (\i line -> toRecord " " (i + 1) line)
        |> List.filterMap (\r -> String.toFloat (field n r))
        |> List.sum




-- AWK STRING FUNCTIONS ----------------------------------------------------
-- awk's built-in string functions (1-based, like awk). These operate on plain strings, so they work
-- on `field n record` or any text.


{-| `length(s)` — the number of characters in `s`. -}
length : String -> Int
length =
    String.length


{-| `substr(s, m, n)` — the `n`-character substring of `s` starting at position `m` (1-based). -}
substr : Int -> Int -> String -> String
substr m n s =
    String.slice (m - 1) (m - 1 + n) s


{-| `substr(s, m)` — the substring of `s` from position `m` (1-based) to the end. -}
substrFrom : Int -> String -> String
substrFrom m s =
    String.dropLeft (m - 1) s


{-| `index(s, t)` — the 1-based position of the first occurrence of `t` in `s`, or `0` if absent. -}
index : String -> String -> Int
index s t =
    case String.indexes t s of
        i :: _ ->
            i + 1

        [] ->
            0


{-| `split(s, fs)` — splits `s` into fields on `fs` (whitespace when `fs` is `" "`). -}
split : String -> String -> List String
split fs s =
    splitFields fs s


{-| `toupper(s)` — `s` upper-cased. -}
toupper : String -> String
toupper =
    String.toUpper


{-| `tolower(s)` — `s` lower-cased. -}
tolower : String -> String
tolower =
    String.toLower


nth : Int -> List a -> Maybe a
nth i xs =
    List.head (List.drop i xs)

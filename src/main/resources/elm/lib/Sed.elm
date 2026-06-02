module Sed exposing
    ( substitute
    , substituteAll
    , deleteMatching
    , keepMatching
    , transliterate
    , lineRange
    , eachLine
    , filterLines
    )

{-| A tiny **sed** in Elm: the common stream-editor one-liners, applied line by line and backed by
the `Regex` module. The names map to sed commands:

  - `substitute re repl` — `sed 's/re/repl/'` (replace the first match on each line)
  - `substituteAll re repl` — `sed 's/re/repl/g'` (replace every match on each line)
  - `deleteMatching re` — `sed '/re/d'` (drop lines matching `re`)
  - `keepMatching re` — `sed -n '/re/p'` (keep only matching lines, like grep)
  - `transliterate from to` — `sed 'y/from/to/'`
  - `lineRange a b` — `sed -n 'a,bp'` (keep lines `a`..`b`, 1-based)

In a replacement, `&` stands for the matched text (sed's convention). Everything is `String -> String`,
so commands compose with `|>`:

    import Sed

    out : String -> String
    out input =
        input
            |> Sed.deleteMatching "^#"          -- drop comment lines
            |> Sed.substituteAll "\\s+" " "      -- squeeze runs of whitespace

-}

import Regex exposing (Regex)


{-| Applies `f` to each line (splitting and rejoining on `\n`, so the line structure is preserved). -}
eachLine : (String -> String) -> String -> String
eachLine f input =
    String.split "\n" input |> List.map f |> String.join "\n"


{-| Keeps only the lines for which `pred` is true. -}
filterLines : (String -> Bool) -> String -> String
filterLines pred input =
    String.split "\n" input |> List.filter pred |> String.join "\n"


{-| Compiles a regex, falling back to a never-matching regex on a syntax error. -}
regex : String -> Regex
regex re =
    Maybe.withDefault Regex.never (Regex.fromString re)


{-| `sed 's/re/repl/'`: replace the first match of `re` on every line (`&` is the matched text). -}
substitute : String -> String -> String -> String
substitute re repl input =
    let
        r =
            regex re
    in
    eachLine
        (Regex.replace r
            (\m ->
                if m.number == 1 then
                    String.replace "&" m.match repl

                else
                    m.match
            )
        )
        input


{-| `sed 's/re/repl/g'`: replace every match of `re` on every line (`&` is the matched text). -}
substituteAll : String -> String -> String -> String
substituteAll re repl input =
    eachLine (Regex.replace (regex re) (\m -> String.replace "&" m.match repl)) input


{-| `sed '/re/d'`: drop the lines that match `re`. -}
deleteMatching : String -> String -> String
deleteMatching re input =
    let
        r =
            regex re
    in
    filterLines (\line -> not (Regex.contains r line)) input


{-| `sed -n '/re/p'`: keep only the lines that match `re` (grep). -}
keepMatching : String -> String -> String
keepMatching re input =
    filterLines (Regex.contains (regex re)) input


{-| `sed -n 'a,bp'`: keep the lines numbered `a`..`b` (1-based, inclusive). -}
lineRange : Int -> Int -> String -> String
lineRange a b input =
    String.split "\n" input
        |> List.indexedMap (\i line -> ( i + 1, line ))
        |> List.filter (\( n, _ ) -> n >= a && n <= b)
        |> List.map Tuple.second
        |> String.join "\n"


{-| `sed 'y/from/to/'`: replace each character of the input that appears in `from` with the matching
character in `to` (a character is dropped when `to` is shorter). -}
transliterate : String -> String -> String -> String
transliterate from to input =
    let
        fromList =
            String.toList from

        toList =
            String.toList to
    in
    String.fromList
        (List.filterMap
            (\c ->
                case indexOfChar c fromList 0 of
                    Just i ->
                        nthChar i toList

                    Nothing ->
                        Just c
            )
            (String.toList input)
        )


indexOfChar : Char -> List Char -> Int -> Maybe Int
indexOfChar c chars i =
    case chars of
        x :: rest ->
            if x == c then
                Just i

            else
                indexOfChar c rest (i + 1)

        [] ->
            Nothing


nthChar : Int -> List Char -> Maybe Char
nthChar i chars =
    List.head (List.drop i chars)

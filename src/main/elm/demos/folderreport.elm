module Main exposing (main)

{-| A folder report `elm script`: recursively walks a directory (default `.`) and prints a summary —
file and directory counts, total size, the largest files and a breakdown by extension. Demonstrates
the structured Bash commands: `find` returns `Entry` records, so the report is computed with ordinary
list/record code rather than by parsing `ls` text.

    elm script folderreport src

-}

import Bash exposing (..)


main : Io
main =
    getArgs
        (\args ->
            let
                dir =
                    case args of
                        first :: _ ->
                            first

                        [] ->
                            "."
            in
            find dir
                (\result ->
                    case result of
                        Ok entries ->
                            report dir entries

                        Err message ->
                            print ("cannot read " ++ dir ++ ": " ++ message) (exit 1)
                )
        )


report : String -> List Entry -> Io
report dir entries =
    let
        files =
            List.filter (\e -> not e.isDir) entries

        dirs =
            List.filter (\e -> e.isDir) entries

        totalSize =
            List.foldl (\e acc -> acc + e.size) 0 files

        largest =
            files
                |> List.sortBy (\e -> negate e.size)
                |> List.take 5

        byExt =
            extensionBreakdown files
    in
    [ "Folder report for " ++ dir
    , separator
    , "Files:        " ++ String.fromInt (List.length files)
    , "Directories:  " ++ String.fromInt (List.length dirs)
    , "Total size:   " ++ humanSize totalSize
    , ""
    , "Largest files:"
    ]
        ++ List.map largestLine largest
        ++ ("" :: "By extension:" :: List.map extLine byExt)
        |> printAll


separator : String
separator =
    "----------------------------------------"


{-| Prints every line in turn, then finishes. Lets the report be built as one flat list of lines and
piped here, instead of deeply nesting `print` calls. -}
printAll : List String -> Io
printAll lines =
    List.foldr print done lines


largestLine : Entry -> String
largestLine e =
    "  " ++ e.name ++ " (" ++ humanSize e.size ++ ")"


extLine : ( String, Int ) -> String
extLine pair =
    "  " ++ Tuple.first pair ++ ": " ++ String.fromInt (Tuple.second pair)


{-| A human-readable byte size (B / KB / MB). -}
humanSize : Int -> String
humanSize n =
    if n < 1024 then
        String.fromInt n ++ " B"

    else if n < 1048576 then
        String.fromInt (n // 1024) ++ " KB"

    else
        String.fromInt (n // 1048576) ++ " MB"


{-| Counts files per extension, most common first. -}
extensionBreakdown : List Entry -> List ( String, Int )
extensionBreakdown files =
    List.foldl (\e acc -> bump (extensionOf e.name) acc) [] files
        |> List.sortBy (\pair -> negate (Tuple.second pair))


{-| Increments the count for `key` in an association list (appending it if new). -}
bump : String -> List ( String, Int ) -> List ( String, Int )
bump key assoc =
    case assoc of
        [] ->
            [ ( key, 1 ) ]

        pair :: rest ->
            if Tuple.first pair == key then
                ( key, Tuple.second pair + 1 ) :: rest

            else
                pair :: bump key rest


{-| The file's extension (with leading dot), or `(none)` when it has none. -}
extensionOf : String -> String
extensionOf name =
    case List.reverse (String.split "." name) of
        ext :: _ :: _ ->
            "." ++ ext

        _ ->
            "(none)"

module Main exposing (main)

{-| List project files longer than a line threshold (default 1000), largest first — an `elm script`.

Recursively walks a directory (default `.`), counts each file's lines with `wc`, and prints those
over the threshold sorted by line count (descending). Build output, dependencies and VCS metadata
are skipped, so it reports on source files rather than generated artifacts.

    elm script tools/big-files.elm             # files over 1000 lines, scanning .
    elm script tools/big-files.elm 500         # files over 500 lines, scanning .
    elm script tools/big-files.elm 500 src     # files over 500 lines, scanning src

-}

import Bash exposing (..)


main : Io
main =
    getArgs
        (\args ->
            let
                parsed =
                    parseArgs args

                threshold =
                    Tuple.first parsed

                dir =
                    Tuple.second parsed
            in
            find dir
                (\result ->
                    case result of
                        Ok entries ->
                            let
                                files =
                                    entries
                                        |> List.filter (\e -> not e.isDir)
                                        |> List.filter (\e -> not (ignored e.path))
                            in
                            countAll files [] (\counted -> report threshold counted)

                        Err message ->
                            print ("cannot read " ++ dir ++ ": " ++ message) (exit 1)
                )
        )


{-| `[threshold] [dir]` — both optional. A leading all-digit argument is taken as the threshold;
otherwise a lone argument is the directory. -}
parseArgs : List String -> ( Int, String )
parseArgs args =
    case args of
        a :: b :: _ ->
            ( Maybe.withDefault 1000 (String.toInt a), b )

        a :: [] ->
            case String.toInt a of
                Just n ->
                    ( n, "." )

                Nothing ->
                    ( 1000, a )

        [] ->
            ( 1000, "." )


{-| Skip build output, dependencies and VCS metadata (matching both path-separator styles). -}
ignored : String -> Bool
ignored path =
    List.any (\seg -> String.contains seg path)
        [ "/target/"
        , "\\target\\"
        , "/.git/"
        , "\\.git\\"
        , "/node_modules/"
        , "\\node_modules\\"
        ]


{-| Count every file's lines with `wc`, threading the continuation through the list so the effects
run one after another (files that fail to read are dropped). -}
countAll : List Entry -> List ( Int, String ) -> (List ( Int, String ) -> Io) -> Io
countAll files acc k =
    case files of
        [] ->
            k (List.reverse acc)

        e :: rest ->
            wc e.path
                (\result ->
                    case result of
                        Ok counts ->
                            countAll rest (( counts.lines, e.path ) :: acc) k

                        Err _ ->
                            countAll rest acc k
                )


report : Int -> List ( Int, String ) -> Io
report threshold counted =
    counted
        |> List.filter (\pair -> Tuple.first pair > threshold)
        |> List.sortBy (\pair -> negate (Tuple.first pair))
        |> List.map line
        |> printAll


line : ( Int, String ) -> String
line pair =
    String.padLeft 8 ' ' (String.fromInt (Tuple.first pair)) ++ "  " ++ Tuple.second pair


{-| Prints every line in turn, then finishes (one flat list piped here instead of nested prints). -}
printAll : List String -> Io
printAll lines =
    List.foldr print done lines

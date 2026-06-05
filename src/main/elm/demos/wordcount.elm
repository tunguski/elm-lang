module Main exposing (main)

{-| A `wc`-style command-line script written in Elm, run by `elm script wordcount.elm FILE...`.
It reads each file argument, counts its lines/words/characters, prints a line per file, then a
total — a non-trivial replacement for a small bash/awk pipeline. Demonstrates the Posix I/O API:
process arguments, reading files (with error handling), and printing, all in continuation-passing
style.
-}

import Posix exposing (..)


main : Io
main =
    getArgs
        (\args ->
            case args of
                [] ->
                    print "usage: wordcount FILE..." (exit 2)

                files ->
                    process files 0 0 0
        )


process : List String -> Int -> Int -> Int -> Io
process files totalLines totalWords totalChars =
    case files of
        [] ->
            print
                ("total: "
                    ++ String.fromInt totalLines
                    ++ " lines, "
                    ++ String.fromInt totalWords
                    ++ " words, "
                    ++ String.fromInt totalChars
                    ++ " chars"
                )
                done

        path :: rest ->
            readFile path
                (\result ->
                    case result of
                        Ok content ->
                            let
                                ls =
                                    List.length (String.lines content)

                                ws =
                                    List.length (String.words content)

                                cs =
                                    String.length content
                            in
                            print
                                (path
                                    ++ ": "
                                    ++ String.fromInt ls
                                    ++ " "
                                    ++ String.fromInt ws
                                    ++ " "
                                    ++ String.fromInt cs
                                )
                                (process rest (totalLines + ls) (totalWords + ws) (totalChars + cs))

                        Err e ->
                            print ("error reading " ++ path ++ ": " ++ e) (exit 1)
                )

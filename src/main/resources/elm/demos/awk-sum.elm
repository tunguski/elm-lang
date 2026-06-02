module Main exposing (main)

{-| `elm script awk-sum.elm <column> <file>` — sum a numeric column of a file, the way
`awk '{ s += $N } END { print s }'` does, using the bundled `Awk` library.

    $ elm script awk-sum.elm 2 sales.txt
-}

import Awk
import Posix exposing (..)


main : Io
main =
    getArgs
        (\args ->
            case args of
                col :: path :: _ ->
                    readFile path
                        (\result ->
                            case result of
                                Ok text ->
                                    print (String.fromFloat (Awk.sumColumn (toInt col) text)) done

                                Err message ->
                                    print ("error: " ++ message) (exit 1)
                        )

                _ ->
                    print "usage: awk-sum <column> <file>" (exit 1)
        )


toInt : String -> Int
toInt s =
    Maybe.withDefault 1 (String.toInt s)

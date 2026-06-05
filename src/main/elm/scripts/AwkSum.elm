module AwkSum exposing (main)

{-| `elm script AwkSum.elm <column> <file>` — emit the `awk` command that sums a numeric column of
a file, built with the `Awk` library. The point is to *compose* the awk program (so you can drop it
into a shell script), not to run awk here:

    $ elm script AwkSum.elm 2 sales.csv
    awk '{ s += $2 } END { print s }' sales.csv
-}

import Awk
import Posix exposing (..)


main : Io
main =
    getArgs
        (\args ->
            case args of
                col :: file :: _ ->
                    print ("awk " ++ Awk.oneLiner (sumColumn (toInt col)) ++ " " ++ file) done

                _ ->
                    print "usage: AwkSum <column> <file>" (exit 1)
        )


{-| The awk program `{ s += $col } END { print s }`. -}
sumColumn : Int -> List Awk.Rule
sumColumn col =
    [ Awk.eachLine [ Awk.addTo "s" (Awk.field col) ]
    , Awk.end [ Awk.print [ Awk.var "s" ] ]
    ]


toInt : String -> Int
toInt s =
    Maybe.withDefault 1 (String.toInt s)

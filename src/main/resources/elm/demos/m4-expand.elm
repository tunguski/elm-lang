module Main exposing (main)

{-| `elm script m4-expand.elm <file>` — expand the m4 macros in a file (a tiny `m4`), using the
bundled `M4` library. The file can `define(...)` macros and use them, with `$1`… arguments, quoting
and the `incr`/`eval`/`ifelse`/… builtins.

    $ elm script m4-expand.elm config.m4
-}

import M4
import Posix exposing (..)


main : Io
main =
    getArgs
        (\args ->
            case args of
                path :: _ ->
                    readFile path
                        (\result ->
                            case result of
                                Ok text ->
                                    print (M4.expand text) done

                                Err message ->
                                    print ("error: " ++ message) (exit 1)
                        )

                _ ->
                    print "usage: m4-expand <file>" (exit 1)
        )

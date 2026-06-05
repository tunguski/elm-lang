module Main exposing (main)

{-| `elm script m4-expand.elm <name>` — emit an m4 macro program (a `greet` macro and a call to it),
built with the `M4` library. The point is to *compose* the m4 source so you can save it as a `.m4`
file or pipe it to `m4`, not to expand it here:

    $ elm script m4-expand.elm world
    define(`greet', `Hello $1!')dnl
    greet(`world')

    $ elm script m4-expand.elm world | m4
    Hello world!
-}

import M4
import Posix exposing (..)


main : Io
main =
    getArgs
        (\args ->
            case args of
                name :: _ ->
                    print (M4.program (greetProgram name)) done

                _ ->
                    print "usage: m4-expand <name>" (exit 1)
        )


{-| An m4 document that defines `greet` and calls it with `name`. -}
greetProgram : String -> List String
greetProgram name =
    [ M4.define "greet" ("Hello " ++ M4.arg 1 ++ "!") ++ M4.dnl
    , M4.call "greet" [ M4.quote name ]
    ]

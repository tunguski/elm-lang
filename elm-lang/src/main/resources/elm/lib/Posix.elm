module Posix exposing
    ( Io
    , print
    , readLine
    , readFile
    , writeFile
    , getArgs
    , exit
    , done
    )

{-| A tiny POSIX-style I/O API for writing Elm programs that run as command-line scripts
(inspired by elm-posix). A script's `main : Io` describes a sequence of effects as data; the
`elm script` runner walks that description and performs the real effects (stdin/stdout, files,
process arguments, exit code). Effects that produce a value take a continuation, so scripts are
written in continuation-passing style:

    import Posix exposing (..)

    main : Io
    main =
        getArgs (\args -> print (String.join " " args) done)

-}


{-| A description of a sequence of I/O effects. Opaque: build it with the helpers below. -}
type Io
    = Print String Io
    | ReadLine (String -> Io)
    | ReadFile String (Result String String -> Io)
    | WriteFile String String Io
    | GetArgs (List String -> Io)
    | Exit Int
    | Done


{-| Print a line to stdout, then continue. -}
print : String -> Io -> Io
print =
    Print


{-| Read a line from stdin (empty string at end of input), then continue with it. -}
readLine : (String -> Io) -> Io
readLine =
    ReadLine


{-| Read a whole file; the continuation gets `Ok contents` or `Err message`. -}
readFile : String -> (Result String String -> Io) -> Io
readFile =
    ReadFile


{-| Write a string to a file (overwriting), then continue. -}
writeFile : String -> String -> Io -> Io
writeFile =
    WriteFile


{-| Get the process arguments (everything after the script path), then continue. -}
getArgs : (List String -> Io) -> Io
getArgs =
    GetArgs


{-| Exit immediately with the given status code. -}
exit : Int -> Io
exit =
    Exit


{-| Finish successfully (exit code 0). -}
done : Io
done =
    Done

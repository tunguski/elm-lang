module Posix exposing
    ( Io
    , print
    , readLine
    , readFile
    , writeFile
    , getArgs
    , getEnv
    , listDir
    , exit
    , done
    , Entry
    , Match
    , Counts
    , ls
    , find
    , grep
    , wc
    , pwd
    , mkdir
    , rm
    , cp
    , mv
    , env
    , which
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


{-| File metadata, as returned by `ls`, `find` and `stat` — structured rather than a text line. -}
type alias Entry =
    { name : String
    , path : String
    , isDir : Bool
    , size : Int
    , modified : Int
    }


{-| A single `grep` hit: the 1-based line number and the matching line's text. -}
type alias Match =
    { lineNumber : Int
    , line : String
    }


{-| Line / word / character counts, as returned by `wc`. -}
type alias Counts =
    { lines : Int
    , words : Int
    , chars : Int
    }


{-| A description of a sequence of I/O effects. Opaque: build it with the helpers below. -}
type Io
    = Print String Io
    | ReadLine (String -> Io)
    | ReadFile String (Result String String -> Io)
    | WriteFile String String Io
    | GetArgs (List String -> Io)
    | GetEnv String (Maybe String -> Io)
    | ListDir String (Result String (List String) -> Io)
    | Exit Int
    | Done
    | Ls String (Result String (List Entry) -> Io)
    | Walk String (Result String (List Entry) -> Io)
    | Grep String String (Result String (List Match) -> Io)
    | Wc String (Result String Counts -> Io)
    | Pwd (String -> Io)
    | Mkdir String (Result String String -> Io)
    | Rm String (Result String String -> Io)
    | Cp String String (Result String String -> Io)
    | Mv String String (Result String String -> Io)
    | EnvAll (List ( String, String ) -> Io)
    | Which String (Maybe String -> Io)


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


{-| Read an environment variable; the continuation gets `Just value` or `Nothing`. -}
getEnv : String -> (Maybe String -> Io) -> Io
getEnv =
    GetEnv


{-| List a directory's entries (names only); the continuation gets `Ok names` or `Err message`. -}
listDir : String -> (Result String (List String) -> Io) -> Io
listDir =
    ListDir


{-| Exit immediately with the given status code. -}
exit : Int -> Io
exit =
    Exit


{-| Finish successfully (exit code 0). -}
done : Io
done =
    Done


{-| List a directory's immediate entries with metadata (like `ls -l`), sorted by name. -}
ls : String -> (Result String (List Entry) -> Io) -> Io
ls =
    Ls


{-| Recursively walk a directory, returning every file and sub-directory with metadata (like `find`). -}
find : String -> (Result String (List Entry) -> Io) -> Io
find =
    Walk


{-| Search a file for lines containing a substring (like `grep`); the continuation gets the matches. -}
grep : String -> String -> (Result String (List Match) -> Io) -> Io
grep =
    Grep


{-| Count the lines, words and characters of a file (like `wc`). -}
wc : String -> (Result String Counts -> Io) -> Io
wc =
    Wc


{-| The current working directory (like `pwd`). -}
pwd : (String -> Io) -> Io
pwd =
    Pwd


{-| Create a directory and any missing parents (like `mkdir -p`); `Ok` carries the path. -}
mkdir : String -> (Result String String -> Io) -> Io
mkdir =
    Mkdir


{-| Delete a file or a (recursively emptied) directory (like `rm -r`); `Ok` carries the path. -}
rm : String -> (Result String String -> Io) -> Io
rm =
    Rm


{-| Copy a file from source to destination (like `cp`); `Ok` carries the destination path. -}
cp : String -> String -> (Result String String -> Io) -> Io
cp =
    Cp


{-| Move/rename a file (like `mv`); `Ok` carries the destination path. -}
mv : String -> String -> (Result String String -> Io) -> Io
mv =
    Mv


{-| All environment variables as `(name, value)` pairs (like `env`). -}
env : (List ( String, String ) -> Io) -> Io
env =
    EnvAll


{-| Locate an executable on `PATH` (like `which`); the continuation gets `Just path` or `Nothing`. -}
which : String -> (Maybe String -> Io) -> Io
which =
    Which

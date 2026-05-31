module Bash exposing
    ( Io
    , Entry
    , Match
    , Counts
    , ls
    , find
    , grep
    , wc
    , cat
    , pwd
    , mkdir
    , rm
    , cp
    , mv
    , env
    , which
    , print
    , writeFile
    , getArgs
    , getEnv
    , exit
    , done
    )

{-| Common shell commands for `elm script`, returning **structured** results instead of text you have
to re-parse: `ls`/`find` give `Entry` records (name, path, isDir, size, modified), `grep` gives
`Match` records (line number + text) and `wc` gives a `Counts` record. Everything produces a
`Posix.Io` so these compose with `print`, `writeFile` and friends in one continuation-passing script:

    import Bash exposing (..)

    main : Io
    main =
        ls "." (\result ->
            case result of
                Ok entries ->
                    print (String.fromInt (List.length entries) ++ " entries") done

                Err message ->
                    exit 1
        )

This is a thin, shell-flavoured facade over the `Posix` module.

-}

import Posix


{-| A sequence of script effects (alias of `Posix.Io`). -}
type alias Io =
    Posix.Io


{-| File metadata returned by `ls`/`find`. -}
type alias Entry =
    Posix.Entry


{-| A `grep` hit: 1-based line number and the matching line. -}
type alias Match =
    Posix.Match


{-| Line / word / character counts from `wc`. -}
type alias Counts =
    Posix.Counts


{-| List a directory's immediate entries with metadata (like `ls`), sorted by name. -}
ls : String -> (Result String (List Entry) -> Io) -> Io
ls =
    Posix.ls


{-| Recursively walk a directory, returning every file and sub-directory (like `find`). -}
find : String -> (Result String (List Entry) -> Io) -> Io
find =
    Posix.find


{-| Search a file for lines containing a substring (like `grep`). -}
grep : String -> String -> (Result String (List Match) -> Io) -> Io
grep =
    Posix.grep


{-| Count the lines, words and characters of a file (like `wc`). -}
wc : String -> (Result String Counts -> Io) -> Io
wc =
    Posix.wc


{-| Read a whole file (like `cat`); the continuation gets `Ok contents` or `Err message`. -}
cat : String -> (Result String String -> Io) -> Io
cat =
    Posix.readFile


{-| The current working directory (like `pwd`). -}
pwd : (String -> Io) -> Io
pwd =
    Posix.pwd


{-| Create a directory and any missing parents (like `mkdir -p`). -}
mkdir : String -> (Result String String -> Io) -> Io
mkdir =
    Posix.mkdir


{-| Delete a file or a directory tree (like `rm -r`). -}
rm : String -> (Result String String -> Io) -> Io
rm =
    Posix.rm


{-| Copy a file (like `cp`). -}
cp : String -> String -> (Result String String -> Io) -> Io
cp =
    Posix.cp


{-| Move/rename a file (like `mv`). -}
mv : String -> String -> (Result String String -> Io) -> Io
mv =
    Posix.mv


{-| All environment variables as `(name, value)` pairs (like `env`). -}
env : (List ( String, String ) -> Io) -> Io
env =
    Posix.env


{-| Locate an executable on `PATH` (like `which`). -}
which : String -> (Maybe String -> Io) -> Io
which =
    Posix.which


{-| Print a line to stdout, then continue. -}
print : String -> Io -> Io
print =
    Posix.print


{-| Write a string to a file (overwriting), then continue. -}
writeFile : String -> String -> Io -> Io
writeFile =
    Posix.writeFile


{-| The process arguments (everything after the script path). -}
getArgs : (List String -> Io) -> Io
getArgs =
    Posix.getArgs


{-| Read an environment variable; the continuation gets `Just value` or `Nothing`. -}
getEnv : String -> (Maybe String -> Io) -> Io
getEnv =
    Posix.getEnv


{-| Exit immediately with the given status code. -}
exit : Int -> Io
exit =
    Posix.exit


{-| Finish successfully (exit code 0). -}
done : Io
done =
    Posix.done

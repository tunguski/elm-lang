# Shell scripting in Elm

`elm script <file.elm> [args…]` runs an Elm file as a command-line script on the JIT interpreter —
a from-scratch take on [elm-posix](https://github.com/albertdahlin/elm-posix). A script exposes a
`main : Posix.Io` value that *describes* a sequence of effects as data; the runner walks that
description and performs the real I/O, then exits with the script's status code.

## The Posix module

Build the `Io` description with these helpers (effects that produce a value take a continuation, so
scripts read in continuation-passing style):

| Helper | Type | Effect |
|---|---|---|
| `print` | `String -> Io -> Io` | Write a line to stdout, then continue. |
| `readLine` | `(String -> Io) -> Io` | Read a line from stdin (empty at EOF). |
| `readFile` | `String -> (Result String String -> Io) -> Io` | Read a whole file. |
| `writeFile` | `String -> String -> Io -> Io` | Write a string to a file. |
| `getArgs` | `(List String -> Io) -> Io` | The process arguments. |
| `getEnv` | `String -> (Maybe String -> Io) -> Io` | An environment variable. |
| `listDir` | `String -> (Result String (List String) -> Io) -> Io` | A directory's entries. |
| `exit` | `Int -> Io` | Exit with a status code. |
| `done` | `Io` | Finish successfully (exit 0). |

## Hello, args

```elm
module Main exposing (main)

import Posix exposing (..)

main : Io
main =
    getArgs (\args -> print ("hello " ++ String.join " " args) done)
```

```sh
elm script hello.elm world      # prints: hello world
```

## A worked example: word count

The bundled [`wordcount.elm`](../src/main/resources/elm/demos/wordcount.elm) is a `wc`-style
counter — it reads each file argument, counts lines/words/characters, prints a line per file and a
total, and handles missing files with a non-zero exit:

```sh
elm script wordcount README.md docs/scripting.md
```

The name `wordcount` resolves to the bundled demo; you can also pass any path to your own script.

## How it runs

The handler is pure data, so a script is trivial to test: build the `Io` value and walk it with a
fake stdin/stdout. The runner (`ScriptRunner`) performs `print`/`readLine`/`readFile`/`writeFile`
against real streams and the filesystem, applies each continuation to the result, and returns the
exit code from `exit`/`done`.

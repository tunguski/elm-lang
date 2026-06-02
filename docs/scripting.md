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

## Structured shell commands (the Bash module)

The bundled [`Bash`](../src/main/resources/elm/lib/Bash.elm) module adds common shell commands that
return **structured Elm values** instead of text you have to re-parse — `ls`/`find` give `Entry`
records, `grep` gives `Match` records, `wc` gives a `Counts` record, and `exec` gives a `Proc`:

| Command | Result | Like |
|---|---|---|
| `ls` / `find` | `List Entry` `{ name, path, isDir, size, modified }` | `ls` / `find` |
| `grep` | `List Match` `{ lineNumber, line }` | `grep` |
| `wc` | `Counts` `{ lines, words, chars }` | `wc` |
| `head` / `tail` / `sort` / `uniq` | `List String` | the same |
| `stat` | `Entry` | `stat` |
| `du` | `Int` (bytes) | `du -s` |
| `touch` / `mkdir` / `rm` / `cp` / `mv` | `String` (the path) | the same |
| `pwd` / `which` / `env` | path / `Maybe path` / `List (String, String)` | the same |
| `exec` | `Proc` `{ exitCode, stdout, stderr }` | run a process |

Because the results are typed, you process them with ordinary list/record code. For example, list a
directory and total the size of its files:

```elm
module Main exposing (main)

import Bash exposing (..)

main : Io
main =
    ls "." (\result ->
        case result of
            Ok entries ->
                let
                    files = List.filter (\e -> not e.isDir) entries
                    total = List.foldl (\e acc -> acc + e.size) 0 files
                in
                print (String.fromInt (List.length files) ++ " files, " ++ String.fromInt total ++ " bytes") done

            Err message ->
                print ("error: " ++ message) (exit 1)
    )
```

The bundled [`folderreport.elm`](../src/main/resources/elm/demos/folderreport.elm) goes further —
it `find`s a directory recursively and prints a report from the structured entries:

```sh
elm script folderreport src/main/resources/elm/lib
```

```text
Folder report for src/main/resources/elm/lib
----------------------------------------
Files:        7
Directories:  1
Total size:   28 KB

Largest files:
  Posix.elm (6 KB)
  Bash.elm (5 KB)
  Server.elm (3 KB)
  Site.elm (3 KB)
  Test.elm (2 KB)

By extension:
  .elm: 7
```

And `exec` runs an external process, handing back its exit code and captured output:

```elm
exec "git" [ "rev-parse", "--short", "HEAD" ] (\result ->
    case result of
        Ok proc -> print ("HEAD is " ++ String.trim proc.stdout) done
        Err message -> print message (exit 1)
)
```

```text
HEAD is 7a6146c
```

## Text-processing libraries (Awk, M4, Csv)

These bundled libraries help Elm scripts build text for the classic Unix tools. `Awk` and `M4`
*compose the tool's own source* (an awk program / m4 macros) as plain `String` values you embed in a
shell script or pass on a command line — they don't reimplement awk/m4. `Csv` parses and encodes CSV
data. All are plain functions — no `Io` — so they compose inside any handler and are easy to test.

### Awk

[`Awk`](../src/main/resources/elm/lib/Awk.elm) **builds awk program text** to embed in a generated
shell script or pass to `awk` — it doesn't run awk. An awk program is a list of `pattern { action }`
rules: `begin`/`end`/`on cond`/`matchLine re`/`eachLine`. `program` renders them, `oneLiner`
single-quotes the result for a command line, and `invocation` builds the `awk` argument list. The
expression helpers produce awk text: `field 1` is `$1`, `nf`/`nr`, `print`/`printf`/`assign`/`addTo`,
`call` for functions (`substr`, `toupper`, `gsub`, …) and `matches` for `~ /re/`.

```elm
import Awk exposing (..)
import Bash

-- awk 'BEGIN { FS="," } { s += $2 } END { print s }' data.csv
sumCommand : List String
sumCommand =
    invocation
        [ begin [ assign "FS" (str ",") ]
        , eachLine [ addTo "s" (field 2) ]
        , end [ print [ var "s" ] ]
        ]
        [ "data.csv" ]

run : Bash.Io
run =
    Bash.exec "awk" sumCommand (\_ -> Bash.done)
```

The bundled `awk-sum.elm` demo prints the awk command to sum a column:
`elm script awk-sum.elm 2 sales.csv`.

### M4

[`M4`](../src/main/resources/elm/lib/M4.elm) **builds m4 macro source** to save as a `.m4` file or
pipe to `m4` — it doesn't run m4. `define`/`undefine` write quoted definitions, `call` writes an
invocation, and `program` joins statements into a document. Inside a body, `arg 1` is `$1`, `args` is
`$*`, `argCount` is `$#` and `macroName` is `$0`; `ifelse`/`ifdef`/`eval`/`include` write those
builtins, `quote` adds `` `…' `` quoting and `dnl` swallows a trailing newline.

```elm
import M4

-- define(`greet', `Hello $1!')dnl
-- greet(`world')
out : String
out =
    M4.program
        [ M4.define "greet" ("Hello " ++ M4.arg 1 ++ "!") ++ M4.dnl
        , M4.call "greet" [ M4.quote "world" ]
        ]
```

The bundled `m4-expand.elm` demo emits such a program: `elm script m4-expand.elm world` (pipe it to
`m4` to get `Hello world!`).

### Csv

[`Csv`](../src/main/resources/elm/lib/Csv.elm) parses and encodes RFC-4180 CSV: `parse` returns rows
of fields (honouring quoted fields with embedded commas/newlines), `encode` is the inverse, and
`parseWithHeader` pairs each row with the header columns (records, looked up with `get`).

```elm
import Csv

-- [["a","b,c"],["1","2"]]
rows : List (List String)
rows =
    Csv.parse "a,\"b,c\"\n1,2"
```

`csv-report.elm` renders a CSV as an HTML table page (`Csv` + `Site`):
`elm script csv-report.elm people.csv > report.html`.

## How it runs

The handler is pure data, so a script is trivial to test: build the `Io` value and walk it with a
fake stdin/stdout. The runner (`ScriptRunner`) performs `print`/`readLine`/`readFile`/`writeFile`
against real streams and the filesystem, applies each continuation to the result, and returns the
exit code from `exit`/`done`.

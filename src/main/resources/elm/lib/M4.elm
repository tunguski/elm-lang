module M4 exposing
    ( program
    , quote
    , define
    , undefine
    , call
    , arg
    , args
    , argCount
    , macroName
    , ifelse
    , ifdef
    , include
    , eval
    , dnl
    , comment
    )

{-| Helpers that **build m4 macro source** — text you store in a `.m4` file, embed in a shell script,
or pipe to `m4`. This library does *not* run m4: it composes the macro definitions and calls as plain
`String` values, so you assemble them with ordinary Elm and let real `m4` do the expansion.

A definition quotes its name and body (m4's `` `…' `` quotes) so they aren't expanded at define
time; inside a body, `arg 1` is `$1`, `args` is `$*`, `argCount` is `$#` and `macroName` is `$0`.
`call` writes a macro invocation, and `program` joins statements into a document.

    import M4

    -- define(`greet', `Hello $1!')dnl
    -- greet(`world')
    out : String
    out =
        M4.program
            [ M4.define "greet" ("Hello " ++ M4.arg 1 ++ "!") ++ M4.dnl
            , M4.call "greet" [ M4.quote "world" ]
            ]

-}


{-| Joins statements into one m4 document, one per line. End `define`/`undefine` lines with {@link dnl}
when you don't want their (empty) expansion to leave a blank line. -}
program : List String -> String
program statements =
    String.join "\n" statements


{-| m4 quoting: wraps `s` in `` ` `` … `'` so it is emitted literally and not expanded. -}
quote : String -> String
quote s =
    "`" ++ s ++ "'"


{-| `` define(`name', `body') `` — the body is quoted so its `$n` placeholders and any nested macro
calls survive until the macro is actually invoked. -}
define : String -> String -> String
define name body =
    call "define" [ quote name, quote body ]


{-| `` undefine(`name') `` — removes a macro definition. -}
undefine : String -> String
undefine name =
    call "undefine" [ quote name ]


{-| A macro invocation: `name(a, b, …)`, or just `name` when there are no arguments. Quote arguments
with {@link quote} when they must not be expanded. -}
call : String -> List String -> String
call name arguments =
    case arguments of
        [] ->
            name

        _ ->
            name ++ "(" ++ String.join ", " arguments ++ ")"


{-| The nth positional parameter inside a macro body: `arg 1` is `$1`. -}
arg : Int -> String
arg n =
    "$" ++ String.fromInt n


{-| `$*` — the call's arguments, comma-joined (use inside a macro body). -}
args : String
args =
    "$*"


{-| `$#` — the number of arguments (use inside a macro body). -}
argCount : String
argCount =
    "$#"


{-| `$0` — the macro's own name (use inside a macro body). -}
macroName : String
macroName =
    "$0"


{-| `ifelse(a, b, t, e)` — emits `t` when `a` equals `b`, otherwise `e`. -}
ifelse : String -> String -> String -> String -> String
ifelse a b t e =
    call "ifelse" [ a, b, t, e ]


{-| `` ifdef(`name', yes, no) `` — `yes` when the macro is defined, else `no`. -}
ifdef : String -> String -> String -> String
ifdef name yes no =
    call "ifdef" [ quote name, yes, no ]


{-| `` include(`file') `` — splice in another m4 file. -}
include : String -> String
include file =
    call "include" [ quote file ]


{-| `` eval(`expr') `` — m4's integer arithmetic (the expression is quoted so its operators and any
commas are kept literal). -}
eval : String -> String
eval expr =
    call "eval" [ quote expr ]


{-| `dnl` — delete to the end of the line; append it to a definition to swallow the trailing newline. -}
dnl : String
dnl =
    "dnl"


{-| An m4 comment: `# …` (passed through to the output by default). -}
comment : String -> String
comment s =
    "# " ++ s

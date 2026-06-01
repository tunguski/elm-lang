module Main exposing (site)

{-| A static site for the elm-lang project, described entirely in Elm with the bundled `Site`
library. Generate it with:

    elm gen-site examples/site/ElmLang.elm out --api src/main/resources/elm/lib --api examples/rts

Each `Page` is rendered to HTML by `Site.render`; `--api` additionally writes grouped API docs.
-}

import Site exposing (..)


site : List Page
site =
    [ home, features, cli, examplesPage ]


nav : Block
nav =
    links
        [ ( "index.html", "Home" )
        , ( "features.html", "Features" )
        , ( "cli.html", "CLI" )
        , ( "examples.html", "Examples" )
        , ( "api/index.html", "API docs" )
        ]


home : Page
home =
    page "index.html"
        "elm-lang — a from-scratch Elm in Java"
        [ nav
        , h1 "elm-lang"
        , text "A from-scratch implementation of Elm in Java/GraalVM: a Truffle JIT interpreter, a bytecode VM, and compilers to JavaScript and WebAssembly (linear-memory and WasmGC)."
        , text "It also ships an in-browser editor, a language server, a test runner, a REPL, a reactor dev server, a package manager, a POSIX-style scripting layer and this static-site generator — all written here."
        , h2 "Try it"
        , bullets
            [ "Compile a module to a live HTML page or a JS bundle"
            , "Run Elm as a command-line script or an HTTP server"
            , "Bundle a script/server into a standalone executable"
            , "Play the in-browser RTS game — no server needed"
            ]
        ]


features : Page
features =
    page "features.html"
        "Features — elm-lang"
        [ nav
        , h1 "Features"
        , h2 "Five backends"
        , bullets
            [ "Truffle JIT tree interpreter"
            , "Bytecode virtual machine"
            , "JavaScript compiler (tree-shaking + minification)"
            , "Linear-memory WebAssembly"
            , "WasmGC (host-GC structs, closures and currying)"
            ]
        , h2 "Tooling"
        , bullets
            [ "Type inference with friendly errors"
            , "Language server (hover, completion, refactors, rename)"
            , "elm test with property (fuzz) tests"
            , "Formatter, linter, docs and package manager"
            ]
        ]


cli : Page
cli =
    page "cli.html"
        "CLI — elm-lang"
        [ nav
        , h1 "Command line"
        , text "Every command takes --help. A few highlights:"
        , h2 "Compile & run"
        , codeBlock "elm run Main.elm\nelm make Main.elm -o app.html --optimize\nelm js Main.elm --min"
        , h2 "Scripting & serving"
        , codeBlock "elm script wordcount.elm README.md\nelm server api.elm --port 8080\nelm bundle script wordcount.elm -o wc   # standalone executable"
        , h2 "This site"
        , codeBlock "elm gen-site examples/site/ElmLang.elm out --api src/main/resources/elm/lib"
        ]


examplesPage : Page
examplesPage =
    page "examples.html"
        "Examples — elm-lang"
        [ nav
        , h1 "Examples"
        , text "The gallery compiles dozens of elm-lang.org examples to live JavaScript. Beyond those:"
        , bullets
            [ "RTS Mini — a real-time strategy game (build, train, gather, explore), running entirely in the browser"
            , "TodoMVC — the flagship TEA app"
            , "The Elm-in-Elm editor — an interpreter written in Elm"
            ]
        , links
            [ ( "rts.html", "Play RTS Mini" )
            , ( "editor.html", "Open the editor" )
            , ( "todomvc.html", "TodoMVC" )
            ]
        , h2 "RTS Mini — documentation"
        , text "The game is split into focused modules; read their generated API docs:"
        , links
            [ ( "rts.html", "Play the game" )
            , ( "api/RTS.Model.html", "RTS.Model" )
            , ( "api/RTS.Logic.html", "RTS.Logic" )
            , ( "api/RTS.View.html", "RTS.View" )
            , ( "api/RTS.Backend.html", "RTS.Backend" )
            ]
        ]

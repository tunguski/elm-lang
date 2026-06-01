module Main exposing (main)

{-| The Elm side of the static-site generator: a script that reads the artifact manifest the Java
compiler wrote (`<dir>/manifest.tsv` — typed, tab-separated lines) and produces the gallery's HTML
pages from it, using the `Site` library. The compiled example demos, the JS-vs-WASM page, the
playground, the editor and the docs are artifacts the Java side produced; the stylesheets
(`styles.css`, `page.css`, `docs.css`) and the gallery script (`gallery.js`) are static files the
Java side copies in. This generator only lays out the HTML, building it from multi-line string
templates with `%param%` placeholders.

    elm script Gallery.elm <siteDir>

Manifest line types:

    example  slug  title  category  demoPath  method
    aux      href  label
    doc      href  label
    stat     live  total

-}

import Bash exposing (..)
import Site exposing (..)


main : Io
main =
    getArgs
        (\args ->
            case args of
                dir :: _ ->
                    build dir

                [] ->
                    print "usage: gallery <siteDir>" (exit 1)
        )


{-| One manifest row. -}
type Row
    = Ex Example
    | Aux String String
    | Doc String String
    | Stat Int Int


type alias Example =
    { slug : String
    , title : String
    , category : String
    , demo : String
    , method : String
    }


build : String -> Io
build dir =
    cat (dir ++ "/manifest.tsv")
        (\result ->
            case result of
                Ok tsv ->
                    -- The shared sidebar fragment (nav.html) is written once by the Java side; we read
                    -- it and embed it verbatim in every sub-page so the navigation is identical.
                    cat (dir ++ "/nav.html")
                        (\navResult ->
                            let
                                nav =
                                    Result.withDefault "" navResult

                                rows =
                                    parse tsv

                                examples =
                                    List.filterMap exampleOf rows

                                docs =
                                    List.filterMap docOf rows
                            in
                            -- The stylesheets and gallery.js are static resources the Java side copies
                            -- in; we only write the HTML pages here.
                            writeFile (dir ++ "/index.html") (render (indexPage rows)) <|
                                writeWrappers dir nav examples <|
                                    writeDocs dir nav docs <|
                                        print ("Generated the gallery (" ++ String.fromInt (List.length examples) ++ " examples, " ++ String.fromInt (List.length docs) ++ " docs) in " ++ dir) done
                        )

                Err message ->
                    print ("cannot read manifest.tsv: " ++ message) (exit 1)
        )



-- WRAPPER PAGES (one per example) -----------------------------------------


{-| Writes one wrapper page per example: it reads the example's source (written under `examples/` by
the Java side) and lays out the live demo iframe next to the highlighted source. Sequenced as a
continuation-passing fold so each read-then-write happens in order. -}
writeWrappers : String -> String -> List Example -> Io -> Io
writeWrappers dir nav examples andThen =
    case examples of
        [] ->
            andThen

        e :: rest ->
            cat (dir ++ "/examples/" ++ e.slug ++ ".elm")
                (\result ->
                    writeFile (dir ++ "/" ++ e.slug ++ ".html")
                        (wrapperHtml nav e (Result.withDefault "" result))
                        (writeWrappers dir nav rest andThen)
                )


{-| A single example's wrapper page: the shared sidebar, then the live demo in an iframe next to the
source highlighted by highlight.js (Elm + Bash languages, loaded from a CDN). Built from a template;
`%SOURCE%` is filled last so source text can't collide with a placeholder. -}
wrapperHtml : String -> Example -> String -> String
wrapperHtml nav e source =
    """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%TITLE% — elm-lang</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css">
<link rel="stylesheet" href="page.css">
<link rel="stylesheet" href="nav.css">
</head><body>
<div class="layout">%NAV%
<main>
<h1>%TITLE% <small>%CATEGORY%</small> <span class="badge %CSS%">%METHOD%</span></h1>
<section class="demo"><div class="demo-head"><a class="newtab" href="%DEMO%" target="_blank" rel="noopener">Open demo in a new tab &#8599;</a></div>
<iframe title="%TITLE% demo" src="%DEMO%" loading="lazy"></iframe></section>
<section class="src"><h2>Source</h2><pre><code class="language-elm">%SOURCE%</code></pre></section>
</main>
</div>
<script src="nav.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/elm.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/bash.min.js"></script>
<script>hljs.highlightAll();</script>
</body></html>
"""
        |> String.replace "%NAV%" nav
        |> String.replace "%CSS%" (badgeClass e.method)
        |> String.replace "%METHOD%" (escapeHtml e.method)
        |> String.replace "%CATEGORY%" (escapeHtml e.category)
        |> String.replace "%DEMO%" (escapeHtml e.demo)
        |> String.replace "%TITLE%" (escapeHtml e.title)
        |> String.replace "%SOURCE%" (escapeHtml source)



-- DOCUMENTATION PAGES -----------------------------------------------------


{-| A documentation row as (href, title). -}
docOf : Row -> Maybe ( String, String )
docOf row =
    case row of
        Doc href label ->
            Just ( href, label )

        _ ->
            Nothing


{-| Writes one documentation page per `doc` entry: reads the Markdown body the Java side rendered to
`<slug>.bodyhtml` and wraps it in the shared page chrome (the sidebar, footer, and highlight.js). -}
writeDocs : String -> String -> List ( String, String ) -> Io -> Io
writeDocs dir nav docs andThen =
    case docs of
        [] ->
            andThen

        ( href, label ) :: rest ->
            let
                slug =
                    String.dropRight 5 href
            in
            cat (dir ++ "/" ++ slug ++ ".bodyhtml")
                (\result ->
                    writeFile (dir ++ "/" ++ href)
                        (docPageHtml nav label (Result.withDefault "" result))
                        (writeDocs dir nav rest andThen)
                )


docPageHtml : String -> String -> String -> String
docPageHtml nav title body =
    """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%TITLE% — elm-lang</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css">
<link rel="stylesheet" href="docs.css">
<link rel="stylesheet" href="nav.css"></head><body>
<div class="layout">%NAV%
<main>
%BODY%
<footer>Documentation for the from-scratch Elm implementation · <a href="https://github.com/tunguski/elm-lang">source on GitHub</a></footer>
</main>
</div>
<script src="nav.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/elm.min.js"></script>
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/bash.min.js"></script>
<script>hljs.highlightAll();</script>
</body></html>
"""
        |> String.replace "%NAV%" nav
        |> String.replace "%TITLE%" (escapeHtml title)
        |> String.replace "%BODY%" body



-- INDEX PAGE --------------------------------------------------------------


indexPage : List Row -> Page
indexPage rows =
    let
        examples =
            List.filterMap exampleOf rows
    in
    page "index.html"
        "elm-lang — example gallery"
        ([ raw """<link rel="stylesheet" href="styles.css">"""
         , raw (hero rows)
         , raw controls
         ]
            ++ List.map categorySection (groupByCategory examples)
            ++ [ raw footer, raw """<script src="gallery.js"></script>""" ]
        )


{-| The hero header: title, blurb, and a stats line linking the auxiliary pages and docs. -}
hero : List Row -> String
hero rows =
    let
        ( live, total ) =
            statOf rows

        links =
            String.concat (List.filterMap auxLink rows ++ List.filterMap docLink rows)
    in
    """<header class="hero"><h1>elm-lang</h1>
<p>A from-scratch Elm implementation in Java — a Truffle JIT interpreter, a bytecode VM, and a compiler to JavaScript. Every example below is the <strong>JavaScript-compiled</strong> output running live in your browser; the multi-module Playground games and a couple of GPU-bound programs fall back to a server-side-rendered initial frame. This page is generated from Elm by the Site library.</p>
<p class="stats">%LIVE% of %TOTAL% examples run as live compiled JavaScript · %LINKS%<a href="https://github.com/tunguski/elm-lang">source on GitHub</a></p></header>"""
        |> String.replace "%LIVE%" (String.fromInt live)
        |> String.replace "%TOTAL%" (String.fromInt total)
        |> String.replace "%LINKS%" links


auxLink : Row -> Maybe String
auxLink row =
    case row of
        Aux href label ->
            Just (linkChip href label)

        _ ->
            Nothing


docLink : Row -> Maybe String
docLink row =
    case row of
        Doc href label ->
            Just (linkChip href label)

        _ ->
            Nothing


linkChip : String -> String -> String
linkChip href label =
    """<a href="%HREF%">%LABEL% &#8594;</a> · """
        |> String.replace "%HREF%" (escapeHtml href)
        |> String.replace "%LABEL%" (escapeHtml label)


statOf : List Row -> ( Int, Int )
statOf rows =
    case rows of
        (Stat live total) :: _ ->
            ( live, total )

        _ :: rest ->
            statOf rest

        [] ->
            ( 0, 0 )


{-| The search box and the dark/light theme toggle (driven by gallery.js). -}
controls : String
controls =
    """<div class="controls"><input id="search" type="search" placeholder="Search examples…" aria-label="Search examples"><button id="theme-toggle" type="button" aria-label="Toggle dark mode">🌓 Theme</button></div>"""


{-| One category as a titled grid of cards. -}
categorySection : ( String, List Example ) -> Block
categorySection ( category, es ) =
    raw
        ("""<section class="cat"><h2>%CAT%</h2><div class="grid">%CARDS%</div></section>"""
            |> String.replace "%CAT%" (escapeHtml category)
            |> String.replace "%CARDS%" (String.concat (List.map card es))
        )


{-| A card linking its wrapper page, with a live demo thumbnail and a method badge; the lowercased
`data-name` drives the client-side search. -}
card : Example -> String
card e =
    """<a class="card" href="%HREF%" data-name="%NAME%"><span class="thumb"><iframe tabindex="-1" scrolling="no" src="%DEMO%" loading="lazy"></iframe></span><span class="meta"><strong>%TITLE%</strong><span class="badge %CSS%">%METHOD%</span></span></a>"""
        |> String.replace "%HREF%" (escapeHtml (e.slug ++ ".html"))
        |> String.replace "%NAME%" (escapeHtml (String.toLower e.title))
        |> String.replace "%DEMO%" (escapeHtml e.demo)
        |> String.replace "%CSS%" (badgeClass e.method)
        |> String.replace "%METHOD%" (escapeHtml e.method)
        |> String.replace "%TITLE%" (escapeHtml e.title)


{-| Maps a method label to its badge CSS class. -}
badgeClass : String -> String
badgeClass method =
    if String.contains "Live" method then
        "live"

    else if String.contains "snapshot" method then
        "snapshot"

    else
        "failed"


footer : String
footer =
    """<footer>Generated from the test corpus: the Java side compiles the demos and a manifest, the Elm Site library lays out this page.</footer>"""



-- MANIFEST PARSING --------------------------------------------------------


parse : String -> List Row
parse tsv =
    List.filterMap parseLine (String.lines tsv)


parseLine : String -> Maybe Row
parseLine line =
    case String.split "\t" line of
        "example" :: slug :: title :: category :: demo :: method :: _ ->
            Just (Ex { slug = slug, title = title, category = category, demo = demo, method = method })

        "aux" :: href :: label :: _ ->
            Just (Aux href label)

        "doc" :: href :: label :: _ ->
            Just (Doc href label)

        "stat" :: live :: total :: _ ->
            Just (Stat (toInt live) (toInt total))

        _ ->
            Nothing


toInt : String -> Int
toInt s =
    Maybe.withDefault 0 (String.toInt s)


exampleOf : Row -> Maybe Example
exampleOf row =
    case row of
        Ex e ->
            Just e

        _ ->
            Nothing


escapeHtml : String -> String
escapeHtml s =
    s
        |> String.replace "&" "&amp;"
        |> String.replace "<" "&lt;"
        |> String.replace ">" "&gt;"
        |> String.replace "\"" "&quot;"


{-| The example rows grouped by category, categories kept in first-seen order. -}
groupByCategory : List Example -> List ( String, List Example )
groupByCategory examples =
    List.map (\c -> ( c, List.filter (\e -> e.category == c) examples )) (distinctCategories examples)


distinctCategories : List Example -> List String
distinctCategories examples =
    List.foldl
        (\e acc ->
            if List.member e.category acc then
                acc

            else
                acc ++ [ e.category ]
        )
        []
        examples

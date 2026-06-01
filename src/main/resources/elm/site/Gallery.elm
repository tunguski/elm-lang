module Main exposing (main)

{-| The Elm side of the static-site generator: a script that reads the artifact manifest the Java
compiler wrote (`<dir>/manifest.tsv` — typed, tab-separated lines) and produces ALL of the gallery
index's HTML and CSS from it, using the `Site` library. The compiled example demos, the JS-vs-WASM
page, the playground, the editor and the docs are artifacts the Java side produced; this generator
reads the manifest describing them and lays out the index page (hero, stats, links and the
searchable card grid) plus its stylesheet.

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
                    let
                        rows =
                            parse tsv

                        examples =
                            List.filterMap exampleOf rows
                    in
                    writeFile (dir ++ "/styles.css") styles <|
                        writeFile (dir ++ "/index.html") (render (indexPage rows)) <|
                            print ("Generated the gallery index (" ++ String.fromInt (List.length examples) ++ " examples) in " ++ dir) done

                Err message ->
                    print ("cannot read manifest.tsv: " ++ message) (exit 1)
        )


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


indexPage : List Row -> Page
indexPage rows =
    let
        examples =
            List.filterMap exampleOf rows
    in
    page "index.html"
        "elm-lang — example gallery"
        ([ raw "<link rel=\"stylesheet\" href=\"styles.css\">"
         , raw (hero rows)
         , raw controls
         ]
            ++ List.map categorySection (groupByCategory examples)
            ++ [ raw footer, raw script ]
        )


{-| The hero header: title, blurb, and a stats line that links to the auxiliary pages and docs. -}
hero : List Row -> String
hero rows =
    let
        ( live, total ) =
            statOf rows

        auxLinks =
            List.filterMap auxLink rows ++ List.filterMap docLink rows
    in
    "<header class=\"hero\"><h1>elm-lang</h1>"
        ++ "<p>A from-scratch Elm implementation in Java — a Truffle JIT interpreter, a bytecode VM, "
        ++ "and a compiler to JavaScript. Every example below is the <strong>JavaScript-compiled</strong> "
        ++ "output running live in your browser; the multi-module Playground games and a couple of "
        ++ "GPU-bound programs fall back to a server-side-rendered initial frame. This page is generated "
        ++ "from Elm by the Site library.</p>"
        ++ "<p class=\"stats\">"
        ++ String.fromInt live
        ++ " of "
        ++ String.fromInt total
        ++ " examples run as live compiled JavaScript · "
        ++ String.concat auxLinks
        ++ "<a href=\"https://github.com/tunguski/elm-lang\">source on GitHub</a></p></header>"


auxLink : Row -> Maybe String
auxLink row =
    case row of
        Aux href label ->
            Just ("<a href=\"" ++ escapeHtml href ++ "\">" ++ escapeHtml label ++ " &#8594;</a> · ")

        _ ->
            Nothing


docLink : Row -> Maybe String
docLink row =
    case row of
        Doc href label ->
            Just ("<a href=\"" ++ escapeHtml href ++ "\">" ++ escapeHtml label ++ " &#8594;</a> · ")

        _ ->
            Nothing


statOf : List Row -> ( Int, Int )
statOf rows =
    case rows of
        (Stat live total) :: _ ->
            ( live, total )

        _ :: rest ->
            statOf rest

        [] ->
            ( 0, 0 )


{-| The search box and the dark/light theme toggle (handled client-side by the script below). -}
controls : String
controls =
    "<div class=\"controls\">"
        ++ "<input id=\"search\" type=\"search\" placeholder=\"Search examples…\" aria-label=\"Search examples\">"
        ++ "<button id=\"theme-toggle\" type=\"button\" aria-label=\"Toggle dark mode\">🌓 Theme</button>"
        ++ "</div>"


{-| One category as a titled grid of cards. Each card links its wrapper page and shows a live
thumbnail of the compiled demo, plus a method badge; the lowercased `data-name` drives the search. -}
categorySection : ( String, List Example ) -> Block
categorySection ( category, es ) =
    raw
        ("<section class=\"cat\"><h2>"
            ++ escapeHtml category
            ++ "</h2><div class=\"grid\">"
            ++ String.concat (List.map card es)
            ++ "</div></section>"
        )


card : Example -> String
card e =
    "<a class=\"card\" href=\""
        ++ escapeHtml (e.slug ++ ".html")
        ++ "\" data-name=\""
        ++ escapeHtml (String.toLower e.title)
        ++ "\"><span class=\"thumb\"><iframe tabindex=\"-1\" scrolling=\"no\" src=\""
        ++ escapeHtml e.demo
        ++ "\" loading=\"lazy\"></iframe></span><span class=\"meta\"><strong>"
        ++ escapeHtml e.title
        ++ "</strong><span class=\"badge "
        ++ badgeClass e.method
        ++ "\">"
        ++ escapeHtml e.method
        ++ "</span></span></a>"


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
    "<footer>Generated from the test corpus: the Java side compiles the demos and a manifest, "
        ++ "the Elm Site library lays out this page.</footer>"


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


{-| Client-side behaviour: persist a chosen theme (overriding the OS default), and filter the cards
as you type, hiding categories that end up empty. -}
script : String
script =
    "<script>"
        ++ "(function(){var root=document.documentElement;"
        ++ "var saved=localStorage.getItem('theme');if(saved){root.setAttribute('data-theme',saved);}"
        ++ "var t=document.getElementById('theme-toggle');"
        ++ "if(t){t.addEventListener('click',function(){"
        ++ "var cur=root.getAttribute('data-theme')==='dark'?'light':'dark';"
        ++ "root.setAttribute('data-theme',cur);localStorage.setItem('theme',cur);});}"
        ++ "var s=document.getElementById('search');"
        ++ "if(s){s.addEventListener('input',function(){var q=s.value.toLowerCase();"
        ++ "document.querySelectorAll('section.cat').forEach(function(sec){var any=false;"
        ++ "sec.querySelectorAll('a.card').forEach(function(c){"
        ++ "var m=c.getAttribute('data-name').indexOf(q)>=0;c.style.display=m?'':'none';if(m){any=true;}});"
        ++ "sec.style.display=any?'':'none';});});}})();"
        ++ "</script>"


{-| The gallery stylesheet (written to styles.css), loaded after the Site base style so its rules
win. Mirrors the previous Java-generated index.css, plus the search controls and a dark theme. -}
styles : String
styles =
    ":root{--accent:#5fabdc;--ink:#293c4b;--bg:#fafafa}\n"
        ++ "*{box-sizing:border-box}\n"
        ++ "body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink);background:var(--bg)}\n"
        ++ ".hero{padding:48px 24px 24px;max-width:1000px;margin:0 auto}\n"
        ++ ".hero h1{font-size:2.6rem;margin:0 0 8px;color:var(--accent)}\n"
        ++ ".hero p{max-width:60ch;line-height:1.5}\n"
        ++ ".stats{font-size:.9rem;color:#667}\n"
        ++ "main{max-width:1000px;margin:0 auto;padding:0 24px 48px}\n"
        ++ "h2{margin:32px 0 12px;border-bottom:2px solid #eee;padding-bottom:4px}\n"
        ++ ".controls{display:flex;gap:10px;max-width:1000px;margin:0 auto;padding:0 24px}\n"
        ++ "#search{flex:1;padding:8px 10px;border:1px solid #cdd6e0;border-radius:6px;font:inherit}\n"
        ++ "#theme-toggle{padding:8px 12px;border:1px solid #cdd6e0;border-radius:6px;background:#fff;cursor:pointer;font:inherit}\n"
        ++ ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:16px}\n"
        ++ ".card{display:flex;flex-direction:column;border:1px solid #e3e3e3;border-radius:10px;overflow:hidden;"
        ++ "text-decoration:none;color:inherit;background:#fff;transition:box-shadow .15s,transform .15s}\n"
        ++ ".card:hover{box-shadow:0 6px 20px rgba(0,0,0,.12);transform:translateY(-2px)}\n"
        ++ ".thumb{height:150px;background:#fff;overflow:hidden;border-bottom:1px solid #eee;position:relative}\n"
        ++ ".thumb iframe{position:absolute;top:0;left:0;width:200%;height:300px;border:0;transform:scale(.5);transform-origin:top left;pointer-events:none}\n"
        ++ ".meta{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;gap:8px}\n"
        ++ ".badge{font-size:.7rem;padding:2px 8px;border-radius:999px;white-space:nowrap}\n"
        ++ ".badge.live{background:#e3f4e1;color:#246b1e}\n"
        ++ ".badge.snapshot{background:#fdf0d5;color:#8a5a00}\n"
        ++ ".badge.failed{background:#f6dada;color:#9a1e1e}\n"
        ++ "footer{max-width:1000px;margin:0 auto;padding:24px;color:#889;font-size:.85rem}\n"
        -- Dark theme: explicit toggle, or the OS default unless the visitor chose light.
        ++ "[data-theme=dark] body{background:#0f1720;color:#e6edf3}\n"
        ++ "[data-theme=dark] .card{background:#1b2535;border-color:#2f3e54}\n"
        ++ "[data-theme=dark] #search,[data-theme=dark] #theme-toggle{background:#1b2535;border-color:#2f3e54;color:#cbd5e1}\n"
        ++ "[data-theme=dark] h2{border-bottom-color:#243041}\n"
        ++ "@media (prefers-color-scheme: dark){\n"
        ++ "  :root:not([data-theme=light]) body{background:#0f1720;color:#e6edf3}\n"
        ++ "  :root:not([data-theme=light]) .card{background:#1b2535;border-color:#2f3e54}\n"
        ++ "  :root:not([data-theme=light]) #search,:root:not([data-theme=light]) #theme-toggle{background:#1b2535;border-color:#2f3e54;color:#cbd5e1}\n"
        ++ "}\n"

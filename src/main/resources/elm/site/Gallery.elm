module Main exposing (main)

{-| The Elm side of the static-site generator: a script that reads the artifact manifest the Java
compiler wrote (`<dir>/manifest.tsv` — one example per line, tab-separated) and produces ALL of the
gallery's HTML and CSS from it, using the `Site` library. The compiled example demos themselves are
artifacts the Java side produced; this generator only reads them and lays out the pages.

    elm script Gallery.elm <siteDir>

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


{-| An example artifact, as one row of the manifest. -}
type alias Entry =
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
                        entries =
                            parse tsv
                    in
                    writeFile (dir ++ "/styles.css") styles <|
                        writeFile (dir ++ "/index.html") (render (galleryPage entries)) <|
                            print ("Generated the gallery (" ++ String.fromInt (List.length entries) ++ " examples) in " ++ dir) done

                Err message ->
                    print ("cannot read manifest.tsv: " ++ message) (exit 1)
        )


parse : String -> List Entry
parse tsv =
    List.filterMap parseLine (String.lines tsv)


parseLine : String -> Maybe Entry
parseLine line =
    case String.split "\t" line of
        slug :: title :: category :: demo :: method :: _ ->
            Just { slug = slug, title = title, category = category, demo = demo, method = method }

        _ ->
            Nothing


galleryPage : List Entry -> Page
galleryPage entries =
    page "index.html"
        "elm-lang — example gallery"
        ([ raw "<link rel=\"stylesheet\" href=\"styles.css\">"
         , h1 "Example gallery"
         , text "Every example below is the JavaScript-compiled output, running live in your browser. This whole page is generated from Elm by the Site library."
         , raw controls
         ]
            ++ List.map categorySection (groupByCategory entries)
            ++ [ raw script ]
        )


{-| The search box and the dark/light theme toggle (filtering and theming are done client-side by
the script below). -}
controls : String
controls =
    "<div class=\"controls\">"
        ++ "<input id=\"search\" type=\"search\" placeholder=\"Search examples…\" aria-label=\"Search examples\">"
        ++ "<button id=\"theme-toggle\" type=\"button\" aria-label=\"Toggle dark mode\">🌓 Theme</button>"
        ++ "</div>"


{-| One category as a titled grid of cards. Each card carries a lowercased `data-name` so the search
script can show/hide it (and hide a whole category when nothing in it matches). -}
categorySection : ( String, List Entry ) -> Block
categorySection ( category, es ) =
    raw
        ("<section class=\"cat\"><h2>"
            ++ escapeHtml category
            ++ "</h2><div class=\"grid\">"
            ++ String.concat (List.map card es)
            ++ "</div></section>"
        )


card : Entry -> String
card e =
    "<a class=\"card\" href=\""
        ++ escapeHtml e.demo
        ++ "\" data-name=\""
        ++ escapeHtml (String.toLower e.title)
        ++ "\">"
        ++ escapeHtml e.title
        ++ "</a>"


escapeHtml : String -> String
escapeHtml s =
    s
        |> String.replace "&" "&amp;"
        |> String.replace "<" "&lt;"
        |> String.replace ">" "&gt;"
        |> String.replace "\"" "&quot;"


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


{-| The example rows grouped by category, categories kept in first-seen order. -}
groupByCategory : List Entry -> List ( String, List Entry )
groupByCategory entries =
    List.map (\c -> ( c, List.filter (\e -> e.category == c) entries )) (distinctCategories entries)


distinctCategories : List Entry -> List String
distinctCategories entries =
    List.foldl
        (\e acc ->
            if List.member e.category acc then
                acc

            else
                acc ++ [ e.category ]
        )
        []
        entries


{-| Gallery-specific CSS the generator writes to styles.css (on top of the Site library's base).
Loaded after the base inline stylesheet, so its rules win where they overlap — including the dark
theme, which applies on an explicit `[data-theme=dark]` toggle or by the OS preference. -}
styles : String
styles =
    "main { max-width: 820px; }\n"
        ++ "h2 { border-bottom: 1px solid #e3e8ee; padding-bottom: 4px; }\n"
        ++ ".controls { display: flex; gap: 10px; margin: 16px 0 8px; }\n"
        ++ "#search { flex: 1; padding: 8px 10px; border: 1px solid #c7d2fe; border-radius: 6px;"
        ++ " font: inherit; }\n"
        ++ "#theme-toggle { padding: 8px 12px; border: 1px solid #c7d2fe; border-radius: 6px;"
        ++ " background: #eef2ff; cursor: pointer; font: inherit; }\n"
        ++ ".grid { display: flex; flex-wrap: wrap; gap: 10px; }\n"
        ++ ".card { background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 6px;"
        ++ " padding: 6px 10px; text-decoration: none; color: #2563eb; }\n"
        ++ ".card:hover { background: #e0e7ff; }\n"
        -- Dark theme: explicit toggle, or the OS default unless the visitor chose light.
        ++ "[data-theme=dark] body { background: #0f1720; color: #e6edf3; }\n"
        ++ "[data-theme=dark] h2 { border-bottom-color: #243041; }\n"
        ++ "[data-theme=dark] #search, [data-theme=dark] #theme-toggle,"
        ++ " [data-theme=dark] .card { background: #1b2535; border-color: #2f3e54; color: #cbd5e1; }\n"
        ++ "[data-theme=dark] .card:hover { background: #243149; }\n"
        ++ "@media (prefers-color-scheme: dark) {\n"
        ++ "  :root:not([data-theme=light]) body { background: #0f1720; color: #e6edf3; }\n"
        ++ "  :root:not([data-theme=light]) h2 { border-bottom-color: #243041; }\n"
        ++ "  :root:not([data-theme=light]) #search, :root:not([data-theme=light]) #theme-toggle,"
        ++ "  :root:not([data-theme=light]) .card { background: #1b2535; border-color: #2f3e54; color: #cbd5e1; }\n"
        ++ "}\n"

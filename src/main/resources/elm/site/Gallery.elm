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

                        docs =
                            List.filterMap docOf rows
                    in
                    writeFile (dir ++ "/styles.css") styles <|
                        writeFile (dir ++ "/page.css") pageStyles <|
                            writeFile (dir ++ "/docs.css") docsStyles <|
                                writeFile (dir ++ "/index.html") (render (indexPage rows)) <|
                                    writeWrappers dir examples <|
                                        writeDocs dir docs docs <|
                                            print ("Generated the gallery (" ++ String.fromInt (List.length examples) ++ " examples, " ++ String.fromInt (List.length docs) ++ " docs) in " ++ dir) done

                Err message ->
                    print ("cannot read manifest.tsv: " ++ message) (exit 1)
        )


{-| Writes one wrapper page per example: it reads the example's source (written under `examples/` by
the Java side) and lays out the live demo iframe next to the highlighted source. Sequenced as a
continuation-passing fold so each read-then-write happens in order. -}
writeWrappers : String -> List Example -> Io -> Io
writeWrappers dir examples andThen =
    case examples of
        [] ->
            andThen

        e :: rest ->
            cat (dir ++ "/examples/" ++ e.slug ++ ".elm")
                (\result ->
                    writeFile (dir ++ "/" ++ e.slug ++ ".html")
                        (wrapperHtml e (Result.withDefault "" result))
                        (writeWrappers dir rest andThen)
                )


{-| A single example's wrapper page: header with a method badge, the live demo in an iframe, and the
source highlighted by highlight.js (loaded from a CDN, as before). -}
wrapperHtml : Example -> String -> String
wrapperHtml e source =
    "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        ++ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        ++ "<title>"
        ++ escapeHtml e.title
        ++ " — elm-lang</title>"
        ++ "<link rel=\"stylesheet\" href=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css\">"
        ++ "<link rel=\"stylesheet\" href=\"page.css\">"
        ++ "</head><body>"
        ++ "<header class=\"bar\"><a class=\"home\" href=\"index.html\">&larr; All examples</a>"
        ++ "<span class=\"badge "
        ++ badgeClass e.method
        ++ "\">"
        ++ escapeHtml e.method
        ++ "</span></header><main>"
        ++ "<h1>"
        ++ escapeHtml e.title
        ++ " <small>"
        ++ escapeHtml e.category
        ++ "</small></h1>"
        ++ "<section class=\"demo\"><div class=\"demo-head\">"
        ++ "<a class=\"newtab\" href=\""
        ++ escapeHtml e.demo
        ++ "\" target=\"_blank\" rel=\"noopener\">Open demo in a new tab &#8599;</a></div>"
        ++ "<iframe title=\""
        ++ escapeHtml e.title
        ++ " demo\" src=\""
        ++ escapeHtml e.demo
        ++ "\" loading=\"lazy\"></iframe></section>"
        ++ "<section class=\"src\"><h2>Source</h2><pre><code class=\"language-elm\">"
        ++ escapeHtml source
        ++ "</code></pre></section></main>"
        ++ "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js\"></script>"
        ++ "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/elm.min.js\"></script>"
        ++ "<script>hljs.highlightAll();</script>"
        ++ "</body></html>\n"


{-| The wrapper-page stylesheet (written to page.css), formerly inlined by the Java generator. -}
pageStyles : String
pageStyles =
    ":root{--accent:#5fabdc;--ink:#293c4b}\n"
        ++ "*{box-sizing:border-box}\n"
        ++ "body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink)}\n"
        ++ ".bar{display:flex;align-items:center;justify-content:space-between;padding:12px 24px;"
        ++ "border-bottom:1px solid #eee;position:sticky;top:0;background:#fff}\n"
        ++ ".home{color:var(--accent);text-decoration:none;font-weight:600}\n"
        ++ "main{max-width:900px;margin:0 auto;padding:24px}\n"
        ++ "h1 small{font-size:.9rem;color:#889;font-weight:400}\n"
        ++ ".demo-head{display:flex;justify-content:flex-end;margin-bottom:6px}\n"
        ++ ".newtab{color:var(--accent);text-decoration:none;font-size:.85rem;font-weight:600}\n"
        ++ ".demo iframe{width:100%;min-height:420px;border:1px solid #e3e3e3;border-radius:10px;background:#fff}\n"
        ++ ".src pre{background:#0f1720;border-radius:10px;overflow:auto;line-height:1.5}\n"
        ++ ".src pre code{display:block;padding:16px;color:#e6edf3}\n"
        ++ ".badge{font-size:.72rem;padding:3px 10px;border-radius:999px}\n"
        ++ ".badge.live{background:#e3f4e1;color:#246b1e}\n"
        ++ ".badge.snapshot{background:#fdf0d5;color:#8a5a00}\n"
        ++ ".badge.failed{background:#f6dada;color:#9a1e1e}\n"


{-| A documentation row as (href, title). -}
docOf : Row -> Maybe ( String, String )
docOf row =
    case row of
        Doc href label ->
            Just ( href, label )

        _ ->
            Nothing


{-| Writes one documentation page per `doc` entry: reads the Markdown body the Java side rendered to
`<slug>.bodyhtml` and wraps it in the page chrome (header nav across all guides, footer). -}
writeDocs : String -> List ( String, String ) -> List ( String, String ) -> Io -> Io
writeDocs dir allDocs docs andThen =
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
                        (docPageHtml allDocs href label (Result.withDefault "" result))
                        (writeDocs dir allDocs rest andThen)
                )


docPageHtml : List ( String, String ) -> String -> String -> String -> String
docPageHtml allDocs href title body =
    "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        ++ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        ++ "<title>"
        ++ escapeHtml title
        ++ " — elm-lang</title><link rel=\"stylesheet\" href=\"docs.css\"></head><body>"
        ++ "<header class=\"bar\"><a href=\"index.html\">&larr; Gallery</a> "
        ++ docNav allDocs href
        ++ "</header><main>"
        ++ body
        ++ "</main><footer>Documentation for the from-scratch Elm implementation · "
        ++ "<a href=\"https://github.com/tunguski/elm-lang\">source on GitHub</a></footer></body></html>\n"


{-| A nav row linking every guide; the current one is shown inert. -}
docNav : List ( String, String ) -> String -> String
docNav allDocs current =
    String.join " · "
        (List.map
            (\( href, label ) ->
                if href == current then
                    "<strong>" ++ escapeHtml label ++ "</strong>"

                else
                    "<a href=\"" ++ escapeHtml href ++ "\">" ++ escapeHtml label ++ "</a>"
            )
            allDocs
        )


{-| The documentation-page stylesheet (docs.css), formerly inlined by the Java generator. -}
docsStyles : String
docsStyles =
    ":root{--accent:#5fabdc;--ink:#293c4b}\n"
        ++ "*{box-sizing:border-box}\n"
        ++ "body{margin:0;font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:var(--ink);line-height:1.6}\n"
        ++ ".bar{display:flex;align-items:center;gap:16px;padding:12px 24px;border-bottom:1px solid #eee;position:sticky;top:0;background:#fff;z-index:1}\n"
        ++ ".bar a{color:var(--accent);text-decoration:none;font-weight:600}\n"
        ++ ".bar a:hover{text-decoration:underline}\n"
        ++ "main{max-width:820px;margin:0 auto;padding:24px 24px 64px}\n"
        ++ "h1,h2,h3,h4{line-height:1.25;color:#1c2b38}\n"
        ++ "h1{border-bottom:2px solid #eef2f5;padding-bottom:.3em}\n"
        ++ "h2{margin-top:2em;border-bottom:1px solid #eef2f5;padding-bottom:.2em}\n"
        ++ "a{color:var(--accent)}\n"
        ++ "code{background:#f3f5f7;border-radius:5px;padding:.12em .4em;font-size:.92em;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}\n"
        ++ "pre{background:#0f1720;border-radius:10px;overflow:auto;line-height:1.5}\n"
        ++ "pre code{display:block;padding:16px;color:#e6edf3;background:none;border-radius:0}\n"
        ++ "table{border-collapse:collapse;width:100%;margin:1em 0;font-size:.94rem}\n"
        ++ "th,td{border:1px solid #e3e7ea;padding:7px 11px;text-align:left;vertical-align:top}\n"
        ++ "th{background:#f6f8fa}\n"
        ++ "tr:nth-child(even) td{background:#fbfcfd}\n"
        ++ "blockquote{margin:1em 0;padding:.4em 1em;border-left:4px solid var(--accent);background:#f6fafd;color:#3a4a57}\n"
        ++ "footer{max-width:820px;margin:0 auto;padding:24px;border-top:1px solid #eee;color:#889;font-size:.9rem}\n"


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

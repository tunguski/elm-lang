module Main exposing (project)

{-| A static-site build expressed entirely with the bundled `Build` library — the build-tool
counterpart of the Java `SiteGenerator`. Run it with:

    elm build -f site.elm package

It compiles each example to a live, self-contained JS page (`compile JS …`), injects the shared
light/dark theme into each one (`replaceInFile …`, the build-tool counterpart of the generator's
`html.replace("</body>", …)`), renders the Markdown guides to HTML fragments (`markdown …`), writes
the artifact manifest, copies the static stylesheets and scripts, and finally runs the Elm gallery
generator (`Gallery.elm`) over the manifest to lay out `index.html`, the per-example wrapper pages
and the doc pages — all with no Java glue.

This covers the heart of the showcase (live demos + guides + the Elm-laid-out gallery). The
JS-backend can't yet bundle the GPU/WebGL programs or the multi-module Playground games, and the
JS-vs-WASM and editor pages need the WASM/benchmark machinery the Java generator has, so those stay
Java-side for now; everything else is reproducible here.

-}

import Build exposing (..)


{-| The examples the JavaScript backend compiles to live pages, paired with their gallery metadata
(`title`, `category`). -}
examples : List { slug : String, title : String, category : String }
examples =
    [ { slug = "hello", title = "Hello", category = "HTML" }
    , { slug = "groceries", title = "Groceries", category = "HTML" }
    , { slug = "shapes", title = "Shapes", category = "HTML" }
    , { slug = "buttons", title = "Buttons", category = "User Input" }
    , { slug = "text-fields", title = "Text Fields", category = "User Input" }
    , { slug = "forms", title = "Forms", category = "User Input" }
    , { slug = "numbers", title = "Numbers", category = "Random" }
    , { slug = "cards", title = "Cards", category = "Random" }
    , { slug = "time", title = "Time", category = "Time" }
    , { slug = "clock", title = "Clock", category = "Time" }
    ]


{-| The Markdown guides under `docs/`, rendered to `<slug>.bodyhtml` for the gallery to wrap. -}
guides : List String
guides =
    [ "examples", "scripting", "server", "build", "site" ]


project : Project
project =
    Build.project "showcase" "1.0.0"
        [ module_ "site" "."
            |> withGoals
                [ goal Package "site" (\_ -> buildTasks) ]
        ]


{-| The whole pipeline as one flat task list. -}
buildTasks : List Task
buildTasks =
    [ makeDir "out/demos", makeDir "out/examples" ]
        ++ List.concatMap demoTasks examples
        ++ List.map guideTask guides
        ++ [ writeFile "out/manifest.tsv" manifest ]
        ++ List.map copyAsset assets
        ++ [ writeFile "out/nav.html" nav
           , script "Gallery.elm" [ "out" ]
           ]


{-| Per example: compile it to a live page, inject the shared theme script (so the standalone demo
honours the site's light/dark toggle), and copy its source (the wrapper page shows the source). -}
demoTasks : { slug : String, title : String, category : String } -> List Task
demoTasks e =
    [ compile JS ("examples/" ++ e.slug ++ ".elm") ("out/demos/" ++ e.slug ++ ".html")
    , replaceInFile ("out/demos/" ++ e.slug ++ ".html")
        "</body>"
        "<script src=\"../theme.js\"></script></body>"
    , copy ("examples/" ++ e.slug ++ ".elm") ("out/examples/" ++ e.slug ++ ".elm")
    ]


guideTask : String -> Task
guideTask slug =
    markdown ("docs/" ++ slug ++ ".md") ("out/" ++ slug ++ ".bodyhtml")


{-| The tab-separated manifest the gallery generator reads (example / doc / stat rows). -}
manifest : String
manifest =
    String.concat (List.map exampleRow examples)
        ++ String.concat (List.map docRow guides)
        ++ ("stat\t" ++ String.fromInt (List.length examples) ++ "\t" ++ String.fromInt (List.length examples) ++ "\n")


exampleRow : { slug : String, title : String, category : String } -> String
exampleRow e =
    "example\t" ++ e.slug ++ "\t" ++ e.title ++ "\t" ++ e.category ++ "\tdemos/" ++ e.slug ++ ".html\tLive JS (compiled)\n"


docRow : String -> String
docRow slug =
    "doc\t" ++ slug ++ ".html\t" ++ slug ++ "\n"


{-| The static assets the gallery only links (copied verbatim into the output). -}
assets : List String
assets =
    [ "styles.css", "page.css", "docs.css", "nav.css", "site.css", "theme.js" ]


copyAsset : String -> Task
copyAsset name =
    copy ("assets/" ++ name) ("out/" ++ name)


{-| A minimal shared sidebar; the gallery embeds it on every sub-page. -}
nav : String
nav =
    "<nav class=\"sidebar\"><a class=\"brand\" href=\"index.html\">elm-lang</a></nav>"

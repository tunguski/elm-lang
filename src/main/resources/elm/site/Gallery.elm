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
         ]
            ++ List.concatMap categorySection (groupByCategory entries)
        )


categorySection : ( String, List Entry ) -> List Block
categorySection ( category, es ) =
    [ h2 category
    , links (List.map (\e -> ( e.demo, e.title )) es)
    ]


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


{-| Gallery-specific CSS the generator writes to styles.css (on top of the Site library's base). -}
styles : String
styles =
    "main { max-width: 820px; }\n"
        ++ ".links { display: flex; flex-wrap: wrap; gap: 10px; }\n"
        ++ ".links a { background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 6px;"
        ++ " padding: 6px 10px; text-decoration: none; }\n"
        ++ "h2 { border-bottom: 1px solid #e3e8ee; padding-bottom: 4px; }\n"

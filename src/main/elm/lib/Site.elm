module Site exposing
    ( Page
    , Block(..)
    , page
    , h1
    , h2
    , h3
    , text
    , code
    , codeBlock
    , bullets
    , link
    , links
    , raw
    , group
    , markdown
    , index
    , feed
    , render
    )

{-| A tiny static-site-generator library: describe pages as data (a title, a path and a list of
content blocks) and `render` each to a self-contained HTML document with a shared stylesheet. A
program exposes `site : List Page`, and the `gen-site` command writes every page to disk.

    import Site exposing (..)

    site : List Page
    site =
        [ page "index.html" "Home"
            [ h1 "Welcome"
            , text "A static site built in Elm."
            , links [ ( "about.html", "About" ) ]
            ]
        ]

-}


{-| A page: where to write it, its title, and its content blocks. -}
type alias Page =
    { path : String
    , title : String
    , blocks : List Block
    }


{-| A unit of page content. Use the builders below rather than the constructors directly. -}
type Block
    = Title Int String
    | Text String
    | Code String
    | Bullets (List String)
    | LinkRow (List ( String, String ))
    | Raw String
    | Group (List Block)


page : String -> String -> List Block -> Page
page path title blocks =
    { path = path, title = title, blocks = blocks }


h1 : String -> Block
h1 =
    Title 1


h2 : String -> Block
h2 =
    Title 2


h3 : String -> Block
h3 =
    Title 3


text : String -> Block
text =
    Text


{-| Inline code (a `<code>` span shown on its own line). -}
code : String -> Block
code s =
    Raw ("<p><code>" ++ escape s ++ "</code></p>")


{-| A preformatted code block. -}
codeBlock : String -> Block
codeBlock =
    Code


bullets : List String -> Block
bullets =
    Bullets


{-| A standalone link. -}
link : String -> String -> Block
link href label =
    LinkRow [ ( href, label ) ]


{-| A row of links (e.g. a nav). -}
links : List ( String, String ) -> Block
links =
    LinkRow


{-| Raw HTML, inserted verbatim. -}
raw : String -> Block
raw =
    Raw


{-| Groups blocks into a `<section>`. -}
group : List Block -> Block
group =
    Group


{-| Converts a (small subset of) Markdown into content blocks: blank-line-separated groups become
`#`/`##`/`###` headings, fenced code blocks, `- ` bullet lists, or paragraphs. Headings, paragraphs
and bullets also support inline `code`, **bold**, *italic* and [label](url). Lets a `*.md` file be
dropped straight into a page. -}
markdown : String -> List Block
markdown src =
    String.split "\n\n" src
        |> List.map String.trim
        |> List.filter (\g -> g /= "")
        |> List.map classifyGroup


classifyGroup : String -> Block
classifyGroup group =
    if String.startsWith "### " group then
        Title 3 (String.dropLeft 4 group)

    else if String.startsWith "## " group then
        Title 2 (String.dropLeft 3 group)

    else if String.startsWith "# " group then
        Title 1 (String.dropLeft 2 group)

    else if String.startsWith "```" group then
        Code (String.join "\n" (List.filter (\l -> not (String.startsWith "```" l)) (String.lines group)))

    else if String.startsWith "- " group then
        Bullets (List.map (String.dropLeft 2) (List.filter (String.startsWith "- ") (String.lines group)))

    else
        Text (String.replace "\n" " " group)


{-| An index `Page` linking to each of the given pages (by title) — a quick table of contents. Add
it to your `site` list:

    site = index "index.html" "All articles" articles :: articles

-}
index : String -> String -> List Page -> Page
index path title pages =
    page path title [ links (List.map (\p -> ( p.path, p.title )) pages) ]


{-| An RSS 2.0 feed (as XML text) for the given pages: each becomes an `<item>` with the page's title
and a link of `baseUrl ++ page.path`. Not a `Page` (it is XML, not HTML), so write it to `feed.xml`
yourself — e.g. from a script with `writeFile`. -}
feed : String -> String -> List Page -> String
feed title baseUrl pages =
    """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>%TITLE%</title><link>%BASE%</link>%ITEMS%</channel></rss>
"""
        |> String.replace "%TITLE%" (escape title)
        |> String.replace "%BASE%" (escape baseUrl)
        |> String.replace "%ITEMS%" (String.concat (List.map (feedItem baseUrl) pages))


feedItem : String -> Page -> String
feedItem baseUrl p =
    """<item><title>%T%</title><link>%L%</link></item>"""
        |> String.replace "%T%" (escape p.title)
        |> String.replace "%L%" (escape (baseUrl ++ p.path))


{-| Renders a page to a complete HTML document. The stylesheet is the static `site.css` (copied in
by the generator), linked rather than inlined; the body is assembled from the page's blocks. -}
render : Page -> String
render p =
    """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%TITLE%</title><link rel="stylesheet" href="site.css"></head><body><main>%BODY%</main></body></html>
"""
        |> String.replace "%TITLE%" (escape p.title)
        |> String.replace "%BODY%" (String.concat (List.map renderBlock p.blocks))


renderBlock : Block -> String
renderBlock block =
    case block of
        Title n s ->
            ("<h%N%>%S%</h%N%>"
                |> String.replace "%N%" (String.fromInt n)
                |> String.replace "%S%" (inline s)
            )

        Text s ->
            "<p>%S%</p>" |> String.replace "%S%" (inline s)

        Code s ->
            "<pre><code>%S%</code></pre>" |> String.replace "%S%" (escape s)

        Bullets items ->
            "<ul>%ITEMS%</ul>"
                |> String.replace "%ITEMS%" (String.concat (List.map listItem items))

        LinkRow rows ->
            """<p class="links">%LINKS%</p>"""
                |> String.replace "%LINKS%" (String.join " · " (List.map linkAnchor rows))

        Raw s ->
            s

        Group blocks ->
            "<section>%BODY%</section>"
                |> String.replace "%BODY%" (String.concat (List.map renderBlock blocks))


listItem : String -> String
listItem item =
    "<li>%S%</li>" |> String.replace "%S%" (inline item)


{-| Inline Markdown within a paragraph/heading/bullet: `code`, **bold**, *italic* and [label](url).
HTML special characters are escaped; an unmatched marker is left as a literal character. -}
inline : String -> String
inline s =
    fmt (String.toList s)


fmt : List Char -> String
fmt chars =
    case chars of
        [] ->
            ""

        '*' :: '*' :: rest ->
            case takeUntilBold [] rest of
                Just ( body, after ) ->
                    "<strong>" ++ fmt body ++ "</strong>" ++ fmt after

                Nothing ->
                    "*" ++ fmt ('*' :: rest)

        '*' :: rest ->
            case takeUntil '*' [] rest of
                Just ( body, after ) ->
                    "<em>" ++ fmt body ++ "</em>" ++ fmt after

                Nothing ->
                    "*" ++ fmt rest

        '`' :: rest ->
            case takeUntil '`' [] rest of
                Just ( body, after ) ->
                    "<code>" ++ escape (String.fromList body) ++ "</code>" ++ fmt after

                Nothing ->
                    "`" ++ fmt rest

        '[' :: rest ->
            case takeLink rest of
                Just ( label, url, after ) ->
                    ("""<a href="%H%">%L%</a>"""
                        |> String.replace "%H%" (escape (String.fromList url))
                        |> String.replace "%L%" (fmt label)
                    )
                        ++ fmt after

                Nothing ->
                    "[" ++ fmt rest

        c :: rest ->
            escapeChar c ++ fmt rest


{-| Splits at the first closing `**`, requiring a non-empty body; `Nothing` if unterminated. -}
takeUntilBold : List Char -> List Char -> Maybe ( List Char, List Char )
takeUntilBold acc chars =
    case chars of
        [] ->
            Nothing

        '*' :: '*' :: rest ->
            if acc == [] then
                Nothing

            else
                Just ( List.reverse acc, rest )

        c :: rest ->
            takeUntilBold (c :: acc) rest


{-| Splits at the first occurrence of `delim`, requiring a non-empty body. -}
takeUntil : Char -> List Char -> List Char -> Maybe ( List Char, List Char )
takeUntil delim acc chars =
    case chars of
        [] ->
            Nothing

        c :: rest ->
            if c == delim then
                if acc == [] then
                    Nothing

                else
                    Just ( List.reverse acc, rest )

            else
                takeUntil delim (c :: acc) rest


{-| Parses the remainder of a `[label](url)` link after the opening `[`. -}
takeLink : List Char -> Maybe ( List Char, List Char, List Char )
takeLink chars =
    case takeUntil ']' [] chars of
        Just ( label, afterLabel ) ->
            case afterLabel of
                '(' :: urlRest ->
                    case takeUntil ')' [] urlRest of
                        Just ( url, after ) ->
                            Just ( label, url, after )

                        Nothing ->
                            Nothing

                _ ->
                    Nothing

        Nothing ->
            Nothing


escapeChar : Char -> String
escapeChar c =
    case c of
        '&' ->
            "&amp;"

        '<' ->
            "&lt;"

        '>' ->
            "&gt;"

        '"' ->
            "&quot;"

        _ ->
            String.fromChar c


linkAnchor : ( String, String ) -> String
linkAnchor ( href, label ) =
    """<a href="%HREF%">%LABEL%</a>"""
        |> String.replace "%HREF%" (escape href)
        |> String.replace "%LABEL%" (escape label)


{-| Escapes the HTML special characters so user text can't break the markup. -}
escape : String -> String
escape s =
    s
        |> String.replace "&" "&amp;"
        |> String.replace "<" "&lt;"
        |> String.replace ">" "&gt;"
        |> String.replace "\"" "&quot;"

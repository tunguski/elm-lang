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
`#`/`##`/`###` headings, fenced code blocks, `- ` bullet lists, or paragraphs. Lets a `*.md` file be
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


{-| Renders a page to a complete HTML document. -}
render : Page -> String
render p =
    "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
        ++ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        ++ "<title>"
        ++ escape p.title
        ++ "</title><style>"
        ++ style
        ++ "</style></head><body><main>"
        ++ String.concat (List.map renderBlock p.blocks)
        ++ "</main></body></html>\n"


renderBlock : Block -> String
renderBlock block =
    case block of
        Title n s ->
            let
                tag =
                    "h" ++ String.fromInt n
            in
            "<" ++ tag ++ ">" ++ escape s ++ "</" ++ tag ++ ">"

        Text s ->
            "<p>" ++ escape s ++ "</p>"

        Code s ->
            "<pre><code>" ++ escape s ++ "</code></pre>"

        Bullets items ->
            "<ul>" ++ String.concat (List.map (\i -> "<li>" ++ escape i ++ "</li>") items) ++ "</ul>"

        LinkRow rows ->
            "<p class=\"links\">"
                ++ String.join " · " (List.map (\( href, label ) -> "<a href=\"" ++ escape href ++ "\">" ++ escape label ++ "</a>") rows)
                ++ "</p>"

        Raw s ->
            s

        Group blocks ->
            "<section>" ++ String.concat (List.map renderBlock blocks) ++ "</section>"


{-| Escapes the HTML special characters so user text can't break the markup. -}
escape : String -> String
escape s =
    s
        |> String.replace "&" "&amp;"
        |> String.replace "<" "&lt;"
        |> String.replace ">" "&gt;"
        |> String.replace "\"" "&quot;"


style : String
style =
    "body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;line-height:1.6;color:#0f1720;"
        ++ "background:#eef1f4;margin:0}main{max-width:760px;margin:40px auto;padding:0 20px}"
        ++ "h1{font-size:2rem}h2{margin-top:1.8em}code{background:#e3e8ee;padding:2px 6px;border-radius:4px}"
        ++ "pre{background:#0f1720;color:#e6edf3;padding:14px;border-radius:8px;overflow:auto}"
        ++ "pre code{background:none;padding:0;color:inherit}a{color:#2563eb}.links a{margin:0}"

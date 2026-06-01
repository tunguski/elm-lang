# Static site generation in Elm

`elm gen-site <file.elm> <out-dir>` turns an Elm program into a static website. You describe the
pages as **data** — a `site : List Site.Page` value built with the bundled `Site` library — and the
generator renders each page to a self-contained HTML document, copies in the shared stylesheet, and
writes a `sitemap.xml`. No HTML templating, no JavaScript: the whole site is plain Elm.

This is the same library the project's own showcase site is built with (see
[`examples/site/ElmLang.elm`](../examples/site/ElmLang.elm)).

## The shape of a site

A program exposes `site : List Page`. Each `Page` is a path to write, a title, and a list of content
blocks:

```elm
module Main exposing (site)

import Site exposing (..)


site : List Page
site =
    [ page "index.html"
        "Home"
        [ h1 "Welcome"
        , text "A static site built entirely in Elm."
        , links [ ( "about.html", "About" ) ]
        ]
    , page "about.html"
        "About"
        [ h1 "About"
        , text "Two pages, no JavaScript."
        , link "index.html" "← Home"
        ]
    ]
```

Generate it:

```text
elm gen-site Main.elm out
```

`out/` now contains `index.html`, `about.html`, `site.css` and `sitemap.xml`.

## Content blocks

Build a page's body from these blocks (import `Site exposing (..)` to use them unqualified):

| Builder | Renders | Notes |
|---|---|---|
| `h1`, `h2`, `h3` | `String -> Block` | A heading (`<h1>`/`<h2>`/`<h3>`). |
| `text` | `String -> Block` | A paragraph. |
| `code` | `String -> Block` | An inline `<code>` span on its own line. |
| `codeBlock` | `String -> Block` | A preformatted `<pre><code>` block. |
| `bullets` | `List String -> Block` | An unordered list. |
| `link` | `String -> String -> Block` | A single link (`href`, then label). |
| `links` | `List ( String, String ) -> Block` | A row of links (e.g. a nav bar). |
| `group` | `List Block -> Block` | Wraps blocks in a `<section>`. |
| `raw` | `String -> Block` | Verbatim HTML, inserted unescaped. |
| `markdown` | `String -> List Block` | Converts a Markdown subset into blocks (see below). |

All text in `h1`/`text`/`code`/`codeBlock`/`bullets`/`link` is HTML-escaped for you; only `raw` is
inserted verbatim, so reserve it for HTML you trust.

Because a nav bar is just a value, factor it out and reuse it across pages:

```elm
nav : Block
nav =
    links
        [ ( "index.html", "Home" )
        , ( "about.html", "About" )
        ]


home : Page
home =
    page "index.html" "Home" [ nav, h1 "Welcome", text "…" ]
```

## Markdown

`markdown : String -> List Block` converts a small Markdown subset to blocks, so you can keep prose
in `.md` files and splice it into a page. Blank-line-separated groups become:

- `#`, `##`, `###` → headings
- a ` ``` `-fenced block → a code block
- lines starting with `- ` → a bullet list
- anything else → a paragraph

```elm
page "guide.html" "Guide" (markdown myMarkdownString)
```

Combine it with other blocks by concatenating lists: `nav :: markdown body`.

## API documentation

`--api DIR` (repeatable) additionally documents every `.elm` file under `DIR`: it writes
`api/<Module>.html` for each module and an `api/index.html` that groups the modules by purpose. The
index is rendered through the same `Site` library, so it matches the rest of your site.

```text
elm gen-site Main.elm out --api src --api vendor/elm-lib
```

Link to it from your nav with `( "api/index.html", "API docs" )`.

## Sitemap

Every run writes `sitemap.xml` listing each generated page. By default the URLs are the relative
paths; pass `--base-url` to make them absolute (for submitting to search engines):

```text
elm gen-site Main.elm out --base-url https://example.com/
```

## Styling

Pages link a static `site.css` (copied into the output directory) rather than inlining CSS, so you
get a clean default look and can override it by editing `out/site.css` after generation — or by
serving your own `site.css` of the same name. The HTML is intentionally minimal (`<main>` wrapping
the blocks), which keeps it easy to restyle.

## Command reference

```text
elm gen-site <file.elm> <out-dir> [--api DIR]... [--base-url URL]
```

| Argument | Meaning |
|---|---|
| `<file.elm>` | The site definition; must expose `site : List Site.Page`. |
| `<out-dir>` | Where the pages, `site.css` and `sitemap.xml` are written. |
| `--api DIR` | Document every `.elm` under `DIR` (repeatable). |
| `--base-url URL` | Prefix for `sitemap.xml` URLs (default: relative). |

> **Note:** `elm gen-site` is the general-purpose generator described here. The separate
> `elm site` command builds *this project's* example gallery specifically and is not a
> general tool.

## Generating pages from files on disk

`gen-site` is **pure**: your `site : List Page` is data, computed without touching the filesystem.
That is ideal for a site whose structure is known in the source. When the pages should instead be
*driven by files on disk* — one page per Markdown file in a folder, or a page assembled from files a
JSON manifest names — reach for a **script** (`elm script`) instead. A script can read the
filesystem with the [`Posix`](scripting.md) effects **and** render with the `Site` library (both are
available to scripts), so you read files and write pages in the same program.

### A page per file in a folder

List a directory, read each file, turn its content into a page (here with `Site.markdown`), render
it, and write it out:

```elm
module Main exposing (main)

import Posix exposing (..)
import Site


main : Io
main =
    getArgs
        (\args ->
            case args of
                dir :: _ ->
                    listDir dir
                        (\result ->
                            case result of
                                Ok names ->
                                    generate dir (List.filter (String.endsWith ".md") names)

                                Err e ->
                                    print ("cannot read " ++ dir ++ ": " ++ e) (exit 1)
                        )

                [] ->
                    print "usage: gen-pages <dir>" (exit 1)
        )


generate : String -> List String -> Io
generate dir names =
    case names of
        [] ->
            done

        name :: rest ->
            readFile (dir ++ "/" ++ name)
                (\result ->
                    case result of
                        Ok content ->
                            let
                                slug =
                                    String.replace ".md" ".html" name

                                page =
                                    Site.page slug name (Site.markdown content)
                            in
                            writeFile (dir ++ "/" ++ slug) (Site.render page) (generate dir rest)

                        Err _ ->
                            generate dir rest
                )
```

```text
elm script gen-pages.elm content/
```

Each `content/*.md` becomes a `content/*.html`. To also emit an index linking them, collect the
slugs as you go and write one more page built from `Site.links`.

### Pages driven by a JSON manifest

A common variant: a JSON file on disk describes a *topic* — a subset of the project's source files
that belong together — and you render one page that gathers them. Read the manifest with `Posix`,
decode it with `Json.Decode`, then read the files it names:

```json
{ "title": "Parser internals", "files": [ "src/Lexer.elm", "src/Parser.elm" ] }
```

```elm
module Main exposing (main)

import Json.Decode as D
import Posix exposing (..)
import Site


type alias Topic =
    { title : String, files : List String }


decoder : D.Decoder Topic
decoder =
    D.map2 Topic
        (D.field "title" D.string)
        (D.field "files" (D.list D.string))


main : Io
main =
    getArgs
        (\args ->
            case args of
                manifest :: _ ->
                    readFile manifest
                        (\result ->
                            case result of
                                Ok json ->
                                    case D.decodeString decoder json of
                                        Ok topic ->
                                            readAll topic [] topic.files

                                        Err e ->
                                            print ("bad manifest: " ++ e) (exit 1)

                                Err e ->
                                    print ("cannot read " ++ manifest ++ ": " ++ e) (exit 1)
                        )

                [] ->
                    print "usage: gen-topic <manifest.json>" (exit 1)
        )


readAll : Topic -> List Site.Block -> List String -> Io
readAll topic acc files =
    case files of
        [] ->
            writeFile (slug topic.title)
                (Site.render (Site.page (slug topic.title) topic.title acc))
                done

        path :: rest ->
            readFile path
                (\result ->
                    case result of
                        Ok src ->
                            readAll topic (acc ++ [ Site.h2 path, Site.codeBlock src ]) rest

                        Err _ ->
                            readAll topic acc rest
                )


slug : String -> String
slug title =
    String.replace " " "-" (String.toLower title) ++ ".html"
```

```text
elm script gen-topic.elm topics/parser.json
```

This reads `topics/parser.json`, then each source file it lists, and writes a single
`parser-internals.html` whose body is each file's path (`h2`) followed by its content
(`codeBlock`). Swap `codeBlock` for `markdown`, syntax highlighting, or a summary as you like —
because it is an ordinary script, the page is whatever you compute from what you read.

> The pages link a `site.css`; a script does not copy one in, so either write your own next to the
> output or drop in the bundled stylesheet. (The pure `gen-site` command copies `site.css` for you.)

## Using it in your project

1. Add a module that exposes `site : List Site.Page` (commonly `module Main exposing (site)`).
2. `import Site exposing (..)` — the library is bundled, so there is nothing to install.
3. Describe your pages with the block builders above; factor shared pieces (nav, footer) into
   reusable `Block`/`Page` values like any other Elm.
4. Run `elm gen-site Main.elm out` (add `--api` for reference docs, `--base-url` for a sitemap).
5. Publish the contents of `out/` to any static host (GitHub Pages, Netlify, an S3 bucket, …).

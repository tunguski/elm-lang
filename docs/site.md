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

## Using it in your project

1. Add a module that exposes `site : List Site.Page` (commonly `module Main exposing (site)`).
2. `import Site exposing (..)` — the library is bundled, so there is nothing to install.
3. Describe your pages with the block builders above; factor shared pieces (nav, footer) into
   reusable `Block`/`Page` values like any other Elm.
4. Run `elm gen-site Main.elm out` (add `--api` for reference docs, `--base-url` for a sitemap).
5. Publish the contents of `out/` to any static host (GitHub Pages, Netlify, an S3 bucket, …).

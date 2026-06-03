# Bundled libraries

Beyond the standard library, the toolchain ships a set of Elm modules in
[`src/main/resources/elm/lib/`](../src/main/resources/elm/lib/). They are available to `elm script`,
`elm server`, `elm build` and the test runner without an install, and any project can `import` them.
This page is the index and quick reference; several have a deeper guide of their own.

| Library | Purpose | Deep guide |
|---|---|---|
| [`Posix`](#posix) | Command-line scripts as `Io` effect descriptions | [scripting.md](scripting.md) |
| [`Bash`](#bash) | Shell commands returning structured Elm values | [scripting.md](scripting.md) |
| [`Awk`](#awk) | Build awk program text | [scripting.md](scripting.md) |
| [`M4`](#m4) | Build m4 macro source | [scripting.md](scripting.md) |
| [`Csv`](#csv) | Parse/encode RFC-4180 CSV | [scripting.md](scripting.md) |
| [`Bytes`](#bytes) | Binary data: encode/decode fixed-width ints | — |
| [`List.Extra`](#listextra) | Common list helpers | — |
| [`Maybe.Extra`](#maybeextra) | Common `Maybe` helpers | — |
| [`Result.Extra`](#resultextra) | Common `Result` helpers | — |
| [`String.Extra`](#stringextra) | Common string helpers | — |
| [`Parser`](#parser) | A small parser-combinator library | — |
| [`Url.Parser`](#urlparser) | Typed URL routing | — |
| [`Site`](#site) | Static-site pages as data | [site.md](site.md) |
| [`Server`](#server) | HTTP request/response handlers | [server.md](server.md) |
| [`Build`](#build) | Declarative build lifecycle | [build.md](build.md) |
| [`Test` / `Expect` / `Fuzz`](#test--expect--fuzz) | Unit and property tests | — |

## Posix

A from-scratch take on elm-posix: `main : Posix.Io` *describes* a sequence of effects as data, which
`elm script` performs. `print`/`readLine`/`readFile`/`writeFile`/`getArgs`/`getEnv`/`listDir`/`exit`/
`done`. See [scripting.md](scripting.md).

## Bash

Structured shell commands: `ls`/`find` give `Entry` records, `grep` gives `Match`, `wc` gives
`Counts`, `exec` gives a `Proc { exitCode, stdout, stderr }` — so you process them with ordinary
record/list code instead of re-parsing text. See [scripting.md](scripting.md).

## Awk

*Builds awk program text* to embed in a shell script or pass to `awk` — it does not run awk. Rules
(`begin`/`end`/`on`/`matchLine`/`eachLine`) render via `program`/`oneLiner`/`invocation`; expression
helpers (`field`, `nf`, `nr`, `print`, `assign`, `addTo`, `str`, `call`, `matches`) emit awk text.

```elm
import Awk exposing (..)
-- '{ s += $2 } END { print s }'
oneLiner [ eachLine [ addTo "s" (field 2) ], end [ print [ var "s" ] ] ]
```

## M4

*Builds m4 macro source* (quoted `define`/`undefine`, `call` invocations, a `program` joiner) to save
as a `.m4` file or pipe to `m4`. Body placeholders `arg`/`args`/`argCount`/`macroName` and the
`ifelse`/`ifdef`/`eval`/`include`/`dnl` builtins emit m4 text; `quote` adds `` `…' `` quoting.

```elm
import M4
M4.program [ M4.define "greet" ("Hello " ++ M4.arg 1 ++ "!") ++ M4.dnl, M4.call "greet" [ M4.quote "world" ] ]
```

## Csv

RFC-4180 CSV: `parse` returns rows of fields (honouring quoted fields with embedded commas/newlines),
`encode` is the inverse, and `parseWithHeader` pairs each row with the header columns (records, read
with `get`).

## Bytes

A compact binary-data library — bytes as a list of `0..255` values, so it runs on every backend
without kernel support. [`Bytes`](../src/main/resources/elm/lib/Bytes.elm) carries `width`/`empty` and
the `fromByteValues`/`toByteValues` bridge; [`Bytes.Encode`](../src/main/resources/elm/lib/Bytes/Encode.elm)
builds bytes from fixed-width unsigned ints (`unsignedInt8`/`16`/`32` with an `Endianness`),
`signedInt8`, nested `bytes` and `sequence`; [`Bytes.Decode`](../src/main/resources/elm/lib/Bytes/Decode.elm)
reads them back with `unsignedInt8`/`16`/`32`, `succeed`/`fail`/`map`/`map2`/`andThen` and a `decode`
that returns `Nothing` on overrun.

```elm
import Bytes exposing (Endianness(..))
import Bytes.Encode as E
import Bytes.Decode as D

D.decode (D.unsignedInt16 BE) (E.encode (E.unsignedInt16 BE 258))  --> Just 258
```

## List.Extra

The most-reached-for elm-community/list-extra helpers, in plain Elm: `last`/`init`, `getAt`/`setAt`/
`updateAt`/`removeAt`, `find`/`findMap`/`findIndex`/`elemIndex`/`count`, `splitAt`/`takeWhile`/
`dropWhile`/`span`/`groupsOf`, `group`/`groupWhile`, `unique`/`uniqueBy`, `foldl1`/`foldr1`/`scanl1`/
`scanl`/`indexedFoldl`, `maximumBy`/`minimumBy`, `zip`/`zip3`/`unzip3`, `interweave`/`intercalate`/
`transpose`/`cartesianProduct`, `isPrefixOf`/`isSuffixOf`/`stripPrefix`, `andMap`/`iterate`/`unfoldr`,
`remove`/`swapAt`, `notMember`.

```elm
import List.Extra as LE
LE.groupsOf 2 [ 1, 2, 3, 4, 5 ]  --> [ [ 1, 2 ], [ 3, 4 ], [ 5 ] ]
```

## Maybe.Extra

The most-reached-for elm-community/maybe-extra helpers, in plain Elm: `isJust`/`isNothing`, `join`,
`or`/`orElse`/`oneOf`, `values`/`combine`/`traverse`, `filter`, `unwrap`, `toList`.

```elm
import Maybe.Extra as ME
ME.values [ Just 1, Nothing, Just 3 ]   --> [ 1, 3 ]
ME.combine [ Just 1, Just 2 ]           --> Just [ 1, 2 ]
```

## Result.Extra

The most-reached-for elm-community/result-extra helpers, in plain Elm: `isOk`/`isErr`, `combine`,
`partition`, `mapBoth`, `merge`, `or`/`orElse`, `unwrap`, `extract`.

```elm
import Result.Extra as RE
RE.combine [ Ok 1, Ok 2 ]   --> Ok [ 1, 2 ]
RE.merge (Ok 3)             --> 3
```

## String.Extra

The most-reached-for elm-community/string-extra helpers, in plain Elm: `toSentenceCase`/
`decapitalize`/`toTitleCase`, `isBlank`/`nonEmpty`/`nonBlank`, `clean`, `countOccurrences`,
`surround`/`unsurround`, `ellipsis`, `insertAt`.

```elm
import String.Extra as SE
SE.clean "  a   b  c "        --> "a b c"
SE.ellipsis 8 "a long string" --> "a lon..."
```

## Parser

A small parser-combinator library ([`Parser`](../src/main/resources/elm/lib/Parser.elm)) in the
elm/parser style — building blocks for hand-written parsers.

## Url.Parser

A typed URL router (a subset of elm/url): match fixed segments with `s`, capture `int`/`string`
segments, read query params (`<?>`, see [`Url.Parser.Query`](../src/main/resources/elm/lib/Url/Parser/Query.elm))
and the `fragment`, and combine with `</>`. `parse` runs a parser against a `Url`.

## Site

Static-site pages as data: a `site : List Site.Page` built with `page`/`h1`/`text`/`codeBlock`/
`bullets`/`link`/`markdown`/… is rendered to a website by `elm gen-site`. See [site.md](site.md).

## Server

Server-side Elm: a handler maps a `Request` to a `Response`, with `segments` for routing, `param` for
query parameters and JSON body decoding. Run with `elm server`. See [server.md](server.md).

## Build

A declarative build: `project : Build.Project` runs through a Maven-style lifecycle (validate →
compile → test → package → verify → install), with multi-module projects and custom goals as plain
Elm functions. See [build.md](build.md).

## Test / Expect / Fuzz

The testing trio the `elm test` runner discovers:

- [`Test`](../src/main/resources/elm/lib/Test.elm) — `test`/`describe`/`concat`, `fuzz`/`fuzz2`/
  `fuzz3`/`fuzzWith`, `only`/`skip`/`todo`.
- [`Expect`](../src/main/resources/elm/lib/Expect.elm) — `equal`/`notEqual`/`equalLists`/…,
  `lessThan`/`greaterThan`/`atMost`/`atLeast`/`within`, `isTrue`/`isFalse`, `ok`/`err`, `all`,
  `pass`/`fail`, `onFail`.
- [`Fuzz`](../src/main/resources/elm/lib/Fuzz.elm) — value generators with shrinking: `int`/`float`/
  `string`/`char`/`bool`, `map`/`map2`/`map3`/`andThen`, `pair`/`triple`/`list`/`array`/`maybe`/
  `result`, `oneOf`/`frequency`/`filter`/`constant`.

```elm
import Test exposing (fuzz)
import Fuzz
import Expect

suite = fuzz Fuzz.int "negate self-inverts" (\n -> Expect.equal n (negate (negate n)))
```

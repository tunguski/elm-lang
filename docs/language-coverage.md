# Language coverage

What of Elm this implementation understands — the surface language, the standard library, and where
the support lives. The [README](../README.md#language-coverage) has the at-a-glance table; this page
is the detailed reference. For *which backend* runs each construct see
[the backends guide](backends.md); for the bundled non-core libraries see
[the libraries reference](libraries.md).

Legend: ✅ supported · ⚠️ partial (notes) · ❌ not implemented.

## Surface language

| Construct | Status | Notes |
|---|---|---|
| Module header, `exposing (..)` / explicit, `port module` | ✅ | dotted module names (`Url.Parser`) too |
| Imports: `import M`, `as`, `exposing`, qualified names | ✅ | import-aware unqualified resolution |
| Custom types (`type`), type aliases (`type alias`) | ✅ | record aliases also define a constructor function |
| `type` / `type alias` **inside a `let`** | ✅ | a superset of stock Elm; constructors scope to the rest of the `let` |
| Type annotations on declarations and `let` bindings | ✅ | checked by Hindley–Milner inference |
| Int / Float / String / Char literals, multi-line strings | ✅ | |
| Lists, tuples, unit, records | ✅ | |
| Record access `r.f`, update `{ r \| f = v }`, accessor `.f` | ✅ | access is row-polymorphic (looked up by field) |
| `if`, `let … in`, lambdas, curried & partial application | ✅ | |
| `case … of` patterns: var, wildcard, literal (Int/String/Char), ctor, tuple, list, cons, record, alias (`as`) | ✅ | exhaustiveness-checked |
| Operators with the elm/core fixity table; layout / offside rule | ✅ | |
| User/package-defined infix operators (`(+++) a b = …`) | ✅ | non-standard in app code, but they run here |
| Ports (`Cmd`/`Sub` ports) | ✅ | |
| GLSL shader literals `[glsl\| … \|]` | ⚠️ | lexed/parsed; rendered by the JS/WebGL backend |
| `Debug.todo` / `Debug.log` / `Debug.toString` | ✅ | |

## Standard library

Modules the type checker knows ([`Signatures`](../src/main/java/pl/matsuo/elm/types/Signatures.java))
and the runtime implements ([`Prelude`](../src/main/java/pl/matsuo/elm/interp/Prelude.java) for the
interpreters, [`kernel.js`](../src/main/resources/elm/js/kernel.js) /
[`dom.js`](../src/main/resources/elm/js/dom.js) for the JS backend). The two are kept in lock-step by
a [coverage guard](../src/test/java/pl/matsuo/elm/types/BuiltinSignatureCoverageTest.java) in both
directions.

| Module | Status | Notes |
|---|---|---|
| `Basics` | ✅ | arithmetic, comparison, `modBy`/`remainderBy`, `toPolar`/`fromPolar`, `clamp`, trig, … |
| `List` | ✅ | the usual map/filter/fold/sort family; `(++)` concatenation |
| `String` | ✅ | incl. `toList`/`fromList`/`cons`/`uncons`, `split`/`join`, `toInt`/`toFloat` |
| `Char` | ✅ | `toCode`/`fromCode`, case, `isDigit`/`isAlpha`/…, plus `isSpace`/`isPunctuation`/`isControl` |
| `Maybe`, `Result` | ✅ | map/andThen/withDefault, `map2…` |
| `Tuple` | ✅ | |
| `Dict`, `Set` | ✅ | |
| `Array` | ✅ | list-backed |
| `Json.Decode` / `Json.Encode` | ✅ | decoders run against a real JSON parser |
| `Random`, `Time`, `Task`, `Process` | ✅ | deterministic under the headless driver |
| `Http` | ✅ | `get`/`post`/`request`, `header`, `emptyBody`/`stringBody`/`jsonBody`, `expectString`/`expectJson`/`expectWhatever`, the `Error` constructors |
| `Browser` | ✅ | `sandbox`/`element`/`document`/`application` (the last runs headlessly with a synthetic `Url`/`Key`) |
| `Browser.Dom` | ⚠️ | `getViewport`/`getElement` return fixed boxes headlessly; `focus`/`blur`/`setViewport` are JS-backend effects |
| `Browser.Events`, `Browser.Navigation` | ✅ | subscriptions/keys; navigation is a no-op headlessly |
| `Url`, `Url.Parser`, `Url.Parser.Query` | ✅ | a typed URL router (bundled library) |
| `Html` / `Html.Attributes` / `Html.Events`, `Svg` | ✅ | virtual DOM, rendered to HTML headlessly and to the real DOM by the JS backend |
| `WebGL`, `Math.Vector*`, `Math.Matrix4` | ⚠️ | computed for real by the JS backend; opaque/headless in the interpreter |
| `Bitwise` | ✅ | 32-bit, matching elm/core and JS |
| `Bytes` / `Bytes.Encode` / `Bytes.Decode` | ✅ | compact list-of-bytes [library](libraries.md#bytes) (every backend) |

## Beyond stock Elm

A few deliberate supersets and extras:

- **`type` / `type alias` in a `let`** — handy for local helper types; stock Elm forbids it.
- **User-defined infix operators in application code** — stock Elm reserves these for packages.
- **`Char.isSpace` / `isPunctuation` / `isControl`** — common predicates elm/core omits.
- **Bundled libraries** that ship with the toolchain — `Posix`, `Bash`, `Awk`, `M4`, `Csv`,
  `Bytes`, `List.Extra`, `Site`, `Build`, `Server`, `Test`/`Expect`/`Fuzz` — documented in
  [the libraries reference](libraries.md).

## Not implemented

- Loading third-party **package sources on the WASM backend** (the interpreter, type checker and JS
  backend do resolve, type-check and run installed packages).
- Running GLSL/WebGL shaders outside the browser (the JS backend does the real GPU work).

# elm-lang

[![CI](https://github.com/tunguski/elm-lang/actions/workflows/ci.yml/badge.svg)](https://github.com/tunguski/elm-lang/actions/workflows/ci.yml)
[![Example gallery](https://img.shields.io/badge/gallery-tunguski.github.io%2Felm--lang-5fabdc)](https://tunguski.github.io/elm-lang/)

An implementation of the [Elm](https://elm-lang.org) language in Java 25, built around
**GraalVM Truffle**. It has one front end (lexer + parser) feeding **four backends**:

1. **JIT interpreter** — a Truffle language: the AST is compiled to a tree of `Node`s rooted at
   `RootCallTarget`s, so on GraalVM the Graal compiler partial-evaluates hot functions into
   machine code.
2. **JavaScript compiler** — textual codegen plus a small kernel runtime (functions become native
   curried arrow functions), in the spirit of the official Elm compiler. Bundles multiple modules
   and ships a browser runtime (virtual-DOM diff, effects: Random/Http/Time/Task/File, WebGL).
3. **Bytecode compiler + stack VM** — a compact 24-opcode bytecode and an operand-stack VM.
4. **WebAssembly compiler** — emits a wasm binary (no external assembler) for the integer/boolean/
   **float** fragment plus a growable linear-memory heap (cons-lists, tuples, tagged custom types,
   type-directed **strings**, and self-describing **row-polymorphic records**), and first-class
   functions, **closures and currying** (a uniform closure value + a generic `$apply` runtime,
   lambdas lambda-lifted), with **tail calls** compiled to `return_call` so recursive loops run at
   any depth, so recursive list/ADT, float, string, record, higher-order and curried code compile
   and run anywhere `WebAssembly` does. A second, **WasmGC** code path (`WasmGc`)
   compiles a broad subset to host-garbage-collected `struct`/`array` references — with no linear
   memory and no manual reclamation — covering `Int`/`Bool`/`Float`, `String`, lists of any element,
   tuples, closed records, nullary and **argument-carrying custom types** (including recursive ones)
   and **polymorphic** custom types (monomorphised to their use), including the built-in
   `Maybe`/`Result`. Closures remain on the linear-memory path.

All four share one value model and are **differential-tested** against each other (including
property-based testing over randomly generated expressions — extended to a fifth path, the **WasmGC**
backend, over the fragment it supports — and a non-trivial records/lists/closures "task summary" core
that must agree on all four). A flagship **TodoMVC** app
([`demos/todomvc.elm`](src/main/resources/elm/demos/todomvc.elm)) runs end to end on the two
TEA-capable backends — driven through add/toggle/delete/clear on the interpreter and rendered +
reacted live by the JavaScript backend in headless Chrome.

## Build & test

The project is a single Maven module at the repository root and uses the Maven wrapper
(Maven 3.9.9, GraalVM for JDK 25).

```sh
./mvnw.cmd test        # Windows (PowerShell);  ./mvnw test on Unix
```

## Standalone executable

```sh
./mvnw.cmd -DskipTests package          # builds target/elm.jar (runnable fat JAR)
java -jar target/elm.jar eval "List.foldl (+) 0 (List.range 1 100)"   # -> 5050
java -jar target/elm.jar run Main.elm   # loads the optimizing Truffle (Graal JIT) runtime
```

For a clean run (no GraalVM/`Unsafe` startup warnings), add
`--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow`. The
[`elm.sh`](elm.sh) wrapper already passes these.

A true native binary (instant startup, no JVM) is available via GraalVM `native-image`:

```sh
./mvnw.cmd -Pnative package             # -> target/elm(.exe)
```

This needs a platform C toolchain on `PATH`: on **Windows**, MSVC (run from the
"x64 Native Tools Command Prompt for VS", or with Visual Studio Build Tools installed);
on Linux/macOS, `gcc`/`clang`. The `native` Maven profile is configured (`--no-fallback`,
`-O2`); it was not built in this environment because MSVC is not installed here.

## Run

```sh
# Evaluate an expression (interpreter or bytecode VM)
./mvnw.cmd -q exec:java -Dexec.mainClass=pl.matsuo.elm.Main -Dexec.args="eval \"List.map (\\x -> x*x) [1,2,3]\""

# Run a module's `main` (renders Html / Browser programs to HTML)
java -cp <classpath> pl.matsuo.elm.Main run path/to/Main.elm --backend interp

# Compile a module to JavaScript
java -cp <classpath> pl.matsuo.elm.Main js path/to/Main.elm
```

The [`elm.sh`](elm.sh) wrapper runs any command without the full `java -cp …`
invocation (e.g. `./elm.sh eval "List.range 1 5"`); it compiles the project first if
`Main.class` is missing and caches the classpath for fast subsequent calls.

### CLI commands

Run any of these as `elm <command>` via the [`elm.sh`](elm.sh) wrapper, `java -jar target/elm.jar
<command>`, or the native binary. Pass `--help` to any command for full options.

| Command | What it does |
|---|---|
| `run <file.elm> [--value NAME] [--backend interp\|bytecode] [--watch] [--no-check]` | Evaluate a definition (default `main`) and print it; Html/programs render to HTML. Type-checks first. |
| `eval "<expr>" [--backend interp\|bytecode]` | Evaluate a single expression. |
| `make <file.elm…> [--project DIR] [-o out.html\|out.js] [--optimize] [--cache DIR] [--watch] [--no-check]` | Compile to a deployable HTML page or JS bundle; `--project` pulls in an `elm.json`'s local + installed-dependency sources; `--cache` reuses per-module compiled output (recompiling only changed modules); `--optimize` tree-shakes + minifies. |
| `js <file.elm> [--min] [--map]` | Emit JavaScript (optionally minified, with an inline column-level source map). |
| `check <file.elm> [more.elm…]` | Type-check a module or a multi-module project. |
| `test <file.elm…> [--fuzz N] [--seed S] [--filter TEXT] [--coverage] [--report FMT]` | Run `Test` suites (bundled `Test`/`Expect`/`Fuzz`): unit and **property (`fuzz`)** tests; reports pass/fail/skipped with timing and the failing fuzz input, non-zero exit on failure. `--fuzz` sets inputs per property, `--seed` makes them reproducible, `--filter` runs only matching tests, `--coverage` reports which test-file functions ran, `--report` chooses the output (`console`/`tap`/`junit`/`json`) for CI. |
| `format <file.elm> [--write \| --check \| --project]` | Format (elm-format style); `--check` gates CI. |
| `lint <file.elm…>` | Report leftover `Debug.*` and unused definitions (non-zero exit on findings). |
| `docs <file.elm> [--json]` / `docs --pkg author/name [--pkg-version V] [--elm URL]` | Generate API docs from a local module's doc comments + inferred types (Markdown, or structured `docs.json`), **or fetch a published package's `docs.json`** from the registry (default the public package.elm-lang.org; latest version unless pinned). |
| `diff <old.elm> <new.elm>` | Compare two versions of a module's public API → the semver magnitude (MAJOR/MINOR/PATCH) and the changes. |
| `bump <old.elm> <new.elm> [version]` | Propose the next version from the API change since a baseline. |
| `coverage <file.elm> [--value NAME]` | Run a definition and report which top-level definitions executed. |
| `repl` | Interactive REPL: expressions, persistent `x = …` definitions, multi-line input, and `:type`, `:load <file.elm>` (bring a module's definitions into scope), `:history`, `:reset`. |
| `lsp` | Language server over stdio: diagnostics (errors **and warnings**: unused imports, unused private definitions, parameters and `let` bindings), hover, completion (with inferred-type detail), **inlay type hints**, **signature help**, document symbols, code actions (add type annotation, fill missing case branches, remove/add/organize imports), **"extract to function"** and **"inline"** refactors (a selected expression becomes a fresh top-level function with its free locals lifted to parameters; inline does the reverse), **call hierarchy** (incoming/outgoing calls across modules), **code lenses** (reference counts per definition), **document formatting** (elm-format style), **document highlight** (every occurrence of the symbol under the cursor), semantic-token highlighting, **workspace symbol search**, and **workspace-wide** go-to-definition, find-references and rename across modules. A ready-to-run **[VS Code client](editor/vscode/)** wraps it (with format-on-save). See **[docs/lsp.md](docs/lsp.md)** for the full capability list, editor setup (VS Code / Neovim / generic) and protocol notes. |
| `script <file.elm> [args…]` | Run an Elm file as a POSIX-style CLI script (the bundled `Posix` module). |
| `server <file.elm> [--port N] [--static DIR]` | Serve HTTP from an Elm handler (stateless `handle` or stateful `Server.Program`). |
| `reactor [dir] [--port N]` | Dev server: compiles each `.elm` module to a live page on the fly and hot-reloads the browser when a source file changes; a compile or **located type error** (excerpt + caret) is shown as a styled overlay that disappears once fixed. |
| `project <elm.json\|dir> [check\|run]` | Load an `elm.json` project and check or run it. |
| `init [dir]` | Scaffold `elm.json` + `src/`. |
| `install <author/name> [--registry DIR] [--from URL] [--elm [URL]]` | Add a package to `elm.json`, re-solve dependencies, and download its sources into the cache (`--elm` = the public package.elm-lang.org registry) so it compiles and runs. |
| `bench [fibN]` | Benchmark the four backends on a recursive workload. |
| `site <examplesDir> <Playground.elm> <outDir> [docsDir]` | Generate the static example gallery (optionally rendering Markdown docs). |

The compiled TEA runtime also ships a **time-travel debugger**: append `?debug` to a page URL for a
step-back/forward overlay; `window.$app` exposes `history()`, `goto(i)`, `live()`, `messages()` and
`replay(log)` (deterministic re-fold of a recorded message log). The gallery includes a
[JS-vs-WASM page](https://tunguski.github.io/elm-lang/backends.html), an interactive
[playground](https://tunguski.github.io/elm-lang/playground.html), a live
[TodoMVC](https://tunguski.github.io/elm-lang/todomvc.html), and a reusable
[editor](https://tunguski.github.io/elm-lang/editor.html) that **fetches the example files over HTTP
at startup** (every gallery example plus its own demos, served as raw `.elm` under `examples/`) and
runs each selected file's `main` live in the browser — static views, computed values, and interactive
`Browser.sandbox`/`Browser.element` apps with `onClick`/`onInput`, tuples, `List`/`Maybe` helpers and
opaque `Cmd`/`Sub`, all via a from-scratch Elm interpreter written in Elm (itself a `Browser.element`
app using `Http`). Its lexer/parser handle `--` line comments, the pipe/compose operators
(`|>`, `<|`, `>>`, `<<`, desugared to application/lambdas), multi-binding `let`, record `type alias`
constructors (`Point 3 4`), negative literals in argument position (`zigzag -2 …`), the common
`Html.Attributes` (`placeholder`, `type_`, `value`, `class`, `href`, …) and `Svg`/`Svg.Attributes`
(so the **shapes** and **clock** examples render as inline SVG), `String.*`, `Time` (UTC), `Basics`
math (`cos`/`sin`/`pi`/`toFloat`/…), and a built-in **`elm-playground`** (`picture` and `animation`,
with the shape constructors, colours and transforms rendered to SVG) — enough that every pure-Html /
TEA / SVG / `picture` / `animation` example from the elm-lang.org gallery parses and renders. It also
runs **effects live**: `Random.generate` (sampled from a seed the editor advances — so **numbers**
rolls a die and **cards** draws on click), `Time.every` subscriptions (wired to a real tick — so
the **clock** ticks), `elm-playground`'s interactive **`game`** (a keyboard + animation-frame
loop, so **turtle**/**keyboard**/**mario** move with the arrow keys; tuple-pattern parameters like
`view computer (x, y) = …` are desugared so they parse), and **`Http.get`** (the editor issues a real
request and feeds the response back, so the **book** loads live via `Http.expectString` and **quotes**
via `Http.expectJson` — a small `Json.Decode` of `field`/`string`/`int`/`map2..map4` runs against the
parsed body). It also has a **time-travel debugger**: every message the running app dispatches is
recorded, and a scrubber re-renders any earlier model (dispatching from a past state continues
history from there). Examples that need WebGL or `File` effect runtimes parse but fall outside this
teaching-subset interpreter.

## Type inference

A from-scratch **Hindley–Milner** type checker ([`pl.matsuo.elm.types`](src/main/java/pl/matsuo/elm/types/)),
in the style of Algorithm W. Highlights:

- **Constrained type variables** — `number`, `comparable`, `appendable` (so `1 + 1.5 : Float`,
  `List.map : (a -> b) -> List a -> List b`).
- **Let-generalization** in dependency order (Tarjan SCCs), **row-polymorphic records**, custom
  types, record-alias constructors, type-alias expansion, and annotation checking.
- **Exhaustiveness & redundancy** for `case` (Maranget's usefulness algorithm), reporting a witness
  of the missing input (e.g. `Missing a branch for: Blue`) and unreachable branches.
- **Elm-style errors**: a located source excerpt with a caret under the offending sub-expression,
  the mismatch phrased as **expected `X` but got `Y`**, a tailored hint (number↔String coercion,
  `number`/`comparable`/`appendable` constraints, …), field-by-field **record mismatches** with a
  **"Did you mean `field`?"** suggestion for a misspelled field, and **"Did you mean …?"** for
  misspelled names. The LSP surfaces the same diagnostics in-editor.
- **Multi-error recovery**: a failed top-level definition is bound to a fresh polymorphic type and
  inference carries on, so one pass reports *every* independent type error (in source order) rather
  than stopping at the first — and a clean caller of a broken definition isn't flagged with a
  cascade error. The CLI prints them as one `Found N type errors:` report; the LSP publishes a
  separate diagnostic per error.
- **Multi-module/project** checking (`check a.elm b.elm …`) across module boundaries.
- Catches `1 + "a"`, a non-`Bool` `if`, `\f -> f f`, unknown names, etc.

The prelude signatures cover elm/core plus the `Html`/`Svg`/`Browser`/`Events`/`Dom`, effect
(`Cmd`/`Sub`/`Random`/`Time`/`Task`/`Http`/`Json`/`File`), collection and
`Math.*`/`WebGL`/`WebGL.Texture` builtins the examples use, so **all 21 single-module
elm-lang.org examples type-check end to end**. `run` and `make` **type-check by default** (pass
`--no-check` to skip; a checker *limitation* on a program it can't fully analyze is non-fatal and
falls through to evaluation).

## Language coverage

| Feature | Status | Notes |
|---|---|---|
| Modules, imports (`as`, `exposing (..)`), qualified names | ✅ | |
| Custom types, type aliases (incl. record constructors), annotations | ✅ | |
| Literals, lists, tuples, records (access / update / `.field` accessor) | ✅ | |
| `if`, `let`, lambdas, curried application | ✅ | |
| `case` with full patterns (ctor / tuple / list / cons / record / alias / literal / as) | ✅ | exhaustiveness-checked |
| Operators with the elm/core fixity table; layout / offside rule | ✅ | |
| **Ports** (`port module`, `Cmd`/`Sub` ports) | ✅ | |
| **User/package-defined infix operators** (`(+++) a b = …`) | ✅ | non-standard in app code; here they run |
| The Elm Architecture (`Browser.sandbox`/`element`/`document`), virtual-DOM | ✅ | with a time-travel debugger |
| Effects: `Random`, `Time`, `Task`, `Http`, `File`, `Browser.Events`/`Dom` | ✅ | |
| WebGL (`Math.Vector*`/`Matrix4`, shaders, textures) | ✅ | renders in a real `<canvas>` |
| Third-party packages from the registry | ⚠️ | `elm install` solves, downloads (`--from`) and the interpreter/type-checker compile & run them; public registry + JS/WASM loading pending — see Known limitations |
| GLSL custom binary operators from packages (`\|.`, `</>`) | ⚠️ | lex & parse; run only if you define them |

**Prelude**: `Basics`, `List`, `String`, `Char`, `Maybe`, `Result`, `Tuple`, `Dict`, `Set`,
`Array`, `Debug`; `Html`/`Html.Attributes`/`Html.Events`, `Svg`/`Svg.Attributes`, `Browser`
(`sandbox`/`element`/`document`), `Cmd`/`Sub`, `Random`, `Time`, `Task`, `Http`, `Json.Decode`/
`Json.Encode`, `Url`, `WebGL`/`WebGL.Texture`/`Math.*`.

**Effects outside the browser**: `elm run`/`project run` execute a `Browser` program's initial
commands for real — an `Http.get` performs an actual `java.net.http` request and `Random` is seeded
non-deterministically — so an effectful program renders its fetched result headlessly. (Tests use an
offline driver, so they stay deterministic and never touch the network.)

## Scripting (POSIX-style)

`elm script <file.elm> [args…]` runs an Elm file as a command-line script on the JIT interpreter,
inspired by [elm-posix](https://github.com/albertdahlin/elm-posix). The bundled
[`Posix`](src/main/resources/elm/lib/Posix.elm) module gives a script's `main : Posix.Io`
a description of effects — `print`, `readLine`, `readFile`, `writeFile`, `getArgs`, `getEnv`,
`listDir`, `exit`, `done` — which the runner walks, performing the real I/O and returning the
process exit code. Effects that
produce a value take a continuation, so scripts are written in continuation-passing style. The
[`wordcount.elm`](src/main/resources/elm/demos/wordcount.elm) example is a `wc`-style
line/word/char counter:

```sh
./elm.sh script wordcount README.md   # 'wordcount' resolves to the bundled demo; or pass a path
```

## HTTP server (server-side Elm)

`elm server <file.elm> [--port N]` serves HTTP from an Elm application that exposes a pure handler,
using the bundled [`Server`](src/main/resources/elm/lib/Server.elm) API:

```elm
import Server exposing (..)

handle : Request -> Response
handle req =
    case segments req of
        [ "ping" ] -> text "pong"
        [ "hello" ] -> text ("Hello, " ++ Maybe.withDefault "world" (param "name" req))
        [ "users", id ] -> json ("{\"id\":\"" ++ id ++ "\"}")   -- path parameter
        _ -> notFound
```

The runner (on the JDK's built-in HTTP server, no dependencies) builds a `Request` for each
incoming request — method, path, parsed `query` parameters, body — applies `handle` on the JIT
interpreter, and writes the `Response`. `segments` gives the path parts for routing (capturing path
parameters via `case`), `param` looks up a query parameter, and a JSON body is decoded with the
`Json.Decode` module. Because the handler is a pure `Request -> Response`, it is trivial to
unit-test — `ServerRunnerTest` checks routing, path/query parameters and status by direct dispatch
and also over a real socket. See [`simple-server-showcase.elm`](src/main/resources/elm/demos/simple-server-showcase.elm).

For **stateful** servers, expose `main : Server.Program model` instead — an in-memory `model`, an
`onRequest : Request -> model -> ( model, Response )`, and a background `onTick : model -> model`
run every `tickMillis`. The runner holds the model (guarded across the request and tick threads).
The [`live-dashboard.elm`](src/main/resources/elm/demos/live-dashboard.elm) example is a full-stack
demo: an in-memory time series advanced by a **server-side seeded random walk** on every tick, with
the Elm server also serving its own client — an HTML page, a stylesheet, and JavaScript that polls
`/api/series` once a second and draws the series as an SVG graph (`elm server live-dashboard`).

`elm server <app> --static <dir>` additionally serves text files (HTML/CSS/JS/JSON/SVG) from a
directory before falling through to the Elm handler (path traversal is refused).

## Packages & dependencies

The standard library is **built in**: every prelude module (`elm/core`, `elm/html`, `elm/browser`,
`elm/json`, `elm/time`, `elm/url`, the WebGL/playground builtins the examples use …) is provided
directly by the interpreter, type checker and JS kernel. On top of that there is a **working
package manager** for everything outside the bundled set:

- **Solving.** `elm install <author/name>` adds a package to your `elm.json` and re-solves the
  dependency set with a real semantic-version model, Elm-style `LOWER <= v < UPPER` constraints, and
  a **backtracking constraint solver** that pins one compatible version of every package in the
  transitive closure (highest allowed, backing off on conflict).
- **Cache.** Packages live in an on-disk cache laid out as
  `<root>/<author>/<name>/<version>/{elm.json, src/…}` (default `$ELM_REGISTRY` or `~/.elm/registry`).
- **Download.** Two remote protocols are supported. `--from <url>` uses a tiny static-file protocol
  (`versions.txt`, per-version `elm.json`, `files.txt`); `--elm [url]` (default the **public**
  `package.elm-lang.org`) speaks the real registry shape — `all-packages` for the version index,
  `packages/<a>/<n>/<v>/elm.json` for constraints, and `endpoint.json` → a GitHub **zipball** that is
  downloaded and unpacked (top-level dir stripped, zip-slip guarded) into the cache.
- **Compilation.** `project`/`check`/`run` *and* the **JavaScript backend** (`make --project` /
  `js`) load the resolved dependencies' modules from the cache alongside your local
  `source-directories`, so an installed package's modules are handed to the **same type checker,
  interpreter and JS compiler** as your own code — `import`s of it resolve, type-check, run and
  compile into the bundle. (Built-in packages are skipped to avoid double-defining the standard
  library.)
- **Publishing.** `docs --json` emits a standard-format `docs.json` (a JSON array of module objects,
  each carrying its doc `comment`, exposed values with inferred types, custom types, aliases and a
  `binops` list — the shape package.elm-lang.org serves); `diff` compares two API versions into a semver magnitude
  (a removed/changed entry is MAJOR, an addition MINOR, nothing PATCH); `bump` proposes the next
  version from that magnitude — Elm's API-driven semver, enforced from the code.

What remains is the WASM backend loading package sources (the others now do), and registry niceties
like checksum verification and `elm.json` `test-dependencies`.

## Performance (JIT benchmark)

`elm bench [fibN]` times a hot, call-heavy workload across backends. On GraalVM the Truffle
interpreter's hot `CallTarget`s are partial-evaluated and compiled by the Graal compiler, so it
outruns the simple bytecode VM. Measured here (GraalVM CE 25, `fib(30)`, best of 50 warm runs):

| backend | cold | warm (best) |
|---|---|---|
| Truffle interpreter | 550 ms | **469 ms** |
| Bytecode VM | 769 ms | 762 ms |

The Graal-compiled Truffle interpreter is ~**1.6×** the bytecode VM after warm-up.

## elm-lang.org examples

See [docs/examples.md](docs/examples.md). **All 27** run headlessly with tests:

- **HTML**: Hello, Groceries, Shapes
- **User Input**: Buttons, Text Fields, Forms
- **Random**: Numbers, Cards, Positions
- **Time**: Time, Clock
- **HTTP**: Book, Quotes (against stubbed responses)
- **Files**: Upload, Drag-and-Drop, Image Previews (with stub files / `File.toUrl`)
- **Playground**: Picture, Animation, Mouse, Keyboard, Turtle, Mario — these load the **real
  unmodified [evancz/elm-playground](https://github.com/evancz/elm-playground) source** (1700+
  lines) via the module system and render to SVG.
- **WebGL**: Triangle, Cube, Crate, Thwomp, First Person — the programs run (building meshes,
  computing `Math.Matrix4`/`Math.Vector3` transforms, assembling entities, loading stub textures)
  and emit a `<canvas>` describing the scene.

Interactive examples are driven by a headless `Tea` runtime that dispatches messages through
`update` and re-renders the virtual DOM to HTML/SVG; effects (`Random`, `Task`, `Http`, `File`,
`Time`) are interpreted deterministically with stubs.

### Caveat on WebGL

The WebGL examples execute correctly and produce the scene structure + a `<canvas>`, but **actual
rasterized pixels need a real GPU/WebGL context**, which a headless JVM cannot provide. Verifying
pixels would require running the JS backend in a real browser.

## Example gallery

A static gallery of the **JavaScript-compiled** examples is published to
**<https://tunguski.github.io/elm-lang/>** by the [Pages workflow](.github/workflows/pages.yml).
For each example it emits a self-contained live demo page plus a wrapper that shows the demo (with
an "open in a new tab" link) next to its syntax-highlighted Elm source. **All 27 examples run as
live compiled JavaScript** — the Playground games are bundled multi-module with the real
evancz/elm-playground source, and the WebGL examples render into a real `<canvas>`. A separate
[**JS vs WASM**](https://tunguski.github.io/elm-lang/backends.html) page runs Elm snippets
through both compiled backends in the browser, side by side — `Int`, `Float`, `String` and `List`
results, where the non-numeric ones cross the wasm boundary as heap pointers the page decodes from
linear memory (string `{len, bytes}`, cons-list cells, Float bit-patterns). Build it locally with:

```sh
./mvnw.cmd -DskipTests package
java -jar target/elm.jar site src/test/resources/examples src/test/resources/Playground.elm target/site
```

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) builds and runs the full test suite on
GraalVM for JDK 25 (via the Maven wrapper) for every push and pull request, with real headless
**Chrome** installed so the browser-fidelity tests run. A separate
[`native.yml`](.github/workflows/native.yml) builds the GraalVM native binary on Linux and
publishes it as an artifact.

## Known limitations

This section states plainly what the implementation does **not** do or does only partially. (For
what each backend *does* support, see the architecture overview at the top of this README and the
[Language coverage](#language-coverage) section.) Nothing here is a silent failure — an unsupported
construct is rejected with a clear "unsupported" error, not miscompiled.

**Backends — what runs where.** The **interpreter** and **JavaScript** backends are full (TEA apps,
the effect set — Random/Http/Time/Task/File/WebGL — virtual-DOM, etc.). The two **WebAssembly**
backends compile **pure computation only** — they evaluate functions and data, *not* `Browser`
programs or effects. Run interactive/effectful programs on the interpreter or JS.

- **WASM (linear-memory) backend** — does **not** support: effects/TEA, the full standard library
  (only a prelude of `List`/`Maybe`/`Result` helpers compiles — most `String`, `Dict`, `Set`,
  `Array` and `Regex` operations are absent), or returning compound values to the host as anything
  but an opaque heap pointer. `++`/`==` require operands statically typed (no fully
  polymorphic `==`). Loading installed **package sources** is not wired up for this backend.
- **WasmGC backend** — does **not** yet support: **closures / higher-order functions** (so
  `List.map`, `foldl`, lambdas don't compile here — they run on the linear-memory backend or JS);
  **row-polymorphic / open records** (only closed records with a fixed field set); a single
  polymorphic union used at **two different representations** in one module (it is monomorphised to
  one; the second use is rejected); a **code-point-aware `String`** (length is byte/ASCII-correct
  only) and most of the `String`/`Dict`/`Array` API.
- **Packages / registry** — `elm install` resolves and downloads from a static-file registry
  (`--from`) or the public `package.elm-lang.org` (`--elm`), and the interpreter/type-checker/JS
  backend compile installed modules; but there is **no checksum verification**, **no
  test-dependencies**, and the **WASM backends can't load package sources**. See
  [Packages & dependencies](#packages--dependencies).

**Type system**
- Inference is **Hindley–Milner for the supported subset**. It does **not** implement real Elm's
  fully extensible-records-everywhere typing or kind checking; it is sound for what it accepts, but a
  program leaning on those subtleties may be rejected. `run`/`make` fall back to evaluation if the
  checker can't analyze a program. (In practice it type-checks every elm-lang.org example and the
  full ~1700-line evancz/elm-playground end to end.)

**WebGL**
- The textured WebGL examples need a **real GPU and a real browser**; cross-origin textures are
  vendored into the gallery, and verifying rasterized pixels can't be done headlessly.

**Editor (in-browser Elm-in-Elm interpreter)**
- The live editor interprets a subset: it does **not** run WebGL or `File` effects. Its
  `Json.Decode` covers the common combinators (`string`/`int`/`float`/`bool`/`field`/`succeed`/
  `map`–`map4`/`list`/`andThen`/`oneOf`/`nullable`) — not the full decoder API.

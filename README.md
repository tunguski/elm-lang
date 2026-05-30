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
4. **WebAssembly compiler** — emits a wasm binary (no external assembler) for the numeric/boolean
   fragment plus a growable linear-memory heap (cons-lists, tuples, tagged custom types, **strings**
   and **records**, the last two type-directed), first-class top-level functions (funcref table +
   `call_indirect`), so recursive list/ADT, string, record and higher-order code compile and run
   anywhere `WebAssembly` does.

All four share one value model and are **differential-tested** against each other (including
property-based testing over randomly generated expressions).

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
| `make <file.elm…> [-o out.html\|out.js] [--optimize] [--watch] [--no-check]` | Compile to a deployable HTML page or JS bundle; `--optimize` tree-shakes + minifies. |
| `js <file.elm> [--min] [--map]` | Emit JavaScript (optionally minified, with an inline column-level source map). |
| `check <file.elm> [more.elm…]` | Type-check a module or a multi-module project. |
| `test <file.elm…>` | Run `Test` suites (bundled `Test`/`Expect`); reports pass/fail, non-zero exit on failure. |
| `format <file.elm> [--write \| --check \| --project]` | Format (elm-format style); `--check` gates CI. |
| `lint <file.elm…>` | Report leftover `Debug.*` and unused definitions (non-zero exit on findings). |
| `docs <file.elm>` | Generate Markdown API docs from doc comments + inferred types. |
| `coverage <file.elm> [--value NAME]` | Run a definition and report which top-level definitions executed. |
| `repl` | Interactive REPL: expressions, persistent `x = …` definitions, `:type`, multi-line input. |
| `lsp` | Language server over stdio (diagnostics, hover, completion, document symbols, code actions, semantic-token highlighting, and **workspace-wide** go-to-definition, find-references and rename across modules). |
| `script <file.elm> [args…]` | Run an Elm file as a POSIX-style CLI script (the bundled `Posix` module). |
| `server <file.elm> [--port N] [--static DIR]` | Serve HTTP from an Elm handler (stateless `handle` or stateful `Server.Program`). |
| `project <elm.json\|dir> [check\|run]` | Load an `elm.json` project and check or run it. |
| `init [dir]` | Scaffold `elm.json` + `src/`. |
| `install <author/name> [--registry DIR] [--from URL]` | Add a package to `elm.json`, re-solve dependencies, and (with `--from`) download its sources into the cache so it compiles and runs. |
| `bench [fibN]` | Benchmark the four backends on a recursive workload. |
| `site <examplesDir> <Playground.elm> <outDir> [docsDir]` | Generate the static example gallery (optionally rendering Markdown docs). |

The compiled TEA runtime also ships a **time-travel debugger**: append `?debug` to a page URL for a
step-back/forward overlay; `window.$app` exposes `history()`, `goto(i)`, `live()`, `messages()` and
`replay(log)` (deterministic re-fold of a recorded message log). The gallery includes a
[JS-vs-WASM page](https://tunguski.github.io/elm-lang/backends.html), an interactive
[playground](https://tunguski.github.io/elm-lang/playground.html), and a multi-file
[editor](https://tunguski.github.io/elm-lang/editor.html) running in the browser.

## Type inference

A from-scratch **Hindley–Milner** type checker ([`pl.matsuo.elm.types`](src/main/java/pl/matsuo/elm/types/)),
in the style of Algorithm W. Highlights:

- **Constrained type variables** — `number`, `comparable`, `appendable` (so `1 + 1.5 : Float`,
  `List.map : (a -> b) -> List a -> List b`).
- **Let-generalization** in dependency order (Tarjan SCCs), **row-polymorphic records**, custom
  types, record-alias constructors, type-alias expansion, and annotation checking.
- **Exhaustiveness & redundancy** for `case` (Maranget's usefulness algorithm), reporting a witness
  of the missing input (e.g. `Missing a branch for: Blue`) and unreachable branches.
- **Elm-style errors**: a source excerpt, a caret under the offending sub-expression, the location,
  a hint, and **"Did you mean …?"** suggestions for misspelled names.
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
- **Download.** With `--from <url>` the solver runs against a remote registry (a tiny static-file
  protocol — `versions.txt`, per-version `elm.json`, `files.txt`) and the resolved packages' sources
  are downloaded into the cache.
- **Compilation.** `project`/`check`/`run` load the resolved dependencies' modules from the cache
  alongside your local `source-directories`, so an installed package's modules are handed to the
  **same type checker and interpreter** as your own code — `import`s of it resolve, type-check and
  run. (Built-in packages are skipped to avoid double-defining the standard library.)

What remains is integrating the **public** `package.elm-lang.org` registry (its GitHub-zipball
download protocol differs from the simple static-file one above) and the JS/WASM backends loading
package sources the way the interpreter and type checker now do.

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
[**JS vs WASM**](https://tunguski.github.io/elm-lang/backends.html) page runs numeric snippets
through both compiled backends in the browser, side by side. Build it locally with:

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

- **Package manager — public registry**: `elm install` solves dependencies, downloads sources from
  a remote registry (`--from`), and the interpreter + type checker compile and run installed
  packages' modules. The remaining gaps are the **public `package.elm-lang.org`** protocol (it
  serves GitHub zipballs, not the simple static-file layout used here) and the **JS/WASM backends**
  loading package sources the way the interpreter/checker now do. See
  [Packages & dependencies](#packages--dependencies).
- **WASM backend** scope: numbers/booleans, a growable linear-memory heap for cons-lists, tuples,
  tagged custom types, **strings** and **records** (the last two type-directed — record access needs
  a known closed type, and `++`/`==` need operands statically typed `String`), plus first-class
  **top-level functions** (a funcref table + `call_indirect`, so higher-order code over named
  functions works). Still not in WASM: **floats, closures** (capturing locals) and **currying /
  partial application** — those remain on the JS backend.
- The full **1700-line elm-playground** still hits a few type-inference edge cases (every
  single-module elm-lang.org example type-checks); `run`/`make` fall through to evaluation when the
  checker can't fully analyze a program.
- The textured **WebGL** examples need a real GPU and same-origin images (cross-origin textures are
  vendored into the gallery); verifying rasterized pixels requires a real browser.
- **Type inference** is HM without records-as-extensible-everywhere subtleties of real Elm and
  without kind checking; it's sound for the supported subset.

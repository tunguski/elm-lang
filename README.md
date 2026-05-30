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
   `Int`/`Bool` fragment; runs anywhere `WebAssembly` does.

All four share one value model and are **differential-tested** against each other (including
property-based testing over randomly generated expressions).

## Build & test

The project lives in [`elm-lang/`](elm-lang/) and uses the Maven wrapper (Maven 3.9.9, GraalVM
for JDK 25).

```sh
cd elm-lang
./mvnw.cmd test        # Windows (PowerShell);  ./mvnw test on Unix
```

## Standalone executable

```sh
cd elm-lang
./mvnw.cmd -DskipTests package          # builds target/elm.jar (runnable fat JAR)
java -jar target/elm.jar eval "List.foldl (+) 0 (List.range 1 100)"   # -> 5050
java -jar target/elm.jar run Main.elm   # loads the optimizing Truffle (Graal JIT) runtime
```

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

CLI commands: `run <file.elm> [--backend interp|bytecode] [--value NAME] [--strict]`,
`js <file.elm> [--min] [--map]`, `eval "<expr>" [--backend ...]`,
`check <file.elm> [more.elm …]` (type-check a module or project), `repl`, `lsp`
(language server over stdio), `project <elm.json|dir> [check|run]`, `bench [fibN]`,
`site <examplesDir> <Playground.elm> <outDir>`.

It also ships a **REPL** (`elm repl`), a **language server** (`elm lsp` — diagnostics
+ hover types, reusing the parser and HM checker), `elm.json` **project mode**, and JS
**source maps** (`js --map`). The gallery includes a [JS-vs-WASM
page](https://tunguski.github.io/elm-lang/backends.html) and an interactive
[playground](https://tunguski.github.io/elm-lang/playground.html) running both compiled
backends in the browser.

## Type inference

A from-scratch **Hindley–Milner** type checker ([`pl.matsuo.elm.types`](elm-lang/src/main/java/pl/matsuo/elm/types/))
infers types for expressions and whole modules, with Elm's constrained type variables
(`number`/`comparable`/`appendable`), let-generalization, row-polymorphic records, custom types,
record-alias constructors, type-alias expansion and annotation checking. So `1 + 1.5 : Float`,
`List.map : (a -> b) -> List a -> List b`, and mistakes like `1 + "a"`, a non-`Bool` `if`
condition, or `\f -> f f` are reported as type errors. It runs via `check` / `TypeChecker`; it is
not wired as a mandatory pass before evaluation. The prelude signatures cover elm/core plus the
`Html`/`Svg`/`Browser`/`Events`/`Dom`, effect (`Cmd`/`Sub`/`Random`/`Time`/`Task`/`Http`/`Json`/
`File`), collection and `Math.*`/`WebGL`/`WebGL.Texture` builtins the examples use, so **all 21
single-module elm-lang.org examples type-check end to end** (`ModuleCheckTest`). It also does
**multi-module/project** checking — `TypeChecker.checkProject` (CLI `check a.elm b.elm …`) orders
modules by their imports and resolves names, constructors and aliases across module boundaries.
Error messages are Elm-style: a source excerpt, a caret under the offending sub-expression, the
location and a hint. `run <file> --strict` type-checks before evaluating and refuses to run on a
type error.

## Language coverage

Modules, imports (incl. `exposing (..)`, aliases), custom types, type aliases (incl. record
constructors), type annotations; expressions: literals, lists, tuples, records (access/update/
accessor), `if`, `case` (full patterns: ctor/tuple/list/cons/record/alias/literal), `let`,
lambdas, curried application, operators with the elm/core fixity table, qualified names. Layout
is handled by the offside rule.

Prelude: `Basics`, `List`, `String`, `Char`, `Maybe`, `Result`, `Tuple`, `Dict`, `Set`,
`Array`, `Debug`, plus
`Html`/`Html.Attributes`/`Html.Events`, `Svg`/`Svg.Attributes`, `Browser`
(`sandbox`/`element`/`document`), `Cmd`/`Sub`, `Random`, `Time`, `Task`, `Http`, `Json.Decode`.

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
cd elm-lang
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

- Type inference (Hindley–Milner, see above) is **not a mandatory pass** before evaluation
  (`run --strict` opts in). `check` handles single modules and multi-module projects
  (`check a.elm b.elm`); the full 1700-line elm-playground still hits inference edge cases, though
  every single-module example type-checks.
- The **WASM backend** covers the numeric/boolean `Int`/`Bool` fragment only — lists, strings,
  records and effects (which need a heap) are left to the JS backend.
- The textured WebGL examples depend on cross-origin images and a real GPU; `first-person` waits on
  asset/viewport state. Custom package infix operators (`|.`, `</>`, …) lex and parse but their
  defining package isn't bundled for execution.

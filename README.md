# elm-lang

An implementation of the [Elm](https://elm-lang.org) language in Java 25, built around
**GraalVM Truffle**. It has one front end (lexer + parser) feeding **three backends**:

1. **JIT interpreter** — a Truffle language: the AST is compiled to a tree of `Node`s rooted at
   `RootCallTarget`s, so on GraalVM the Graal compiler partial-evaluates hot functions into
   machine code.
2. **JavaScript compiler** — textual codegen plus a small kernel runtime (functions become native
   curried arrow functions), in the spirit of the official Elm compiler.
3. **Bytecode compiler + stack VM** — a compact 24-opcode bytecode and an operand-stack VM.

All three share one value model and are **differential-tested** against each other.

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

CLI commands: `run <file.elm> [--backend interp|bytecode] [--value NAME]`, `js <file.elm>`,
`eval "<expr>" [--backend ...]`, `check <file.elm>` (type-check and print inferred types).

## Type inference

A from-scratch **Hindley–Milner** type checker ([`pl.matsuo.elm.types`](elm-lang/src/main/java/pl/matsuo/elm/types/))
infers types for expressions and whole modules, with Elm's constrained type variables
(`number`/`comparable`/`appendable`), let-generalization, row-polymorphic records, custom types,
record-alias constructors, type-alias expansion and annotation checking. So `1 + 1.5 : Float`,
`List.map : (a -> b) -> List a -> List b`, and mistakes like `1 + "a"`, a non-`Bool` `if`
condition, or `\f -> f f` are reported as type errors. It runs via `check` / `TypeChecker`; it is
not yet wired as a mandatory pass before evaluation (the prelude signatures cover the core library,
not yet every Html/WebGL builtin).

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

## Known limitations

- No type inference: numeric literals default to `Int`; use float literals in `Float` contexts.
- Operator characters exclude `.`, so dot-operators (e.g. elm/parser's `|.`) aren't lexed.

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
`eval "<expr>" [--backend ...]`.

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

See [docs/examples.md](docs/examples.md). **19 of 27** pass headlessly with tests:

- **HTML**: Hello, Groceries, Shapes
- **User Input**: Buttons, Text Fields, Forms
- **Random**: Numbers, Cards, Positions
- **Time**: Time, Clock
- **HTTP**: Book, Quotes (against stubbed responses)
- **Playground**: Picture, Animation, Mouse, Keyboard, Turtle, Mario — these load the **real
  unmodified [evancz/elm-playground](https://github.com/evancz/elm-playground) source** (1700+
  lines) via the module system and render to SVG.

Interactive examples are driven by a headless `Tea` runtime that dispatches messages through
`update` and re-renders the virtual DOM to HTML/SVG; commands (`Random`, `Task`, `Http`) and
`Time` subscriptions are interpreted deterministically.

### Not yet working

- **Files** (Upload, Drag-and-Drop, Image Previews): need real browser file selection / async
  `File.toUrl` tasks.
- **WebGL** (Triangle, Cube, Crate, Thwomp, First Person): need a GPU/WebGL context.

These require executing in a **real browser** via the JS backend extended with the full Elm
effect-manager kernel — a planned next phase, not achievable in a headless JVM.

## Known limitations

- No type inference: numeric literals default to `Int`; use float literals in `Float` contexts.
- Operator characters exclude `.`, so dot-operators (e.g. elm/parser's `|.`) aren't lexed.

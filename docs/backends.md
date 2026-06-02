# Backends

One front end (lexer + parser + Hindley–Milner type checker) feeds **five** execution backends. They
share a single value model and are kept honest by [cross-backend differential
tests](../src/test/java/pl/matsuo/elm/DifferentialPropertyTest.java). This guide compares them: what
each runs, how to invoke it, and when to reach for it. See also the
[language coverage reference](language-coverage.md).

## At a glance

| Backend | Invoke | Language fragment | Effects | Best for |
|---|---|---|---|---|
| **Truffle interpreter** | `run` / `eval` / `repl` / `script` / `server` / `test` (default) | the full language | headless (real `Http`/`Random`/files via Java) | development, scripting, tests — the reference semantics |
| **Bytecode VM** | `run --backend bytecode`, `eval --backend bytecode` | the full language | headless | a compact, allocation-light alternative interpreter |
| **JavaScript** | `make` / `js` (`-o out.js` / HTML) | the full language | the browser: virtual-DOM, Random/Http/Time/Task/File, WebGL | shipping apps to the web |
| **WASM (linear memory)** | `wasm` (`-o out.wasm`) | a broad runtime subset (below) | none (pure compute) | portable numeric/list/record/string compute |
| **WasmGC** | `bench`, differential tests (library code path `WasmGc`) | a broad subset on GC refs | none | GC-managed structured compute without manual memory |

All five are exercised together by `elm bench` (fib, list-fold, record-update) and the differential
property suite.

## Truffle interpreter (the reference)

The AST is compiled to a tree of Truffle `Node`s rooted at call targets, so on GraalVM the Graal
compiler partial-evaluates hot functions into machine code; arithmetic/comparison use self-specializing
DSL nodes (`+ - * // < == …`). This is the default and the **source of truth** for semantics: every
other backend is differential-tested against it. It runs the whole language and performs real effects
headlessly (`elm run`/`script`/`server` do actual HTTP, file and time I/O; tests use a deterministic
offline driver).

## Bytecode VM

A compact 24-opcode bytecode compiled from the same AST and run on an operand-stack VM, with tail
calls (`TAIL_CALL`) so deep recursion doesn't overflow. Same language and semantics as the
interpreter (they are differential-tested against each other), with a different performance profile —
useful as an allocation-light alternative and a check on the interpreter.

## JavaScript

Textual codegen plus a small kernel runtime (`kernel.js`/`dom.js`); functions become native curried
arrow functions, in the spirit of the official Elm compiler. It bundles multiple modules and ships a
browser runtime: virtual-DOM diff for The Elm Architecture (`Browser.sandbox`/`element`/`document`/
`application`), and real effects — `Random`, `Http` (full request builder), `Time`, `Task`, `File`,
`Browser.Dom`/`Events`, and WebGL rendering to a real `<canvas>`. This is the backend for deploying
to the web; `make --optimize` tree-shakes and minifies.

## WASM — linear memory

Emits a WebAssembly binary directly (no external assembler) for a broad runtime subset:

- `Int` / `Bool` / `Float` (an `f64` carried in the uniform `i64`),
- a growable linear-memory heap holding **cons-lists**, **tuples**, **tagged custom types**,
  type-directed **strings**, and self-describing **row-polymorphic records**,
- **first-class functions** — closures and currying via a uniform closure value + a generic `$apply`
  runtime, with lambdas lambda-lifted,
- **tail calls** compiled to `return_call`, so recursive loops run at any depth,
- `case` over `[]`/`::`, tuples, custom-type tags and **scalar (Int/Char) literals**,
- list `(++)` concatenation, and **partially-applied constructors** (a constructor used as a value
  becomes a closure over a synthesised wrapper).

So recursive list/ADT, float, string, record, higher-order and curried code compiles and runs
anywhere `WebAssembly` does. It performs no effects — it is pure computation.

## WasmGC

A second WASM code path that targets host-**garbage-collected** `struct`/`array` references — no
linear memory, no manual reclamation. It covers `Int`/`Bool`/`Float`, `String`, lists of any element,
tuples, closed records, nullary and **argument-carrying custom types** (including recursive and
**polymorphic** ones, monomorphised to their uses — including built-in `Maybe`/`Result`), `case` over
all of those plus scalar literals, list `(++)`, and **first-class functions** for unary top-level
functions and capture-free lambdas (`ref.func`/`call_ref`, so higher-order `map`/`filter` over GC
lists work). Capturing lambdas and currying still need the linear-memory path.

## Choosing

- **Writing/iterating, scripts, servers, tests** → the interpreter (default). Real effects, full
  language, best diagnostics.
- **Shipping a web app** → the JavaScript backend (`make`).
- **Portable pure compute** (sandbox, plugin, edge) → linear-memory WASM, or WasmGC where a
  host-GC'd value model is preferable and you don't need capturing closures.
- **A second opinion on semantics / allocation profile** → the bytecode VM.

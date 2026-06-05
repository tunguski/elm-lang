# Splitting large source files

This is a design note, not a task list. It looks at every source file currently over **1000 lines**
and proposes how it could be split into smaller, cohesive files. The goal is *not* to hit a line
count — it is to cut each file along the seams that already exist in it, so that each resulting file
has one responsibility and the boundaries between them carry real meaning. Where a file is large but
*coherent* (one job, done in one place), the honest recommendation is to leave it alone.

You can regenerate the list this doc is based on with the bundled script:

```bash
elm script BigFiles            # files over 1000 lines, largest first
```

## Principles used here

- **Cut along responsibility seams, not at line numbers.** A good boundary is one where the two
  sides talk to each other through a small, namable interface (a few functions, one shared map),
  not one where a helper on each side reaches into the other's internals.
- **Keep mutually-recursive cores whole.** A tree-walking evaluator, a Pratt expression parser, or a
  pattern-match compiler are webs of small functions that call each other over shared state.
  Splitting *inside* such a web creates churn and import noise for no clarity. Split *around* it.
- **Prefer "extract a cohesive island" over "halve the file".** The best candidates are
  self-contained sub-systems (a vendored stdlib string, a hand-assembled runtime, an optimisation
  pipeline) that depend on little and are depended on narrowly.
- **Preserve the public surface.** Splitting should be invisible to callers: the same module/class
  name keeps the same exported functions; new files are package-private (Java) or internal modules
  re-exported by the original (Elm).
- **Don't split vendored code.** Files we mirror from upstream stay one file, or we knowingly fork.

A note on language mechanics:

- **Java** makes this easy: move an inner class to its own package-private top-level class, or move a
  cluster of `static` methods into a `final` helper class that writes into the same shared maps. No
  visible API change.
- **Elm** is stricter: a module's split halves must not import each other in a cycle, and every
  helper crossing the new boundary has to become an explicit `exposing` entry. That cost is the main
  reason some Elm cuts below are recommended cautiously.

---

## The files

Line counts and status as of 2026-06-05. ✅ done, 🟡 partially done (an island already
extracted; more proposed below), ⬜ not started, ⏸ deliberately left whole.

| File | Lines | Recommendation | Status |
|------|-------|----------------|--------|
| `editor/Eval.elm` | ~3910 | **Split** — 6 modules along interpreter / stdlib / app / effects / playground / json | 🟡 `EvalRender` + `EvalPlayground` extracted; Core/Builtins/App/Json remain |
| `wasm/WasmCompiler.java` | ~2767 | **Split** — extract prelude, string runtime, binary encoding; keep the codegen core | 🟡 `WasmPrelude` + `WasmEncoding` extracted; string runtime remains |
| `lsp/LspServer.java` | ~2602 | **Split** — transport vs. analysis vs. code-actions/refactors | ⬜ |
| `wasm/WasmGc.java` | ~2440 | **Split** — extract the type registry and the shared encoding; keep `Gen` | 🟡 `WasmEncoding` shared; `Tuples` type registry remains |
| `Main.java` | ~1898 | **Split** — one file per CLI command group + a shared support file | ⬜ |
| `interp/Prelude.java` | ~967 | **Split** — one class per Elm module group (cleanest of all) | ✅ `PreludeCollections` + `PreludeJson` + `PreludeCore` extracted; now under threshold |
| `examples/Playground.elm` | ~1708 | **Leave** — vendored elm-playground; splitting forks upstream | ⏸ |
| `editor/Editor.elm` | ~1370 | **Split** — app/update vs. view vs. session vs. html-bridge | ⬜ |
| `js/JsCompiler.java` | ~1239 | **Partial** — extract the optimiser pipeline; keep codegen together | ✅ `JsOptimizer` + `JsRuntime` extracted; remainder coherent |
| `test/WasmHeapTest.java` | ~1221 | **Split** — by feature area, with a shared test-helper base | ⬜ |
| `test/EditorInterpreterTest.java` | ~1066 | **Watch** — cohesive editor-interpreter suite; split by feature only if it keeps growing | ⬜ |

`parser/Parser.java` has dropped to ~995 (below the 1000-line threshold): `OperatorFixities`
was extracted, and the recursive-descent core is intentionally kept whole — so it no longer
appears above.

---

### `editor/Eval.elm` (~4344) — the biggest, and a clean split 🟡 in progress

Being done incrementally, one cycle-free module at a time (Elm forbids import cycles, and the
evaluator core is one mutually-recursive web). `EvalRender` (pure display) is extracted; the leaf
bands that call back into the evaluator (`Eval.Playground`, the `Json.Decode` interpreter) will take
`applyValue`/`mainValue` as parameters rather than importing the core, and `Eval` re-exposes the
public functions via thin aliases. (The line ranges below are approximate and predate recent edits —
vector math, Random combinators, the `EvalRender` extraction.)

`Eval.elm` is the in-browser editor's Elm-in-Elm interpreter. It has grown to hold five jobs that
only share the `Value`/`Globals`/`Env` types and the central `evalExpr`/`applyValue` pair. Those
jobs are visible as contiguous bands in the file:

1. **`Eval.Core`** — `evalExpr` (≈232–529), pattern matching/`matchPattern`/`evalCase` (≈2276–2416),
   operators/`applyOp`/equality (≈2417–2570), `applyValue` (≈537–567), and `lookup`. *Why together:*
   these are one mutually-recursive evaluator; every arrow between them is a hot call, not an API.
   This module owns the `Value` type and stays the dependency root.
2. **`Eval.Builtins`** — the `builtins`/`arity` tables (≈17–231), `runBuiltin` (≈910–2007), and the
   collection implementations it dispatches to: Dict/Set/Array (≈568–909) and the polymorphic
   list combinators `mapValues`/`foldlValues`/… (≈2007–2274). *Why this seam:* `runBuiltin` is a giant
   `case name of` that is conceptually the "standard library", distinct from the evaluator that *calls*
   it. It depends on `Eval.Core` (to apply closures) but `Eval.Core` never calls it back — a clean
   one-way edge.
3. **`Eval.App`** — the Elm-Architecture glue: `hasApp`, `appInit`/`appUpdate`/`appView` (≈2782–2939),
   the effect handlers `randomCmd`/`httpCmd`/`fileSelectCmd`/`taskResult` (≈2962–3119), and the
   time-travel `debugSteps` (≈2695–2760). *Why:* this is the bridge the editor drives every frame; it
   is a layer *on top of* evaluation and changes for UI reasons, not language reasons.
4. **`Eval.Json`** — the hand-rolled JSON parser/serialiser and the `Json.Decode`/`Encode`
   interpreter (≈3119–3574). *Why:* it is a self-contained codec with one entry point (`runDecoder`,
   `jsonEncode`) used only by `Eval.App` (HTTP) and a few `runBuiltin` cases. It touches nothing else.
5. **`Eval.Playground`** ✅ — elm-playground shape construction, SVG rendering, and the game/animation
   loop now live in `editor/EvalPlayground.elm`. *Why:* a closed world — shapes in, SVG out. It needs
   the evaluator only to apply a game's `view`/`update` and resolve `main`, so `applyValue`/`mainValue`
   are passed into `gameView`/`gameStep`/`gameInitMem` as parameters (no import back into `Eval`);
   everything else is pure. `Eval` re-exposes the game functions via thin wrappers. ~464 lines.
6. **`Eval.Render`** ✅ — the pure display helpers `renderValue` + the Html-value→string
   `htmlToString`/`attrKey` now live in `editor/EvalRender.elm` (flat module name to match the editor's
   flat, module=filename convention; `Eval` re-exposes `renderValue` via a thin alias since Elm has no
   re-export). *Why:* display logic with no dependency on evaluation — the cleanest, injection-free
   leaf. `renderProgram` stays in the core (it orchestrates init/view, i.e. it evaluates).

`Eval` itself becomes a thin module re-exposing the ~25 public functions so `Editor.elm` is
untouched. The risk to watch: keep `Eval.Core` free of imports from the other five so there is no
cycle. `runBuiltin` is the one place that may need a function passed in (to evaluate closures) rather
than importing `Eval.App`.

### `wasm/WasmCompiler.java` (~2767) — extract the islands, keep the engine 🟡 partial

Three large parts of this file are only loosely attached to the actual compiler:

- **`WasmPrelude`** ✅ — the `WASM_PRELUDE` Elm-source string and the `PRELUDE_NAMES` map (~320 lines
  of data) now live in `wasm/WasmPrelude.java`. *Why:* it is data, not logic; it changes when we add a
  stdlib function, a different cadence from changing codegen.
- **`WasmStringRuntime`** ⬜ — the hand-assembled native functions `stringRuntime()` and their entry
  builders (`strToListEntry`, `strReverseEntry`, `strConcatEntry`, …, ~900 lines) **remain**. *Why
  extract:* raw-bytecode emitters that depend only on the encoding helpers (`leb`/`sleb`/`entry`) — the
  file's most self-contained island and the part least related to compiling Elm ASTs. The next cut here.
- **`WasmEncoding`** ✅ (shared — see cross-cutting note) — `leb`/`sleb`/`section`/`name`/`nameSection`
  now in `wasm/WasmEncoding.java`, shared with `WasmGc`.

What stays in `WasmCompiler`: the `FunctionGen` inner class (≈769–1918) — the expression→bytecode
compiler — together with `compileModules`/`assemble` and the lambda-lifting pass. `FunctionGen` is a
mutually-recursive web over the shared `funcs`/`ctorTag`/`nodeTypes` maps (`intExpr` ↔ `intApp` ↔
`intCase` ↔ `tailExpr`); cutting inside it would scatter that web. It could become its own
top-level package-private `FunctionGen.java`, but its halves should not be split further.

### `wasm/WasmGc.java` (~2440) — lift out the type registry 🟡 partial

The WasmGC backend's natural seam is the **`Tuples`** inner class (≈812–1105): the struct/type
registry that assigns stable indices to every cons/tuple/record/closure shape. It is a cohesive
data structure with its own helpers and no dependency on codegen — a clean `WasmGcTypes.java`. ⬜
**remains.** The `StructDef`/`W` type model (≈140–178) goes with it. The `Gen` inner class is the
codegen engine and, like `FunctionGen`, stays whole. The `leb`/`sleb`/`section`/`name` helpers ✅ have
already moved to the shared `WasmEncoding`.

### `interp/Prelude.java` (~967, under threshold) — the cleanest split of all ✅

`Prelude` is one `static` class that registers ~400 builtins into three shared maps (`BUILTINS`,
`UNQUALIFIED`, `CTOR_ARITY`) from a static initialiser. Crucially, the `registerXxx()` methods **do
not call each other** — each is ~80–150 self-contained lines keyed by Elm module. That makes it the
textbook candidate: move each group into its own package-private class that registers into the same
maps, leaving `Prelude` as the initialiser that calls them.

Proposed grouping (by how often they change together, not one-class-per-method):

- `PreludeCore` ✅ — Basics, List, String, Char, Bitwise (the high-traffic core), now in
  `interp/PreludeCore.java`. The shared helpers it needed (`basics`/`javaList`/`ordering`/`split` +
  the `UNQUALIFIED` map) became package-private; this single ~595-line cut took Prelude under 1000.
- `PreludeCollections` ✅ — Array, Dict, Set, now in `interp/PreludeCollections.java`.
- `PreludeData` — Maybe, Result, Tuple, Debug, constructors. ⬜
- `PreludeEffects` — Cmd/Sub, Random (incl. the seeded `stepGen` cluster), Time, Task,
  Browser.Events. *Why kept together:* `registerEffects` and `stepGen` share the `advance`/
  `scrambleSeed` helpers — the one genuinely coupled sub-system here. ⬜
- `PreludeJson` ✅ — Json.Decode/Encode, Url, Navigation, Storage and `decodeErrorToString` (with the
  Url helpers `parseUrl`/`percentEncode`/`urlToString`) now live in `interp/PreludeJson.java`. *Why:*
  the decoder and its error renderer are a bound pair, and the Url helpers are used only here. (The
  shared `d` data-builder became package-private for it.)
- `PreludeHtml` — registerHtml/registerSvg/registerBrowser and the tag/attr tables. ⬜
- `PreludeMedia` — WebGL, Math (Vec/Mat), Regex, File. ⬜

The only shared surface is the handful of one-liners `fn`/`basics`/`just`/`d`/`isJust`/`isOk`, which
become `static` helpers on a small `PreludeSupport`.

### `Main.java` (~1898) — one class per command, already

`Main` is a picocli CLI whose body is **33 independent `@Command` static inner classes** (`Run`, `Js`,
`Make`, `Eval`, `Script`, `Serve`, `Bundle`, `TestCmd`, `Docs`, `Lsp`, `Format`, `Build`, …). They
share no state — only three helpers (`readElmSource`, `typeError`, `render`, ≈1793–1859) and a couple
of text templates. picocli registers subcommands by class, so each command can live in its own file
with no behaviour change.

Rather than 33 tiny files, group by domain so related commands sit together:

- `cmd/CompileCommands` — Run, Eval, Js, Wasm, Bytecode, Make, Bundle.
- `cmd/CheckCommands` — Check, Lint, Format, Doctest, Repl.
- `cmd/ServeCommands` — Serve, Reactor, Script.
- `cmd/SiteCommands` — Site, GenSite, Gallery, Docs.
- `cmd/PackageCommands` — Install, Upgrade, Uninstall, Outdated, Verify, Diff, Bump, Publish, Init.
- `cmd/TestCommands` — TestCmd, CoverageCmd, Bench.
- `cmd/CliSupport` — the shared `readElmSource`/`typeError`/`render` + templates.

`Main` keeps only the root `@Command`, the exception handler, and `main()`. *Why group rather than
one-per-file:* the seam that matters is "what part of the toolchain does this drive", and commands in
a group tend to change together (e.g. all the package commands when the registry format moves).

### `js/JsCompiler.java` (~1239) — extract only the optimiser ✅ done

Most of this file is one tightly-woven codegen pipeline: expression `compile` ↔ pattern `matchJs` ↔
the TCO path `compileNamedFunction` all share the local-scope stack and temp counter. That web stays.
The cleanly separable part is the **post-processing pipeline** — `minify`, `treeShake`, `pruneKernel`,
`balancedLine` (≈414–565): pure `String → String` passes with no dependency on the compiler instance.
These now live in `JsOptimizer.java`, and the runtime kernel in `JsRuntime.java` — the remainder is
the coherent codegen pipeline, left whole as recommended. (A further multi-module caching layer
`JsBundleCache` was noted as an optional second candidate; not pursued.)

### `editor/Editor.elm` (~1370) — separate UI from orchestration

`Editor.elm` mixes the TEA wiring with a large view layer. The seam is clear because the view
functions only *read* the model:

- **`Editor`** — `Model`/`Msg` (≈35–85), `program`, `subscriptions` (≈121–219), `update` and its
  execution helpers `stepApp`/`runCmd`/`refreshAndRun` (≈317–724). The orchestration core.
- **`Editor.View`** — `view`, `codeEditor` + gutter/squiggle/completion, `fileSidebar`, the result
  `mainPane`/`appPane`/`gamePane`, and the time-travel `debugBar` (≈781–1153). *Why:* pure
  `Model → Html Msg` rendering; it never updates state.
- **`Editor.Session`** — autosave/restore and the file-list helpers (≈726–779). Small but
  self-contained persistence logic.
- **`Editor.Render`** — `renderHtml`/`renderAttr`, the bridge from interpreted `Value` Html to real
  `Html Msg` with editor event wiring (≈1170–1231). *Why:* the one place that re-wires interpreted
  events back into `Msg`; isolating it documents that boundary.

`Editor.View`/`Render` import `Editor` for the `Model`/`Msg` types; `Editor` must not import them
back (move the few shared layout constants down, not up) to avoid a cycle.

### `parser/Parser.java` (~995, now under threshold) — extract the edges, keep the descent ✅ done

A recursive-descent + Pratt parser is the canonical "leave the core whole" case: `parseExpr`,
`parseApplication`, `parseAtom`, `parsePattern`, and `parseType` all advance the same `tokens`/`p`
cursor and call one another freely. Splitting that across files would mean threading a shared
`ParserState` through dozens of methods purely to satisfy the file boundary — cost without clarity.

What *can* leave cleanly:

- **`OperatorFixities`** ✅ — the static `FIXITY` table, `scanFixities`, `scanInfixDeclarations` now
  live in `parser/OperatorFixities.java`. Pure precedence data + a pre-scan, independent of the cursor.
- **Layout helpers** — `atNewLine`/`continues`/`withIndent` and the error-recovery
  `recoverToNextTopLevel` could become a small `Layout` mixin, though the payoff is modest; left in place.

The modest recommendation was taken: fixities extracted, and the ~900-line descent core kept as one
coherent unit (the file is now ~995 lines, below the threshold). Forcing a 50/50 split here would
make the parser harder to read, not easier — exactly the "random split" this doc exists to avoid.

### `test/WasmHeapTest.java` (~1202) — split by feature, share the harness

This is a test file, and tests split painlessly because each `@Test` is independent. The clusters are
already obvious: standard-library (lists/strings/maybe), float arithmetic, core language (patterns,
recursion), records, higher-order/closures, and the property-based RNG tests. The shared harness —
`agrees`, `runMain`, `runMainString`, `decodeList`, `agreesFloat`, `NODE` detection (≈24–135, 245–350)
— moves to a `WasmTestSupport` base class, and the clusters become `WasmStringTest`,
`WasmRecordTest`, `WasmHigherOrderTest`, etc. *Why bother for tests:* faster targeted runs and a
clearer map of what the WASM backend guarantees, with no production risk.

### `test/EditorInterpreterTest.java` (~1066) — watch, don't split yet

New over the threshold (it grew as the editor gallery examples gained interpreter-fidelity regression
tests — Mouse colours, Upload `multiple`, FirstPerson vectors, Positions `Random`, Thwomp textures).
It is still **one cohesive suite**: every `@Test` exercises the same `Eval`/`Editor` interpreter
through the shared `evalProject`/`files`/`renderGame` helpers. If it keeps growing, the natural cut
mirrors `WasmHeapTest`: an `EditorInterpreterTestSupport` base holding the helpers, with the `@Test`s
split into language-core, gallery-examples, and lexer/format clusters. Until then, leaving it whole
keeps the editor's behavioural contract in one readable place.

### `examples/Playground.elm` (~1708) — do not split

This is **vendored** evancz/elm-playground, kept as a test resource so the gallery's Playground
examples compile against the real library. It has clean internal seams (entry points, transforms,
colours, rendering) — but splitting it would fork it from upstream, and every future sync would have
to be re-applied across our pieces. The right move is to leave it whole and treat its size as the
cost of vendoring. If we ever *do* want it modular, that should be a deliberate, documented fork.

---

## Cross-cutting: the duplicated WASM encoder ✅ done

`WasmCompiler.java` and `WasmGc.java` used to carry **byte-identical** copies of `leb`, `sleb`,
`section`, and `name`. These now live in a single `wasm/WasmEncoding.java` that both backends call —
the duplication hazard (a LEB128 fix needed twice) is gone. This was the recommended first step and
de-risked the remaining WASM islands.

## Suggested sequencing

Ordered by payoff-to-risk, easiest and safest first. ✅ = completed.

1. ✅ **`WasmEncoding`** extraction — done; removed the duplicated encoder.
2. ✅ **`Prelude.java`** — `PreludeCollections` (Array/Dict/Set), `PreludeJson` (Json/Url/Nav/Storage)
   and `PreludeCore` (Basics/List/String/Char/Bitwise) extracted; now ~967 lines (under threshold).
   The remaining groups (Data, Effects, Html, Media) could still be split for cleanliness, but the
   size target is met.
3. ⬜ **`Main.java`** — independent command classes; pure file moves into a `cmd/` package.
4. ⬜ **`WasmHeapTest.java`** — test-only, no production risk.
5. 🟡 **`WasmCompiler` / `WasmGc`** islands — `WasmPrelude` extracted; the hand-assembled string
   runtime (`WasmCompiler`) and the `Tuples` type registry (`WasmGc`) remain.
6. ⬜ **`Eval.elm`** and **`Editor.elm`** — highest value but need care with Elm import cycles; do the
   evaluator/UI split first and the finer cuts only if they still feel warranted afterwards.
7. ✅ **`JsCompiler` / `Parser`** — `JsOptimizer`/`JsRuntime` and `OperatorFixities` extracted; the
   codegen and recursive-descent cores are intentionally left whole.

Each step should be a behaviour-preserving move validated by the existing test suite before the next.
The measure of success is not the line count afterwards — it is whether a newcomer can guess which
file a given change belongs in.

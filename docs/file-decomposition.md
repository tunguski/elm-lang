# Splitting large source files

This is a design note, not a task list. It looks at every source file currently over **1000 lines**
and proposes how it could be split into smaller, cohesive files. The goal is *not* to hit a line
count — it is to cut each file along the seams that already exist in it, so that each resulting file
has one responsibility and the boundaries between them carry real meaning. Where a file is large but
*coherent* (one job, done in one place), the honest recommendation is to leave it alone.

You can regenerate the list this doc is based on with the bundled script:

```bash
elm script big-files            # files over 1000 lines, largest first
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

| File | Lines | Recommendation |
|------|-------|----------------|
| `editor/Eval.elm` | ~4195 | **Split** — 6 modules along interpreter / stdlib / app / effects / playground / json |
| `wasm/WasmCompiler.java` | ~3215 | **Split** — extract prelude, string runtime, binary encoding; keep the codegen core |
| `lsp/LspServer.java` | ~2602 | **Split** — transport vs. analysis vs. code-actions/refactors |
| `wasm/WasmGc.java` | ~2461 | **Split** — extract the type registry and the shared encoding; keep `Gen` |
| `interp/Prelude.java` | ~2138 | **Split** — one class per Elm module group (cleanest of all) |
| `Main.java` | ~1860 | **Split** — one file per CLI command group + a shared support file |
| `test/Playground.elm` | ~1708 | **Leave** — vendored elm-playground; splitting forks upstream |
| `js/JsCompiler.java` | ~1397 | **Partial** — extract the optimiser pipeline; keep codegen together |
| `editor/Editor.elm` | ~1230 | **Split** — app/update vs. view vs. session vs. html-bridge |
| `test/WasmHeapTest.java` | ~1202 | **Split** — by feature area, with a shared test-helper base |
| `parser/Parser.java` | ~1083 | **Partial** — extract fixities + layout; keep the recursive-descent core |

---

### `editor/Eval.elm` (~4195) — the biggest, and a clean split

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
5. **`Eval.Playground`** — elm-playground shape construction, SVG rendering, and the game/animation
   loop `gameInitMem`/`gameStep` (≈3766–4196). *Why:* a closed world — shapes in, SVG out — that only
   needs `applyValue`. The single most extractable band in the file.
6. **`Eval.Render`** — `renderValue`/`renderProgram` and the Html-value→string helpers
   (≈2569–2658, 3611–3763). *Why:* display logic, used by the REPL path and the editor's result pane.

`Eval` itself becomes a thin module re-exposing the ~25 public functions so `Editor.elm` is
untouched. The risk to watch: keep `Eval.Core` free of imports from the other five so there is no
cycle. `runBuiltin` is the one place that may need a function passed in (to evaluate closures) rather
than importing `Eval.App`.

### `wasm/WasmCompiler.java` (~3215) — extract the islands, keep the engine

Three large parts of this file are only loosely attached to the actual compiler:

- **`WasmPrelude`** — the `WASM_PRELUDE` Elm-source string and the `PRELUDE_NAMES` map (≈93–411,
  ~320 lines of data). *Why:* it is data, not logic; it changes when we add a stdlib function, which
  is a different cadence from changing codegen. Moving it also makes it diffable without scrolling
  past bytecode emitters.
- **`WasmStringRuntime`** — the hand-assembled native functions `stringRuntime()` and their entry
  builders (`strToListEntry`, `strReverseEntry`, `strConcatEntry`, …) (≈1928–2823, ~900 lines). *Why:*
  these are raw-bytecode emitters that depend only on the encoding helpers (`leb`/`sleb`/`entry`).
  They are the file's most self-contained island and the part least related to compiling Elm ASTs.
- **`WasmEncoding`** (shared — see cross-cutting note) — `leb`/`sleb`/`section`/`name`/`nameSection`
  (≈3057–3214). *Why:* pure binary-format plumbing, byte-identical to the copies in `WasmGc.java`.

What stays in `WasmCompiler`: the `FunctionGen` inner class (≈769–1918) — the expression→bytecode
compiler — together with `compileModules`/`assemble` and the lambda-lifting pass. `FunctionGen` is a
mutually-recursive web over the shared `funcs`/`ctorTag`/`nodeTypes` maps (`intExpr` ↔ `intApp` ↔
`intCase` ↔ `tailExpr`); cutting inside it would scatter that web. It could become its own
top-level package-private `FunctionGen.java`, but its halves should not be split further.

### `wasm/WasmGc.java` (~2461) — lift out the type registry

The WasmGC backend's natural seam is the **`Tuples`** inner class (≈812–1105): the struct/type
registry that assigns stable indices to every cons/tuple/record/closure shape. It is a cohesive
data structure with its own helpers and no dependency on codegen — a clean `WasmGcTypes.java`.
The `StructDef`/`W` type model (≈140–178) goes with it. The `Gen` inner class (≈1109–2264) is the
codegen engine and, like `FunctionGen`, stays whole. The `leb`/`sleb`/`section`/`name` helpers
(≈2423–2459) are the same duplicated encoding utilities and should move to the shared `WasmEncoding`.

### `interp/Prelude.java` (~2138) — the cleanest split of all

`Prelude` is one `static` class that registers ~400 builtins into three shared maps (`BUILTINS`,
`UNQUALIFIED`, `CTOR_ARITY`) from a static initialiser. Crucially, the `registerXxx()` methods **do
not call each other** — each is ~80–150 self-contained lines keyed by Elm module. That makes it the
textbook candidate: move each group into its own package-private class that registers into the same
maps, leaving `Prelude` as the initialiser that calls them.

Proposed grouping (by how often they change together, not one-class-per-method):

- `PreludeCore` — Basics, List, String, Char, Bitwise (≈1410–2004; the high-traffic core).
- `PreludeCollections` — Array, Dict, Set (≈130–475).
- `PreludeData` — Maybe, Result, Tuple, Debug, constructors (≈2006–2138, 1397–1408).
- `PreludeEffects` — Cmd/Sub, Random (incl. the seeded `stepGen` cluster), Time, Task,
  Browser.Events (≈475–562, 564–1040). *Why kept together:* `registerEffects` and `stepGen` share
  the `advance`/`scrambleSeed` helpers — the one genuinely coupled sub-system here.
- `PreludeJson` — Json.Decode/Encode, Url, Navigation, Storage, plus `decodeErrorToString`
  (≈795–1290). *Why:* the decoder and its error renderer are a bound pair.
- `PreludeHtml` — registerHtml/registerSvg/registerBrowser and the tag/attr tables (≈1292–1395).
- `PreludeMedia` — WebGL, Math (Vec/Mat), Regex, File (≈602–764).

The only shared surface is the handful of one-liners `fn`/`basics`/`just`/`d`/`isJust`/`isOk`, which
become `static` helpers on a small `PreludeSupport`.

### `Main.java` (~1860) — one class per command, already

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

### `js/JsCompiler.java` (~1397) — extract only the optimiser

Most of this file is one tightly-woven codegen pipeline: expression `compile` ↔ pattern `matchJs` ↔
the TCO path `compileNamedFunction` all share the local-scope stack and temp counter. That web stays.
The cleanly separable part is the **post-processing pipeline** — `minify`, `treeShake`, `pruneKernel`,
`balancedLine` (≈414–565): pure `String → String` passes with no dependency on the compiler instance.
Moving them to `JsOptimizer.java` removes ~150 lines and isolates the one part that is about output
text rather than Elm semantics. The multi-module caching layer (`appBundleProjectCached`,
`interfaceSalt`, `sha256`, ≈255–332) is a second, smaller candidate (`JsBundleCache.java`). Below
~1100 lines the remainder is coherent enough to leave.

### `editor/Editor.elm` (~1230) — separate UI from orchestration

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

### `parser/Parser.java` (~1083) — extract the edges, keep the descent

A recursive-descent + Pratt parser is the canonical "leave the core whole" case: `parseExpr`,
`parseApplication`, `parseAtom`, `parsePattern`, and `parseType` all advance the same `tokens`/`p`
cursor and call one another freely. Splitting that across files would mean threading a shared
`ParserState` through dozens of methods purely to satisfy the file boundary — cost without clarity.

What *can* leave cleanly:

- **`OperatorFixities`** — the static `FIXITY` table, `scanFixities`, `scanInfixDeclarations`
  (≈27–152). Pure precedence data + a pre-scan, independent of the cursor.
- **Layout helpers** — `atNewLine`/`continues`/`withIndent` and the error-recovery
  `recoverToNextTopLevel` (≈228–341) could become a small `Layout` mixin, though the payoff is
  modest.

The recommendation is the modest one: extract fixities (and optionally layout), and accept that the
~900-line descent core is one coherent unit. Forcing a 50/50 split here would make the parser harder
to read, not easier — exactly the "random split" this doc exists to avoid.

### `test/WasmHeapTest.java` (~1202) — split by feature, share the harness

This is a test file, and tests split painlessly because each `@Test` is independent. The clusters are
already obvious: standard-library (lists/strings/maybe), float arithmetic, core language (patterns,
recursion), records, higher-order/closures, and the property-based RNG tests. The shared harness —
`agrees`, `runMain`, `runMainString`, `decodeList`, `agreesFloat`, `NODE` detection (≈24–135, 245–350)
— moves to a `WasmTestSupport` base class, and the clusters become `WasmStringTest`,
`WasmRecordTest`, `WasmHigherOrderTest`, etc. *Why bother for tests:* faster targeted runs and a
clearer map of what the WASM backend guarantees, with no production risk.

### `test/Playground.elm` (~1708) — do not split

This is **vendored** evancz/elm-playground, kept as a test resource so the gallery's Playground
examples compile against the real library. It has clean internal seams (entry points, transforms,
colours, rendering) — but splitting it would fork it from upstream, and every future sync would have
to be re-applied across our pieces. The right move is to leave it whole and treat its size as the
cost of vendoring. If we ever *do* want it modular, that should be a deliberate, documented fork.

---

## Cross-cutting: the duplicated WASM encoder

`WasmCompiler.java` and `WasmGc.java` carry **byte-identical** copies of `leb`, `sleb`, `section`, and
`name`, and `WasmGc` already reaches into `WasmCompiler.nameSection`. This is the one change that
shrinks two files at once and removes a real duplication hazard (a fix to LEB128 encoding has to be
made twice today). Extract a single `wasm/WasmEncoding.java` holding the LEB128 writers, the section
framer, and the name-section builder, and have both backends call it. Low risk, high leverage — a
good first step before any of the larger structural splits.

## Suggested sequencing

Ordered by payoff-to-risk, easiest and safest first:

1. **`WasmEncoding`** extraction — mechanical, removes duplication, de-risks the two WASM splits.
2. **`Prelude.java`** — independent register methods make this the lowest-risk large split.
3. **`Main.java`** — independent command classes; pure file moves.
4. **`WasmHeapTest.java`** — test-only, no production risk.
5. **`WasmCompiler` / `WasmGc`** islands (prelude string, string runtime, type registry).
6. **`Eval.elm`** and **`Editor.elm`** — highest value but need care with Elm import cycles; do the
   evaluator/UI split first and the finer cuts only if they still feel warranted afterwards.
7. **`JsCompiler` / `Parser`** — extract only the clean edges (optimiser, fixities); leave the cores.

Each step should be a behaviour-preserving move validated by the existing test suite before the next.
The measure of success is not the line count afterwards — it is whether a newcomer can guess which
file a given change belongs in.

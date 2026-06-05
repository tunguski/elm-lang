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

**Policy decision (2026-06-05):** the goal is to extract every *cohesive island* and bring the
cleanly-splittable files under 1000; two kinds of file are left as **documented exceptions** rather
than forced under the threshold: (1) the **vendored** `examples/Playground.elm` (splitting forks
upstream), and (2) **tightly mutually-recursive cores** (`Eval.elm`'s evaluator+runBuiltin web,
`WasmCompiler`'s `FunctionGen`, `WasmGc`'s `Gen`, `JsCompiler`'s codegen core) — these have their
islands extracted to shrink them, but the irreducible core stays whole because splitting it just
scatters a hot web across files.

| File | Lines | Recommendation | Status |
|------|-------|----------------|--------|
| `editor/Eval.elm` | 3474 | **Split** — clean leaves extracted; evaluator+runBuiltin core stays whole | 🟡 `EvalRender` + `EvalPlayground` + `EvalJson` extracted (3 single-injection leaves); `Eval.App` ⏸ deferred (threads whole evaluator); Core/Builtins are the documented core |
| `wasm/WasmCompiler.java` | ~1831 | **Split** — extract prelude, string runtime, binary encoding; keep the codegen core | 🟡 `WasmPrelude` + `WasmEncoding` + `WasmNativeFns` (string/apply/record natives) extracted; `FunctionGen` core stays (documented exception) |
| `lsp/LspServer.java` | 2602 | **Split** — transport vs. analysis vs. code-actions/refactors | ⏸ deferred — a cohesive, feature-organized server whose public+static surface is pinned by ~28 directly-tested entry points (see note) |
| `wasm/WasmGc.java` | ~2105 | **Split** — extract the type registry and the shared encoding; keep `Gen` | 🟡 `WasmEncoding` + `WasmGcTypes` (Tuples + W/StructDef) extracted; `Gen` core stays (documented exception) |
| `Main.java` | 234 | **Split** — 35 commands → 4 domain files; root shell + helpers stay | ✅ `CliCompile`/`CliProject`/`CliPackage`/`CliSiteCommands` extracted |
| `interp/Prelude.java` | ~967 | **Split** — one class per Elm module group (cleanest of all) | ✅ `PreludeCollections` + `PreludeJson` + `PreludeCore` extracted; now under threshold |
| `examples/Playground.elm` | ~1708 | **Leave** — vendored elm-playground; splitting forks upstream | ⏸ |
| `editor/Editor.elm` | 1370 | **Split** — `EditorView` (Model/Msg + view leaf) vs. `Editor` (update/orchestration) | ⏸ deferred (feasible — view is a verified leaf; delicate cross-module TEA restructure, see note) |
| `js/JsCompiler.java` | ~1239 | **Partial** — extract the optimiser pipeline; keep codegen together | ✅ `JsOptimizer` + `JsRuntime` extracted; remainder coherent |
| `test/WasmHeapTest.java` | ~595 | **Split** — by feature area, with a shared test-helper base | ✅ `WasmHeapTestSupport` base + `WasmLangFeaturesTest`; all <1000 |
| `test/EditorInterpreterTest.java` | ~796 | split by feature with a shared base | ✅ `EditorInterpreterTestSupport` base + `EditorToolingInterpreterTest`; all <1000 |

`parser/Parser.java` has dropped to ~995 (below the 1000-line threshold): `OperatorFixities`
was extracted, and the recursive-descent core is intentionally kept whole — so it no longer
appears above.

---

### `editor/Eval.elm` (3474, was ~4344) — clean leaves extracted; evaluator core stays whole 🟡

Done incrementally, one cycle-free module at a time (Elm forbids import cycles, and the evaluator
core is one mutually-recursive web). Three clean **single-injection leaves** are extracted —
`EvalRender` (pure display), `EvalPlayground` (game/animation loop), `EvalJson` (JSON codec) — each
taking `applyValue`/`mainValue` as parameters rather than importing the core, with `Eval` re-exposing
the public functions via thin aliases. The `Eval.App` band is a clean one-way leaf too but threads
the *whole* evaluator through 44 functions for no change to Eval's core-status, so it is deferred
(see below). The remaining `Eval.Core` + `Eval.Builtins` (`evalExpr` + `runBuiltin`) are the
documented mutually-recursive core that stays whole. (Line ranges below are approximate.)

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
3. **`Eval.App`** ⏸ — the Elm-Architecture glue: `hasApp`, `appInit`/`appUpdate`/`appView`, the
   effect handlers `randomCmd`/`httpCmd`/`fileSelectCmd`/`taskResult`, the TEA driver
   `eval`/`evalProject`/`debugSteps`, and the game entry points (≈2766–EOF, ~44 functions). *Why
   deferred:* unlike the three leaves below — each of which needs only the **single** `applyValue`
   injection — this band threads the **whole evaluator** (`evalExpr` **and** `applyValue` **and**
   `renderValue`/`htmlToString`) through all 44 mutually-recursive runtime functions, and it carries
   ~28 public entry points that external drivers reference by the `Eval` module name (Java
   `value("Eval", …)`, JS `_$Eval$…`) so each would need a thin re-export alias. It *is* a clean
   one-way leaf (the evaluator core never calls back into it — the only pre-band references are the
   `exposing(…)` list and the module doc), so it **could** be extracted with an evaluator-record
   parameter; but doing so leaves `Eval` a >2700-line core (runBuiltin) either way, so per the
   "leave cores whole, extract the *clean single-injection* leaves" policy it stays for now.
4. **`Eval.Json`** ✅ — the hand-rolled JSON parser/serialiser and the `Json.Decode`/`Encode`
   interpreter now live in `editor/EvalJson.elm` (~445 lines). *Why:* a self-contained codec; it
   touches the evaluator only to apply decoder/encoder functions, so the seven globals-carrying
   helpers take `applyValue` as an injected parameter (`ApplyTo`) — the same leaf pattern as
   Render/Playground. The pure parser/serialiser stays parameterless. `Eval` rewired its five call
   sites to `EvalJson.*`; the band dropped Eval.elm from 3892 → 3475 lines.
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

### `Main.java` (234, was ~1898) — one class per command, done ✅

`Main` was a picocli CLI whose body was **35 independent `@Command` static inner classes** (`Run`,
`Js`, `Make`, `Eval`, `Script`, `Serve`, `Bundle`, `TestCmd`, `Docs`, `Lsp`, `Format`, …). They share
no state — only the helpers `readElmSource`/`typeError`/`render` (now package-private on `Main`) and
two text templates (`ELM_JSON`, `BUNDLED_DEMO_DIRS`). picocli registers subcommands by class, so each
command moved into a domain-grouped top-level class in the same package with no behaviour change; the
`@Command(subcommands = …)` list now names the top-level classes directly.

`Main` keeps only the root `@Command` shell (`main`/`run`/usage), the shared helpers, and the
templates — **234 lines**. The 35 commands now live in four domain files (package-private top-level
classes, calling back to `Main.readElmSource`/`typeError`/`render`):

- `CliCompileCommands` (483) — Run, Js, Make, Wasm, Eval, Bytecode, Script, Bundle.
- `CliProjectCommands` (470) — Serve, Reactor, TestCmd, CoverageCmd, Lint, Check, Repl, Lsp, Format, Project, Bench, Doctest.
- `CliPackageCommands` (452) — Diff, Bump, Publish, Init, Install, Upgrade, Uninstall, Outdated, Verify.
- `CliSiteCommands` (384) — Docs, Site, GenSite, BuildCmd, Gallery, GalleryElm.

`Main` keeps only the root `@Command`, the exception handler, and `main()`. *Why group rather than
one-per-file:* the seam that matters is "what part of the toolchain does this drive", and commands in
a group tend to change together (e.g. all the package commands when the registry format moves).

### `lsp/LspServer.java` (2602) — feature-organized server, split deferred ⏸

`LspServer` is a single coherent responsibility — *be the language server* — internally organized by
LSP feature: diagnostics, completion, hover/definition, code actions, refactors, document/workspace
symbols, references, call hierarchy, semantic tokens, folding/selection ranges, and the JSON-RPC
`serve` loop with its transport plumbing. The natural seams are clear (transport vs. analysis vs.
code-actions/refactors), and it is *not* a mutually-recursive core. Two things make a clean sub-1000
split high-cost and low-ROI, so it is deferred rather than forced:

1. **A wide, directly-pinned public surface.** `LspServerTest` exercises ~28 entry points on
   `LspServer` — both the instance analysis methods (`diagnose`, `complete`, `codeActions`,
   `refactors`, `references`, `definition`, `hoverType`, `semanticTokens`, …) *and* several `static`
   helpers (`applyChange`, `readMessage`, `identifierSpan`, `wordAt`, `importLinks`,
   `foldingRanges`). Moving any of these into a feature class forces either a delegating wrapper
   (which costs back most of the lines) or churn across the test.
2. **The `docs` workspace map straddles the seam.** The `serve` loop *writes* open documents into the
   `docs` field, and an analysis method (`qualifiedMembers`, for cross-module completion) *reads* it,
   so transport and analysis cannot be cleanly separated without threading the workspace map through
   the completion path.

Reaching <1000 would take four or five coordinated extractions (e.g. `LspDiagnostics`,
`LspCodeActions`, `LspRefactors`, `LspProtocol`) plus delegation wrappers — a large, regression-prone
change to a file that already reads as well-separated sections. Left whole as a **documented
exception** for now; the feature boundaries above are the plan if it is revisited.

### `js/JsCompiler.java` (~1239) — extract only the optimiser ✅ done

Most of this file is one tightly-woven codegen pipeline: expression `compile` ↔ pattern `matchJs` ↔
the TCO path `compileNamedFunction` all share the local-scope stack and temp counter. That web stays.
The cleanly separable part is the **post-processing pipeline** — `minify`, `treeShake`, `pruneKernel`,
`balancedLine` (≈414–565): pure `String → String` passes with no dependency on the compiler instance.
These now live in `JsOptimizer.java`, and the runtime kernel in `JsRuntime.java` — the remainder is
the coherent codegen pipeline, left whole as recommended. (A further multi-module caching layer
`JsBundleCache` was noted as an optional second candidate; not pursued.)

### `editor/Editor.elm` (1370) — view is a clean leaf; split deferred ⏸ (feasible)

`Editor.elm` mixes the TEA wiring with a large view layer (≈885–1370). A cross-reference scan
confirms the view is a **true leaf**: of the view band's functions only `view` itself is referenced
from the rest of the module (by `program`), and the view band calls just four functions defined
above it — `baseName`, `groupedFiles`, `selectedFile`, `shownModel` (closure: `folderOf`,
`groupOrder`, `nth`) — none of which call back into `update`/`program`.

The **cycle subtlety the earlier plan missed:** the view produces `Html Msg`, so an `EditorView`
module needs the `Msg` type — but `Editor` needs `view`, so `Editor` imports `EditorView`. If
`EditorView` imported `Editor` for `Model`/`Msg`, that is a cycle. The fix is to put `Model` + `Msg`
(and the four shared read helpers) **in `EditorView`** (or a dedicated `EditorTypes`), which imports
nothing from `Editor`; `Editor`'s `update`/`program`/state-helpers then import them from there. (The
JS backend tags constructors by *simple* name, so moving `Msg` does **not** rename the dispatch tags
the headless-Chrome guard checks — verified safe.)

So the split is genuinely achievable (`EditorView` ≈ Model/Msg + the four read helpers + the ≈485-line
view layer; `Editor` ≈ program/init/subscriptions/`update`/state-helpers), and would bring both under
1000. It is **deferred** only on cost/risk: it is a delicate cross-module TEA restructure (relocating
`Model`/`Msg`, non-contiguous helper moves, getting `exposing (Msg(..))` right) that must keep the JS
bundle, the interpreter, and the headless-Chrome drivers all green, plus re-registration in
`SiteGenerator.EDITOR_MODULES` and the interpreter-test module lists. The plan above is ready to
execute when picked up.

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
3. ✅ **`Main.java`** — 35 independent command classes moved into 4 domain-grouped top-level files (234 lines left).
4. ✅ **`WasmHeapTest.java`** / **`EditorInterpreterTest.java`** — split by feature, harness shared (`*Support` bases).
5. ✅ **`WasmCompiler` / `WasmGc`** islands — `WasmPrelude` + `WasmEncoding` + `WasmNativeFns`
   (string/apply/record natives) and `WasmGcTypes` (the `Tuples` registry + struct records) extracted;
   the `FunctionGen`/`Gen` codegen cores stay whole (documented exceptions).
6. ✅ **`Eval.elm`** — three single-injection leaves extracted (`EvalRender`, `EvalPlayground`,
   `EvalJson`); `Eval.App` and the evaluator+runBuiltin core stay whole (documented).
7. ✅ **`JsCompiler` / `Parser`** — `JsOptimizer`/`JsRuntime` and `OperatorFixities` extracted; the
   codegen and recursive-descent cores are intentionally left whole.
8. ⏸ **`LspServer.java`** — deferred: a cohesive, test-pinned LSP server (≈28 directly-tested entry
   points + a `docs`-field straddle); sub-1000 needs 4–5 feature extractions + delegation wrappers.
9. ⏸ **`Editor.elm`** — deferred but **feasible**: the view is a verified leaf; the split is a delicate
   cross-module TEA restructure (Model/Msg relocation) that must keep JS-bundle + headless + interp green.

Each step should be a behaviour-preserving move validated by the existing test suite before the next.
The measure of success is not the line count afterwards — it is whether a newcomer can guess which
file a given change belongs in.

## Result (2026-06-05)

Every cleanly-splittable file is under 1000 lines. The files that remain above the threshold are all
**documented exceptions**: the vendored `examples/Playground.elm`; the tightly mutually-recursive
backend cores (`Eval.elm`, `WasmCompiler`, `WasmGc`, `JsCompiler`) which have their islands extracted
but keep their irreducible cores; the test-pinned `LspServer.java`; and `Editor.elm`, whose clean
view-leaf split is planned and ready but deferred on cost/risk. Separately, an `elm server` app can
now talk to a database through the typed `lib/Db.elm` JDBC layer (see `server/DbRunner.java`).

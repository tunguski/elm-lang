# Bug report: page blanks at load with `$listToArray(undefined)`

**Reported by:** BlueBox (`bbx-web`) side, while building the standalone documentation
page (`Documentation.elm`, a large `Browser.element` program).

**Status:** **RESOLVED** (2026-06-18). Root cause confirmed and fixed: a **top-level
binding emission-order defect** in the JS backend's dependency analysis. The original
guesses here (“positional type-alias constructors vs. record literals”) were **wrong**;
the “forward-reference / declaration-order” hypothesis at the bottom was **right**, and
this section records the precise mechanism and the fix.

### Confirmed root cause

`JsCompiler.declarations()` topologically orders parameterless top-level values so each
is initialised after the values it reads. To find a value's dependencies it followed
references through called functions — and, fatally, **descended into lambda bodies**,
treating a reference that only fires when a lambda is later invoked (an event-handler
decoder, a stored callback, `init`/`update`/`view`) as an *eager* dependency.

That manufactured **false cycles**. The canonical shape (exactly the doc-page structure
described below): a top-level list `allSections = [intro, usage]` eagerly lists its
sibling section values, while each section — through a shared helper — captures
`allSections` inside an `on "click"` decoder lambda. Statically this looks like
`intro → allSections → intro`, a cycle; the only way the sort can break it is to emit
`allSections` **before** `intro`, so `allSections` reads `intro` as an unassigned `var`
(`undefined`). The first List op to touch it throws `$listToArray: got undefined`, and
the page renders blank. The **non-determinism across rebuilds** (221 560 vs 221 912 bytes)
came from iterating a `HashSet` of dependencies — unstable order decided which cycle
participant was emitted first.

### Fix

`collectDeps` now classifies a reference as **eager** (read while the initialiser runs —
directly or through a called function) or **deferred** (occurs inside a lambda). Eager
references are hard ordering constraints; a deferred reference is emitted *before* the
value only when that introduces no cycle (so an eagerly-invoked lambda such as
`List.map (\x -> … d …) [literal]` still sees `d` initialised), and *after* it otherwise
(the capturing lambda runs only once every initialiser has completed). Dependency sets
are now `LinkedHashSet`s, so emission is deterministic. `$listToArray` already throws a
named error on `null`/`undefined` (suggestion 2 below, implemented earlier). Regression:
`TopLevelBindingOrderTest` (compiles the false-cycle shape, asserts the eager list is
emitted after its siblings, and mounts the app under Node to confirm it renders).

---

#### Original (pre-fix) notes follow.

The text below is the report as originally filed; kept for context.

---

## Symptom

A compiled page (single self-contained HTML from `elm make … -o page.html --no-check`)
rendered **completely blank**. The browser console showed exactly one error, thrown at
load (before anything painted):

```
Uncaught TypeError: Cannot read properties of undefined (reading '$')
```

The throw site is the kernel helper:

`src/main/resources/elm/js/kernel.js:11`
```js
function $listToArray(v){ var a=[]; while(v.$==='::'){a.push(v.a); v=v.b;} return a; }
```

i.e. **a `List` value arrived at `$listToArray` as `undefined`** (so `v.$` throws).
`$listToArray` is the funnel for `List.map`, `List.filter`, `List.foldl`,
`List.indexedMap`, `Dict.fromList`, `Set.fromList`, `++` on lists, `$show`, etc.
(kernel.js lines 53, 74, 107, 129, 144, 193–196 …), so the real trigger is “some
list-typed expression evaluated to `undefined`,” and one of those consumers then
dereferenced it.

Confirmed it was this helper by patching the generated file:
```js
function $listToArray(v){ if(v===undefined||v===null){console.log(new Error().stack);} … }
```
— the guard fired during initial evaluation.

## Where / when it happened

- Module: a large `Browser.element` page (`Program () () msg`) — `Documentation.elm` in
  `bbx-web` — whose top level has **~8 zero-argument `Html` values** (one per doc
  section) plus helper functions and a couple of top-level data lists, all referencing
  each other.
- It failed at **module init / first render**, not on user interaction: replacing a
  section view’s body with a fully static `div [] [ text "x" ]` did **not** help as long
  as the other top-level `Html` values were still present — which points at eager
  evaluation of a top-level binding, not at the specific view being rendered.

## Why this looks like a codegen/runtime bug (not the source)

The decisive evidence: **the same `.elm` source produced different compiled output and
different behaviour across rebuilds within one session.**

- One build of the unchanged source: `documentation.html` = **221 560 bytes** → blank +
  `$listToArray(undefined)`.
- A later build of the **same** source: **221 912 bytes** → renders fine.

Same input, different generated JS size and different result ⇒ the **compiler/runtime
changed between builds** (the elm-lang compiler was being actively rebuilt at the time).
Both the positional-constructor form *and* the record-literal form were observed failing
before the change and passing after it, which is why the original
“positional-vs-record-literal” hypothesis is disproven.

## Most likely root cause (hypothesis)

A list-typed **top-level binding referenced before it was assigned** — i.e. a
declaration-order / forward-reference problem in the emitted JS. If top-level values are
emitted as `var`s and an eagerly-evaluated value reads another top-level `var` declared
later in the file, that read is `undefined` (JS hoisting). When the undefined value is a
`List` handed to e.g. `List.map`, you get exactly `$listToArray(undefined)`.

The failing module had many mutually-referencing top-level bindings (zero-arg `Html`
values calling helpers and data lists defined further down). A fix that **topologically
orders top-level bindings** (or makes them lazy/thunked) would explain why it now works.

## Reproduction attempts (all PASS on the current compiler)

Built with `elm.sh make … --no-check` and checked in headless Chrome — none reproduce:

1. Minimal positional constructor with a `List` field:
   ```elm
   type alias Item = { name : String, tags : List String }
   items = [ Item "a" [ "x", "y" ], Item "b" [ "z" ] ]
   view = ul [] (List.map (\i -> li [] [ text i.name ]) items)
   ```
2. A close clone of the failing view (positional `StatusInfo` with a 4th `List` field,
   `nameOf` via `List.filter |> List.head |> Maybe.map .name`, a `case` on an empty list,
   `List.map` over the data into a table).
3. The **full** original module restored to positional constructors (9 + 4 of them) and
   rebuilt — renders cleanly.

So either it is already fixed, or it is latent and sensitive to something not captured by
the small repros (module scale, exact binding order, a specific codegen path).

## Suggested actions for the elm-lang side

1. **Add a regression test** around the failing shape: a `Browser.element` module with
   several mutually-referencing top-level `Html` values + data lists where a value uses a
   helper/list **declared later in the file**, compiled and asserted to render (the
   existing `HeadlessChromeTest` could host it).
2. **Make `$listToArray` fail loudly** for diagnosability — e.g.
   `if (v == null) throw new Error('listToArray: got ' + v + ' (uninitialised List binding?)')`
   so a future occurrence names the problem instead of `reading '$' of undefined`.
3. **Verify top-level binding emission** is either topologically ordered by dependency or
   lazily thunked, so a value can reference a binding defined later in the module without
   reading an un-hoisted `undefined`.

## Environment

- Compiler: tunguski `elm-lang` (`pl.matsuo.elm.Main`), invoked via `elm.sh` / `target/elm.jar`.
- `elm make <Main> --project=<elm.json> -o <out.html> --no-check`.
- Browser: Chrome headless (`--headless=new`), file:// load.

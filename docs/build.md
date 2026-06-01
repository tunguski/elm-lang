# The build tool (`elm build`)

`elm build [phase]` runs a build that is **described in Elm**. The design borrows Maven's shape — a
declarative project and a fixed lifecycle of phases — but the extension mechanism is just ordinary
Elm functions, not a plugin system. A single file can describe a whole **multi-module** build.

> Status: this is a **foundation**. The lifecycle, the declarative model, the planner and a runner
> that performs the core task kinds are in place; richer dependency resolution and a larger built-in
> task catalogue are intended to grow on top of it.

## How it works

A build file exposes `project : Build.Project` (in any module — `Main` by convention), built with the
bundled `Build` library:

```elm
module Main exposing (project)

import Build exposing (..)


project : Project
project =
    Build.project "my-app" "1.0.0"
        [ module_ "app" "." |> withEntry "src/Main.elm" |> withOutput "out" ]
```

Run it:

```text
elm build              # validate → compile → test → package (the default)
elm build test         # everything up to the test phase
elm build install      # the whole lifecycle
elm build clean        # remove every module's output
elm build --dry-run    # print the plan (phases, goals, tasks) without running it
elm build --watch      # re-run whenever a .elm file changes (Ctrl-C to stop)
elm build --init       # scaffold a starter build.elm
elm build install -f packages.build.elm   # a non-default build file
```

The runner loads `project`, asks the library for the plan, and executes it:

1. **Plan** — `Build.plan <phase> project` purely expands the project into an ordered list of steps
   (phase → module → goal → tasks). Because it is pure, the plan is fully testable and could drive a
   dry run without touching anything.
2. **Execute** — each step's tasks run in order; the build stops at the first failure (fail-fast),
   returning that task's exit code.

Relative paths in a build resolve against the **build file's directory** (like Maven resolving
against the `pom.xml` directory), so a build behaves the same wherever you invoke it from.

## The lifecycle

The phases are fixed and ordered. Asking for a phase runs **every earlier phase first**:

| Phase | Intent |
|---|---|
| `validate` | Check the project is sound (e.g. type-check). |
| `compile` | Produce the compiled artifacts. |
| `test` | Run the test suites. |
| `package` | Assemble the distributable output. |
| `verify` | Run checks on the packaged result. |
| `install` | Make it available locally. |

`clean` is **not** part of this chain — it is a separate operation you request explicitly, exactly
as in Maven.

## Modules and goals

A `Module` is one buildable unit (Maven calls them modules; a `Project` lists several):

| Field | Meaning |
|---|---|
| `name` | The module's name (used in artifact names and log lines). |
| `path` | Its root directory. |
| `entry` | Its main source file (default `<path>/src/Main.elm`). |
| `sources` | Extra source files/globs. |
| `dependencies` | `List Dependency` (name + version). |
| `output` | Its target directory (default `<path>/build`). |
| `goals` | The goals bound to phases (empty → the defaults apply). |

Build a module with `module_ name path` and refine it with `withEntry`, `withSources`,
`withDependencies`, `withOutput`, `withGoals`, `addGoal`.

A **goal** is the work bound to a phase. It is just a function `Module -> List Task`:

```elm
goal : Phase -> String -> (Module -> List Task) -> Goal
```

When a module declares no goals, the **default lifecycle bindings** (`defaultGoals`) apply: validate
type-checks the entry, compile makes the output dir and emits a JS bundle, test runs the module's
`tests/` directory, package zips the output to `dist/<name>.zip`, and install copies that archive
into `build-repo/`. To keep the defaults *and* add your own, include them explicitly:

```elm
module_ "app" "."
    |> withGoals
        (defaultGoals
            ++ [ goal Package "bundle" (\m -> [ copy (m.output ++ "/app.js") "dist/app.js" ]) ]
        )
```

Declaring any goals **replaces** the defaults, so you stay in full control.

## Tasks

A goal produces a list of `Task`s — the atomic actions the runner performs. Use the lowercase
builders:

| Builder | Action |
|---|---|
| `check : String -> Task` | Type-check an entry file (fails the build on a type error). |
| `compile : Backend -> String -> String -> Task` | Compile an entry file to a target (`JS`, `Wasm`, `WasmGc`). |
| `test : String -> Task` | Run the tests under a directory (a no-op if absent). |
| `archive : String -> String -> Task` | Zip a directory tree into an archive file. |
| `exec : String -> List String -> Task` | Run an external command (the escape hatch). |
| `makeDir : String -> Task` | Create a directory. |
| `copy : String -> String -> Task` | Copy a file or directory tree. |
| `remove : String -> Task` | Delete a path (recursively). |
| `writeFile : String -> String -> Task` | Write a file. |
| `markdown : String -> String -> Task` | Render a Markdown file to an HTML fragment file. |
| `script : String -> List String -> Task` | Run an Elm script (`main : Posix.Io`, with the Posix/Bash/Site libraries in scope) — relative path arguments resolve against the build dir. |
| `log : String -> Task` | Print a line. |

Because a goal is plain Elm, you can compute its tasks however you like — branch on the module name,
map over `m.sources`, generate files, shell out. That is the whole "custom function" story: no
plugin packaging, just functions.

Together these are enough to drive a **static-site build** entirely from `build.elm`: `compile JS`
emits a live demo page per module, `markdown` turns guides into page bodies, and `script` runs a
gallery generator written with the [Site library](site.html) to lay out the index — the same
pipeline the project's own showcase site uses, with no Java glue.

## A multi-module build

One file, several modules, one shared lifecycle:

```elm
module Main exposing (project)

import Build exposing (..)


project : Project
project =
    Build.project "suite" "2.1.0"
        [ module_ "core" "core" |> withOutput "core/out"
        , module_ "web" "web"
            |> withEntry "web/src/Main.elm"
            |> withDependencies [ dependency "suite/core" "2.1.0" ]
            |> withGoals
                (defaultGoals
                    ++ [ goal Package "wasm" (\m -> [ compile WasmGc m.entry (m.output ++ "/web.wasm") ]) ]
                )
        ]
```

`elm build package` runs `validate`, then `compile`, then `test`, then `package` — each phase across
**every** module (Maven reactor order) — so the whole suite is built from this single definition.

## Using it in your project

1. Add `build.elm` exposing `project : Build.Project`.
2. `import Build exposing (..)` — the library is bundled, nothing to install.
3. List your modules; lean on `defaultGoals`, then add `goal`s for anything project-specific.
4. Run `elm build` (or a specific phase); use `elm build clean` to start fresh.

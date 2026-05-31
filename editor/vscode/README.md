# Elm (tunguski/elm-lang) — VS Code extension

A thin VS Code client for the language server built into the [tunguski/elm-lang](../../README.md)
toolchain. It launches `java -jar elm.jar lsp` over stdio and surfaces everything the server
implements:

- **Diagnostics** — parse and type errors (Hindley–Milner), including **multi-error** reports, plus
  **warnings** (unused imports, private definitions, parameters and `let` bindings, **non-exhaustive
  `case`**), on open and as you type.
- **Hover** — the inferred type of the definition under the cursor.
- **Go to definition / Find references / Rename** — workspace-wide (every `.elm` file under the
  workspace root is indexed).
- **Workspace symbols** — search top-level symbols across the whole project (`Ctrl+T`).
- **Call hierarchy** — incoming and outgoing calls of a function, across modules.
- **Completion** — module-local names plus the bundled standard library; after `Module.` the
  module's members, after a `record.` its field names.
- **Document symbols** (outline), **document highlight** (every occurrence of the symbol under the
  cursor), **inlay hints** (inferred signatures), **signature help**, **code lenses** (reference
  counts per definition), and **semantic tokens** for highlighting (with a TextMate grammar as a
  fallback).
- **Code actions** — quick-fixes (add type annotation, fill missing `case` branches, remove/add/
  organize imports) and refactors: **"Extract to function"** (free locals become parameters) and
  **"Inline"** (the reverse).
- **Formatting** — elm-format-style; **format-on-save** is enabled by default for `.elm` files.

See [docs/lsp.md](../../docs/lsp.md) for the full capability list and protocol details.

## Prerequisites

1. A JDK on `PATH` (or set `elmLang.javaPath`).
2. The shaded CLI jar. From the repo root:

   ```sh
   ./mvnw -DskipTests package    # produces target/elm.jar
   ```

## Try it

```sh
cd editor/vscode
npm install            # fetches vscode-languageclient
code .                 # open this folder in VS Code
# Press F5 → "Run Extension" to launch an Extension Development Host.
```

Open any `.elm` file in the dev host. If you opened the **elm-lang repo** as the workspace, the
extension finds `target/elm.jar` automatically; otherwise set `elmLang.serverJar` to its absolute
path.

## Packaging

```sh
npm install -g @vscode/vsce
vsce package           # produces elm-lang-tunguski-<version>.vsix
code --install-extension elm-lang-tunguski-*.vsix
```

## Settings

| Setting | Default | Description |
| --- | --- | --- |
| `elmLang.serverJar` | `""` | Absolute path to `elm.jar`. Empty → look for `target/elm.jar` under the workspace root. |
| `elmLang.javaPath` | `java` | The `java` executable used to launch the server. |
| `elmLang.trace.server` | `off` | Trace JSON-RPC traffic (`off` / `messages` / `verbose`). |

The server is the same one you can run by hand with `elm lsp` — this extension just wires it into
VS Code's language features.

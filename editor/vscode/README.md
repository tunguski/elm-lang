# Elm (tunguski/elm-lang) — VS Code extension

A thin VS Code client for the language server built into the [tunguski/elm-lang](../../README.md)
toolchain. It launches `java -jar elm.jar lsp` over stdio and surfaces everything the server
implements:

- **Diagnostics** — parse and type errors (Hindley–Milner), including **multi-error** reports, on
  open and as you type.
- **Hover** — the inferred type of the definition under the cursor.
- **Go to definition / Find references / Rename** — workspace-wide (every `.elm` file under the
  workspace root is indexed).
- **Completion** — module-local names plus the bundled standard library.
- **Document symbols** (outline), **inlay hints** (inferred signatures), **signature help**, and
  **semantic tokens** for highlighting (with a TextMate grammar as a fallback).
- **Formatting** — elm-format-style; **format-on-save** is enabled by default for `.elm` files.

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

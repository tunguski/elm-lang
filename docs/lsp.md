# Language server (`elm lsp`)

`elm lsp` runs a [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
server for Elm over **stdio**. It speaks JSON-RPC 2.0 with the usual `Content-Length`-framed
messages, so any LSP-capable editor can drive it. The same binary backs the
[VS Code client](../editor/vscode/) — that extension is just a thin launcher.

```sh
./mvnw -DskipTests package      # build target/elm.jar
java -jar target/elm.jar lsp    # start the server on stdin/stdout
```

The server is **single-process, in-memory, and dependency-free** (no `elm.json`, no network, no
index files on disk). It type-checks with the same Hindley–Milner inference the rest of the
toolchain uses, so hovers, inlay hints and diagnostics are all driven by real inference rather than
heuristics.

---

## Capabilities at a glance

| Feature | LSP method | Notes |
|---|---|---|
| Diagnostics | `textDocument/publishDiagnostics` | Errors **and** warnings, on open and on every change. |
| Hover | `textDocument/hover` | Inferred type of the definition under the cursor. |
| Go to definition | `textDocument/definition` | Resolves locally and **across the workspace** (and through qualified imports). |
| Find references | `textDocument/references` | Workspace-wide. |
| Rename | `textDocument/rename` | Rewrites every occurrence, across modules. |
| Completion | `textDocument/completion` | Module-local names + the bundled standard library, with inferred-type detail. |
| Document symbols | `textDocument/documentSymbol` | Outline of a file's top-level declarations. |
| Workspace symbols | `workspace/symbol` | Search top-level symbols across **all** indexed files (e.g. VS Code `Ctrl+T`). |
| Document highlight | `textDocument/documentHighlight` | Every occurrence of the symbol under the cursor in the file. |
| Code lenses | `textDocument/codeLens` | A reference count above each top-level definition. |
| Inlay hints | `textDocument/inlayHint` | Inferred type signatures shown inline for unannotated values. |
| Signature help | `textDocument/signatureHelp` | The called function's type while typing arguments. |
| Semantic tokens | `textDocument/semanticTokens/full` | Keyword / type / number / … classification for highlighting. |
| Code actions | `textDocument/codeAction` | Quick-fixes and refactors (see below). |
| Formatting | `textDocument/formatting` | Whole-document elm-format-style reformat. |

### Diagnostics

Published on `didOpen` and after each `didChange`. Each diagnostic carries `source: "elm-lang"` and a
`severity`:

- **Error** (`severity: 1`) — parse errors and type errors. Multiple independent errors are reported
  together rather than stopping at the first.
- **Warning** (`severity: 2`) — unused imports, unused exposed/imported names, unused private
  top-level definitions, and unused function parameters and `let` bindings.

### Code actions

Offered for the cursor's position / selection:

**Quick-fixes** (`kind: "quickfix"`)

- **Add type annotation** — adds the inferred signature above an unannotated top-level value.
- **Add missing branches** — fills the constructors a non-exhaustive `case` does not cover.
- **Remove unused import** / **Add missing import** — for an unused or unresolved import.
- **Organize imports** — sorts the import block by module, sorts each `exposing` list, and drops
  unused names and fully-unused imports.

**Refactors** (`kind: "refactor.extract"`)

- **Extract to function** — turns the selected expression into a fresh top-level function and
  replaces the selection with a call to it. The expression's **free local variables** (anything not
  bound to a top-level name or constructor) become the new function's parameters, so the result still
  resolves. Declined when the selection isn't a complete expression.

---

## Editor integration

### VS Code

Use the bundled client in [`editor/vscode/`](../editor/vscode/) — see its
[README](../editor/vscode/README.md). It launches `java -jar elm.jar lsp`, enables format-on-save,
and exposes `elmLang.serverJar` / `elmLang.javaPath` / `elmLang.trace.server` settings.

### Neovim (built-in LSP)

```lua
vim.api.nvim_create_autocmd("FileType", {
  pattern = "elm",
  callback = function(args)
    vim.lsp.start({
      name = "elm-lang",
      cmd = { "java", "-jar", "/abs/path/to/target/elm.jar", "lsp" },
      root_dir = vim.fs.dirname(vim.fs.find({ "elm.json", ".git" }, { upward = true })[1]),
    })
  end,
})
```

(There is no `elm.json` requirement; `root_dir` only scopes which `.elm` files get indexed for
workspace-wide navigation.)

### Any other client

Spawn `java -jar elm.jar lsp`, connect its stdin/stdout, and send a normal `initialize` handshake.
Pass the workspace root as `rootUri` (or `workspaceFolders`) so the server can index every `.elm`
file under it for workspace symbols, references and rename. The server advertises full
`textDocumentSync` (send whole-document `didChange` updates).

---

## Workspace indexing

On `initialize`, the server walks the `rootUri` / `workspaceFolders` directories and reads every
`.elm` file into memory, so go-to-definition, find-references, rename and workspace-symbol search
work **before** a file is opened. Opening or editing a file (`didOpen` / `didChange`) overrides the
on-disk copy with the editor's in-memory buffer. Indexing is best-effort — unreadable trees are
skipped.

---

## Protocol notes

- **Transport:** stdio. Messages are UTF-8 JSON-RPC 2.0 framed with `Content-Length: <n>\r\n\r\n`
  headers, exactly as the LSP spec specifies.
- **Positions** are 0-based `{ line, character }`, as in LSP.
- **Lifecycle:** `initialize` → (work) → `shutdown` → `exit`. The process exits cleanly on `exit` or
  when stdin closes.
- Unknown requests get an empty (`null`) result rather than an error, so a client probing optional
  methods won't break.

## Trying it by hand

A minimal session (newlines shown for clarity — real messages are `Content-Length`-framed):

```
--> initialize            { "rootUri": "file:///abs/project" }
<-- (capabilities)
--> textDocument/didOpen   { "textDocument": { "uri": "file:///abs/project/Main.elm", "text": "…" } }
<-- textDocument/publishDiagnostics   { "uri": …, "diagnostics": [ … ] }
--> textDocument/hover     { "textDocument": { "uri": … }, "position": { "line": 2, "character": 4 } }
<-- (inferred type)
```

The VS Code client's `elmLang.trace.server: "verbose"` setting prints this traffic to its output
channel, which is the easiest way to watch a real conversation.

## Troubleshooting

- **No diagnostics / features:** confirm `java -jar target/elm.jar lsp` starts (build it with
  `./mvnw -DskipTests package`). In VS Code, set `elmLang.serverJar` to the jar's absolute path if it
  isn't found automatically.
- **Workspace navigation misses a file:** it must be under the `rootUri` you sent at `initialize` and
  have a `.elm` extension.
- **Tracing:** set `elmLang.trace.server` to `messages` or `verbose` (VS Code), or have your client
  log JSON-RPC traffic.

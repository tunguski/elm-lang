// VS Code client for the tunguski/elm-lang language server.
//
// It launches `java -jar <elm.jar> lsp` over stdio and lets vscode-languageclient drive every
// capability the server already implements: diagnostics, hover types, go-to-definition, completion,
// references, rename, document symbols, inlay hints, signature help and semantic tokens.
//
// Plain CommonJS — no build step. Run `npm install` once to fetch vscode-languageclient, then
// launch the "Run Extension" target (or package with `vsce package`).

const fs = require("fs");
const path = require("path");
const vscode = require("vscode");
const { LanguageClient, TransportKind } = require("vscode-languageclient/node");

let client;

/** Resolves the elm.jar path: the `elmLang.serverJar` setting, else `target/elm.jar` in a folder. */
function resolveServerJar() {
  const configured = vscode.workspace.getConfiguration("elmLang").get("serverJar");
  if (configured && configured.trim() !== "") {
    return configured.trim();
  }
  const folders = vscode.workspace.workspaceFolders || [];
  for (const folder of folders) {
    const candidate = path.join(folder.uri.fsPath, "target", "elm.jar");
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }
  return null;
}

function activate(context) {
  const jar = resolveServerJar();
  if (!jar) {
    vscode.window.showErrorMessage(
      "Elm: no language server jar found. Build it with `mvn package` and set `elmLang.serverJar` " +
        "to the resulting target/elm.jar (or open the elm-lang project so target/elm.jar is found)."
    );
    return;
  }
  if (!fs.existsSync(jar)) {
    vscode.window.showErrorMessage("Elm: configured elmLang.serverJar does not exist: " + jar);
    return;
  }

  const java = vscode.workspace.getConfiguration("elmLang").get("javaPath") || "java";
  const serverOptions = {
    run: { command: java, args: ["-jar", jar, "lsp"], transport: TransportKind.stdio },
    debug: { command: java, args: ["-jar", jar, "lsp"], transport: TransportKind.stdio },
  };

  const clientOptions = {
    documentSelector: [{ scheme: "file", language: "elm" }],
    synchronize: {
      fileEvents: vscode.workspace.createFileSystemWatcher("**/*.elm"),
    },
  };

  client = new LanguageClient("elmLang", "Elm Language Server", serverOptions, clientOptions);
  context.subscriptions.push({ dispose: () => client && client.stop() });
  client.start();
}

function deactivate() {
  return client ? client.stop() : undefined;
}

module.exports = { activate, deactivate };

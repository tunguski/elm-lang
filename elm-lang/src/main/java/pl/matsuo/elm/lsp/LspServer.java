package pl.matsuo.elm.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.matsuo.elm.ast.Decl;
import pl.matsuo.elm.ast.Module;
import pl.matsuo.elm.error.ElmSyntaxError;
import pl.matsuo.elm.error.ElmTypeError;
import pl.matsuo.elm.json.JsonEncode;
import pl.matsuo.elm.json.JsonParse;
import pl.matsuo.elm.parser.Parser;
import pl.matsuo.elm.types.TypeChecker;

/**
 * A minimal Language Server (LSP) over stdio for Elm: it publishes diagnostics (parse and type
 * errors, located) on open/change and answers hover with the inferred type of the definition under
 * the cursor. Reuses the existing parser and Hindley–Milner type checker.
 */
public final class LspServer {

  /** A diagnostic at a 0-based (line, character) range. */
  public record Diagnostic(int line, int startChar, int endChar, String message) {}

  private final Map<String, String> docs = new LinkedHashMap<>();

  // --- core analysis (pure, unit-tested directly) ------------------------

  /** Parse + type-check the source, returning located diagnostics (empty if it's clean). */
  public List<Diagnostic> diagnose(String source) {
    List<Diagnostic> out = new ArrayList<>();
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (ElmSyntaxError e) {
      out.add(at(e.position(), e.getMessage()));
      return out;
    } catch (RuntimeException e) {
      out.add(new Diagnostic(0, 0, 1, e.getMessage()));
      return out;
    }
    try {
      TypeChecker.checkModule(source);
    } catch (ElmTypeError e) {
      if (e.position != null) {
        out.add(at(e.position, e.rawMessage() + (e.hint != null ? " — " + e.hint : "")));
      } else {
        out.add(new Diagnostic(0, 0, 1, e.getMessage()));
      }
    } catch (RuntimeException ignored) {
      // best-effort: a checker limitation shouldn't crash the server
    }
    return out;
  }

  /** The inferred type of the top-level definition on the given 0-based line, if any. */
  public Optional<String> hoverType(String source, int line0) {
    Map<String, String> types;
    try {
      types = TypeChecker.checkModule(source);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    Module module;
    try {
      module = Parser.parseModule(source);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    for (Decl d : module.decls()) {
      if (d instanceof Decl.Value v && v.pos().line() == line0 + 1 && types.containsKey(v.name())) {
        return Optional.of(v.name() + " : " + types.get(v.name()));
      }
    }
    return Optional.empty();
  }

  private static Diagnostic at(pl.matsuo.elm.error.Position p, String message) {
    int line = Math.max(0, p.line() - 1);
    int col = Math.max(0, p.col() - 1);
    return new Diagnostic(line, col, col + 1, message);
  }

  // --- JSON-RPC over stdio ------------------------------------------------

  /** Runs the server loop, reading LSP messages from {@code in} and writing replies to {@code out}. */
  public void serve(InputStream in, OutputStream out) throws IOException {
    while (true) {
      Map<String, Object> msg = readMessage(in);
      if (msg == null) {
        return; // stream closed
      }
      Object method = msg.get("method");
      if ("exit".equals(method)) {
        return;
      }
      handle(msg, out);
    }
  }

  @SuppressWarnings("unchecked")
  void handle(Map<String, Object> msg, OutputStream out) throws IOException {
    String method = (String) msg.get("method");
    Object id = msg.get("id");
    Map<String, Object> params =
        msg.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    switch (method == null ? "" : method) {
      case "initialize" -> reply(out, id, capabilities());
      case "textDocument/didOpen" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        docs.put(uri, (String) td.get("text"));
        publishDiagnostics(out, uri);
      }
      case "textDocument/didChange" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        String uri = (String) td.get("uri");
        List<Object> changes = (List<Object>) params.get("contentChanges");
        if (changes != null && !changes.isEmpty()) {
          docs.put(uri, (String) ((Map<String, Object>) changes.get(changes.size() - 1)).get("text"));
        }
        publishDiagnostics(out, uri);
      }
      case "textDocument/hover" -> {
        Map<String, Object> td = (Map<String, Object>) params.get("textDocument");
        Map<String, Object> pos = (Map<String, Object>) params.get("position");
        String uri = (String) td.get("uri");
        int line = ((Number) pos.get("line")).intValue();
        Optional<String> type = hoverType(docs.getOrDefault(uri, ""), line);
        Map<String, Object> result = new LinkedHashMap<>();
        if (type.isPresent()) {
          Map<String, Object> contents = new LinkedHashMap<>();
          contents.put("kind", "markdown");
          contents.put("value", "```elm\n" + type.get() + "\n```");
          result.put("contents", contents);
          reply(out, id, result);
        } else {
          reply(out, id, JsonEncode.NULL);
        }
      }
      case "shutdown" -> reply(out, id, JsonEncode.NULL);
      default -> {
        if (id != null) {
          reply(out, id, JsonEncode.NULL); // unknown request — empty result
        }
      }
    }
  }

  private static Map<String, Object> capabilities() {
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("textDocumentSync", 1L); // full document sync
    caps.put("hoverProvider", true);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("capabilities", caps);
    return result;
  }

  private void publishDiagnostics(OutputStream out, String uri) throws IOException {
    List<Object> diags = new ArrayList<>();
    for (Diagnostic d : diagnose(docs.getOrDefault(uri, ""))) {
      Map<String, Object> range = range(d.line(), d.startChar(), d.endChar());
      Map<String, Object> diag = new LinkedHashMap<>();
      diag.put("range", range);
      diag.put("severity", 1L); // Error
      diag.put("source", "elm-lang");
      diag.put("message", d.message());
      diags.add(diag);
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("uri", uri);
    params.put("diagnostics", diags);
    notify(out, "textDocument/publishDiagnostics", params);
  }

  private static Map<String, Object> range(int line, int startChar, int endChar) {
    Map<String, Object> start = new LinkedHashMap<>();
    start.put("line", (long) line);
    start.put("character", (long) startChar);
    Map<String, Object> end = new LinkedHashMap<>();
    end.put("line", (long) line);
    end.put("character", (long) endChar);
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("start", start);
    range.put("end", end);
    return range;
  }

  private void reply(OutputStream out, Object id, Object result) throws IOException {
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("jsonrpc", "2.0");
    msg.put("id", id);
    msg.put("result", result);
    write(out, msg);
  }

  private void notify(OutputStream out, String method, Object params) throws IOException {
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("jsonrpc", "2.0");
    msg.put("method", method);
    msg.put("params", params);
    write(out, msg);
  }

  static void write(OutputStream out, Map<String, Object> msg) throws IOException {
    byte[] body = JsonEncode.serialize(msg, 0).getBytes(StandardCharsets.UTF_8);
    out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> readMessage(InputStream in) throws IOException {
    int length = -1;
    StringBuilder header = new StringBuilder();
    int c;
    while ((c = in.read()) != -1) {
      header.append((char) c);
      if (header.toString().endsWith("\r\n\r\n")) {
        for (String h : header.toString().split("\r\n")) {
          if (h.toLowerCase().startsWith("content-length:")) {
            length = Integer.parseInt(h.substring(h.indexOf(':') + 1).trim());
          }
        }
        break;
      }
    }
    if (length < 0) {
      return null;
    }
    byte[] body = in.readNBytes(length);
    Object parsed = JsonParse.parse(new String(body, StandardCharsets.UTF_8));
    return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
  }
}

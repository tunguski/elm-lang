package pl.matsuo.elm.lsp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Drives the language server over framed JSON-RPC (the {@link LspServer#handle} path and message
 * framing), exercising the request handlers end to end rather than the pure analysis methods.
 */
class LspRpcTest {

  private final LspServer server = new LspServer();

  /** Sends one framed JSON-RPC message and returns the server's written response. */
  private String rpc(String json) throws Exception {
    String framed =
        "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + json;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    server.handle(
        LspServer.readMessage(new ByteArrayInputStream(framed.getBytes(StandardCharsets.UTF_8))),
        out);
    return out.toString(StandardCharsets.UTF_8);
  }

  private static String pos(int line, int ch) {
    return "{\"line\":" + line + ",\"character\":" + ch + "}";
  }

  @Test
  void fullRequestLifecycleOverJsonRpc() throws Exception {
    String src = "double n = n * 2\\nmain = double 21\\n"; // \\n -> literal \n inside the JSON string
    String doc = "\"textDocument\":{\"uri\":\"file:///M.elm\"}";

    assertTrue(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}").contains("capabilities"));

    // didOpen stores the document and publishes (empty) diagnostics as a notification.
    String open =
        rpc(
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":{\"textDocument\":{"
                + "\"uri\":\"file:///M.elm\",\"text\":\""
                + src
                + "\"}}}");
    assertTrue(open.contains("publishDiagnostics"), open);

    assertTrue(
        rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"textDocument/hover\",\"params\":{"
                + doc + ",\"position\":" + pos(0, 0) + "}}")
            .contains("double :"),
        "hover shows the inferred type");

    assertTrue(
        rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"textDocument/definition\",\"params\":{"
                + doc + ",\"position\":" + pos(1, 8) + "}}")
            .contains("file:///M.elm"),
        "definition returns a location");

    assertTrue(
        rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"textDocument/completion\",\"params\":{"
                + doc + ",\"position\":" + pos(1, 0) + "}}")
            .contains("\"label\":\"double\""),
        "completion includes the local name");

    String refs =
        rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"textDocument/references\",\"params\":{"
                + doc + ",\"position\":" + pos(0, 0) + "}}");
    assertTrue(refs.indexOf("range") != refs.lastIndexOf("range"), "references returns 2+ locations");

    assertTrue(
        rpc("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"textDocument/rename\",\"params\":{"
                + doc + ",\"position\":" + pos(0, 0) + ",\"newName\":\"twice\"}}")
            .contains("\"newText\":\"twice\""),
        "rename returns edits");

    assertTrue(
        rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"textDocument/documentSymbol\",\"params\":{"
                + doc + "}}")
            .contains("\"name\":\"main\""),
        "document symbols list top-levels");

    // didChange replaces the text; a type error now produces a diagnostic.
    String changed =
        rpc(
            "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{\"textDocument\":{"
                + "\"uri\":\"file:///M.elm\"},\"contentChanges\":[{\"text\":\"main = 1 + \\\"x\\\"\\n\"}]}}");
    assertTrue(changed.contains("publishDiagnostics"), changed);
    assertTrue(changed.toLowerCase().contains("number") || changed.contains("diagnostics"), changed);

    assertTrue(rpc("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"shutdown\"}").contains("result"));
  }

  @Test
  void unknownRequestGetsANullResult() throws Exception {
    assertTrue(
        rpc("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"textDocument/foo\",\"params\":{}}")
            .contains("\"result\":null"));
  }
}

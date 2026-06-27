package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The JS runtime's {@code Http.*} helpers must issue the correct HTTP method. {@code Http.post} (and
 * {@code Http.get}) take a shorthand record that carries no {@code method} field, so the method must
 * be forced by the helper — otherwise a body-bearing POST silently went out as a GET (the request
 * record's missing {@code method} defaulted to GET in {@code fetch}).
 */
class HttpMethodTest {

  @Test
  void getPostAndRequestUseTheRightMethod() {
    org.junit.jupiter.api.Assumptions.assumeTrue(nodeAvailable(), "node not installed");
    String module =
        """
        module Main exposing (g, p, r)
        import Http
        import Json.Encode as E
        g = Http.get { url = "/g", expect = Http.expectWhatever identity }
        p = Http.post { url = "/p", body = Http.jsonBody (E.object [ ( "x", E.int 1 ) ]), expect = Http.expectWhatever identity }
        r = Http.request { method = "DELETE", headers = [], url = "/d", body = Http.emptyBody, expect = Http.expectWhatever identity, timeout = Nothing, tracker = Nothing }
        """;
    // A fetch stub that records "<METHOD> <url> ct=<content-type>" for each request, then satisfies
    // the runtime's .then().then().catch() chain. Each top-level value is a Cmd ($data '$Cmd' [run]);
    // calling run(dispatch) issues the fetch.
    String driver =
        """
        globalThis.window = globalThis;
        var captured = [];
        globalThis.fetch = function(url, init){
          captured.push(init.method + ' ' + url + ' ct=' + ((init.headers||{})['Content-Type']||'-'));
          var noop = { then:function(){ return noop; }, catch:function(){ return noop; } };
          return noop;
        };
        """;
    String drive =
        """
        _$g._[0](function(){});
        _$p._[0](function(){});
        _$r._[0](function(){});
        process.stdout.write(captured.join('|'));
        """;
    String program = driver + JsCompiler.declarationsScriptWithDom(module) + "\n" + drive;
    String out = runNode(program);
    assertEquals(
        "GET /g ct=-|POST /p ct=application/json|DELETE /d ct=-", out,
        "Http.get -> GET, Http.post -> POST (with its json body's Content-Type), "
            + "Http.request -> its declared method");
  }

  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").start();
      p.getInputStream().readAllBytes();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-http-", ".js");
      Files.writeString(file, program, StandardCharsets.UTF_8);
      Process p = new ProcessBuilder("node", file.toString()).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("node timed out");
      }
      Files.deleteIfExists(file);
      if (p.exitValue() != 0) {
        throw new IllegalStateException("node failed: " + err);
      }
      return out;
    } catch (IOException | InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }
}

package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runtime behaviour of the code-splitting kernel ({@code $a}, {@code $elm$chunkLoaded}, {@code
 * Chunk.load}) under Node — the global-namespace mechanism a split build relies on.
 */
class JsChunkLoaderTest {

  @Test
  void aResolvesAfterTheChunkPopulatesTheGlobalAndThrowsBefore() {
    assumeTrue(nodeAvailable());
    // The base reaches a chunk symbol only via $a("tag","name"). Before the chunk loads, $a throws a
    // clear error; after the chunk script defines the global and signals ready, $a resolves it. Then a
    // Chunk.load Cmd in a no-document (Node) context succeeds immediately (everything is already here).
    String driver =
        String.join(
            "\n",
            "var out=[];",
            "try { $a('Heavy','run'); out.push('before:NO-THROW'); }",
            "catch(e){ out.push('before:'+/Chunk not loaded/.test(e.message)); }",
            "$self['_$Heavy$run']=function(x){return x+1;};", // the chunk script's effect
            "$self.$elm$chunkLoaded('heavy');",
            "out.push('after:'+$a('Heavy','run')(41));",
            "var cmd=$rt['Chunk.load']('heavy')(function(r){return r;});",
            "cmd._[0](function(msg){ out.push('cmd:'+msg.$); });",
            "process.stdout.write(out.join('|'));");
    // dom.js is the browser runtime — give it a `window` to load under Node (but no `document`, so
    // Chunk.load takes its headless immediate-success path).
    String program =
        "globalThis.window=globalThis;\n" + JsRuntime.SOURCE + "\n" + JsRuntime.DOM + "\n" + driver;
    assertEquals("before:true|after:42|cmd:Ok", runNode(program));
  }

  @Test
  void chunkLoadInjectsTheScriptThenDispatchesAndAResolves() {
    assumeTrue(nodeAvailable());
    // The real browser path: with a document present, Chunk.load injects the chunk <script>; once it
    // runs (defining the global and calling $elm$chunkLoaded), the Cmd dispatches Ok and $a resolves
    // the now-loaded symbol. A shim document evals a stand-in chunk on appendChild and fires onload.
    String shim =
        String.join(
            "\n",
            "globalThis.window=globalThis;",
            // a stand-in chunk: defines the global symbol and signals ready (as a real chunk file does)
            "var CHUNK=\"globalThis['_$Heavy$run']=function(x){return x+1;}; $elm$chunkLoaded('heavy');\";",
            "globalThis.document={ createElement:function(){return {};},",
            "  head:{ appendChild:function(s){ if(s.src){ (0,eval)(CHUNK); setTimeout(function(){ if(s.onload) s.onload(); },0); } } } };");
    String driver =
        String.join(
            "\n",
            "var cmd=$rt['Chunk.load']('heavy')(function(r){return r;});",
            "cmd._[0](function(msg){",
            "  process.stdout.write('loaded:'+msg.$+'|run:'+$a('Heavy','run')(41));",
            "});");
    String program = shim + "\n" + JsRuntime.SOURCE + "\n" + JsRuntime.DOM + "\n" + driver;
    assertEquals("loaded:Ok|run:42", runNode(program));
  }

  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-chunk-", ".js");
      Files.writeString(file, program, StandardCharsets.UTF_8);
      Process p = new ProcessBuilder("node", file.toString()).redirectErrorStream(false).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(30, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IllegalStateException("node timed out");
      }
      Files.deleteIfExists(file);
      if (p.exitValue() != 0) {
        throw new IllegalStateException("node failed: " + err + "\n--- program ---\n" + program);
      }
      return out;
    } catch (IOException | InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static boolean nodeAvailable() {
    try {
      Process p = new ProcessBuilder("node", "--version").start();
      return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }
}

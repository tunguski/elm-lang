package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A coverage gate for the JS runtime ({@code kernel.js} + {@code dom.js}). JaCoCo measures only JVM
 * bytecode, so the ~1900-line JS runtime — the code every compiled Elm app ships on — is invisible
 * to the project's coverage numbers. This test fills that blind spot without any npm tooling: it
 * runs a broad exercise (a stdlib-heavy pure probe for kernel.js, plus mounting a sandbox and
 * dispatching to drive render/diff/events for dom.js) under Node's built-in {@code
 * NODE_V8_COVERAGE}, then asserts the runtime bundle's function coverage stays at or above a ratchet.
 *
 * <p>The absolute number is modest because the runtime is a <em>library</em>: the bundle defines a
 * function per stdlib builtin and per Html/Svg element, the large majority of which no single
 * program uses. So this is a regression smoke-gate — a notable drop means a core path (render/diff,
 * {@code $mount}/{@code $dispatch}, the exercised stdlib) stopped being reached, not that any one
 * leaf builtin is untested. Per-leaf coverage is the job of {@code JsKernelSignatureCoverageTest}
 * (every binding exists) and the functional suites; a higher cumulative number would come from
 * aggregating {@code NODE_V8_COVERAGE} across the whole Node-running suite (a CI enhancement). Raise
 * {@link #MIN_FUNCTION_COVERAGE} if the exercise is broadened.
 */
class JsRuntimeCoverageTest {

  /** Ratchet: the fraction of runtime-bundle functions the broad exercise below must reach (it
   *  measures ~29%; held just under that as a regression floor, not an aspirational target). */
  private static final double MIN_FUNCTION_COVERAGE = 0.25;

  @Test
  void jsRuntimeBundleIsExercised() throws Exception {
    assumeTrue(nodeAvailable(), "node not installed");
    Path dir = Files.createTempDirectory("elm-jscov-");
    Path bundle = dir.resolve("bundle.js");
    Files.writeString(bundle, exercise(), StandardCharsets.UTF_8);
    Path covDir = dir.resolve("cov");

    // Run the exercise with V8 coverage on.
    runNode(bundle, covDir);

    // Reduce the coverage to covered/total functions for the bundle script.
    String ratio = computeCoverage(dir, covDir, bundle);
    String[] parts = ratio.trim().split("/");
    int covered = Integer.parseInt(parts[0]);
    int total = Integer.parseInt(parts[1]);
    double pct = covered / (double) total;
    assertTrue(
        pct >= MIN_FUNCTION_COVERAGE,
        String.format(
            "JS runtime function coverage %d/%d (%.1f%%) is below the %.0f%% ratchet — the exercise"
                + " no longer reaches a part of kernel.js/dom.js it used to (or a large untested"
                + " region was added).",
            covered, total, pct * 100, MIN_FUNCTION_COVERAGE * 100));
  }

  /** A self-contained program: DOM shim + runtime bundle + a broad exercise of kernel and dom. */
  private static String exercise() {
    String module =
        """
        module M exposing (main, probe)
        import Browser
        import Html exposing (..)
        import Html.Attributes exposing (class, id, href, value)
        import Html.Events exposing (onClick)
        import Svg
        import Svg.Attributes as SA
        import Dict
        import Set
        import Array
        import Bitwise
        import Char
        import Json.Encode as E
        import Json.Decode as D
        probe =
            String.join ","
                [ String.fromInt (List.sum (List.map (\\n -> n * 2) (List.range 1 5)))
                , String.fromInt (List.length (List.filter (\\n -> n > 2) [ 1, 2, 3, 4 ]))
                , String.fromInt (List.foldl (+) 0 (List.reverse (List.sort [ 3, 1, 2 ])))
                , String.fromInt (List.foldr (-) 0 (List.indexedMap (+) [ 1, 2 ]))
                , String.join "-" (List.concatMap (\\n -> [ String.fromInt n ]) (List.take 2 (List.drop 1 (List.range 1 9))))
                , String.fromInt (Dict.size (Dict.remove 1 (Dict.insert 3 "c" (Dict.fromList [ ( 1, "a" ), ( 2, "b" ) ]))))
                , String.join "" (Dict.values (Dict.map (\\_ v -> v) (Dict.fromList [ ( 1, "a" ) ])))
                , String.fromInt (Set.size (Set.union (Set.fromList [ 1, 2 ]) (Set.diff (Set.fromList [ 2, 3, 4 ]) (Set.fromList [ 4 ]))))
                , String.fromInt (Maybe.withDefault 0 (Array.get 1 (Array.push 9 (Array.fromList [ 1, 2, 3 ]))))
                , E.encode 0 (E.object [ ( "x", E.int 1 ), ( "y", E.list E.int [ 1, 2 ] ), ( "b", E.bool True ) ])
                , Result.withDefault "?" (D.decodeString (D.field "a" D.string) "{\\"a\\":\\"ok\\"}")
                , String.toUpper (String.reverse (String.append (String.left 2 "abcd") (String.right 1 "xyz")))
                , String.fromInt (String.length (String.trim (String.replace "x" "y" "  xx  ")))
                , String.fromInt (Maybe.withDefault 0 (List.maximum [ 9, 8 ]))
                , String.fromInt (Result.withDefault 0 (String.toInt "42"))
                , String.fromInt (Bitwise.or (Bitwise.and 6 3) (Bitwise.shiftLeftBy 1 1))
                , String.fromChar (Char.toUpper (Char.fromCode 98))
                , String.fromInt (Tuple.first (Tuple.mapSecond (\\x -> x + 1) ( 5, 6 )))
                , String.fromInt (abs (negate (clamp 0 10 20)) + modBy 3 7 + (2 ^ 3))
                ]
        type Msg = Bump
        view n =
            div [ class "c", id "i" ]
                [ button [ onClick Bump ] [ text (String.fromInt n) ]
                , a [ href "/x" ] [ text "link" ]
                , Svg.svg [ SA.width "40" ] [ Svg.circle [ SA.cx "5", SA.r "3" ] [], Svg.rect [ SA.x "1" ] [] ]
                , ul [] (List.map (\\i -> li [ value (String.fromInt i) ] [ text (String.fromInt i) ]) (List.range 0 n))
                ]
        main = Browser.sandbox { init = 0, update = \\_ n -> n + 1, view = view }
        """;
    String shim =
        """
        globalThis.requestAnimationFrame=function(){};
        function $cEl(tag,ns){return{tag:tag,nodeType:1,namespaceURI:ns||'h',_attrs:{},_style:{},style:{setProperty:function(){},removeProperty:function(){}},childNodes:[],setAttribute:function(k,v){this._attrs[k]=v;},removeAttribute:function(k){delete this._attrs[k];},setAttributeNS:function(n,k,v){this._attrs[k]=v;},appendChild:function(c){this.childNodes.push(c);return c;},insertBefore:function(c,r){var i=r?this.childNodes.indexOf(r):this.childNodes.length;if(i<0)i=this.childNodes.length;this.childNodes.splice(i,0,c);return c;},removeChild:function(c){var i=this.childNodes.indexOf(c);if(i>=0)this.childNodes.splice(i,1);return c;},replaceChild:function(n,o){var i=this.childNodes.indexOf(o);if(i>=0)this.childNodes[i]=n;return o;},addEventListener:function(){},removeEventListener:function(){},get lastChild(){return this.childNodes[this.childNodes.length-1];}};}
        globalThis.document={createElement:function(t){return $cEl(t);},createElementNS:function(ns,t){return $cEl(t,ns);},createTextNode:function(s){return{nodeType:3,nodeValue:String(s)};}};
        globalThis.window=globalThis;
        """;
    String body =
        // window.$dispatch(0);window.$dispatch(0); -> re-render -> $patch, keyed/list diff
        """
        var root=$cEl('root');
        window.$mount($data('$Sandbox',[{init:0,update:function(m){return function(n){return n+1;};},view:_$M$view}]),root);
        window.$dispatch(0);window.$dispatch(0);
        if(typeof _$M$probe!=='string') throw new Error('probe did not evaluate');
        process.stdout.write(_$M$probe);
        """;
    return shim + JsCompiler.declarationsScriptWithDomProject(module) + "\n" + body;
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

  /** Runs {@code bundle} under Node with V8 coverage writing into {@code covDir}. */
  private static void runNode(Path bundle, Path covDir) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("node", bundle.toString());
    pb.environment().put("NODE_V8_COVERAGE", covDir.toString());
    Process p = pb.start();
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    p.getInputStream().readAllBytes();
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("node timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException("node exercise failed: " + err);
    }
  }

  /** Runs a tiny Node reducer over the V8 coverage, returning "covered/total" functions for the bundle. */
  private static String computeCoverage(Path dir, Path covDir, Path bundle)
      throws IOException, InterruptedException {
    Path reducer = dir.resolve("reduce.js");
    Files.writeString(
        reducer,
        """
        const fs=require('fs'),path=require('path');
        const dir=process.argv[2], name=path.basename(process.argv[3]);
        let total=0, covered=0;
        for(const f of fs.readdirSync(dir).filter(x=>x.endsWith('.json'))){
          const c=JSON.parse(fs.readFileSync(path.join(dir,f),'utf8'));
          for(const r of c.result){ if(!r.url.endsWith(name)) continue;
            for(const fn of r.functions){ total++; if(fn.ranges[0].count>0) covered++; } } }
        process.stdout.write(covered+'/'+total);
        """,
        StandardCharsets.UTF_8);
    Process p =
        new ProcessBuilder("node", reducer.toString(), covDir.toString(), bundle.toString()).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    p.waitFor(30, TimeUnit.SECONDS);
    if (p.exitValue() != 0) {
      throw new IllegalStateException("coverage reducer failed: " + err);
    }
    return out;
  }
}

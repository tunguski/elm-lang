package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Regression for the top-level binding emission order in the JS backend. Parameterless top-level
 * values are eagerly initialised at module load, so each must be emitted AFTER every value it reads
 * <em>eagerly</em>. The original dependency analysis treated a reference that occurs only inside a
 * lambda (an event-handler decoder, a stored callback) as eager too. A common, real shape — a
 * top-level list {@code sections = [intro, usage]} whose elements, through a shared helper, capture
 * {@code sections} inside a click-handler lambda — then looked cyclic, and the topological sort
 * could only break the false cycle by emitting {@code sections} before {@code intro}. {@code
 * sections} would then read {@code intro} as an unassigned {@code var} (i.e. {@code undefined}); the
 * first List operation to touch it threw {@code $listToArray: got undefined} at load and the page
 * rendered blank. The fix classifies inside-a-lambda references as deferred (they run only after all
 * initialisers complete) so they impose no false ordering constraint.
 */
class TopLevelBindingOrderTest {

  // A Browser.element app with the false-cycle shape AND a model whose later fields are List-typed
  // (the originally reported trigger): `allSections` eagerly lists the sibling section values, while
  // each section, via `sectionShell`, captures `allSections` inside an `on "click"` decoder lambda.
  private static final String SOURCE =
      """
      module Main exposing (main)
      import Browser
      import Html exposing (Html, div, text, ul, li, button)
      import Html.Events
      import Json.Decode as D
      type alias Model = { login : String, visibleCols : List String, selected : List Int }
      type Msg = JumpTo (List (Html Msg))
      main : Program () Model Msg
      main =
          Browser.element
              { init = \\_ -> ( { login = "u", visibleCols = cols, selected = sel }, Cmd.none )
              , update = \\_ m -> ( m, Cmd.none )
              , view = \\_ -> page
              , subscriptions = \\_ -> Sub.none
              }
      page : Html Msg
      page = div [] [ intro, usage ]
      intro : Html Msg
      intro = sectionShell (ul [] (List.map (\\c -> li [] [ text c ]) cols))
      usage : Html Msg
      usage = sectionShell (ul [] (List.map (\\i -> li [] [ text (String.fromInt i) ]) sel))
      sectionShell : Html Msg -> Html Msg
      sectionShell body =
          div []
              [ button [ Html.Events.on "click" (D.map (\\_ -> JumpTo allSections) (D.succeed ())) ] [ text "^" ]
              , body
              ]
      allSections : List (Html Msg)
      allSections = [ intro, usage ]
      cols : List String
      cols = [ "id", "title" ]
      sel : List Int
      sel = [ 1, 2 ]
      """;

  @Test
  void eagerListIsEmittedAfterTheSiblingsItReferences() {
    String bundle = JsCompiler.appBundle(SOURCE);
    int allSections = bundle.indexOf("_$allSections =");
    int intro = bundle.indexOf("_$intro =");
    int usage = bundle.indexOf("_$usage =");
    assertTrue(intro >= 0 && usage >= 0 && allSections >= 0, "all top-level vars are emitted");
    // `allSections = [intro, usage]` reads both eagerly, so its declaration must come after theirs —
    // the deferred capture of `allSections` in the click handler must not reorder them.
    assertTrue(
        allSections > intro && allSections > usage,
        "allSections (eagerly listing intro/usage) must be declared after them, not before");
  }

  @Test
  void appMountsAndRendersWithoutUninitialisedListBinding() {
    org.junit.jupiter.api.Assumptions.assumeTrue(nodeAvailable(), "node not installed");
    String program = SHIM + JsCompiler.appBundle(SOURCE) + "\n" + SERIALIZE;
    String out = runNode(program);
    // Both sections render with their list items — proving no top-level List binding was read as
    // `undefined` during init (which would have thrown $listToArray and produced an empty render).
    assertTrue(out.contains("<li>id</li>") && out.contains("<li>title</li>"), out);
    assertTrue(out.contains("<li>1</li>") && out.contains("<li>2</li>"), out);
  }

  // A minimal DOM the runtime can mount into; getElementById('app') is the mount root.
  private static final String SHIM =
      """
      globalThis.requestAnimationFrame=function(f){};
      function $e(tag,ns){var s={};return{tag:tag,nodeType:1,
      namespaceURI:ns||'http://www.w3.org/1999/xhtml',_attrs:{},_style:s,
      style:{setProperty:function(k,v){s[k]=String(v);},removeProperty:function(k){delete s[k];}},
      childNodes:[],setAttribute:function(k,v){this._attrs[k]=String(v);},
      removeAttribute:function(k){delete this._attrs[k];},
      setAttributeNS:function(n,k,v){this._attrs[k]=String(v);},
      appendChild:function(c){this.childNodes.push(c);return c;},
      insertBefore:function(c,r){var i=r?this.childNodes.indexOf(r):this.childNodes.length;
      if(i<0)i=this.childNodes.length;this.childNodes.splice(i,0,c);return c;},
      removeChild:function(c){var i=this.childNodes.indexOf(c);if(i>=0)this.childNodes.splice(i,1);return c;},
      replaceChild:function(n,o){var i=this.childNodes.indexOf(o);if(i>=0)this.childNodes[i]=n;else this.childNodes.push(n);return o;},
      addEventListener:function(){},removeEventListener:function(){},
      get lastChild(){return this.childNodes[this.childNodes.length-1];}};}
      var $root=$e('root');
      globalThis.document={createElement:function(t){return $e(t);},
      createElementNS:function(ns,t){return $e(t,ns);},
      createTextNode:function(s){return{nodeType:3,nodeValue:String(s)};},
      getElementById:function(){return $root;},title:''};
      globalThis.window=globalThis;
      function $ser(n){if(n.nodeType===3)return n.nodeValue;
      return '<'+n.tag+'>'+n.childNodes.map($ser).join('')+'</'+n.tag+'>';}
      """;

  private static final String SERIALIZE = "process.stdout.write($ser($root));\n";

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
      Path file = Files.createTempFile("elm-order-", ".js");
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

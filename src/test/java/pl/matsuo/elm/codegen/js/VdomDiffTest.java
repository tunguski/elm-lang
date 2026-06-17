package pl.matsuo.elm.codegen.js;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Fast, headless coverage of the JS vdom diff ({@code $toDom}/{@code $patch}/{@code applyProps} in
 * dom.js) — the runtime correctness that previously could only be exercised through real Chrome
 * ({@code HeadlessChromeTest}, browser-tagged, slow). A tiny DOM shim under plain Node records what
 * the runtime does, so render-A → patch-to-B transitions (the shape most vdom bugs hide in: stale
 * attribute removal, namespace-by-context, tag swaps, style removal, keyed reorder) run cheaply in
 * the default test suite. Each recent vdom fix maps to a case here.
 */
class VdomDiffTest {

  @Test
  void diffTransitionsProduceCorrectDom() {
    // Each line is one render-A-then-patch-to-B transition; assert the resulting serialized DOM.
    String[] r = runDiffs().split("\n", -1);
    int i = 0;
    // 1. A class present in A but absent in B must be removed (not left stale).
    assertEquals("<div>x</div>", r[i++]);
    // 2. A changed class value is overwritten.
    assertEquals("<div class=\"y\">x</div>", r[i++]);
    // 3. A top-level <a> is in the HTML namespace (not the SVG <a>, which would be 0x0/invisible).
    assertEquals("<a href=\"/y\">k</a>", r[i++]);
    // 4. Inside <svg>, elements (incl. the ambiguous <a>) are in the SVG namespace.
    assertEquals("<svg:svg><svg:a></svg:a></svg:svg>", r[i++]);
    // 5. A changed tag replaces the node.
    assertEquals("<span>b</span>", r[i++]);
    // 6. A style set in A but absent in B is removed.
    assertEquals("<div></div>", r[i++]);
    // 7. Extra trailing children are removed when B is shorter.
    assertEquals("<ul><li>a</li></ul>", r[i++]);
  }

  /** Builds the harness, runs it under Node, returns one serialized DOM per transition (newline-sep). */
  private static String runDiffs() {
    String shim =
        "globalThis.requestAnimationFrame=function(){};\n"
            + "function $shimStyle(s){return{setProperty:function(k,v){s[k]=String(v);},"
            + "removeProperty:function(k){delete s[k];}};}\n"
            + "function $shimEl(tag,ns){var s={};return{tag:tag,nodeType:1,"
            + "namespaceURI:ns||'http://www.w3.org/1999/xhtml',_attrs:{},_style:s,style:$shimStyle(s),"
            + "childNodes:[],setAttribute:function(k,v){this._attrs[k]=String(v);},"
            + "removeAttribute:function(k){delete this._attrs[k];},"
            + "setAttributeNS:function(n,k,v){this._attrs[k]=String(v);},"
            + "appendChild:function(c){this.childNodes.push(c);return c;},"
            + "insertBefore:function(c,r){var i=r?this.childNodes.indexOf(r):this.childNodes.length;"
            + "if(i<0)i=this.childNodes.length;this.childNodes.splice(i,0,c);return c;},"
            + "removeChild:function(c){var i=this.childNodes.indexOf(c);if(i>=0)this.childNodes.splice(i,1);return c;},"
            + "replaceChild:function(n,old){var i=this.childNodes.indexOf(old);if(i>=0)this.childNodes[i]=n;else this.childNodes.push(n);return old;},"
            + "addEventListener:function(){},removeEventListener:function(){},"
            + "get lastChild(){return this.childNodes[this.childNodes.length-1];}};}\n"
            + "function $shimTxt(s){return{nodeType:3,nodeValue:String(s)};}\n"
            + "globalThis.document={createElement:function(t){return $shimEl(t);},"
            + "createElementNS:function(ns,t){return $shimEl(t,ns);},"
            + "createTextNode:function(s){return $shimTxt(s);}};\n"
            + "globalThis.window=globalThis;\n"
            + "function $ser(n){if(n.nodeType===3)return n.nodeValue;"
            + "var tag=(String(n.namespaceURI).indexOf('2000/svg')>=0?'svg:':'')+n.tag;"
            + "var a=Object.keys(n._attrs).sort().map(function(k){return ' '+k+'=\"'+n._attrs[k]+'\"';}).join('');"
            + "var st=Object.keys(n._style).sort().map(function(k){return k+':'+n._style[k];}).join(';');"
            + "if(st)a+=' style=\"'+st+'\"';"
            + "return '<'+tag+a+'>'+n.childNodes.map($ser).join('')+'</'+tag+'>';}\n";
    String body =
        "function el(mod,tag,attrs,kids){return $rt[mod+'.'+tag]($list(attrs))($list(kids));}\n"
            + "function at(n,v){return $rt['Html.Attributes.'+n](v);}\n"
            + "function tx(s){return $rt['Html.text'](s);}\n"
            // Drive the real re-render path: mount a sandbox whose view is A at init and B after any
            // message, then dispatch — so $mount renders A and the dispatch patches it to B.
            + "function diff(a,b){var root=$shimEl('root');"
            + "var def={init:false,update:function(msg){return function(model){return true;};},"
            + "view:function(model){return model?b:a;}};"
            + "window.$mount($data('$Sandbox',[def]),root);window.$dispatch(true);"
            + "return $ser(root.childNodes[0]);}\n"
            + "var out=[];\n"
            + "out.push(diff(el('Html','div',[at('class','x')],[tx('x')]),el('Html','div',[],[tx('x')])));\n"
            + "out.push(diff(el('Html','div',[at('class','x')],[tx('x')]),el('Html','div',[at('class','y')],[tx('x')])));\n"
            + "out.push(diff(el('Html','a',[at('href','/x')],[tx('k')]),el('Html','a',[at('href','/y')],[tx('k')])));\n"
            + "out.push(diff(el('Svg','svg',[],[el('Svg','a',[],[])]),el('Svg','svg',[],[el('Svg','a',[],[])])));\n"
            + "out.push(diff(el('Html','div',[],[tx('a')]),el('Html','span',[],[tx('b')])));\n"
            + "out.push(diff(el('Html','div',[$rt['Html.Attributes.style']('color')('red')],[]),el('Html','div',[],[])));\n"
            + "out.push(diff(el('Html','ul',[],[el('Html','li',[],[tx('a')]),el('Html','li',[],[tx('b')])]),"
            + "el('Html','ul',[],[el('Html','li',[],[tx('a')])])));\n"
            + "process.stdout.write(out.join('\\n'));\n";
    String program =
        shim + JsCompiler.declarationsScriptWithDomProject("module M exposing (x)\nx = 0\n") + "\n" + body;
    return runNode(program);
  }

  private static String runNode(String program) {
    try {
      Path file = Files.createTempFile("elm-vdom-", ".js");
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
        throw new IllegalStateException("node failed: " + err);
      }
      return out;
    } catch (IOException | InterruptedException ex) {
      throw new RuntimeException(ex);
    }
  }
}

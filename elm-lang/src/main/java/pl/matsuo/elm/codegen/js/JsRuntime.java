package pl.matsuo.elm.codegen.js;

/**
 * The JavaScript kernel runtime prepended to compiled output. Mirrors the interpreter's value model
 * and prelude: Int/Float are JS numbers, Bool is boolean, String is string, Char is {@code
 * {$:'Char',c}}, lists are nil/cons cells, tuples are {@code {$:'#',vs}}, records are plain objects
 * and custom-type values are {@code {$:ctor,_:[args]}}. Functions are native curried arrow
 * functions, so application is {@code f(x)(y)}.
 */
public final class JsRuntime {

  private JsRuntime() {}

  public static final String SOURCE =
      """
      "use strict";
      // ---- value constructors ----
      var $unit = {$:'()'};
      var $nil = {$:'[]'};
      function $cons(h, t){ return {$:'::', a:h, b:t}; }
      function $list(arr){ var r=$nil; for (var i=arr.length-1;i>=0;i--) r=$cons(arr[i], r); return r; }
      function $tuple(vs){ return {$:'#', vs:vs}; }
      function $char(c){ return {$:'Char', c:c}; }
      function $data(name, args){ return {$:name, _:args}; }
      function $update(rec, upd){ return Object.assign({}, rec, upd); }
      function $listToArray(v){ var a=[]; while(v.$==='::'){a.push(v.a); v=v.b;} return a; }

      // ---- equality & comparison ----
      function $eq(x, y){
        if (typeof x === 'number' || typeof x === 'boolean' || typeof x === 'string') return x === y;
        if (x.$ === 'Char') return y.$==='Char' && x.c === y.c;
        if (x.$ === '#'){ for (var i=0;i<x.vs.length;i++) if(!$eq(x.vs[i],y.vs[i])) return false; return true; }
        if (x.$ === '::' || x.$ === '[]'){
          while (x.$==='::' && y.$==='::'){ if(!$eq(x.a,y.a)) return false; x=x.b; y=y.b; }
          return x.$ === y.$;
        }
        if (x._ !== undefined){
          if (x.$ !== y.$ || x._.length !== y._.length) return false;
          for (var j=0;j<x._.length;j++) if(!$eq(x._[j],y._[j])) return false;
          return true;
        }
        // record
        var ks = Object.keys(x);
        for (var k=0;k<ks.length;k++) if(!$eq(x[ks[k]], y[ks[k]])) return false;
        return true;
      }
      function $cmp(x, y){
        if (typeof x === 'number') return x<y?-1:(x>y?1:0);
        if (typeof x === 'string') return x<y?-1:(x>y?1:0);
        if (x.$ === 'Char') return x.c<y.c?-1:(x.c>y.c?1:0);
        if (x.$ === '::' || x.$ === '[]'){
          while (x.$==='::' && y.$==='::'){ var c=$cmp(x.a,y.a); if(c!==0) return c; x=x.b; y=y.b; }
          return (x.$==='::'?1:0) - (y.$==='::'?1:0);
        }
        if (x.$ === '#'){ for (var i=0;i<x.vs.length;i++){ var d=$cmp(x.vs[i],y.vs[i]); if(d!==0) return d; } return 0; }
        throw new Error('cannot compare');
      }
      function $append(x, y){
        if (typeof x === 'string') return x + y;
        return $list($listToArray(x).concat($listToArray(y)));
      }
      function $compose(f, g){ return function(x){ return f(g(x)); }; }
      function $ord(c){ return $data(c<0?'LT':(c===0?'EQ':'GT'), []); }

      // ---- show (matches Show.plain in the interpreter) ----
      function $showArg(x){
        var s=$show(x,true);
        if (x && typeof x==='object' && x._!==undefined && x._.length>0) return '('+s+')';
        if (typeof x==='number' && x<0) return '('+s+')';
        return s;
      }
      function $show(v, q){
        if (typeof v === 'number') return String(v);
        if (typeof v === 'boolean') return v ? 'True' : 'False';
        if (typeof v === 'string') return q ? '"'+v+'"' : v;
        if (typeof v === 'function') return '<function>';
        var t = v.$;
        if (t === 'Char') return q ? "'"+String.fromCodePoint(v.c)+"'" : String.fromCodePoint(v.c);
        if (t === '()') return '()';
        if (t === '::' || t === '[]'){ var a=$listToArray(v).map(function(x){return $show(x,true);}); return '['+a.join(',')+']'; }
        if (t === '#'){ return '('+v.vs.map(function(x){return $show(x,true);}).join(',')+')'; }
        if (t !== undefined){ if (v._.length===0) return t; return t+' '+v._.map($showArg).join(' '); }
        var ks=Object.keys(v);
        return '{ '+ks.map(function(k){return k+' = '+$show(v[k],true);}).join(', ')+' }';
      }

      // ---- prelude (canonical Module.name -> curried function) ----
      function $maybe(v){ return v===undefined?$data('Nothing',[]):$data('Just',[v]); }
      var $rt = {
        'Basics.identity': function(x){ return x; },
        'Basics.always': function(a){ return function(b){ return a; }; },
        'Basics.not': function(b){ return !b; },
        'Basics.negate': function(n){ return -n; },
        'Basics.abs': function(n){ return Math.abs(n); },
        'Basics.sqrt': function(n){ return Math.sqrt(n); },
        'Basics.toFloat': function(n){ return n; },
        'Basics.round': function(n){ return Math.round(n); },
        'Basics.floor': function(n){ return Math.floor(n); },
        'Basics.ceiling': function(n){ return Math.ceil(n); },
        'Basics.truncate': function(n){ return Math.trunc(n); },
        'Basics.modBy': function(m){ return function(n){ return ((n % m) + m) % m; }; },
        'Basics.remainderBy': function(m){ return function(n){ return n % m; }; },
        'Basics.min': function(a){ return function(b){ return $cmp(a,b)<=0?a:b; }; },
        'Basics.max': function(a){ return function(b){ return $cmp(a,b)>=0?a:b; }; },
        'Basics.compare': function(a){ return function(b){ return $ord($cmp(a,b)); }; },
        'Basics.clamp': function(lo){ return function(hi){ return function(x){ return $cmp(x,lo)<0?lo:($cmp(x,hi)>0?hi:x); }; }; },
        'Basics.xor': function(a){ return function(b){ return a !== b; }; },
        'Basics.pi': Math.PI,
        'Basics.e': Math.E,
        'List.map': function(f){ return function(xs){ return $list($listToArray(xs).map(function(x){return f(x);})); }; },
        'List.indexedMap': function(f){ return function(xs){ return $list($listToArray(xs).map(function(x,i){return f(i)(x);})); }; },
        'List.filter': function(f){ return function(xs){ return $list($listToArray(xs).filter(function(x){return f(x);})); }; },
        'List.foldl': function(f){ return function(acc){ return function(xs){ $listToArray(xs).forEach(function(x){ acc=f(x)(acc); }); return acc; }; }; },
        'List.foldr': function(f){ return function(acc){ return function(xs){ var a=$listToArray(xs); for(var i=a.length-1;i>=0;i--) acc=f(a[i])(acc); return acc; }; }; },
        'List.length': function(xs){ return $listToArray(xs).length; },
        'List.reverse': function(xs){ return $list($listToArray(xs).reverse()); },
        'List.member': function(x){ return function(xs){ return $listToArray(xs).some(function(y){return $eq(x,y);}); }; },
        'List.append': function(a){ return function(b){ return $append(a,b); }; },
        'List.concat': function(xss){ var out=[]; $listToArray(xss).forEach(function(xs){ out=out.concat($listToArray(xs)); }); return $list(out); },
        'List.sum': function(xs){ return $listToArray(xs).reduce(function(a,b){return a+b;},0); },
        'List.product': function(xs){ return $listToArray(xs).reduce(function(a,b){return a*b;},1); },
        'List.range': function(lo){ return function(hi){ var out=[]; for(var i=lo;i<=hi;i++) out.push(i); return $list(out); }; },
        'List.head': function(xs){ return xs.$==='::'?$data('Just',[xs.a]):$data('Nothing',[]); },
        'List.tail': function(xs){ return xs.$==='::'?$data('Just',[xs.b]):$data('Nothing',[]); },
        'List.isEmpty': function(xs){ return xs.$==='[]'; },
        'List.take': function(n){ return function(xs){ return $list($listToArray(xs).slice(0,Math.max(0,n))); }; },
        'List.drop': function(n){ return function(xs){ return $list($listToArray(xs).slice(Math.max(0,n))); }; },
        'List.singleton': function(x){ return $cons(x,$nil); },
        'List.map2': function(f){ return function(xs){ return function(ys){ var a=$listToArray(xs), b=$listToArray(ys), o=[]; for(var i=0;i<Math.min(a.length,b.length);i++) o.push(f(a[i])(b[i])); return $list(o); }; }; },
        'List.sort': function(xs){ return $list($listToArray(xs).slice().sort($cmp)); },
        'List.sortBy': function(f){ return function(xs){ return $list($listToArray(xs).slice().sort(function(a,b){return $cmp(f(a),f(b));})); }; },
        'String.isEmpty': function(s){ return s.length===0; },
        'String.length': function(s){ return s.length; },
        'String.reverse': function(s){ return s.split('').reverse().join(''); },
        'String.append': function(a){ return function(b){ return a+b; }; },
        'String.concat': function(xs){ return $listToArray(xs).join(''); },
        'String.join': function(sep){ return function(xs){ return $listToArray(xs).join(sep); }; },
        'String.split': function(sep){ return function(s){ return $list(sep===''?s.split(''):s.split(sep)); }; },
        'String.words': function(s){ var t=s.trim(); return $list(t===''?[]:t.split(/\\s+/)); },
        'String.lines': function(s){ return $list(s.split('\\n')); },
        'String.toUpper': function(s){ return s.toUpperCase(); },
        'String.toLower': function(s){ return s.toLowerCase(); },
        'String.trim': function(s){ return s.trim(); },
        'String.fromInt': function(n){ return String(n); },
        'String.fromFloat': function(n){ return String(n); },
        'String.fromChar': function(c){ return String.fromCodePoint(c.c); },
        'String.toInt': function(s){ var n=parseInt(s,10); return (/^\\s*-?\\d+\\s*$/.test(s) && !isNaN(n))?$data('Just',[n]):$data('Nothing',[]); },
        'String.toFloat': function(s){ var n=parseFloat(s); return (!isNaN(n))?$data('Just',[n]):$data('Nothing',[]); },
        'String.contains': function(sub){ return function(s){ return s.indexOf(sub)>=0; }; },
        'String.startsWith': function(p){ return function(s){ return s.startsWith(p); }; },
        'String.endsWith': function(p){ return function(s){ return s.endsWith(p); }; },
        'String.left': function(n){ return function(s){ return s.slice(0,Math.max(0,n)); }; },
        'String.right': function(n){ return function(s){ return n<=0?'':s.slice(-n); }; },
        'String.dropLeft': function(n){ return function(s){ return s.slice(Math.max(0,n)); }; },
        'String.dropRight': function(n){ return function(s){ return n<=0?s:s.slice(0,-n); }; },
        'String.repeat': function(n){ return function(s){ return s.repeat(Math.max(0,n)); }; },
        'String.replace': function(a){ return function(b){ return function(s){ return s.split(a).join(b); }; }; },
        'String.toList': function(s){ return $list(Array.from(s).map(function(ch){return $char(ch.codePointAt(0));})); },
        'String.fromList': function(xs){ return $listToArray(xs).map(function(c){return String.fromCodePoint(c.c);}).join(''); },
        'Char.toCode': function(c){ return c.c; },
        'Char.fromCode': function(n){ return $char(n); },
        'Char.toUpper': function(c){ return $char(String.fromCodePoint(c.c).toUpperCase().codePointAt(0)); },
        'Char.toLower': function(c){ return $char(String.fromCodePoint(c.c).toLowerCase().codePointAt(0)); },
        'Char.isDigit': function(c){ return c.c>=48 && c.c<=57; },
        'Char.isUpper': function(c){ var s=String.fromCodePoint(c.c); return s!==s.toLowerCase() && s===s.toUpperCase(); },
        'Char.isLower': function(c){ var s=String.fromCodePoint(c.c); return s!==s.toUpperCase() && s===s.toLowerCase(); },
        'Char.isAlpha': function(c){ return /\\p{L}/u.test(String.fromCodePoint(c.c)); },
        'Maybe.withDefault': function(d){ return function(m){ return m.$==='Just'?m._[0]:d; }; },
        'Maybe.map': function(f){ return function(m){ return m.$==='Just'?$data('Just',[f(m._[0])]):m; }; },
        'Maybe.andThen': function(f){ return function(m){ return m.$==='Just'?f(m._[0]):m; }; },
        'Maybe.map2': function(f){ return function(a){ return function(b){ return (a.$==='Just'&&b.$==='Just')?$data('Just',[f(a._[0])(b._[0])]):$data('Nothing',[]); }; }; },
        'Result.withDefault': function(d){ return function(r){ return r.$==='Ok'?r._[0]:d; }; },
        'Result.map': function(f){ return function(r){ return r.$==='Ok'?$data('Ok',[f(r._[0])]):r; }; },
        'Result.mapError': function(f){ return function(r){ return r.$==='Err'?$data('Err',[f(r._[0])]):r; }; },
        'Result.andThen': function(f){ return function(r){ return r.$==='Ok'?f(r._[0]):r; }; },
        'Result.toMaybe': function(r){ return r.$==='Ok'?$data('Just',[r._[0]]):$data('Nothing',[]); },
        'Tuple.pair': function(a){ return function(b){ return $tuple([a,b]); }; },
        'Tuple.first': function(t){ return t.vs[0]; },
        'Tuple.second': function(t){ return t.vs[1]; },
        'Tuple.mapFirst': function(f){ return function(t){ return $tuple([f(t.vs[0]), t.vs[1]]); }; },
        'Tuple.mapSecond': function(f){ return function(t){ return $tuple([t.vs[0], f(t.vs[1])]); }; },
        'Debug.toString': function(v){ return $show(v,true); },
        'Debug.log': function(m){ return function(v){ console.log(m + ': ' + $show(v,true)); return v; }; }
      };
      function $g(name){ var v=$rt[name]; if (v===undefined) throw new Error('Unbound: '+name); return v; }
      """;

  /**
   * The browser DOM + The-Elm-Architecture runtime, appended after {@link #SOURCE} for app bundles.
   * Provides the Html/Svg/Browser/Events builtins (same canonical keys as the Java prelude) plus
   * {@code $mount}, which renders the virtual DOM to the real DOM, wires events and runs the
   * update loop. Exposes {@code window.$app.dispatch} so tests can drive messages.
   */
  public static final String DOM =
      """
      (function(){
        var SVG = 'http://www.w3.org/2000/svg';
        var SVG_TAGS = {svg:1,circle:1,rect:1,line:1,polygon:1,polyline:1,ellipse:1,g:1,path:1,image:1};
        function node(tag){ return function(attrs){ return function(kids){ return $data('$Node',[tag,attrs,kids]); }; }; }
        var elements = ['div','span','p','h1','h2','h3','h4','h5','h6','ul','ol','li','a','img',
          'button','input','label','form','section','header','footer','nav','br','hr','table','tr',
          'td','th','thead','tbody','pre','code','strong','em','small','select','option','textarea',
          'blockquote','cite','figure','figcaption','b','i'];
        elements.forEach(function(t){ $rt['Html.'+t]=node(t); });
        $rt['Html.text']=function(s){ return $data('$Text',[s]); };
        $rt['Html.node']=function(t){ return node(t); };
        var strAttrs=['class','id','href','src','alt','title','placeholder','value','name',
          'type_:type','for_:for','rel','target','min','max','step','cols','rows'];
        strAttrs.forEach(function(spec){ var p=spec.split(':'); var nm=p[1]||p[0];
          $rt['Html.Attributes.'+p[0]]=function(v){ return $data('$Att',[nm,v]); }; });
        $rt['Html.Attributes.width']=function(v){ return $data('$Att',['width',v]); };
        $rt['Html.Attributes.height']=function(v){ return $data('$Att',['height',v]); };
        ['disabled','checked','selected','required','autofocus','hidden','multiple'].forEach(function(nm){
          $rt['Html.Attributes.'+nm]=function(v){ return $data('$Prop',[nm,v]); }; });
        $rt['Html.Attributes.style']=function(k){ return function(v){ return $data('$Style',[k,v]); }; };
        $rt['Html.Events.onClick']=function(m){ return $data('$On',['click',m]); };
        $rt['Html.Events.onInput']=function(f){ return $data('$On',['input',f]); };
        $rt['Html.Events.onCheck']=function(f){ return $data('$On',['check',f]); };
        $rt['Html.Events.onSubmit']=function(m){ return $data('$On',['submit',m]); };
        $rt['Html.Events.on']=function(e){ return function(d){ return $data('$On',[e,d]); }; };
        $rt['Html.Events.preventDefaultOn']=function(e){ return function(d){ return $data('$On',[e,d]); }; };
        Object.keys(SVG_TAGS).forEach(function(t){ $rt['Svg.'+t]=node(t); });
        $rt['Svg.text']=function(s){ return $data('$Text',[s]); };
        var svgAttrs=['width','height','viewBox','cx','cy','r','x','y','x1','y1','x2','y2','rx','ry',
          'fill','stroke','strokeWidth:stroke-width','points','d','transform','opacity',
          'textAnchor:text-anchor','fontSize:font-size','xlinkHref:xlink:href'];
        svgAttrs.forEach(function(spec){ var p=spec.split(':'); var nm=spec.indexOf(':')<0?p[0]:spec.substring(spec.indexOf(':')+1);
          $rt['Svg.Attributes.'+p[0]]=function(v){ return $data('$Att',[nm,v]); }; });
        $rt['Browser.sandbox']=function(r){ return $data('$Sandbox',[r]); };
        $rt['Browser.element']=function(r){ return $data('$Element',[r]); };
        $rt['Browser.document']=function(r){ return $data('$Document',[r]); };
        $rt['Cmd.none']=$data('$CmdNone',[]); $rt['Cmd.batch']=function(l){ return $data('$CmdBatch',[l]); };
        $rt['Sub.none']=$data('$SubNone',[]); $rt['Sub.batch']=function(l){ return $data('$SubBatch',[l]); };

        function setAttr(el, a){
          var t=a.$, nm=a._[0], val=a._[1];
          if (t==='$Att'){
            // `value`/`checked` are controlled by the model; set the live property (and only when it
            // changed) so we don't fight the cursor, rather than the inert default-value attribute.
            if (nm==='value'){ var s=String(val); if(el.value!==s) el.value=s; }
            else el.setAttribute(nm, String(val));
          }
          else if (t==='$Prop'){ if (typeof val==='boolean'){ el[nm]=val; if(val) el.setAttribute(nm,''); else el.removeAttribute(nm); } else { el[nm]=val; el.setAttribute(nm,String(val)); } }
          else if (t==='$Style'){ el.style.setProperty(nm, val); }
          else if (t==='$On'){
            var ev=nm, h=a._[1];
            var domEvent = ev==='check'?'change':ev;
            var fn = function(e){
              var msg = (ev==='input') ? h(e.target.value) : (ev==='check') ? h(e.target.checked) : h;
              window.$dispatch(msg); e.stopPropagation();
            };
            el.addEventListener(domEvent, fn);
            (el.$ls=el.$ls||[]).push([domEvent, fn]);
          }
        }
        // (Re)apply a node's attribute list, clearing the previous render's listeners, styles and
        // any attributes no longer present, so an element can be reused across renders.
        function applyProps(el, attrs){
          (el.$ls||[]).forEach(function(l){ el.removeEventListener(l[0], l[1]); });
          el.$ls=[];
          var seen={}, styled={};
          attrs.forEach(function(a){
            if (a.$==='$Att'||a.$==='$Prop') seen[a._[0]]=1;
            else if (a.$==='$Style') styled[a._[0]]=1;
            setAttr(el,a);
          });
          // Drop attributes/styles set last render but absent now (don't touch cssText: assigning it
          // even an empty string leaves a stray `style=""` attribute on every element).
          (el.$at||[]).forEach(function(nm){ if(!seen[nm] && nm!=='value') el.removeAttribute(nm); });
          (el.$st||[]).forEach(function(nm){ if(!styled[nm]) el.style.removeProperty(nm); });
          el.$at=Object.keys(seen); el.$st=Object.keys(styled);
        }
        window.$toDom = function(v){
          if (v.$==='$Text') return document.createTextNode(String(v._[0]));
          var tag=v._[0];
          var el = SVG_TAGS[tag] ? document.createElementNS(SVG,tag) : document.createElement(tag);
          $listToArray(v._[1]).forEach(function(a){ setAttr(el,a); });
          $listToArray(v._[2]).forEach(function(k){ el.appendChild(window.$toDom(k)); });
          return el;
        };
        // Same virtual node kind? Text vs element, and matching element tag.
        function $sameType(a,b){ return a.$===b.$ && (a.$!=='$Node' || a._[0]===b._[0]); }
        // Diff old/new virtual nodes and patch the real DOM in place, preserving element identity
        // (and thus focus/selection on inputs) instead of rebuilding the subtree every render.
        function $patch(parent, dom, oldV, newV){
          if (oldV==null){ var n=window.$toDom(newV); parent.appendChild(n); return n; }
          if (newV==null){ if(dom) parent.removeChild(dom); return null; }
          if (!$sameType(oldV,newV)){ var n=window.$toDom(newV); parent.replaceChild(n,dom); return n; }
          if (newV.$==='$Text'){ var s=String(newV._[0]); if(dom.nodeValue!==s) dom.nodeValue=s; return dom; }
          applyProps(dom, $listToArray(newV._[1]));
          var oldKids=$listToArray(oldV._[2]), newKids=$listToArray(newV._[2]);
          for (var i=0;i<newKids.length;i++){ $patch(dom, dom.childNodes[i]||null, oldKids[i]||null, newKids[i]); }
          for (var j=oldKids.length-1;j>=newKids.length;j--){ if(dom.childNodes[j]) dom.removeChild(dom.childNodes[j]); }
          return dom;
        }
        window.$mount = function(program, root){
          var def = program._[0], kind = program.$, model;
          if (kind==='$Sandbox') model = def.init;
          else { var pair = def.init($unit); model = pair.vs[0]; }
          var current=null, dom=null;
          function viewVNode(){
            var v = def.view(model);
            if (kind==='$Document'){ v = $data('$Node',['div', $nil, v.body]); }
            return v;
          }
          function render(){
            var v = viewVNode();
            if (dom==null){ dom = window.$toDom(v); root.appendChild(dom); }
            else { dom = $patch(root, dom, current, v); }
            current = v;
          }
          window.$dispatch = function(msg){
            if (kind==='$Sandbox') model = def.update(msg)(model);
            else { var pair = def.update(msg)(model); model = pair.vs[0]; }
            render();
          };
          window.$app = { dispatch: function(m){ window.$dispatch(m); }, model: function(){ return model; } };
          render();
        };
        // Entry point: a static Html value is rendered directly; a Browser program is mounted.
        window.$start = function(main, root){
          if (main.$==='$Node' || main.$==='$Text') { root.appendChild(window.$toDom(main)); }
          else { window.$mount(main, root); }
        };
      })();
      """;
}

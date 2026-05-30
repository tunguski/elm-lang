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
        'Basics.sin': function(n){ return Math.sin(n); },
        'Basics.cos': function(n){ return Math.cos(n); },
        'Basics.tan': function(n){ return Math.tan(n); },
        'Basics.asin': function(n){ return Math.asin(n); },
        'Basics.acos': function(n){ return Math.acos(n); },
        'Basics.atan': function(n){ return Math.atan(n); },
        'Basics.atan2': function(y){ return function(x){ return Math.atan2(y,x); }; },
        'Basics.logBase': function(b){ return function(n){ return Math.log(n)/Math.log(b); }; },
        'Basics.degrees': function(d){ return d*Math.PI/180; },
        'Basics.radians': function(r){ return r; },
        'Basics.turns': function(t){ return t*2*Math.PI; },
        'Basics.toPolar': function(p){ var x=p.vs[0],y=p.vs[1]; return $tuple([Math.sqrt(x*x+y*y), Math.atan2(y,x)]); },
        'Basics.fromPolar': function(p){ var r=p.vs[0],t=p.vs[1]; return $tuple([r*Math.cos(t), r*Math.sin(t)]); },
        'Basics.isNaN': function(n){ return isNaN(n); },
        'Basics.isInfinite': function(n){ return !isFinite(n) && !isNaN(n); },
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
          'textAnchor:text-anchor','fontSize:font-size','xlinkHref:xlink:href',
          'strokeLinecap:stroke-linecap','strokeLinejoin:stroke-linejoin','fillOpacity:fill-opacity',
          'strokeOpacity:stroke-opacity','fontFamily:font-family','dominantBaseline:dominant-baseline',
          'strokeDasharray:stroke-dasharray','offset','stopColor:stop-color','gradientUnits:gradientUnits'];
        svgAttrs.forEach(function(spec){ var p=spec.split(':'); var nm=spec.indexOf(':')<0?p[0]:spec.substring(spec.indexOf(':')+1);
          $rt['Svg.Attributes.'+p[0]]=function(v){ return $data('$Att',[nm,v]); }; });
        $rt['Browser.sandbox']=function(r){ return $data('$Sandbox',[r]); };
        $rt['Browser.element']=function(r){ return $data('$Element',[r]); };
        $rt['Browser.document']=function(r){ return $data('$Document',[r]); };
        // ---- effects: Cmd / Sub / Task / Generator / Decoder kernels ----
        // A Cmd is $Cmd[run] where run(dispatch) performs the side effect; none/batch compose them.
        function $cmd(run){ return $data('$Cmd',[run]); }
        function runCmd(c, d){
          if(!c||c.$==='$CmdNone') return;
          if(c.$==='$CmdBatch'){ $listToArray(c._[0]).forEach(function(x){ runCmd(x,d); }); return; }
          if(c.$==='$Cmd'){ c._[0](d); }
        }
        $rt['Cmd.none']=$data('$CmdNone',[]); $rt['Cmd.batch']=function(l){ return $data('$CmdBatch',[l]); };
        $rt['Cmd.map']=function(f){ return function(c){ return $cmd(function(d){ runCmd(c, function(m){ d(f(m)); }); }); }; };
        // A Sub is $Sub[key,start]; start(dispatch) returns a stop function. Keyed for diffing.
        function $sub(key, start){ return $data('$Sub',[key,start]); }
        function collectSubs(s, out){
          if(!s||s.$==='$SubNone') return;
          if(s.$==='$SubBatch'){ $listToArray(s._[0]).forEach(function(x){ collectSubs(x,out); }); return; }
          if(s.$==='$Sub') out.push(s);
        }
        $rt['Sub.none']=$data('$SubNone',[]); $rt['Sub.batch']=function(l){ return $data('$SubBatch',[l]); };
        $rt['Sub.map']=function(f){ return function(s){ if(!s||s.$!=='$Sub') return s; return $sub('map:'+s._[0], function(d){ return s._[1](function(m){ d(f(m)); }); }); }; };
        // A Task is $Task[run] where run(onOk,onErr).
        function $task(run){ return $data('$Task',[run]); }
        $rt['Task.succeed']=function(v){ return $task(function(ok,err){ ok(v); }); };
        $rt['Task.fail']=function(e){ return $task(function(ok,err){ err(e); }); };
        $rt['Task.andThen']=function(f){ return function(t){ return $task(function(ok,err){ t._[0](function(v){ f(v)._[0](ok,err); }, err); }); }; };
        $rt['Task.map']=function(f){ return function(t){ return $task(function(ok,err){ t._[0](function(v){ ok(f(v)); }, err); }); }; };
        $rt['Task.sequence']=function(l){ var ts=$listToArray(l); return $task(function(ok,err){ var res=[]; (function go(i){ if(i>=ts.length){ ok($list(res)); return; } ts[i]._[0](function(v){ res.push(v); go(i+1); }, err); })(0); }); };
        $rt['Task.perform']=function(toMsg){ return function(t){ return $cmd(function(d){ t._[0](function(v){ d(toMsg(v)); }, function(e){}); }); }; };
        $rt['Task.attempt']=function(toMsg){ return function(t){ return $cmd(function(d){ t._[0](function(v){ d(toMsg($data('Ok',[v]))); }, function(e){ d(toMsg($data('Err',[e]))); }); }); }; };
        // Random: generators produce a value on demand (real client-side randomness).
        function $gen(g){ return $data('$Gen',[g]); }
        $rt['Random.int']=function(lo){ return function(hi){ return $gen(function(){ return lo+Math.floor(Math.random()*(hi-lo+1)); }); }; };
        $rt['Random.float']=function(lo){ return function(hi){ return $gen(function(){ return lo+Math.random()*(hi-lo); }); }; };
        $rt['Random.uniform']=function(x){ return function(xs){ var a=[x].concat($listToArray(xs)); return $gen(function(){ return a[Math.floor(Math.random()*a.length)]; }); }; };
        $rt['Random.weighted']=function(first){ return function(rest){ var ps=[first].concat($listToArray(rest)); return $gen(function(){ var total=0,i; for(i=0;i<ps.length;i++) total+=ps[i].vs[0]; var r=Math.random()*total; for(i=0;i<ps.length;i++){ r-=ps[i].vs[0]; if(r<=0) return ps[i].vs[1]; } return ps[ps.length-1].vs[1]; }); }; };
        $rt['Random.constant']=function(x){ return $gen(function(){ return x; }); };
        $rt['Random.list']=function(n){ return function(g){ return $gen(function(){ var r=[]; for(var i=0;i<n;i++) r.push(g._[0]()); return $list(r); }); }; };
        $rt['Random.pair']=function(a){ return function(b){ return $gen(function(){ return $tuple([a._[0](),b._[0]()]); }); }; };
        $rt['Random.map']=function(f){ return function(g){ return $gen(function(){ return f(g._[0]()); }); }; };
        $rt['Random.map2']=function(f){ return function(g1){ return function(g2){ return $gen(function(){ return f(g1._[0]())(g2._[0]()); }); }; }; };
        $rt['Random.map3']=function(f){ return function(g1){ return function(g2){ return function(g3){ return $gen(function(){ return f(g1._[0]())(g2._[0]())(g3._[0]()); }); }; }; }; };
        $rt['Random.andThen']=function(f){ return function(g){ return $gen(function(){ return f(g._[0]())._[0](); }); }; };
        $rt['Random.generate']=function(toMsg){ return function(g){ return $cmd(function(d){ d(toMsg(g._[0]())); }); }; };
        // Json.Decode: a decoder is $Dec[run] where run(jsValue) -> {ok, v}. (v is the value or error.)
        function $dec(run){ return $data('$Dec',[run]); }
        function $arr(j){ return Array.isArray(j)?j:(j&&typeof j.length==='number'?[].slice.call(j):null); }
        $rt['Json.Decode.string']=$dec(function(j){ return typeof j==='string'?{ok:1,v:j}:{ok:0,v:'expected a string'}; });
        $rt['Json.Decode.int']=$dec(function(j){ return typeof j==='number'?{ok:1,v:j|0}:{ok:0,v:'expected an int'}; });
        $rt['Json.Decode.float']=$dec(function(j){ return typeof j==='number'?{ok:1,v:j}:{ok:0,v:'expected a float'}; });
        $rt['Json.Decode.bool']=$dec(function(j){ return typeof j==='boolean'?{ok:1,v:j}:{ok:0,v:'expected a bool'}; });
        $rt['Json.Decode.value']=$dec(function(j){ return {ok:1,v:j}; });
        $rt['Json.Decode.succeed']=function(v){ return $dec(function(j){ return {ok:1,v:v}; }); };
        $rt['Json.Decode.fail']=function(m){ return $dec(function(j){ return {ok:0,v:m}; }); };
        $rt['Json.Decode.field']=function(name){ return function(dec){ return $dec(function(j){ if(j==null||typeof j!=='object'||!(name in j)) return {ok:0,v:'no field '+name}; return dec._[0](j[name]); }); }; };
        $rt['Json.Decode.at']=function(path){ return function(dec){ var ks=$listToArray(path); return $dec(function(j){ for(var i=0;i<ks.length;i++){ if(j==null) return {ok:0,v:'bad path'}; j=j[ks[i]]; } return dec._[0](j); }); }; };
        $rt['Json.Decode.list']=function(dec){ return $dec(function(j){ var a=$arr(j); if(!a) return {ok:0,v:'expected a list'}; var r=[]; for(var i=0;i<a.length;i++){ var x=dec._[0](a[i]); if(!x.ok) return x; r.push(x.v); } return {ok:1,v:$list(r)}; }); };
        $rt['Json.Decode.map']=function(f){ return function(dec){ return $dec(function(j){ var x=dec._[0](j); return x.ok?{ok:1,v:f(x.v)}:x; }); }; };
        function decMapN(n){ return function(f){ var ds=[]; function step(d){ ds.push(d); if(ds.length<n) return step; var cap=ds.slice(); return $dec(function(j){ var acc=f; for(var i=0;i<cap.length;i++){ var x=cap[i]._[0](j); if(!x.ok) return x; acc=acc(x.v); } return {ok:1,v:acc}; }); } return step; }; }
        $rt['Json.Decode.map2']=decMapN(2); $rt['Json.Decode.map3']=decMapN(3); $rt['Json.Decode.map4']=decMapN(4);
        $rt['Json.Decode.map5']=decMapN(5); $rt['Json.Decode.map6']=decMapN(6);
        $rt['Json.Decode.andThen']=function(f){ return function(dec){ return $dec(function(j){ var x=dec._[0](j); if(!x.ok) return x; return f(x.v)._[0](j); }); }; };
        $rt['Json.Decode.oneOrMore']=function(f){ return function(dec){ return $dec(function(j){ var a=$arr(j); if(!a||a.length===0) return {ok:0,v:'expected a non-empty list'}; var head=dec._[0](a[0]); if(!head.ok) return head; var rest=[]; for(var i=1;i<a.length;i++){ var x=dec._[0](a[i]); if(!x.ok) return x; rest.push(x.v); } return {ok:1,v:f(head.v)($list(rest))}; }); }; };
        $rt['Json.Decode.oneOf']=function(l){ var ds=$listToArray(l); return $dec(function(j){ for(var i=0;i<ds.length;i++){ var x=ds[i]._[0](j); if(x.ok) return x; } return {ok:0,v:'no matching decoder'}; }); };
        $rt['Json.Decode.maybe']=function(dec){ return $dec(function(j){ var x=dec._[0](j); return x.ok?{ok:1,v:$data('Just',[x.v])}:{ok:1,v:$data('Nothing',[])}; }); };
        $rt['Json.Decode.nullable']=function(dec){ return $dec(function(j){ if(j==null) return {ok:1,v:$data('Nothing',[])}; var x=dec._[0](j); return x.ok?{ok:1,v:$data('Just',[x.v])}:x; }); };
        $rt['Json.Decode.decodeString']=function(dec){ return function(s){ try{ var x=dec._[0](JSON.parse(s)); return x.ok?$data('Ok',[x.v]):$data('Err',[$data('Failure',[String(x.v)])]); }catch(e){ return $data('Err',[$data('Failure',[String(e)])]); } }; };
        // Http: real fetch; any failure maps to an Http.Error so update's error branch renders.
        $rt['Http.expectString']=function(toMsg){ return $data('$Expect',['string',toMsg,null]); };
        $rt['Http.expectJson']=function(toMsg){ return function(dec){ return $data('$Expect',['json',toMsg,dec]); }; };
        function httpGet(req){ var url=req.url, ex=req.expect; return $cmd(function(d){
          fetch(url).then(function(r){ if(!r.ok) throw {bad:r.status}; return ex._[0]==='json'? r.json() : r.text(); })
            .then(function(data){ var msg; if(ex._[0]==='json'){ var x=ex._[2]._[0](data); msg=x.ok? ex._[1]($data('Ok',[x.v])) : ex._[1]($data('Err',[$data('BadBody',[String(x.v)])])); } else { msg=ex._[1]($data('Ok',[data])); } d(msg); })
            .catch(function(e){ var err = (e&&e.bad)? $data('BadStatus',[e.bad]) : $data('NetworkError',[]); d(ex._[1]($data('Err',[err]))); });
        }); }
        $rt['Http.get']=function(req){ return httpGet(req); };
        $rt['Http.request']=function(req){ return httpGet(req); };
        // Time: real wall clock; subscriptions via setInterval. Zone carries an offset in minutes.
        $rt['Time.millisToPosix']=function(n){ return n; };
        $rt['Time.posixToMillis']=function(p){ return p; };
        $rt['Time.now']=$task(function(ok,err){ ok(Date.now()); });
        $rt['Time.utc']=$data('$Zone',[0]);
        $rt['Time.here']=$task(function(ok,err){ ok($data('$Zone',[-new Date().getTimezoneOffset()])); });
        function zoned(p, zone){ return p + (zone&&zone._?zone._[0]:0)*60000; }
        $rt['Time.toHour']=function(z){ return function(p){ return Math.floor(zoned(p,z)/3600000)%24; }; };
        $rt['Time.toMinute']=function(z){ return function(p){ return Math.floor(zoned(p,z)/60000)%60; }; };
        $rt['Time.toSecond']=function(z){ return function(p){ return Math.floor(zoned(p,z)/1000)%60; }; };
        $rt['Time.toMillis']=function(z){ return function(p){ return zoned(p,z)%1000; }; };
        $rt['Time.every']=function(ms){ return function(toMsg){ return $sub('every:'+ms, function(d){ var id=setInterval(function(){ d(toMsg(Date.now())); }, ms); return function(){ clearInterval(id); }; }); }; };
        // File: real <input type=file> selection and FileReader-based reads.
        $rt['File.decoder']=$dec(function(j){ return (j&&typeof j==='object')?{ok:1,v:j}:{ok:0,v:'expected a file'}; });
        $rt['File.name']=function(f){ return f.name||''; };
        $rt['File.mime']=function(f){ return f.type||''; };
        $rt['File.size']=function(f){ return f.size||0; };
        $rt['File.toUrl']=function(file){ return $task(function(ok,err){ var r=new FileReader(); r.onload=function(){ ok(r.result); }; r.onerror=function(){ err($data('$FileErr',[])); }; r.readAsDataURL(file); }); };
        $rt['File.toString']=function(file){ return $task(function(ok,err){ var r=new FileReader(); r.onload=function(){ ok(r.result); }; r.onerror=function(){ err($data('$FileErr',[])); }; r.readAsText(file); }); };
        function selectInput(multiple, mimes){ var inp=document.createElement('input'); inp.type='file'; inp.accept=$listToArray(mimes).join(','); if(multiple) inp.multiple=true; return inp; }
        $rt['File.Select.file']=function(mimes){ return function(toMsg){ return $cmd(function(d){ var inp=selectInput(false,mimes); inp.onchange=function(){ if(inp.files[0]) d(toMsg(inp.files[0])); }; inp.click(); }); }; };
        $rt['File.Select.files']=function(mimes){ return function(toMsg){ return $cmd(function(d){ var inp=selectInput(true,mimes); inp.onchange=function(){ var fs=[].slice.call(inp.files); if(fs.length) d(toMsg(fs[0])($list(fs.slice(1)))); }; inp.click(); }); }; };

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
              var msg;
              if (ev==='input') msg = h(e.target.value);
              else if (ev==='check') msg = h(e.target.checked);
              else if (h && h.$==='$Dec'){ var r=h._[0](e); if(!r.ok) return; msg=r.v; } // on "ev" decoder
              else msg = h;
              if (msg!==undefined){ window.$dispatch(msg); }
              e.stopPropagation();
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
          var def = program._[0], kind = program.$, model, initCmd=null;
          if (kind==='$Sandbox') model = def.init;
          else { var pair = def.init($unit); model = pair.vs[0]; initCmd = pair.vs[1]; }
          var current=null, dom=null, subs={};
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
          // Reconcile subscriptions: keep running ones whose key persists, start new, stop gone.
          function syncSubs(){
            if (kind==='$Sandbox' || !def.subscriptions) return;
            var list=[]; collectSubs(def.subscriptions(model), list);
            var next={};
            list.forEach(function(s){ var k=s._[0]; next[k] = subs[k] || s._[1](window.$dispatch); });
            Object.keys(subs).forEach(function(k){ if(!next[k] && subs[k]) subs[k](); });
            subs = next;
          }
          window.$dispatch = function(msg){
            var cmd=null;
            if (kind==='$Sandbox') model = def.update(msg)(model);
            else { var pair = def.update(msg)(model); model = pair.vs[0]; cmd = pair.vs[1]; }
            render(); syncSubs();
            if (cmd) runCmd(cmd, window.$dispatch);
          };
          window.$app = { dispatch: function(m){ window.$dispatch(m); }, model: function(){ return model; } };
          render(); syncSubs();
          if (initCmd) runCmd(initCmd, window.$dispatch);
        };
        // Entry point: a static Html value is rendered directly; a Browser program is mounted.
        window.$start = function(main, root){
          if (main.$==='$Node' || main.$==='$Text') { root.appendChild(window.$toDom(main)); }
          else { window.$mount(main, root); }
        };
      })();
      """;
}

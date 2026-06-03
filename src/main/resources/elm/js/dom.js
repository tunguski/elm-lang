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
  $rt['Html.Attributes.attribute']=function(k){ return function(v){ return $data('$Att',[k,v]); }; };
  $rt['Html.Events.onClick']=function(m){ return $data('$On',['click',m]); };
  $rt['Html.Events.onInput']=function(f){ return $data('$On',['input',f]); };
  $rt['Html.Events.onCheck']=function(f){ return $data('$On',['check',f]); };
  $rt['Html.Events.onSubmit']=function(m){ return $data('$On',['submit',m]); };
  $rt['Html.Events.on']=function(e){ return function(d){ return $data('$On',[e,d]); }; };
  $rt['Html.Events.preventDefaultOn']=function(e){ return function(d){ return $data('$On',[e,d]); }; };
  // More plain-message event handlers ($On[domEvent, msg]).
  ['onDoubleClick:dblclick','onMouseDown:mousedown','onMouseUp:mouseup','onMouseEnter:mouseenter',
   'onMouseLeave:mouseleave','onMouseOver:mouseover','onMouseOut:mouseout','onFocus:focus','onBlur:blur'
  ].forEach(function(spec){ var p=spec.split(':'); $rt['Html.Events.'+p[0]]=function(m){ return $data('$On',[p[1],m]); }; });
  // classList: a class attribute of the classes whose flag is True.
  $rt['Html.Attributes.classList']=function(pairs){ var cs=[]; $listToArray(pairs).forEach(function(p){ if(p.vs[1]) cs.push(p.vs[0]); }); return $data('$Att',['class', cs.join(' ')]); };
  // Html.map / Svg.map: rebuild a virtual node, routing every event's message through f.
  function $mapAttr(f, a){
    if (a.$!=='$On') return a;
    var ev=a._[0], h=a._[1];
    if (ev==='input'||ev==='check') return $data('$On',[ev, function(x){ return f(h(x)); }]);
    if (h && h.$==='$Dec') return $data('$On',[ev, $data('$Dec',[function(e){ var r=h._[0](e); return r.ok?{ok:1,v:f(r.v)}:r; }])]);
    return $data('$On',[ev, f(h)]);
  }
  function $mapHtml(f, v){
    if (v.$==='$Node') return $data('$Node',[v._[0], $list($listToArray(v._[1]).map(function(a){return $mapAttr(f,a);})), $list($listToArray(v._[2]).map(function(k){return $mapHtml(f,k);}))]);
    return v; // $Text (and anything without events) is unchanged
  }
  $rt['Html.map']=function(f){ return function(v){ return $mapHtml(f, v); }; };
  $rt['Svg.map']=$rt['Html.map'];
  // Keyed nodes: children are (key, Html) tuples; the runtime diffs them by key (see $patchKeyed).
  $rt['Html.Keyed.node']=function(tag){ return function(attrs){ return function(kids){ return $data('$Keyed',[tag,attrs,kids]); }; }; };
  $rt['Html.Keyed.ul']=$rt['Html.Keyed.node']('ul');
  $rt['Html.Keyed.ol']=$rt['Html.Keyed.node']('ol');
  $rt['Svg.Keyed.node']=$rt['Html.Keyed.node'];
  // Lazy nodes: $Lazy[fn, args]. The view fn is re-run only when an arg changes (=== identity).
  $rt['Html.Lazy.lazy']=function(f){ return function(a){ return $data('$Lazy',[f,[a]]); }; };
  $rt['Html.Lazy.lazy2']=function(f){ return function(a){ return function(b){ return $data('$Lazy',[f,[a,b]]); }; }; };
  $rt['Html.Lazy.lazy3']=function(f){ return function(a){ return function(b){ return function(c){ return $data('$Lazy',[f,[a,b,c]]); }; }; }; };
  $rt['Html.Lazy.lazy4']=function(f){ return function(a){ return function(b){ return function(c){ return function(d){ return $data('$Lazy',[f,[a,b,c,d]]); }; }; }; }; };
  $rt['Html.Lazy.lazy5']=function(f){ return function(a){ return function(b){ return function(c){ return function(d){ return function(e){ return $data('$Lazy',[f,[a,b,c,d,e]]); }; }; }; }; }; };
  $rt['Svg.Lazy.lazy']=$rt['Html.Lazy.lazy'];
  $rt['Svg.Lazy.lazy2']=$rt['Html.Lazy.lazy2'];
  $rt['Svg.Lazy.lazy3']=$rt['Html.Lazy.lazy3'];
  $rt['Svg.Lazy.lazy4']=$rt['Html.Lazy.lazy4'];
  $rt['Svg.Lazy.lazy5']=$rt['Html.Lazy.lazy5'];
  function $forceLazy(v){ var r=v._[0]; v._[1].forEach(function(a){ r=r(a); }); return r; }
  function $sameArgs(a,b){ if(!a||a.length!==b.length) return false; for(var i=0;i<a.length;i++){ if(a[i]!==b[i]) return false; } return true; }
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
  $rt['Browser.application']=function(r){ return $data('$Application',[r]); };
  $rt['Platform.worker']=function(r){ return $data('$Worker',[r]); }; // a headless program (no view)
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
  // ---- ports ----
  // Each port has subscribers (JS callbacks for outgoing values) and handlers (push values from JS
  // into incoming subscriptions). `$portOut`/`$portIn` build the Elm-side function for a `port`
  // declaration; `app.ports[name].subscribe/send` (wired in $mount) is the JS side.
  var $ports = {};
  function $ensurePort(name){ return $ports[name] || ($ports[name] = {subs:[], handlers:[]}); }
  window.$portOut = function(name){ $ensurePort(name); return function(v){ return $cmd(function(d){ $ensurePort(name).subs.forEach(function(f){ f(v); }); }); }; };
  window.$portIn = function(name){ $ensurePort(name); return function(toMsg){ return $sub('port:'+name, function(d){ var p=$ensurePort(name); var h=function(v){ d(toMsg(v)); }; p.handlers.push(h); return function(){ var i=p.handlers.indexOf(h); if(i>=0) p.handlers.splice(i,1); }; }); }; };
  function $portsApi(){ var api={}; Object.keys($ports).forEach(function(name){ api[name]={ subscribe:function(fn){ $ensurePort(name).subs.push(fn); }, unsubscribe:function(fn){ var s=$ensurePort(name).subs; var i=s.indexOf(fn); if(i>=0) s.splice(i,1); }, send:function(v){ $ensurePort(name).handlers.slice().forEach(function(h){ h(v); }); } }; }); return api; }
  // A Task is $Task[run] where run(onOk,onErr).
  function $task(run){ return $data('$Task',[run]); }
  $rt['Task.succeed']=function(v){ return $task(function(ok,err){ ok(v); }); };
  $rt['Task.fail']=function(e){ return $task(function(ok,err){ err(e); }); };
  $rt['Task.andThen']=function(f){ return function(t){ return $task(function(ok,err){ t._[0](function(v){ f(v)._[0](ok,err); }, err); }); }; };
  $rt['Task.map']=function(f){ return function(t){ return $task(function(ok,err){ t._[0](function(v){ ok(f(v)); }, err); }); }; };
  $rt['Task.mapError']=function(f){ return function(t){ return $task(function(ok,err){ t._[0](ok, function(e){ err(f(e)); }); }); }; };
  $rt['Task.onError']=function(f){ return function(t){ return $task(function(ok,err){ t._[0](ok, function(e){ f(e)._[0](ok,err); }); }); }; };
  $rt['Process.sleep']=function(ms){ return $task(function(ok,err){ setTimeout(function(){ ok($unit); }, ms); }); };
  $rt['Task.sequence']=function(l){ var ts=$listToArray(l); return $task(function(ok,err){ var res=[]; (function go(i){ if(i>=ts.length){ ok($list(res)); return; } ts[i]._[0](function(v){ res.push(v); go(i+1); }, err); })(0); }); };
  $rt['Task.perform']=function(toMsg){ return function(t){ return $cmd(function(d){ t._[0](function(v){ d(toMsg(v)); }, function(e){}); }); }; };
  $rt['Task.attempt']=function(toMsg){ return function(t){ return $cmd(function(d){ t._[0](function(v){ d(toMsg($data('Ok',[v]))); }, function(e){ d(toMsg($data('Err',[e]))); }); }); }; };
  // Random: generators produce a value on demand (real client-side randomness).
  function $gen(g){ return $data('$Gen',[g]); }
  // Generators draw from $rand (default Math.random); Random.step swaps in a seeded PRNG for purity.
  var $rand = Math.random;
  $rt['Random.int']=function(lo){ return function(hi){ return $gen(function(){ return lo+Math.floor($rand()*(hi-lo+1)); }); }; };
  $rt['Random.float']=function(lo){ return function(hi){ return $gen(function(){ return lo+$rand()*(hi-lo); }); }; };
  $rt['Random.uniform']=function(x){ return function(xs){ var a=[x].concat($listToArray(xs)); return $gen(function(){ return a[Math.floor($rand()*a.length)]; }); }; };
  $rt['Random.weighted']=function(first){ return function(rest){ var ps=[first].concat($listToArray(rest)); return $gen(function(){ var total=0,i; for(i=0;i<ps.length;i++) total+=ps[i].vs[0]; var r=$rand()*total; for(i=0;i<ps.length;i++){ r-=ps[i].vs[0]; if(r<=0) return ps[i].vs[1]; } return ps[ps.length-1].vs[1]; }); }; };
  $rt['Random.constant']=function(x){ return $gen(function(){ return x; }); };
  $rt['Random.list']=function(n){ return function(g){ return $gen(function(){ var r=[]; for(var i=0;i<n;i++) r.push(g._[0]()); return $list(r); }); }; };
  $rt['Random.pair']=function(a){ return function(b){ return $gen(function(){ return $tuple([a._[0](),b._[0]()]); }); }; };
  $rt['Random.map']=function(f){ return function(g){ return $gen(function(){ return f(g._[0]()); }); }; };
  $rt['Random.map2']=function(f){ return function(g1){ return function(g2){ return $gen(function(){ return f(g1._[0]())(g2._[0]()); }); }; }; };
  $rt['Random.map3']=function(f){ return function(g1){ return function(g2){ return function(g3){ return $gen(function(){ return f(g1._[0]())(g2._[0]())(g3._[0]()); }); }; }; }; };
  $rt['Random.map4']=function(f){ return function(g1){ return function(g2){ return function(g3){ return function(g4){ return $gen(function(){ return f(g1._[0]())(g2._[0]())(g3._[0]())(g4._[0]()); }); }; }; }; }; };
  $rt['Random.map5']=function(f){ return function(g1){ return function(g2){ return function(g3){ return function(g4){ return function(g5){ return $gen(function(){ return f(g1._[0]())(g2._[0]())(g3._[0]())(g4._[0]())(g5._[0]()); }); }; }; }; }; }; };
  $rt['Random.andThen']=function(f){ return function(g){ return $gen(function(){ return f(g._[0]())._[0](); }); }; };
  $rt['Random.generate']=function(toMsg){ return function(g){ return $cmd(function(d){ d(toMsg(g._[0]())); }); }; };
  // Pure seeded randomness: a Seed is $Seed[state]; step runs the generator against a seeded PRNG.
  $rt['Random.initialSeed']=function(n){ var s=(n>>>0)||1; return $data('$Seed',[s]); };
  $rt['Random.step']=function(gen){ return function(seed){
    var state=(seed._[0]>>>0)||1, prev=$rand;
    $rand=function(){ state^=state<<13; state^=state>>>17; state^=state<<5; state=state>>>0; return state/4294967296; };
    var v; try{ v=gen._[0](); } finally { $rand=prev; }
    return $tuple([v, $data('$Seed',[state])]); }; };
  $rt['Random.independentSeed']=$gen(function(){ return $data('$Seed',[Math.floor($rand()*4294967296)]); });
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
  $rt['Json.Decode.map7']=decMapN(7); $rt['Json.Decode.map8']=decMapN(8);
  $rt['Json.Decode.andThen']=function(f){ return function(dec){ return $dec(function(j){ var x=dec._[0](j); if(!x.ok) return x; return f(x.v)._[0](j); }); }; };
  $rt['Json.Decode.oneOrMore']=function(f){ return function(dec){ return $dec(function(j){ var a=$arr(j); if(!a||a.length===0) return {ok:0,v:'expected a non-empty list'}; var head=dec._[0](a[0]); if(!head.ok) return head; var rest=[]; for(var i=1;i<a.length;i++){ var x=dec._[0](a[i]); if(!x.ok) return x; rest.push(x.v); } return {ok:1,v:f(head.v)($list(rest))}; }); }; };
  $rt['Json.Decode.oneOf']=function(l){ var ds=$listToArray(l); return $dec(function(j){ for(var i=0;i<ds.length;i++){ var x=ds[i]._[0](j); if(x.ok) return x; } return {ok:0,v:'no matching decoder'}; }); };
  $rt['Json.Decode.maybe']=function(dec){ return $dec(function(j){ var x=dec._[0](j); return x.ok?{ok:1,v:$data('Just',[x.v])}:{ok:1,v:$data('Nothing',[])}; }); };
  $rt['Json.Decode.nullable']=function(dec){ return $dec(function(j){ if(j==null) return {ok:1,v:$data('Nothing',[])}; var x=dec._[0](j); return x.ok?{ok:1,v:$data('Just',[x.v])}:x; }); };
  $rt['Json.Decode.null']=function(v){ return $dec(function(j){ return j==null?{ok:1,v:v}:{ok:0,v:'expected null'}; }); };
  $rt['Json.Decode.index']=function(i){ return function(dec){ return $dec(function(j){ var a=$arr(j); if(!a||i<0||i>=a.length) return {ok:0,v:'expected index '+i}; return dec._[0](a[i]); }); }; };
  $rt['Json.Decode.lazy']=function(thunk){ return $dec(function(j){ return thunk(null)._[0](j); }); };
  $rt['Json.Decode.dict']=function(dec){ return $dec(function(j){ if(j==null||typeof j!=='object'||Array.isArray(j)) return {ok:0,v:'expected an object'}; var d={$:'Dict',a:[]}; var ks=Object.keys(j); for(var i=0;i<ks.length;i++){ var x=dec._[0](j[ks[i]]); if(!x.ok) return x; d=$dictInsert(d,ks[i],x.v); } return {ok:1,v:d}; }); };
  $rt['Json.Decode.keyValuePairs']=function(dec){ return $dec(function(j){ if(j==null||typeof j!=='object'||Array.isArray(j)) return {ok:0,v:'expected an object'}; var ks=Object.keys(j),r=[]; for(var i=0;i<ks.length;i++){ var x=dec._[0](j[ks[i]]); if(!x.ok) return x; r.push($tuple([ks[i],x.v])); } return {ok:1,v:$list(r)}; }); };
  // Url / Browser.Navigation: an elm/url-shaped record { protocol, host, port_, path, query, fragment }.
  function $url(href){ var u; try{ u=new URL(href, (typeof location!=='undefined'?location.href:'http://localhost/')); }catch(e){ return null; }
    return { protocol: (u.protocol==='https:'?$data('Https',[]):$data('Http',[])), host: u.hostname,
             port_: (u.port?$data('Just',[parseInt(u.port,10)]):$data('Nothing',[])), path: u.pathname,
             query: (u.search?$data('Just',[u.search.slice(1)]):$data('Nothing',[])),
             fragment: (u.hash?$data('Just',[u.hash.slice(1)]):$data('Nothing',[])) }; }
  function $urlToString(u){ var s=(u.protocol&&u.protocol.$==='Https'?'https':'http')+'://'+u.host;
    if(u.port_&&u.port_.$==='Just') s+=':'+u.port_._[0]; s+=u.path;
    if(u.query&&u.query.$==='Just') s+='?'+u.query._[0]; if(u.fragment&&u.fragment.$==='Just') s+='#'+u.fragment._[0]; return s; }
  $rt['Url.toString']=function(u){ return (u&&u.path!=null)?$urlToString(u):String(u); };
  $rt['Url.fromString']=function(s){ var u=$url(s); return u?$data('Just',[u]):$data('Nothing',[]); };
  $rt['Url.percentEncode']=function(s){ return encodeURIComponent(s); };
  $rt['Url.percentDecode']=function(s){ try{ return $data('Just',[decodeURIComponent(s)]); }catch(e){ return $data('Nothing',[]); } };
  $rt['Browser.Navigation.load']=function(url){ return $cmd(function(d){ try{ location.href=url; }catch(e){} }); };
  // pushUrl/replaceUrl change history and then notify a Browser.application's onUrlChange (if mounted).
  $rt['Browser.Navigation.pushUrl']=function(key){ return function(url){ return $cmd(function(d){ try{ history.pushState({},'',url); if(window.$onUrlChange) window.$onUrlChange(); }catch(e){} }); }; };
  $rt['Browser.Navigation.replaceUrl']=function(key){ return function(url){ return $cmd(function(d){ try{ history.replaceState({},'',url); if(window.$onUrlChange) window.$onUrlChange(); }catch(e){} }); }; };
  $rt['Browser.Navigation.back']=function(key){ return function(n){ return $cmd(function(d){ try{ history.go(-n); }catch(e){} }); }; };
  $rt['Browser.Navigation.forward']=function(key){ return function(n){ return $cmd(function(d){ try{ history.go(n); }catch(e){} }); }; };
  // getHash/setHash: a minimal permalink bridge (used by the editor's Share feature). getHash reads
  // the current URL fragment (decoded, without the leading '#') and dispatches it once at startup;
  // setHash writes one (URI-encoded) so a session round-trips through the address bar.
  $rt['Browser.Navigation.getHash']=function(toMsg){ return $cmd(function(d){ var h=''; try{ h=(window.location&&window.location.hash)||''; if(h.charAt(0)==='#') h=h.slice(1); h=decodeURIComponent(h); }catch(e){ h=''; } d(toMsg(h)); }); };
  $rt['Browser.Navigation.setHash']=function(s){ return $cmd(function(d){ try{ window.location.hash=encodeURIComponent(s); }catch(e){} }); };
  // localStorage bridge (used by the editor's autosave): save/load a string under a key.
  $rt['Storage.save']=function(key){ return function(val){ return $cmd(function(d){ try{ localStorage.setItem(key, val); }catch(e){} }); }; };
  $rt['Storage.load']=function(key){ return function(toMsg){ return $cmd(function(d){ var v=null; try{ v=localStorage.getItem(key); }catch(e){} d(toMsg(v==null?$data('Nothing',[]):$data('Just',[v]))); }); }; };
  $rt['Json.Decode.decodeString']=function(dec){ return function(s){ try{ var x=dec._[0](JSON.parse(s)); return x.ok?$data('Ok',[x.v]):$data('Err',[$data('Failure',[String(x.v)])]); }catch(e){ return $data('Err',[$data('Failure',[String(e)])]); } }; };
  $rt['Json.Decode.decodeValue']=function(dec){ return function(j){ var x=dec._[0](j); return x.ok?$data('Ok',[x.v]):$data('Err',[$data('Failure',[String(x.v)])]); }; };
  function $errStr(e,path){ switch(e.$){
    case 'Field': { var n=e._[0]; return $errStr(e._[1], path + (/^[A-Za-z_][A-Za-z0-9_]*$/.test(n)?'.'+n:"['"+n+"']")); }
    case 'Index': return $errStr(e._[1], path+'['+e._[0]+']');
    case 'OneOf': { var es=$listToArray(e._[0]); if(es.length===0) return 'oneOf with no possibilities'; return es.map(function(x){return $errStr(x,'');}).join('\n  - '); }
    default: { var m=e._[0]; return path===''?m:'Problem with the value at json'+path+':\n\n    '+m; } } }
  $rt['Json.Decode.errorToString']=function(e){ return $errStr(e,''); };
  // Http: real fetch; any failure maps to an Http.Error so update's error branch renders.
  $rt['Http.expectString']=function(toMsg){ return $data('$Expect',['string',toMsg,null]); };
  $rt['Http.expectJson']=function(toMsg){ return function(dec){ return $data('$Expect',['json',toMsg,dec]); }; };
  $rt['Http.expectWhatever']=function(toMsg){ return $data('$Expect',['whatever',toMsg,null]); };
  $rt['Http.header']=function(k){ return function(v){ return {k:k,v:v}; }; };
  $rt['Http.emptyBody']={t:'empty'};
  $rt['Http.stringBody']=function(mime){ return function(s){ return {t:'string',mime:mime,body:s}; }; };
  $rt['Http.jsonBody']=function(v){ return {t:'string',mime:'application/json',body:(typeof $jsonStringify==='function'?$jsonStringify(v):JSON.stringify(v))}; };
  function httpInit(req){ var o={ method:(req.method||'GET'), headers:{} };
    var hs=req.headers? $listToArray(req.headers):[]; for(var i=0;i<hs.length;i++) o.headers[hs[i].k]=hs[i].v;
    var b=req.body; if(b&&b.t==='string'){ o.body=b.body; if(b.mime) o.headers['Content-Type']=b.mime; }
    return o; }
  function httpGet(req){ var url=req.url, ex=req.expect, init=httpInit(req); return $cmd(function(d){
    fetch(url,init).then(function(r){ if(!r.ok) throw {bad:r.status}; return ex._[0]==='json'? r.json() : (ex._[0]==='whatever'? null : r.text()); })
      .then(function(data){ var msg; if(ex._[0]==='json'){ var x=ex._[2]._[0](data); msg=x.ok? ex._[1]($data('Ok',[x.v])) : ex._[1]($data('Err',[$data('BadBody',[String(x.v)])])); } else if(ex._[0]==='whatever'){ msg=ex._[1]($data('Ok',[$unit])); } else { msg=ex._[1]($data('Ok',[data])); } d(msg); })
      .catch(function(e){ var err = (e&&e.bad)? $data('BadStatus',[e.bad]) : $data('NetworkError',[]); d(ex._[1]($data('Err',[err]))); });
  }); }
  $rt['Http.get']=function(req){ return httpGet(req); };
  $rt['Http.post']=function(req){ return httpGet(req); };
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
  var $months=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
  var $weekdays=['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
  $rt['Time.toYear']=function(z){ return function(p){ return new Date(zoned(p,z)).getUTCFullYear(); }; };
  $rt['Time.toMonth']=function(z){ return function(p){ return $data($months[new Date(zoned(p,z)).getUTCMonth()],[]); }; };
  $rt['Time.toDay']=function(z){ return function(p){ return new Date(zoned(p,z)).getUTCDate(); }; };
  $rt['Time.toWeekday']=function(z){ return function(p){ return $data($weekdays[(new Date(zoned(p,z)).getUTCDay()+6)%7],[]); }; };
  $rt['Time.customZone']=function(off){ return function(eras){ return $data('$Zone',[off]); }; };
  $rt['Time.every']=function(ms){ return function(toMsg){ return $sub('every:'+ms, function(d){ var id=setInterval(function(){ d(toMsg(Date.now())); }, ms); return function(){ clearInterval(id); }; }); }; };
  // Browser.Events / Browser.Dom: real DOM events as subscriptions (used by elm-playground).
  function domSub(key, target, type, make){ return $sub(key, function(d){ var h=function(e){ var m=make(e); if(m!==undefined) d(m); }; target.addEventListener(type, h); return function(){ target.removeEventListener(type, h); }; }); }
  $rt['Browser.Events.onResize']=function(toMsg){ return domSub('resize', window, 'resize', function(){ return toMsg(window.innerWidth)(window.innerHeight); }); };
  $rt['Browser.Events.onKeyDown']=function(dec){ return domSub('keydown', document, 'keydown', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onKeyUp']=function(dec){ return domSub('keyup', document, 'keyup', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onKeyPress']=function(dec){ return domSub('keypress', document, 'keypress', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onClick']=function(dec){ return domSub('click', document, 'click', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onMouseMove']=function(dec){ return domSub('mousemove', document, 'mousemove', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onMouseDown']=function(dec){ return domSub('mousedown', document, 'mousedown', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onMouseUp']=function(dec){ return domSub('mouseup', document, 'mouseup', function(e){ var r=dec._[0](e); return r.ok?r.v:undefined; }); };
  $rt['Browser.Events.onVisibilityChange']=function(toMsg){ return domSub('vis', document, 'visibilitychange', function(){ return toMsg(document.hidden?$data('Hidden',[]):$data('Visible',[])); }); };
  $rt['Browser.Events.Visible']=$data('Visible',[]); $rt['Browser.Events.Hidden']=$data('Hidden',[]);
  $rt['Browser.Events.onAnimationFrame']=function(toMsg){ return $sub('raf', function(d){ var id; function tick(){ d(toMsg(Date.now())); id=requestAnimationFrame(tick); } id=requestAnimationFrame(tick); return function(){ cancelAnimationFrame(id); }; }); };
  $rt['Browser.Events.onAnimationFrameDelta']=function(toMsg){ return $sub('rafd', function(d){ var id, last=Date.now(); function tick(){ var now=Date.now(); d(toMsg(now-last)); last=now; id=requestAnimationFrame(tick); } id=requestAnimationFrame(tick); return function(){ cancelAnimationFrame(id); }; }); };
  function viewportRecord(){ var w=window.innerWidth||800, h=window.innerHeight||600; return {scene:{width:w,height:h}, viewport:{x:0,y:0,width:w,height:h}}; }
  $rt['Browser.Dom.getViewport']=$task(function(ok,err){ ok(viewportRecord()); });
  $rt['Browser.Dom.getElement']=function(id){ return $task(function(ok,err){ var el=(typeof document!=='undefined')&&document.getElementById(id); if(!el){ err($data('NotFound',[id])); return; } var r=el.getBoundingClientRect(), vp=viewportRecord(); ok({scene:vp.scene, viewport:vp.viewport, element:{x:r.left+(window.pageXOffset||0), y:r.top+(window.pageYOffset||0), width:r.width, height:r.height}}); }); };
  $rt['Browser.Dom.setViewport']=function(x){ return function(y){ return $task(function(ok,err){ window.scrollTo(x,y); ok($unit); }); }; };
  $rt['Browser.Dom.focus']=function(id){ return $task(function(ok,err){ var el=document.getElementById(id); if(el){el.focus(); ok($unit);} else err($data('NotFound',[id])); }); };
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
  // Editor bridge: open a picker and hand the chosen file's name and text content to `toMsg`.
  $rt['File.openPicker']=function(toMsg){ return $cmd(function(d){ var inp=selectInput(false,$nil); inp.onchange=function(){ var f=inp.files&&inp.files[0]; if(!f) return; var r=new FileReader(); r.onload=function(){ d(toMsg(f.name)(String(r.result))); }; r.readAsText(f); }; inp.click(); }); };
  // Set / Dict: backed by a plain object keyed by $show(key) (a canonical key for comparables).
  function $k(x){ return $show(x); }
  $rt['Set.empty']=$data('$Set',[{}]);
  $rt['Set.singleton']=function(x){ var o={}; o[$k(x)]=x; return $data('$Set',[o]); };
  $rt['Set.insert']=function(x){ return function(s){ var o=Object.assign({},s._[0]); o[$k(x)]=x; return $data('$Set',[o]); }; };
  $rt['Set.remove']=function(x){ return function(s){ var o=Object.assign({},s._[0]); delete o[$k(x)]; return $data('$Set',[o]); }; };
  $rt['Set.member']=function(x){ return function(s){ return Object.prototype.hasOwnProperty.call(s._[0],$k(x)); }; };
  $rt['Set.size']=function(s){ return Object.keys(s._[0]).length; };
  $rt['Set.isEmpty']=function(s){ return Object.keys(s._[0]).length===0; };
  $rt['Set.toList']=function(s){ var o=s._[0]; return $list(Object.keys(o).sort().map(function(k){ return o[k]; })); };
  $rt['Set.fromList']=function(l){ var o={}; $listToArray(l).forEach(function(x){ o[$k(x)]=x; }); return $data('$Set',[o]); };
  $rt['Set.union']=function(a){ return function(b){ return $data('$Set',[Object.assign({},b._[0],a._[0])]); }; };
  $rt['Set.foldl']=function(f){ return function(acc){ return function(s){ var o=s._[0]; Object.keys(o).sort().forEach(function(k){ acc=f(o[k])(acc); }); return acc; }; }; };
  $rt['Set.map']=function(f){ return function(s){ var o={}; var src=s._[0]; Object.keys(src).forEach(function(k){ var y=f(src[k]); o[$k(y)]=y; }); return $data('$Set',[o]); }; };
  $rt['Set.filter']=function(f){ return function(s){ var o={}; var src=s._[0]; Object.keys(src).forEach(function(k){ if(f(src[k])) o[k]=src[k]; }); return $data('$Set',[o]); }; };
  $rt['Set.partition']=function(f){ return function(s){ var yes={},no={}; var src=s._[0]; Object.keys(src).forEach(function(k){ (f(src[k])?yes:no)[k]=src[k]; }); return $tuple([$data('$Set',[yes]),$data('$Set',[no])]); }; };
  $rt['Dict.empty']=$data('$Dict',[{}]);
  $rt['Dict.singleton']=function(k){ return function(v){ var o={}; o[$k(k)]=$tuple([k,v]); return $data('$Dict',[o]); }; };
  $rt['Dict.insert']=function(k){ return function(v){ return function(d){ var o=Object.assign({},d._[0]); o[$k(k)]=$tuple([k,v]); return $data('$Dict',[o]); }; }; };
  $rt['Dict.remove']=function(k){ return function(d){ var o=Object.assign({},d._[0]); delete o[$k(k)]; return $data('$Dict',[o]); }; };
  $rt['Dict.get']=function(k){ return function(d){ var e=d._[0][$k(k)]; return e?$data('Just',[e.vs[1]]):$data('Nothing',[]); }; };
  $rt['Dict.member']=function(k){ return function(d){ return Object.prototype.hasOwnProperty.call(d._[0],$k(k)); }; };
  $rt['Dict.size']=function(d){ return Object.keys(d._[0]).length; };
  $rt['Dict.isEmpty']=function(d){ return Object.keys(d._[0]).length===0; };
  $rt['Dict.keys']=function(d){ var o=d._[0]; return $list(Object.keys(o).sort().map(function(k){ return o[k].vs[0]; })); };
  $rt['Dict.values']=function(d){ var o=d._[0]; return $list(Object.keys(o).sort().map(function(k){ return o[k].vs[1]; })); };
  $rt['Dict.toList']=function(d){ var o=d._[0]; return $list(Object.keys(o).sort().map(function(k){ return o[k]; })); };
  $rt['Dict.fromList']=function(l){ var o={}; $listToArray(l).forEach(function(p){ o[$k(p.vs[0])]=p; }); return $data('$Dict',[o]); };
  $rt['Dict.update']=function(k){ return function(f){ return function(d){ var o=Object.assign({},d._[0]); var cur=o[$k(k)]; var mb=f(cur?$data('Just',[cur.vs[1]]):$data('Nothing',[])); if(mb.$==='Just'){ o[$k(k)]=$tuple([k,mb._[0]]); } else { delete o[$k(k)]; } return $data('$Dict',[o]); }; }; };
  $rt['Dict.map']=function(f){ return function(d){ var o={}; var src=d._[0]; Object.keys(src).forEach(function(kk){ var p=src[kk]; o[kk]=$tuple([p.vs[0], f(p.vs[0])(p.vs[1])]); }); return $data('$Dict',[o]); }; };
  $rt['Dict.foldl']=function(f){ return function(acc){ return function(d){ var o=d._[0]; Object.keys(o).sort().forEach(function(kk){ acc=f(o[kk].vs[0])(o[kk].vs[1])(acc); }); return acc; }; }; };
  $rt['Dict.filter']=function(f){ return function(d){ var o={}; var src=d._[0]; Object.keys(src).forEach(function(kk){ var p=src[kk]; if(f(p.vs[0])(p.vs[1])) o[kk]=p; }); return $data('$Dict',[o]); }; };
  $rt['Dict.partition']=function(f){ return function(d){ var yes={},no={}; var src=d._[0]; Object.keys(src).forEach(function(kk){ var p=src[kk]; (f(p.vs[0])(p.vs[1])?yes:no)[kk]=p; }); return $tuple([$data('$Dict',[yes]),$data('$Dict',[no])]); }; };
  // Json.Encode.dict/set over dom's Dict/Set representation (kernel.js's versions assume the other one).
  $rt['Json.Encode.dict']=function(toKey){ return function(toVal){ return function(d){ var o={},src=d._[0]; Object.keys(src).sort().forEach(function(kk){ o[toKey(src[kk].vs[0])]=toVal(src[kk].vs[1]); }); return o; }; }; };
  $rt['Json.Encode.set']=function(toVal){ return function(s){ var o=s._[0]; return Object.keys(o).sort().map(function(kk){ return toVal(o[kk]); }); }; };

  // ---- Math.Vector2/3/4 (plain JS arrays) and Math.Matrix4 (column-major Float32Array) ----
  function v3(x,y,z){ return [x,y,z]; }
  $rt['Math.Vector2.vec2']=function(x){ return function(y){ return [x,y]; }; };
  $rt['Math.Vector2.getX']=function(v){ return v[0]; }; $rt['Math.Vector2.getY']=function(v){ return v[1]; };
  $rt['Math.Vector3.vec3']=function(x){ return function(y){ return function(z){ return [x,y,z]; }; }; };
  $rt['Math.Vector3.getX']=function(v){ return v[0]; }; $rt['Math.Vector3.getY']=function(v){ return v[1]; }; $rt['Math.Vector3.getZ']=function(v){ return v[2]; };
  $rt['Math.Vector3.setX']=function(x){ return function(v){ return [x,v[1],v[2]]; }; };
  $rt['Math.Vector3.setY']=function(y){ return function(v){ return [v[0],y,v[2]]; }; };
  $rt['Math.Vector3.setZ']=function(z){ return function(v){ return [v[0],v[1],z]; }; };
  $rt['Math.Vector3.add']=function(a){ return function(b){ return [a[0]+b[0],a[1]+b[1],a[2]+b[2]]; }; };
  $rt['Math.Vector3.sub']=function(a){ return function(b){ return [a[0]-b[0],a[1]-b[1],a[2]-b[2]]; }; };
  $rt['Math.Vector3.scale']=function(s){ return function(v){ return [v[0]*s,v[1]*s,v[2]*s]; }; };
  $rt['Math.Vector3.dot']=function(a){ return function(b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }; };
  $rt['Math.Vector3.cross']=function(a){ return function(b){ return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]; }; };
  $rt['Math.Vector3.length']=function(v){ return Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]); };
  $rt['Math.Vector3.normalize']=function(v){ var l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])||1; return [v[0]/l,v[1]/l,v[2]/l]; };
  $rt['Math.Vector3.i']=[1,0,0]; $rt['Math.Vector3.j']=[0,1,0]; $rt['Math.Vector3.k']=[0,0,1];
  $rt['Math.Vector3.toRecord']=function(v){ return {x:v[0],y:v[1],z:v[2]}; };
  $rt['Math.Vector3.fromRecord']=function(r){ return [r.x,r.y,r.z]; };
  $rt['Math.Vector4.vec4']=function(x){ return function(y){ return function(z){ return function(w){ return [x,y,z,w]; }; }; }; };
  function m4id(){ return new Float32Array([1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]); }
  function m4mul(a,b){ var o=new Float32Array(16); for(var c=0;c<4;c++){ for(var r=0;r<4;r++){ var s=0; for(var k=0;k<4;k++) s+=a[k*4+r]*b[c*4+k]; o[c*4+r]=s; } } return o; }
  function norm3(v){ var l=Math.sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])||1; return [v[0]/l,v[1]/l,v[2]/l]; }
  function cross3(a,b){ return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]; }
  function dot3(a,b){ return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
  $rt['Math.Matrix4.identity']=m4id();
  $rt['Math.Matrix4.mul']=function(a){ return function(b){ return m4mul(a,b); }; };
  $rt['Math.Matrix4.makeTranslate']=function(v){ var m=m4id(); m[12]=v[0]; m[13]=v[1]; m[14]=v[2]; return m; };
  $rt['Math.Matrix4.makeTranslate3']=function(x){ return function(y){ return function(z){ var m=m4id(); m[12]=x; m[13]=y; m[14]=z; return m; }; }; };
  $rt['Math.Matrix4.makeScale3']=function(x){ return function(y){ return function(z){ var m=m4id(); m[0]=x; m[5]=y; m[10]=z; return m; }; }; };
  $rt['Math.Matrix4.makeRotate']=function(angle){ return function(axis){ var a=norm3(axis), x=a[0],y=a[1],z=a[2], c=Math.cos(angle), s=Math.sin(angle), t=1-c; return new Float32Array([ t*x*x+c, t*x*y+s*z, t*x*z-s*y, 0, t*x*y-s*z, t*y*y+c, t*y*z+s*x, 0, t*x*z+s*y, t*y*z-s*x, t*z*z+c, 0, 0,0,0,1 ]); }; };
  $rt['Math.Matrix4.makePerspective']=function(fovy){ return function(aspect){ return function(near){ return function(far){ var f=1/Math.tan(fovy*Math.PI/360), nf=1/(near-far); return new Float32Array([ f/aspect,0,0,0, 0,f,0,0, 0,0,(far+near)*nf,-1, 0,0,2*far*near*nf,0 ]); }; }; }; };
  $rt['Math.Matrix4.makeOrtho']=function(l){ return function(r){ return function(b){ return function(t){ return function(n){ return function(fa){ return new Float32Array([ 2/(r-l),0,0,0, 0,2/(t-b),0,0, 0,0,-2/(fa-n),0, -(r+l)/(r-l),-(t+b)/(t-b),-(fa+n)/(fa-n),1 ]); }; }; }; }; }; };
  $rt['Math.Matrix4.makeLookAt']=function(eye){ return function(center){ return function(up){ var z=norm3([eye[0]-center[0],eye[1]-center[1],eye[2]-center[2]]); var x=norm3(cross3(up,z)); var y=cross3(z,x); return new Float32Array([ x[0],y[0],z[0],0, x[1],y[1],z[1],0, x[2],y[2],z[2],0, -dot3(x,eye),-dot3(y,eye),-dot3(z,eye),1 ]); }; }; };
  $rt['Math.Matrix4.transform']=function(m){ return function(v){ var w=m[3]*v[0]+m[7]*v[1]+m[11]*v[2]+m[15]||1; return [ (m[0]*v[0]+m[4]*v[1]+m[8]*v[2]+m[12])/w, (m[1]*v[0]+m[5]*v[1]+m[9]*v[2]+m[13])/w, (m[2]*v[0]+m[6]*v[1]+m[10]*v[2]+m[14])/w ]; }; };

  // ---- WebGL: meshes, entities, and a canvas renderer driven by a $GL attribute ----
  $rt['WebGL.triangles']=function(l){ return $data('$Mesh',[l]); };
  $rt['WebGL.indexedTriangles']=function(verts){ return function(idx){ return $data('$MeshIdx',[verts,idx]); }; };
  $rt['WebGL.entity']=function(vs){ return function(fs){ return function(mesh){ return function(uni){ return $data('$Entity',[vs,fs,mesh,uni]); }; }; }; };
  $rt['WebGL.entityWith']=function(s){ return $rt['WebGL.entity']; };
  $rt['WebGL.depth']=function(z){ return $data('$Opt',['depth',z]); };
  $rt['WebGL.clearColor']=function(r){ return function(g){ return function(b){ return function(a){ return $data('$Opt',['clear',[r,g,b,a]]); }; }; }; };
  $rt['WebGL.alpha']=function(b){ return $data('$Opt',['alpha',b]); };
  $rt['WebGL.antialias']=$data('$Opt',['antialias',true]);
  function glNode(attrs, entities, clear){ return $data('$Node',['canvas', $cons($data('$GL',[entities,clear]), attrs), $nil]); }
  $rt['WebGL.toHtml']=function(attrs){ return function(entities){ return glNode(attrs, entities, null); }; };
  $rt['WebGL.toHtmlWith']=function(opts){ return function(attrs){ return function(entities){ var clear=null; $listToArray(opts).forEach(function(o){ if(o&&o.$==='$Opt'&&o._[0]==='clear') clear=o._[1]; }); return glNode(attrs, entities, clear); }; }; };
  // ---- Editor bridge: turn the Elm-in-Elm interpreter's WebGL values into a $GL canvas attribute.
  // The editor evaluates a program to dynamic Lang.Values (VCtor/VStr/VNum/VList/VRecord/VTup,
  // compiled to $data); this converts a list of `WebGL.entity` values into real GL entities so the
  // renderer above draws them — letting the editor render WebGL live, which typed Elm can't bridge.
  function $vList(v){ return (v && v._) ? $listToArray(v._[0]) : []; }
  // Textures the editor's interpreter resolved from `Texture.load url`: cached per-url Image wrapped
  // as a $Texture. The image loads async, so clear the uploaded GL texture on load to force a
  // re-upload on the next frame (animated WebGL scenes redraw every frame).
  var $glTexCache = {};
  function $glTexture(url){
    if ($glTexCache[url]) return $glTexCache[url];
    var img = new Image();
    if (/^https?:/.test(url)) img.crossOrigin = 'anonymous';
    var t = $data('$Texture', [img]);
    img.onload = function(){ t.$tex = null; };
    img.src = url;
    $glTexCache[url] = t;
    return t;
  }
  function $glScalar(v){
    if (!v || !v.$) return 0;
    if (v.$ === 'VNum') return v._[0];
    if (v.$ === 'VCtor' || v.$ === 'VBuiltin'){
      var name = v._[0];
      // A WebGL texture the editor resolved from `Texture.load url` (kept as a url-carrying value):
      // load the image so it can be sampled, before mapping the args through $glScalar.
      if (name === 'Texture.load' || name === 'WebGL.Texture.load'){
        var raw = $listToArray(v._[1])[0];
        return $glTexture(raw && raw._ ? raw._[0] : '');
      }
      var args = $listToArray(v._[1]).map($glScalar);
      if (name === 'vec2' || name === 'vec3' || name === 'vec4') return args;
      if (name.indexOf('Mat4.') === 0){
        var fn = $rt['Math.Matrix4.' + name.slice(5)];
        if (typeof fn === 'function'){ for (var i=0;i<args.length;i++) fn = fn(args[i]); }
        return fn; // a Float32Array (or the identity matrix value)
      }
      // The conventional Vec3/Vec2/Vec4 aliases for Math.Vector{3,2,4} (e.g. Vec3.scale, Vec3.i).
      var vecMod = name.indexOf('Vec3.')===0 ? 'Math.Vector3.' : name.indexOf('Vec2.')===0 ? 'Math.Vector2.' : name.indexOf('Vec4.')===0 ? 'Math.Vector4.' : null;
      if (vecMod){
        var fv = $rt[vecMod + name.slice(5)];
        if (typeof fv === 'function'){ for (var v=0;v<args.length;v++) fv = fv(args[v]); }
        return fv; // a vector (array) value — or a constant like Vec3.i
      }
      if (name.indexOf('Math.Vector') === 0 || name.indexOf('Math.Matrix') === 0){
        var f2 = $rt[name];
        if (typeof f2 === 'function'){ for (var j=0;j<args.length;j++) f2 = f2(args[j]); }
        return f2;
      }
    }
    return 0;
  }
  function $glRecord(v){ var o={}; if (v && v.$ === 'VRecord') $listToArray(v._[0]).forEach(function(t){ o[t.vs[0]] = $glScalar(t.vs[1]); }); return o; }
  function $glMesh(v){
    if (!v || v.$ !== 'VCtor') return $data('$Mesh', [$nil]);
    var a = $listToArray(v._[1]);
    var tris = $vList(a[0]).map(function(tv){ var vs = $listToArray(tv._[0]); return { vs: [ $glRecord(vs[0]), $glRecord(vs[1]), $glRecord(vs[2]) ] }; });
    return $data('$Mesh', [ $list(tris) ]);
  }
  function $glEntity(ev){
    var a = $listToArray(ev._[1]); // [ vShader, fShader, mesh, uniforms ]
    return $data('$Entity', [ $data('$Shader',[ a[0] && a[0]._ ? a[0]._[0] : '' ]),
                              $data('$Shader',[ a[1] && a[1]._ ? a[1]._[0] : '' ]),
                              $glMesh(a[2]), $glRecord(a[3]) ]);
  }
  $rt['WebGL.glAttr'] = function(entities){ return $data('$GL', [ $list($vList(entities).map($glEntity)), null ]); };

  // WebGL.Texture: load an Image; the GL texture is uploaded lazily on first draw.
  $rt['WebGL.Texture.load']=function(url){ return $task(function(ok,err){ var img=new Image(); if(/^https?:/.test(url)) img.crossOrigin='anonymous'; /* only cross-origin URLs need CORS; same-origin assets load untainted */ img.onload=function(){ ok($data('$Texture',[img])); }; img.onerror=function(){ ok($data('$Texture',[null])); /* load failed: succeed with a placeholder so the scene still renders */ }; img.src=url; }); };
  $rt['WebGL.Texture.loadWith']=function(opts){ return $rt['WebGL.Texture.load']; };
  $rt['WebGL.Texture.size']=function(t){ var i=t._[0]; return $tuple([(i&&i.width)||256,(i&&i.height)||256]); };
  ['nearest','linear','nearestMipmapNearest','linearMipmapLinear','repeat','clampToEdge','mirroredRepeat'].forEach(function(n){ $rt['WebGL.Texture.'+n]=$data('$TexOpt',[n]); });

  function glContext(c){ if(c.$ctx!==undefined) return c.$ctx; var gl=null; try{ gl=c.getContext('webgl',{premultipliedAlpha:false})||c.getContext('experimental-webgl'); }catch(e){} c.$ctx=gl; c.$progs={}; return gl; }
  function glShader(gl,type,src){ var s=gl.createShader(type); gl.shaderSource(s,src); gl.compileShader(s); return s; }
  function glProgram(gl,c,vsrc,fsrc){ var key=vsrc+' '+fsrc; if(c.$progs[key]) return c.$progs[key]; var p=gl.createProgram(); gl.attachShader(p, glShader(gl,gl.VERTEX_SHADER,vsrc)); gl.attachShader(p, glShader(gl,gl.FRAGMENT_SHADER,fsrc)); gl.linkProgram(p); c.$progs[key]=p; return p; }
  function attrSize(v){ return Array.isArray(v)?v.length:1; }
  function setUniform(gl,loc,v,texUnit){ if(v instanceof Float32Array && v.length===16){ gl.uniformMatrix4fv(loc,false,v); } else if(Array.isArray(v)){ if(v.length===2)gl.uniform2fv(loc,new Float32Array(v)); else if(v.length===3)gl.uniform3fv(loc,new Float32Array(v)); else if(v.length===4)gl.uniform4fv(loc,new Float32Array(v)); } else if(typeof v==='number'){ gl.uniform1f(loc,v); } else if(v&&v.$==='$Texture'){ bindTexture(gl,loc,v,texUnit); } }
  function bindTexture(gl,loc,t,unit){
    var img=t._[0];
    // An <img> only has usable pixels once it has actually finished loading; uploading one that is
    // still loading (or failed / is cross-origin-tainted) yields an empty BLACK texture. The editor
    // resolves `Texture.load` immediately with an unloaded Image, so without the readiness check the
    // crate cached that black texture forever. Upload a checkerboard placeholder until the image is
    // genuinely ready, then re-upload the real pixels the first frame they become available.
    var ready=img&&img.complete&&img.naturalWidth>0;
    if(!t.$tex||(ready&&!t.$loaded)){
      var tx=t.$tex||gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,tx);
      // Images have a top-left origin but GL texture coords a bottom-left one, so flip Y on upload
      // (elm-explorations/webgl's default) — otherwise textures render vertically mirrored.
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
      try{ if(!ready) throw 0; gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,img); t.$loaded=true; }
      catch(e){ gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,2,2,0,gl.RGBA,gl.UNSIGNED_BYTE,
        new Uint8Array([200,200,200,255, 120,120,120,255, 120,120,120,255, 200,200,200,255])); }
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);
      t.$tex=tx;
    }
    gl.activeTexture(gl.TEXTURE0+unit); gl.bindTexture(gl.TEXTURE_2D,t.$tex); gl.uniform1i(loc,unit);
  }
  function drawEntity(gl,c,e){ var prog=glProgram(gl,c,e._[0]._[0],e._[1]._[0]); gl.useProgram(prog); var mesh=e._[2], verts=[], indices=null;
    if(mesh.$==='$Mesh'){ $listToArray(mesh._[0]).forEach(function(tri){ verts.push(tri.vs[0],tri.vs[1],tri.vs[2]); }); }
    else if(mesh.$==='$MeshIdx'){ verts=$listToArray(mesh._[0]); indices=[]; $listToArray(mesh._[1]).forEach(function(t){ indices.push(t.vs[0],t.vs[1],t.vs[2]); }); }
    if(verts.length===0) return;
    Object.keys(verts[0]).forEach(function(name){ var loc=gl.getAttribLocation(prog,name); if(loc<0) return; var size=attrSize(verts[0][name]); var arr=new Float32Array(verts.length*size); for(var i=0;i<verts.length;i++){ var val=verts[i][name]; if(size===1) arr[i]=val; else for(var j=0;j<size;j++) arr[i*size+j]=val[j]; } var buf=gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER,buf); gl.bufferData(gl.ARRAY_BUFFER,arr,gl.STATIC_DRAW); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc,size,gl.FLOAT,false,0,0); });
    var uni=e._[3], unit=0; Object.keys(uni).forEach(function(name){ var loc=gl.getUniformLocation(prog,name); if(loc==null) return; setUniform(gl,loc,uni[name],unit); if(uni[name]&&uni[name].$==='$Texture') unit++; });
    if(indices){ var ib=gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER,new Uint16Array(indices),gl.STATIC_DRAW); gl.drawElements(gl.TRIANGLES,indices.length,gl.UNSIGNED_SHORT,0); }
    else { gl.drawArrays(gl.TRIANGLES,0,verts.length); }
  }
  function drawGL(c){ var gl=glContext(c); if(!gl) return; gl.viewport(0,0,c.width||400,c.height||400); var cc=c.$glClear; gl.clearColor(cc?cc[0]:0, cc?cc[1]:0, cc?cc[2]:0, cc?cc[3]:0); gl.enable(gl.DEPTH_TEST); gl.clear(gl.COLOR_BUFFER_BIT|gl.DEPTH_BUFFER_BIT); $listToArray(c.$glEntities).forEach(function(e){ try{ drawEntity(gl,c,e); }catch(err){} }); }

  function setAttr(el, a){
    var t=a.$, nm=a._[0], val=a._[1];
    if (t==='$Att'){
      // `value`/`checked` are controlled by the model; set the live property (and only when it
      // changed) so we don't fight the cursor, rather than the inert default-value attribute.
      if (nm==='value'){ var s=String(val); if(el.value!==s) el.value=s; }
      else if (nm==='xlink:href' || nm==='href'){
        // SVG <image>/<use> hrefs must be set in the xlink namespace (and SVG2 `href`) to
        // actually load — a plain setAttribute('xlink:href',...) is ignored by browsers.
        el.setAttributeNS('http://www.w3.org/1999/xlink', 'xlink:href', String(val));
        el.setAttribute('href', String(val));
      }
      else el.setAttribute(nm, String(val));
    }
    else if (t==='$Prop'){ if (typeof val==='boolean'){ el[nm]=val; if(val) el.setAttribute(nm,''); else el.removeAttribute(nm); } else { el[nm]=val; el.setAttribute(nm,String(val)); } }
    else if (t==='$Style'){ el.style.setProperty(nm, val); }
    else if (t==='$GL'){
      // A WebGL canvas: stash the entities/clear-colour and (re)draw after sizing attributes
      // have been applied. Redrawn on every render, so animated scenes update each frame.
      el.$glEntities=a._[0]; el.$glClear=a._[1];
      requestAnimationFrame(function(){ drawGL(el); });
    }
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
    if (v.$==='$Lazy'){ var inner=$forceLazy(v); var el=window.$toDom(inner); el.$lazyArgs=v._[1]; el.$lazyInner=inner; return el; }
    if (v.$==='$Keyed') return $keyedToDom(v);
    var tag=v._[0];
    var el = SVG_TAGS[tag] ? document.createElementNS(SVG,tag) : document.createElement(tag);
    $listToArray(v._[1]).forEach(function(a){ setAttr(el,a); });
    $listToArray(v._[2]).forEach(function(k){ el.appendChild(window.$toDom(k)); });
    return el;
  };
  // Same virtual node kind? Text vs element, and matching element tag (for $Node and $Keyed).
  function $sameType(a,b){ return a.$===b.$ && ((a.$!=='$Node' && a.$!=='$Keyed') || a._[0]===b._[0]); }
  // Diff old/new virtual nodes and patch the real DOM in place, preserving element identity
  // (and thus focus/selection on inputs) instead of rebuilding the subtree every render.
  function $patch(parent, dom, oldV, newV){
    if (oldV==null){ var n=window.$toDom(newV); parent.appendChild(n); return n; }
    if (newV==null){ if(dom) parent.removeChild(dom); return null; }
    if (!$sameType(oldV,newV)){ var n=window.$toDom(newV); parent.replaceChild(n,dom); return n; }
    if (newV.$==='$Text'){ var s=String(newV._[0]); if(dom.nodeValue!==s) dom.nodeValue=s; return dom; }
    if (newV.$==='$Lazy'){
      if ($sameArgs(dom.$lazyArgs, newV._[1])) return dom; // args unchanged: skip the view + diff
      var inner=$forceLazy(newV); var p=$patch(parent, dom, dom.$lazyInner, inner);
      p.$lazyArgs=newV._[1]; p.$lazyInner=inner; return p;
    }
    if (newV.$==='$Keyed'){ return $patchKeyed(dom, newV); }
    applyProps(dom, $listToArray(newV._[1]));
    var oldKids=$listToArray(oldV._[2]), newKids=$listToArray(newV._[2]);
    for (var i=0;i<newKids.length;i++){ $patch(dom, dom.childNodes[i]||null, oldKids[i]||null, newKids[i]); }
    for (var j=oldKids.length-1;j>=newKids.length;j--){ if(dom.childNodes[j]) dom.removeChild(dom.childNodes[j]); }
    return dom;
  }
  // Builds a keyed element: children are (key, vnode) tuples; the key->dom map is stashed for diffing.
  function $keyedToDom(v){
    var tag=v._[0];
    var el = SVG_TAGS[tag] ? document.createElementNS(SVG,tag) : document.createElement(tag);
    $listToArray(v._[1]).forEach(function(a){ setAttr(el,a); });
    el.$keyed=[];
    $listToArray(v._[2]).forEach(function(p){ var c=window.$toDom(p.vs[1]); el.appendChild(c); el.$keyed.push([p.vs[0], p.vs[1], c]); });
    return el;
  }
  // Patches a keyed element by matching children to the previous render by key, so reordered or
  // removed items keep their DOM node (and its focus/scroll/input state) instead of being rebuilt.
  function $patchKeyed(dom, newV){
    applyProps(dom, $listToArray(newV._[1]));
    var oldMap={}; (dom.$keyed||[]).forEach(function(e){ oldMap[e[0]]=e; });
    var pairs=$listToArray(newV._[2]); var next=[];
    pairs.forEach(function(p, i){
      var key=p.vs[0], cv=p.vs[1], old=oldMap[key], childDom;
      if (old){ childDom=$patch(dom, old[2], old[1], cv); delete oldMap[key]; }
      else { childDom=window.$toDom(cv); }
      if (dom.childNodes[i] !== childDom){ dom.insertBefore(childDom, dom.childNodes[i] || null); }
      next.push([key, cv, childDom]);
    });
    while (dom.childNodes.length > pairs.length){ dom.removeChild(dom.lastChild); }
    dom.$keyed=next;
    return dom;
  }
  window.$mount = function(program, root){
    var def = program._[0], kind = program.$, model, initCmd=null;
    var navKey = {}; // Browser.Navigation.Key — opaque; pushUrl/replaceUrl ignore it.
    if (kind==='$Sandbox') model = def.init;
    else if (kind==='$Application'){
      var loc = (typeof location!=='undefined') ? location.href : 'http://localhost/';
      var pair = def.init($unit)($url(loc))(navKey); model = pair.vs[0]; initCmd = pair.vs[1];
    }
    else { var pair = def.init($unit); model = pair.vs[0]; initCmd = pair.vs[1]; }
    var current=null, dom=null, subs={};
    // Time-travel: a snapshot of the model after each step (index 0 = initial). viewIndex===null is
    // "live" (show the latest); a number pins the view to that historical model without mutating it.
    var history=[model], viewIndex=null;
    function shownModel(){ return viewIndex===null ? model : history[viewIndex]; }
    function viewVNode(){
      var v = def.view(shownModel());
      if (kind==='$Document' || kind==='$Application'){
        if (typeof document!=='undefined' && v.title!=null) document.title = v.title;
        v = $data('$Node',['div', $nil, v.body]);
      }
      return v;
    }
    function render(){
      if (kind==='$Worker') return; // a worker has no view
      var v = viewVNode();
      if (dom==null){ dom = window.$toDom(v); root.appendChild(dom); }
      else { dom = $patch(root, dom, current, v); }
      current = v;
      if (debug) updateDebug();
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
    var messages=[]; // the dispatched messages, in order (the effect log)
    window.$dispatch = function(msg){
      var cmd=null;
      if (kind==='$Sandbox') model = def.update(msg)(model);
      else { var pair = def.update(msg)(model); model = pair.vs[0]; cmd = pair.vs[1]; }
      messages.push(msg); history.push(model); viewIndex=null; // a new message returns to live mode
      // Bound the time-travel log so a long-running app (e.g. a `Time.every` game ticking for
      // minutes) doesn't grow memory without limit and eventually freeze. Drop the oldest snapshot
      // and its message together, preserving history.length === messages.length + 1.
      if (history.length > 1000){ history.shift(); messages.shift(); }
      render(); syncSubs();
      if (cmd) runCmd(cmd, window.$dispatch);
    };
    // Deterministic replay: re-fold a recorded message log from the initial model, applying only
    // `update` (no Cmd side effects), so a session reproduces the exact model/view history.
    function replay(log){
      model = (kind==='$Sandbox') ? def.init : def.init($unit).vs[0];
      history=[model]; messages=[]; viewIndex=null;
      log.forEach(function(m){
        if (kind==='$Sandbox') model = def.update(m)(model); else model = def.update(m)(model).vs[0];
        messages.push(m); history.push(model);
      });
      render(); syncSubs();
    }
    // ---- debug / time-travel overlay (opt-in via ?debug or window.$elmDebug) ----
    var debug = (typeof window!=='undefined') &&
      (window.$elmDebug===true || (window.location && /[?&]debug\b/.test(window.location.search||'')));
    var dbgEl=null, dbgLabel=null;
    function goto(i){ viewIndex = Math.max(0, Math.min(history.length-1, i)); render(); }
    function updateDebug(){
      if(!dbgEl){
        dbgEl=document.createElement('div');
        dbgEl.setAttribute('style','position:fixed;bottom:0;left:0;right:0;z-index:99999;background:#0f1720;color:#e6edf3;font:12px monospace;padding:6px 10px;display:flex;gap:8px;align-items:center');
        function btn(t,f){ var b=document.createElement('button'); b.textContent=t; b.onclick=f; b.setAttribute('style','font:12px monospace'); return b; }
        dbgEl.appendChild(btn('⏮',function(){goto(0);}));
        dbgEl.appendChild(btn('◀',function(){goto((viewIndex===null?history.length-1:viewIndex)-1);}));
        dbgEl.appendChild(btn('▶',function(){goto((viewIndex===null?history.length-1:viewIndex)+1);}));
        dbgEl.appendChild(btn('live ⏵',function(){viewIndex=null;render();}));
        dbgLabel=document.createElement('span'); dbgEl.appendChild(dbgLabel);
        document.body.appendChild(dbgEl);
      }
      var idx = viewIndex===null ? history.length-1 : viewIndex;
      dbgLabel.textContent = 'step '+idx+' / '+(history.length-1)+(viewIndex===null?'  (live)':'  (history)');
    }
    window.$app = {
      dispatch: function(m){ window.$dispatch(m); },
      model: function(){ return model; },
      ports: $portsApi(),
      history: function(){ return history; }, // model snapshots (index 0 = initial)
      messages: function(){ return messages; }, // the recorded message log (the effects)
      replay: function(log){ replay(log); },   // deterministically re-fold a recorded message log
      goto: function(i){ goto(i); },           // time-travel: show snapshot i
      live: function(){ viewIndex=null; render(); }
    };
    render(); syncSubs();
    if (initCmd) runCmd(initCmd, window.$dispatch);
    // Browser.application: route URL changes (popstate + pushUrl/replaceUrl) and intercept link clicks.
    if (kind==='$Application' && typeof window!=='undefined'){
      window.$onUrlChange = function(){ if(def.onUrlChange) window.$dispatch(def.onUrlChange($url(location.href))); };
      window.addEventListener('popstate', window.$onUrlChange);
      document.addEventListener('click', function(e){
        if (!def.onUrlRequest || e.defaultPrevented || e.button!==0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        var a=e.target; while(a && a.tagName!=='A') a=a.parentNode;
        if (!a || !a.getAttribute('href') || (a.target && a.target!=='' && a.target!=='_self')) return;
        var href=a.getAttribute('href');
        try {
          var u=new URL(href, location.href);
          if (u.origin===location.origin){ e.preventDefault(); window.$dispatch(def.onUrlRequest($data('Internal',[$url(u.href)]))); }
          else { window.$dispatch(def.onUrlRequest($data('External',[href]))); }
        } catch(err){}
      });
    }
  };
  // Entry point: a static Html value is rendered directly; a Browser program is mounted.
  window.$start = function(main, root){
    if (main.$==='$Node' || main.$==='$Text') { root.appendChild(window.$toDom(main)); }
    else { window.$mount(main, root); }
  };
})();

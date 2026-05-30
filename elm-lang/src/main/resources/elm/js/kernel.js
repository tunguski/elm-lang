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
function $ordToInt(o){ return o.$==='LT'?-1:(o.$==='GT'?1:0); }

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
  'List.concatMap': function(f){ return function(xs){ var out=[]; $listToArray(xs).forEach(function(x){ out=out.concat($listToArray(f(x))); }); return $list(out); }; },
  'List.filterMap': function(f){ return function(xs){ var out=[]; $listToArray(xs).forEach(function(x){ var m=f(x); if(m&&m.$==='Just') out.push(m._[0]); }); return $list(out); }; },
  'List.map3': function(f){ return function(a){ return function(b){ return function(c){ var aa=$listToArray(a),bb=$listToArray(b),cc=$listToArray(c),n=Math.min(aa.length,bb.length,cc.length),o=[]; for(var i=0;i<n;i++) o.push(f(aa[i])(bb[i])(cc[i])); return $list(o); }; }; }; },
  'List.repeat': function(n){ return function(x){ var o=[]; for(var i=0;i<n;i++) o.push(x); return $list(o); }; },
  'List.all': function(f){ return function(xs){ return $listToArray(xs).every(function(x){return f(x);}); }; },
  'List.any': function(f){ return function(xs){ return $listToArray(xs).some(function(x){return f(x);}); }; },
  'List.maximum': function(xs){ var a=$listToArray(xs); return a.length?$data('Just',[a.reduce(function(m,x){return $cmp(x,m)>0?x:m;})]):$data('Nothing',[]); },
  'List.minimum': function(xs){ var a=$listToArray(xs); return a.length?$data('Just',[a.reduce(function(m,x){return $cmp(x,m)<0?x:m;})]):$data('Nothing',[]); },
  'List.sortWith': function(f){ return function(xs){ return $list($listToArray(xs).slice().sort(function(a,b){ return $ordToInt(f(a)(b)); })); }; },
  'List.intersperse': function(sep){ return function(xs){ var a=$listToArray(xs),o=[]; for(var i=0;i<a.length;i++){ if(i>0)o.push(sep); o.push(a[i]); } return $list(o); }; },
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
  'String.words': function(s){ var t=s.trim(); return $list(t===''?[]:t.split(/\s+/)); },
  'String.lines': function(s){ return $list(s.split('\n')); },
  'String.toUpper': function(s){ return s.toUpperCase(); },
  'String.toLower': function(s){ return s.toLowerCase(); },
  'String.trim': function(s){ return s.trim(); },
  'String.fromInt': function(n){ return String(n); },
  'String.fromFloat': function(n){ return String(n); },
  'String.fromChar': function(c){ return String.fromCodePoint(c.c); },
  'String.toInt': function(s){ var n=parseInt(s,10); return (/^\s*-?\d+\s*$/.test(s) && !isNaN(n))?$data('Just',[n]):$data('Nothing',[]); },
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
  'Char.isAlpha': function(c){ return /\p{L}/u.test(String.fromCodePoint(c.c)); },
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
// Json.Encode (pure, lives in the kernel so it works under Node too): a Value is a native JS
// value and encode is JSON.stringify.
$rt['Json.Encode.int']=function(n){ return n; };
$rt['Json.Encode.float']=function(n){ return n; };
$rt['Json.Encode.string']=function(s){ return s; };
$rt['Json.Encode.bool']=function(b){ return b; };
$rt['Json.Encode.null']=null;
$rt['Json.Encode.list']=function(f){ return function(xs){ return $listToArray(xs).map(function(x){ return f(x); }); }; };
$rt['Json.Encode.object']=function(pairs){ var o={}; $listToArray(pairs).forEach(function(p){ o[p.vs[0]]=p.vs[1]; }); return o; };
$rt['Json.Encode.encode']=function(indent){ return function(v){ return JSON.stringify(v, null, indent); }; };

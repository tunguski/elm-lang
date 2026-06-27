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
function $listToArray(v){ if(v==null){throw new Error('$listToArray: got '+v+' — a non-List (likely an uninitialised top-level binding) reached a List operation');} var a=[]; while(v.$==='::'){a.push(v.a); v=v.b;} return a; }

// ---- equality & comparison ----
function $eq(x, y){
  if (typeof x === 'number' || typeof x === 'boolean' || typeof x === 'string') return x === y;
  if (x.$ === 'Char') return y.$==='Char' && x.c === y.c;
  if (x.$ === 'Dict' || x.$ === 'Set' || x.$ === 'Array'){
    if (x.$ !== y.$ || x.a.length !== y.a.length) return false;
    for (var di=0; di<x.a.length; di++){
      if (x.$ === 'Dict'){ if(!$eq(x.a[di][0],y.a[di][0]) || !$eq(x.a[di][1],y.a[di][1])) return false; }
      else if(!$eq(x.a[di],y.a[di])) return false;
    }
    return true;
  }
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
  if (t === 'Dict'){ return 'Dict.fromList ['+v.a.map(function(e){return '('+$show(e[0],true)+','+$show(e[1],true)+')';}).join(',')+']'; }
  if (t === 'Set'){ return 'Set.fromList ['+v.a.map(function(e){return $show(e,true);}).join(',')+']'; }
  if (t === 'Array'){ return 'Array.fromList ['+v.a.map(function(e){return $show(e,true);}).join(',')+']'; }
  if (t !== undefined){ if (v._.length===0) return t; return t+' '+v._.map($showArg).join(' '); }
  var ks=Object.keys(v);
  return '{ '+ks.map(function(k){return k+' = '+$show(v[k],true);}).join(', ')+' }';
}

// ---- Dict / Set (sorted entry arrays; toList/keys are key-sorted, matching the interpreter) ----
function $dictFind(a, k){ var lo=0, hi=a.length-1; while(lo<=hi){ var m=(lo+hi)>>1, c=$cmp(a[m][0],k); if(c<0)lo=m+1; else if(c>0)hi=m-1; else return m; } return -(lo+1); }
function $dictInsert(d, k, v){ var a=d.a.slice(), i=$dictFind(a,k); if(i>=0)a[i]=[k,v]; else a.splice(-i-1,0,[k,v]); return {$:'Dict',a:a}; }
function $setFind(a, x){ var lo=0, hi=a.length-1; while(lo<=hi){ var m=(lo+hi)>>1, c=$cmp(a[m],x); if(c<0)lo=m+1; else if(c>0)hi=m-1; else return m; } return -(lo+1); }
function $setInsert(s, x){ var a=s.a.slice(), i=$setFind(a,x); if(i<0)a.splice(-i-1,0,x); return {$:'Set',a:a}; }
// Bulk builders: sort once (O(n log n)) instead of n inserts that each copy+splice the array
// (O(n^2)). Array.sort is stable (V8/Node), so among equal keys the original order is kept — and
// Dict.fromList is last-wins (foldl insert), so we drop an entry when a later one shares its key.
function $dictFromArray(pairs){ pairs.sort(function(p,q){ return $cmp(p[0],q[0]); }); var a=[]; for(var i=0;i<pairs.length;i++){ if(i+1<pairs.length && $cmp(pairs[i][0],pairs[i+1][0])===0) continue; a.push(pairs[i]); } return {$:'Dict',a:a}; }
function $setFromArray(items){ items.sort($cmp); var a=[]; for(var i=0;i<items.length;i++){ if(i>0 && $cmp(items[i-1],items[i])===0) continue; a.push(items[i]); } return {$:'Set',a:a}; }

// ---- prelude (canonical Module.name -> curried function) ----
function $maybe(v){ return v===undefined?$data('Nothing',[]):$data('Just',[v]); }
// A Regex.Match record from a RegExp exec result (find) or replace-callback arguments.
function $regexMatch(m, number){ var subs=[]; for(var i=1;i<m.length;i++) subs.push($maybe(m[i])); return {match:m[0], index:m.index, number:number, submatches:$list(subs)}; }
function $regexMatchOf(matchStr, index, number, groups){ return {match:matchStr, index:index, number:number, submatches:$list(groups.map($maybe))}; }
var $rt = {
  'Dict.empty': {$:'Dict',a:[]},
  'Dict.singleton': function(k){ return function(v){ return {$:'Dict',a:[[k,v]]}; }; },
  'Dict.insert': function(k){ return function(v){ return function(d){ return $dictInsert(d,k,v); }; }; },
  'Dict.get': function(k){ return function(d){ var i=$dictFind(d.a,k); return i>=0?$data('Just',[d.a[i][1]]):$data('Nothing',[]); }; },
  'Dict.remove': function(k){ return function(d){ var i=$dictFind(d.a,k); if(i<0)return d; var a=d.a.slice(); a.splice(i,1); return {$:'Dict',a:a}; }; },
  'Dict.member': function(k){ return function(d){ return $dictFind(d.a,k)>=0; }; },
  'Dict.size': function(d){ return d.a.length; },
  'Dict.isEmpty': function(d){ return d.a.length===0; },
  'Dict.keys': function(d){ return $list(d.a.map(function(e){return e[0];})); },
  'Dict.values': function(d){ return $list(d.a.map(function(e){return e[1];})); },
  'Dict.toList': function(d){ return $list(d.a.map(function(e){return $tuple([e[0],e[1]]);})); },
  'Dict.fromList': function(xs){ var pairs=[]; while(xs.$==='::'){ pairs.push([xs.a.vs[0], xs.a.vs[1]]); xs=xs.b; } return $dictFromArray(pairs); },
  'Dict.foldl': function(f){ return function(acc){ return function(d){ d.a.forEach(function(e){ acc=f(e[0])(e[1])(acc); }); return acc; }; }; },
  'Dict.foldr': function(f){ return function(acc){ return function(d){ for(var i=d.a.length-1;i>=0;i--){ acc=f(d.a[i][0])(d.a[i][1])(acc); } return acc; }; }; },
  'Dict.foldr': function(f){ return function(acc){ return function(d){ for(var i=d.a.length-1;i>=0;i--) acc=f(d.a[i][0])(d.a[i][1])(acc); return acc; }; }; },
  'Dict.map': function(f){ return function(d){ return {$:'Dict',a:d.a.map(function(e){return [e[0],f(e[0])(e[1])];})}; }; },
  'Dict.filter': function(f){ return function(d){ return {$:'Dict',a:d.a.filter(function(e){return f(e[0])(e[1]);})}; }; },
  'Dict.union': function(a){ return function(b){ var d=b; a.a.forEach(function(e){ d=$dictInsert(d,e[0],e[1]); }); return d; }; },
  'Dict.intersect': function(a){ return function(b){ return {$:'Dict',a:a.a.filter(function(e){return $dictFind(b.a,e[0])>=0;})}; }; },
  'Dict.diff': function(a){ return function(b){ return {$:'Dict',a:a.a.filter(function(e){return $dictFind(b.a,e[0])<0;})}; }; },
  'Dict.update': function(k){ return function(f){ return function(d){ var i=$dictFind(d.a,k); var cur=i>=0?$data('Just',[d.a[i][1]]):$data('Nothing',[]); var r=f(cur); if(r.$==='Just'){ return $dictInsert(d,k,r._[0]); } if(i<0)return d; var a=d.a.slice(); a.splice(i,1); return {$:'Dict',a:a}; }; }; },
  'Dict.partition': function(f){ return function(d){ var yes=[],no=[]; d.a.forEach(function(e){ (f(e[0])(e[1])?yes:no).push(e); }); return $tuple([{$:'Dict',a:yes},{$:'Dict',a:no}]); }; },
  'Dict.merge': function(left){ return function(both){ return function(right){ return function(la){ return function(rb){ return function(acc){ var L=la.a,R=rb.a,i=0,j=0;
    while(i<L.length&&j<R.length){ var c=$cmp(L[i][0],R[j][0]); if(c<0){acc=left(L[i][0])(L[i][1])(acc);i++;} else if(c>0){acc=right(R[j][0])(R[j][1])(acc);j++;} else {acc=both(L[i][0])(L[i][1])(R[j][1])(acc);i++;j++;} }
    for(;i<L.length;i++)acc=left(L[i][0])(L[i][1])(acc); for(;j<R.length;j++)acc=right(R[j][0])(R[j][1])(acc); return acc; }; }; }; }; }; },
  'Set.empty': {$:'Set',a:[]},
  'Set.singleton': function(x){ return {$:'Set',a:[x]}; },
  'Set.insert': function(x){ return function(s){ return $setInsert(s,x); }; },
  'Set.remove': function(x){ return function(s){ var i=$setFind(s.a,x); if(i<0)return s; var a=s.a.slice(); a.splice(i,1); return {$:'Set',a:a}; }; },
  'Set.member': function(x){ return function(s){ return $setFind(s.a,x)>=0; }; },
  'Set.size': function(s){ return s.a.length; },
  'Set.isEmpty': function(s){ return s.a.length===0; },
  'Set.toList': function(s){ return $list(s.a.slice()); },
  'Set.fromList': function(xs){ var items=[]; while(xs.$==='::'){ items.push(xs.a); xs=xs.b; } return $setFromArray(items); },
  'Set.foldl': function(f){ return function(acc){ return function(s){ s.a.forEach(function(x){ acc=f(x)(acc); }); return acc; }; }; },
  'Set.foldr': function(f){ return function(acc){ return function(s){ for(var i=s.a.length-1;i>=0;i--){ acc=f(s.a[i])(acc); } return acc; }; }; },
  'Set.foldr': function(f){ return function(acc){ return function(s){ for(var i=s.a.length-1;i>=0;i--) acc=f(s.a[i])(acc); return acc; }; }; },
  'Set.map': function(f){ return function(s){ var r={$:'Set',a:[]}; s.a.forEach(function(x){ r=$setInsert(r,f(x)); }); return r; }; },
  'Set.filter': function(f){ return function(s){ return {$:'Set',a:s.a.filter(function(x){return f(x);})}; }; },
  'Set.partition': function(f){ return function(s){ var yes=[],no=[]; s.a.forEach(function(x){ (f(x)?yes:no).push(x); }); return $tuple([{$:'Set',a:yes},{$:'Set',a:no}]); }; },
  'Set.union': function(a){ return function(b){ var s=a; b.a.forEach(function(x){ s=$setInsert(s,x); }); return s; }; },
  'Set.intersect': function(a){ return function(b){ return {$:'Set',a:a.a.filter(function(x){return $setFind(b.a,x)>=0;})}; }; },
  'Set.diff': function(a){ return function(b){ return {$:'Set',a:a.a.filter(function(x){return $setFind(b.a,x)<0;})}; }; },
  'Array.empty': {$:'Array',a:[]},
  'Array.isEmpty': function(arr){ return arr.a.length===0; },
  'Array.length': function(arr){ return arr.a.length; },
  'Array.repeat': function(n){ return function(x){ var a=[]; for(var i=0;i<n;i++)a.push(x); return {$:'Array',a:a}; }; },
  'Array.initialize': function(n){ return function(f){ var a=[]; for(var i=0;i<n;i++)a.push(f(i)); return {$:'Array',a:a}; }; },
  'Array.fromList': function(xs){ return {$:'Array',a:$listToArray(xs)}; },
  'Array.toList': function(arr){ return $list(arr.a.slice()); },
  'Array.push': function(x){ return function(arr){ return {$:'Array',a:arr.a.concat([x])}; }; },
  'Array.get': function(i){ return function(arr){ return (i>=0&&i<arr.a.length)?$data('Just',[arr.a[i]]):$data('Nothing',[]); }; },
  'Array.set': function(i){ return function(x){ return function(arr){ if(i<0||i>=arr.a.length)return arr; var a=arr.a.slice(); a[i]=x; return {$:'Array',a:a}; }; }; },
  'Array.append': function(a){ return function(b){ return {$:'Array',a:a.a.concat(b.a)}; }; },
  'Array.map': function(f){ return function(arr){ return {$:'Array',a:arr.a.map(function(x){return f(x);})}; }; },
  'Array.indexedMap': function(f){ return function(arr){ return {$:'Array',a:arr.a.map(function(x,i){return f(i)(x);})}; }; },
  'Array.foldl': function(f){ return function(acc){ return function(arr){ arr.a.forEach(function(x){ acc=f(x)(acc); }); return acc; }; }; },
  'Array.foldr': function(f){ return function(acc){ return function(arr){ for(var i=arr.a.length-1;i>=0;i--) acc=f(arr.a[i])(acc); return acc; }; }; },
  'Array.slice': function(from){ return function(to){ return function(arr){ var n=arr.a.length; var s=from<0?Math.max(n+from,0):Math.min(from,n); var e=to<0?Math.max(n+to,0):Math.min(to,n); return {$:'Array',a:e>s?arr.a.slice(s,e):[]}; }; }; },
  'Array.filter': function(f){ return function(arr){ return {$:'Array',a:arr.a.filter(function(x){return f(x);})}; }; },
  'Array.toIndexedList': function(arr){ return $list(arr.a.map(function(x,i){return $tuple([i,x]);})); },
  'Basics.identity': function(x){ return x; },
  'Basics.always': function(a){ return function(b){ return a; }; },
  'Basics.never': function(n){ throw new Error('Basics.never: a Never value was somehow created'); },
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
  // List walks below traverse the cons-list directly: one result array at most (no source-array temp),
  // and the search/aggregate ops allocate nothing and short-circuit. (Was: $listToArray + Array.map.)
  'List.map': function(f){ return function(xs){ var o=[]; while(xs.$==='::'){ o.push(f(xs.a)); xs=xs.b; } return $list(o); }; },
  'List.indexedMap': function(f){ return function(xs){ var o=[],i=0; while(xs.$==='::'){ o.push(f(i)(xs.a)); i++; xs=xs.b; } return $list(o); }; },
  'List.filter': function(f){ return function(xs){ var o=[]; while(xs.$==='::'){ if(f(xs.a)) o.push(xs.a); xs=xs.b; } return $list(o); }; },
  'List.foldl': function(f){ return function(acc){ return function(xs){ while(xs.$==='::'){ acc=f(xs.a)(acc); xs=xs.b; } return acc; }; }; },
  'List.foldr': function(f){ return function(acc){ return function(xs){ var a=$listToArray(xs); for(var i=a.length-1;i>=0;i--) acc=f(a[i])(acc); return acc; }; }; },
  'List.length': function(xs){ var n=0; while(xs.$==='::'){ n++; xs=xs.b; } return n; },
  'List.reverse': function(xs){ var r=$nil; while(xs.$==='::'){ r=$cons(xs.a,r); xs=xs.b; } return r; },
  'List.member': function(x){ return function(xs){ while(xs.$==='::'){ if($eq(x,xs.a)) return true; xs=xs.b; } return false; }; },
  'List.append': function(a){ return function(b){ return $append(a,b); }; },
  'List.concat': function(xss){ var out=[]; $listToArray(xss).forEach(function(xs){ out=out.concat($listToArray(xs)); }); return $list(out); },
  'List.concatMap': function(f){ return function(xs){ var out=[]; $listToArray(xs).forEach(function(x){ out=out.concat($listToArray(f(x))); }); return $list(out); }; },
  'List.filterMap': function(f){ return function(xs){ var out=[]; $listToArray(xs).forEach(function(x){ var m=f(x); if(m&&m.$==='Just') out.push(m._[0]); }); return $list(out); }; },
  'List.map3': function(f){ return function(a){ return function(b){ return function(c){ var aa=$listToArray(a),bb=$listToArray(b),cc=$listToArray(c),n=Math.min(aa.length,bb.length,cc.length),o=[]; for(var i=0;i<n;i++) o.push(f(aa[i])(bb[i])(cc[i])); return $list(o); }; }; }; },
  'List.map4': function(f){ return function(a){ return function(b){ return function(c){ return function(d){ var aa=$listToArray(a),bb=$listToArray(b),cc=$listToArray(c),dd=$listToArray(d),n=Math.min(aa.length,bb.length,cc.length,dd.length),o=[]; for(var i=0;i<n;i++) o.push(f(aa[i])(bb[i])(cc[i])(dd[i])); return $list(o); }; }; }; }; },
  'List.map5': function(f){ return function(a){ return function(b){ return function(c){ return function(d){ return function(e){ var aa=$listToArray(a),bb=$listToArray(b),cc=$listToArray(c),dd=$listToArray(d),ee=$listToArray(e),n=Math.min(aa.length,bb.length,cc.length,dd.length,ee.length),o=[]; for(var i=0;i<n;i++) o.push(f(aa[i])(bb[i])(cc[i])(dd[i])(ee[i])); return $list(o); }; }; }; }; }; },
  'List.repeat': function(n){ return function(x){ var o=[]; for(var i=0;i<n;i++) o.push(x); return $list(o); }; },
  'List.all': function(f){ return function(xs){ while(xs.$==='::'){ if(!f(xs.a)) return false; xs=xs.b; } return true; }; },
  'List.any': function(f){ return function(xs){ while(xs.$==='::'){ if(f(xs.a)) return true; xs=xs.b; } return false; }; },
  'List.maximum': function(xs){ var a=$listToArray(xs); return a.length?$data('Just',[a.reduce(function(m,x){return $cmp(x,m)>0?x:m;})]):$data('Nothing',[]); },
  'List.minimum': function(xs){ var a=$listToArray(xs); return a.length?$data('Just',[a.reduce(function(m,x){return $cmp(x,m)<0?x:m;})]):$data('Nothing',[]); },
  'List.sortWith': function(f){ return function(xs){ return $list($listToArray(xs).slice().sort(function(a,b){ return $ordToInt(f(a)(b)); })); }; },
  'List.intersperse': function(sep){ return function(xs){ var a=$listToArray(xs),o=[]; for(var i=0;i<a.length;i++){ if(i>0)o.push(sep); o.push(a[i]); } return $list(o); }; },
  'List.partition': function(f){ return function(xs){ var yes=[],no=[]; $listToArray(xs).forEach(function(x){ (f(x)?yes:no).push(x); }); return $tuple([$list(yes),$list(no)]); }; },
  'List.unzip': function(xs){ var a=[],b=[]; $listToArray(xs).forEach(function(p){ a.push(p.vs[0]); b.push(p.vs[1]); }); return $tuple([$list(a),$list(b)]); },
  'List.unzip3': function(xs){ var a=[],b=[],c=[]; $listToArray(xs).forEach(function(p){ a.push(p.vs[0]); b.push(p.vs[1]); c.push(p.vs[2]); }); return $tuple([$list(a),$list(b),$list(c)]); },
  'List.sum': function(xs){ var s=0; while(xs.$==='::'){ s+=xs.a; xs=xs.b; } return s; },
  'List.product': function(xs){ var p=1; while(xs.$==='::'){ p*=xs.a; xs=xs.b; } return p; },
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
  // Regex (elm/regex): a Regex value is $data('$Regex',[source]); a Match is a plain record.
  'Regex.never': $data('$Regex',['(?!)']),
  'Regex.fromString': function(s){ try { new RegExp(s); return $data('Just',[$data('$Regex',[s])]); } catch(e){ return $data('Nothing',[]); } },
  'Regex.contains': function(re){ return function(s){ return new RegExp(re._[0]).test(s); }; },
  'Regex.split': function(re){ return function(s){ return $list(s.split(new RegExp(re._[0]))); }; },
  'Regex.find': function(re){ return function(s){ var rx=new RegExp(re._[0],'g'),out=[],m,n=0; while((m=rx.exec(s))!==null){ out.push($regexMatch(m, ++n)); if(m.index===rx.lastIndex) rx.lastIndex++; } return $list(out); }; },
  'Regex.replace': function(re){ return function(f){ return function(s){ var rx=new RegExp(re._[0],'g'),n=0; return s.replace(rx, function(){ var a=arguments; var groups=[].slice.call(a,1,a.length-2); return f($regexMatchOf(a[0], a[a.length-2], ++n, groups)); }); }; }; },
  'String.join': function(sep){ return function(xs){ return $listToArray(xs).join(sep); }; },
  'String.split': function(sep){ return function(s){ return $list(sep===''?s.split(''):s.split(sep)); }; },
  'String.words': function(s){ var t=s.trim(); return $list(t===''?[]:t.split(/\s+/)); },
  'String.lines': function(s){ return $list(s.split('\n')); },
  'String.toUpper': function(s){ return s.toUpperCase(); },
  'String.toLower': function(s){ return s.toLowerCase(); },
  'String.trim': function(s){ return s.trim(); },
  'String.trimLeft': function(s){ return s.replace(/^\s+/, ''); },
  'String.trimRight': function(s){ return s.replace(/\s+$/, ''); },
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
  'Char.toLocaleUpper': function(c){ return $char(String.fromCodePoint(c.c).toLocaleUpperCase().codePointAt(0)); },
  'Char.toLocaleLower': function(c){ return $char(String.fromCodePoint(c.c).toLocaleLowerCase().codePointAt(0)); },
  'Char.isDigit': function(c){ return c.c>=48 && c.c<=57; },
  'Char.isUpper': function(c){ var s=String.fromCodePoint(c.c); return s!==s.toLowerCase() && s===s.toUpperCase(); },
  'Char.isLower': function(c){ var s=String.fromCodePoint(c.c); return s!==s.toUpperCase() && s===s.toLowerCase(); },
  'Char.isAlpha': function(c){ return /\p{L}/u.test(String.fromCodePoint(c.c)); },
  'Char.isAlphaNum': function(c){ return /[\p{L}\p{N}]/u.test(String.fromCodePoint(c.c)); },
  'Char.isHexDigit': function(c){ return (c.c>=48&&c.c<=57)||(c.c>=97&&c.c<=102)||(c.c>=65&&c.c<=70); },
  'Char.isOctDigit': function(c){ return c.c>=48 && c.c<=55; },
  'Char.isSpace': function(c){ return c.c===32 || (c.c>=9 && c.c<=13); },
  'Char.isPunctuation': function(c){ return (c.c>=33&&c.c<=47)||(c.c>=58&&c.c<=64)||(c.c>=91&&c.c<=96)||(c.c>=123&&c.c<=126); },
  'Char.isControl': function(c){ return c.c<32 || c.c===127; },
  'String.map': function(f){ return function(s){ return Array.from(s).map(function(ch){ return String.fromCodePoint(f($char(ch.codePointAt(0))).c); }).join(''); }; },
  'String.filter': function(f){ return function(s){ return Array.from(s).filter(function(ch){ return f($char(ch.codePointAt(0))); }).join(''); }; },
  'String.foldl': function(f){ return function(acc){ return function(s){ Array.from(s).forEach(function(ch){ acc=f($char(ch.codePointAt(0)))(acc); }); return acc; }; }; },
  'String.foldr': function(f){ return function(acc){ return function(s){ var a=Array.from(s); for(var i=a.length-1;i>=0;i--){ acc=f($char(a[i].codePointAt(0)))(acc); } return acc; }; }; },
  'String.any': function(f){ return function(s){ return Array.from(s).some(function(ch){ return f($char(ch.codePointAt(0))); }); }; },
  'String.all': function(f){ return function(s){ return Array.from(s).every(function(ch){ return f($char(ch.codePointAt(0))); }); }; },
  'String.cons': function(c){ return function(s){ return String.fromCodePoint(c.c)+s; }; },
  'String.uncons': function(s){ if(s.length===0) return $data('Nothing',[]); var a=Array.from(s); return $data('Just',[$tuple([$char(a[0].codePointAt(0)), a.slice(1).join('')])]); },
  'String.slice': function(from){ return function(to){ return function(s){ var n=s.length; var a=from<0?Math.max(n+from,0):Math.min(from,n); var b=to<0?Math.max(n+to,0):Math.min(to,n); return a<b?s.slice(a,b):''; }; }; },
  'String.padLeft': function(n){ return function(c){ return function(s){ var t=n-Array.from(s).length; return t>0?String.fromCodePoint(c.c).repeat(t)+s:s; }; }; },
  'String.padRight': function(n){ return function(c){ return function(s){ var t=n-Array.from(s).length; return t>0?s+String.fromCodePoint(c.c).repeat(t):s; }; }; },
  'String.pad': function(n){ return function(c){ return function(s){ var t=n-Array.from(s).length; if(t<=0)return s; var f=String.fromCodePoint(c.c); return f.repeat(Math.ceil(t/2))+s+f.repeat(Math.floor(t/2)); }; }; },
  'String.indexes': function(sub){ return function(s){ var out=[]; if(sub.length){ var i=s.indexOf(sub); while(i>=0){ out.push(i); i=s.indexOf(sub,i+1); } } return $list(out); }; },
  'String.indices': function(sub){ return function(s){ var out=[]; if(sub.length){ var i=s.indexOf(sub); while(i>=0){ out.push(i); i=s.indexOf(sub,i+1); } } return $list(out); }; },
  'Bitwise.and': function(a){ return function(b){ return (a & b); }; },
  'Bitwise.or': function(a){ return function(b){ return (a | b); }; },
  'Bitwise.xor': function(a){ return function(b){ return (a ^ b); }; },
  'Bitwise.complement': function(a){ return ~a; },
  'Bitwise.shiftLeftBy': function(n){ return function(v){ return (v << n); }; },
  'Bitwise.shiftRightBy': function(n){ return function(v){ return (v >> n); }; },
  'Bitwise.shiftRightZfBy': function(n){ return function(v){ return (v >>> n); }; },
  'Maybe.withDefault': function(d){ return function(m){ return m.$==='Just'?m._[0]:d; }; },
  'Maybe.map': function(f){ return function(m){ return m.$==='Just'?$data('Just',[f(m._[0])]):m; }; },
  'Maybe.andThen': function(f){ return function(m){ return m.$==='Just'?f(m._[0]):m; }; },
  'Maybe.map2': function(f){ return function(a){ return function(b){ return (a.$==='Just'&&b.$==='Just')?$data('Just',[f(a._[0])(b._[0])]):$data('Nothing',[]); }; }; },
  'Maybe.map3': function(f){ return function(a){ return function(b){ return function(c){ return (a.$==='Just'&&b.$==='Just'&&c.$==='Just')?$data('Just',[f(a._[0])(b._[0])(c._[0])]):$data('Nothing',[]); }; }; }; },
  'Maybe.map4': function(f){ return function(a){ return function(b){ return function(c){ return function(d){ return (a.$==='Just'&&b.$==='Just'&&c.$==='Just'&&d.$==='Just')?$data('Just',[f(a._[0])(b._[0])(c._[0])(d._[0])]):$data('Nothing',[]); }; }; }; }; },
  'Maybe.map5': function(f){ return function(a){ return function(b){ return function(c){ return function(d){ return function(e){ return (a.$==='Just'&&b.$==='Just'&&c.$==='Just'&&d.$==='Just'&&e.$==='Just')?$data('Just',[f(a._[0])(b._[0])(c._[0])(d._[0])(e._[0])]):$data('Nothing',[]); }; }; }; }; }; },
  'Result.withDefault': function(d){ return function(r){ return r.$==='Ok'?r._[0]:d; }; },
  'Result.map': function(f){ return function(r){ return r.$==='Ok'?$data('Ok',[f(r._[0])]):r; }; },
  'Result.map2': function(f){ return function(a){ return function(b){ if(a.$==='Err')return a; if(b.$==='Err')return b; return $data('Ok',[f(a._[0])(b._[0])]); }; }; },
  'Result.map3': function(f){ return function(a){ return function(b){ return function(c){ if(a.$==='Err')return a; if(b.$==='Err')return b; if(c.$==='Err')return c; return $data('Ok',[f(a._[0])(b._[0])(c._[0])]); }; }; }; },
  'Result.map4': function(f){ return function(a){ return function(b){ return function(c){ return function(d){ if(a.$==='Err')return a; if(b.$==='Err')return b; if(c.$==='Err')return c; if(d.$==='Err')return d; return $data('Ok',[f(a._[0])(b._[0])(c._[0])(d._[0])]); }; }; }; }; },
  'Result.map5': function(f){ return function(a){ return function(b){ return function(c){ return function(d){ return function(e){ if(a.$==='Err')return a; if(b.$==='Err')return b; if(c.$==='Err')return c; if(d.$==='Err')return d; if(e.$==='Err')return e; return $data('Ok',[f(a._[0])(b._[0])(c._[0])(d._[0])(e._[0])]); }; }; }; }; }; },
  'Result.mapError': function(f){ return function(r){ return r.$==='Err'?$data('Err',[f(r._[0])]):r; }; },
  'Result.andThen': function(f){ return function(r){ return r.$==='Ok'?f(r._[0]):r; }; },
  'Result.toMaybe': function(r){ return r.$==='Ok'?$data('Just',[r._[0]]):$data('Nothing',[]); },
  'Result.fromMaybe': function(x){ return function(m){ return m.$==='Just'?$data('Ok',[m._[0]]):$data('Err',[x]); }; },
  'Tuple.pair': function(a){ return function(b){ return $tuple([a,b]); }; },
  'Tuple.first': function(t){ return t.vs[0]; },
  'Tuple.second': function(t){ return t.vs[1]; },
  'Tuple.mapFirst': function(f){ return function(t){ return $tuple([f(t.vs[0]), t.vs[1]]); }; },
  'Tuple.mapSecond': function(f){ return function(t){ return $tuple([t.vs[0], f(t.vs[1])]); }; },
  'Tuple.mapBoth': function(f){ return function(g){ return function(t){ return $tuple([f(t.vs[0]), g(t.vs[1])]); }; }; },
  'Debug.toString': function(v){ return $show(v,true); },
  'Debug.todo': function(m){ throw new Error('TODO: ' + m); },
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
$rt['Json.Encode.array']=function(f){ return function(arr){ return $rt['Json.Encode.list'](f)($rt['Array.toList'](arr)); }; };
$rt['Json.Encode.object']=function(pairs){ var o={}; $listToArray(pairs).forEach(function(p){ o[p.vs[0]]=p.vs[1]; }); return o; };
$rt['Json.Encode.dict']=function(toKey){ return function(toVal){ return function(d){ var o={}; d.a.forEach(function(e){ o[toKey(e[0])]=toVal(e[1]); }); return o; }; }; };
$rt['Json.Encode.set']=function(toVal){ return function(s){ return s.a.map(function(x){ return toVal(x); }); }; };
$rt['Json.Encode.encode']=function(indent){ return function(v){ return JSON.stringify(v, null, indent); }; };

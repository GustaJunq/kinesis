package dev.kinesis.core.expr

/**
 * The expression library written in JavaScript. It is executed in the fresh scope of every evaluation, so it can read
 * the per-evaluation variables (`time`, `value`, ...) and the host functions (`__rand`, `__noise`, `__valueAt`, ...).
 */
internal const val PRELUDE = """
var Math = Object.create(__Math);
Math.random = function () { return random(); };
var Date = undefined;

function __a(x) { return x !== null && typeof x === 'object' && typeof x.length === 'number'; }
function __m1(a, f) {
  if (!__a(a)) return f(a);
  var r = []; for (var i = 0; i < a.length; i++) r.push(f(a[i])); return r;
}
function __m2(a, b, f) {
  var x = __a(a), y = __a(b);
  if (!x && !y) return f(a, b);
  var n = x ? (y ? Math.max(a.length, b.length) : a.length) : b.length;
  var r = [];
  for (var i = 0; i < n; i++) r.push(f(x ? (i < a.length ? a[i] : 0) : a, y ? (i < b.length ? b[i] : 0) : b));
  return r;
}
function add(a, b) { return __m2(a, b, function (p, q) { return p + q; }); }
function sub(a, b) { return __m2(a, b, function (p, q) { return p - q; }); }
function mul(a, b) { return __m2(a, b, function (p, q) { return p * q; }); }
function div(a, b) { return __m2(a, b, function (p, q) { return p / q; }); }
function clamp(v, lo, hi) {
  return __m2(__m2(v, lo, function (p, l) { return Math.max(p, l); }), hi, function (p, h) { return Math.min(p, h); });
}
function length(a, b) {
  if (b === undefined) {
    if (!__a(a)) return Math.abs(a);
    var s = 0; for (var i = 0; i < a.length; i++) s += a[i] * a[i];
    return Math.sqrt(s);
  }
  return length(sub(a, b));
}
function normalize(v) { var l = length(v); return l === 0 ? v : div(v, l); }
function dot(a, b) { var s = 0; for (var i = 0; i < Math.min(a.length, b.length); i++) s += a[i] * b[i]; return s; }
function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
function degreesToRadians(d) { return d * Math.PI / 180; }
function radiansToDegrees(r) { return r * 180 / Math.PI; }

function __lerp(a, b, u) { return __m2(a, b, function (p, q) { return p + (q - p) * u; }); }
function __c01(u) { return u < 0 ? 0 : (u > 1 ? 1 : u); }
function __curve(kind, u) {
  if (kind === 0) return u * u * (3 - 2 * u);
  if (kind === 1) return 2 * u * u - u * u * u;
  if (kind === 2) return u + u * u - u * u * u;
  return u;
}
function __interp(kind, t, a, b, c, d) {
  if (c === undefined) return __lerp(a, b, __curve(kind, __c01(t)));
  var u = (b === a) ? (t >= b ? 1 : 0) : __c01((t - a) / (b - a));
  return __lerp(c, d, __curve(kind, u));
}
function linear(t, a, b, c, d) { return __interp(-1, t, a, b, c, d); }
function ease(t, a, b, c, d) { return __interp(0, t, a, b, c, d); }
function easeIn(t, a, b, c, d) { return __interp(1, t, a, b, c, d); }
function easeOut(t, a, b, c, d) { return __interp(2, t, a, b, c, d); }

function random(a, b) {
  if (a === undefined) return __rand();
  if (b === undefined) return __m1(a, function (x) { return __rand() * x; });
  return __m2(a, b, function (lo, hi) { return lo + (hi - lo) * __rand(); });
}
function seedRandom(seed, timeless) { __seed(seed, timeless ? 1 : 0); }

function valueAtTime(t) { return __valueAt(t); }
function key(i) {
  var k = __key(i);
  return { time: k[0], value: k.length === 2 ? k[1] : k.slice(1), index: i };
}
function __nearestIndex(t) {
  var n = __numKeys(), best = 0, bd = Infinity;
  for (var i = 1; i <= n; i++) { var d = Math.abs(__key(i)[0] - t); if (d < bd) { bd = d; best = i; } }
  return best;
}
function velocityAtTime(t) {
  var e = 0.001;
  return div(sub(__valueAt(t + e / 2), __valueAt(t - e / 2)), e);
}
function __slope(t, dir) {
  var e = 0.001;
  return dir > 0 ? div(sub(__valueAt(t), __valueAt(t - e)), e) : div(sub(__valueAt(t + e), __valueAt(t)), e);
}
function posterizeTime(fps) { time = Math.floor(time * fps + 1e-6) / fps; }

function wiggle(freq, amp, octaves, ampMult, t) {
  if (octaves === undefined) octaves = 1;
  if (ampMult === undefined) ampMult = 0.5;
  if (t === undefined) t = time;
  var base = __valueAt(t);
  var isArr = __a(base);
  var dim = isArr ? base.length : 1;
  var out = [];
  for (var d = 0; d < dim; d++) {
    var n = 0, f = freq, a = 1;
    for (var o = 0; o < octaves; o++) { n += a * __noise(t * f, d * 131 + o * 17); f *= 2; a *= ampMult; }
    out.push(n * amp);
  }
  return isArr ? add(base, out) : base + out[0];
}

function __loop(dir, type, nk) {
  var n = __numKeys();
  if (type === undefined) type = 'cycle';
  if (nk === undefined) nk = 0;
  if (n < 2) return __valueAt(time);
  var segs = nk > 0 ? Math.min(nk, n - 1) : n - 1;
  var iA = dir > 0 ? n - segs : 1, iB = dir > 0 ? n : 1 + segs;
  var tA = __key(iA)[0], tB = __key(iB)[0], span = tB - tA;
  if (span <= 0) return __valueAt(time);
  var t = time;
  if (dir > 0 && t <= tB) return __valueAt(t);
  if (dir < 0 && t >= tA) return __valueAt(t);
  var vA = __valueAt(tA), vB = __valueAt(tB);
  if (type === 'continue') {
    return dir > 0 ? add(vB, mul(__slope(tB, 1), t - tB)) : add(vA, mul(__slope(tA, -1), t - tA));
  }
  var e = dir > 0 ? t - tA : tB - t;
  var k = Math.floor(e / span), r = e - k * span, tt;
  if (type === 'pingpong' && k % 2 === 1) tt = dir > 0 ? tB - r : tA + r;
  else tt = dir > 0 ? tA + r : tB - r;
  if (type === 'offset') {
    var delta = mul(sub(vB, vA), k);
    return dir > 0 ? add(__valueAt(tt), delta) : sub(__valueAt(tt), delta);
  }
  return __valueAt(tt);
}
function loopOut(type, nk) { return __loop(1, type, nk); }
function loopIn(type, nk) { return __loop(-1, type, nk); }

var thisProperty = {
  get value() { return __valueAt(time); },
  get numKeys() { return __numKeys(); },
  valueAtTime: valueAtTime, key: key, velocityAtTime: velocityAtTime,
  nearestKey: function (t) { return key(__nearestIndex(t)); },
  loopOut: loopOut, loopIn: loopIn, wiggle: wiggle
};

function __mkEffect(layerIndex, ref) {
  var f = function (param) { return __effectValue(layerIndex, ref, param); };
  f.param = f;
  return f;
}

function __mkLayer(i) {
  return {
    name: i[0], index: i[1], inPoint: i[2], outPoint: i[3], startTime: i[2],
    effect: function (ref) { return __mkEffect(i[1], ref); },
    transform: { position: [i[4], i[5]], scale: [i[6], i[7]], rotation: i[8], opacity: i[9] }
  };
}
var thisLayer = __mkLayer(__layerInfo(index));
var thisComp = {
  name: 'Comp', width: __comp[0], height: __comp[1], duration: __comp[2], frameDuration: __comp[3], numLayers: __comp[4],
  displayStartTime: 0,
  layer: function (x) {
    var i = __layerInfo(x);
    if (!i) throw new Error('Layer "' + x + '" was not found in the composition');
    return __mkLayer(i);
  }
};
function effect(ref) { return thisLayer.effect(ref); }
var textSelectorValue = selectorValue;
"""

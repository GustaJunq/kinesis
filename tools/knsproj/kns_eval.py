"""Scene evaluation for the approximate preview: resolves a project at one instant into a flat list of draw nodes.

Mirrors `SceneEvaluator`, `Animatable`, `Easing`, `Transitions`, `TextAnimation` and `EffectMotion` from the Kotlin
core (core/src/main/kotlin/dev/kinesis/core), so timing, keyframes, easing, parenting, transitions, the camera and the
lights land where the app puts them. Only the standard library is used here; drawing lives in kns_render.py.

What this evaluator does not do (reported as notes by the preview): JavaScript expressions (the keyframed/static value is
used instead), text property animators, `timeSlice`, `echo`.
"""

import math

# ---------------------------------------------------------------------------------------------------------- values


def parse_color(v, default=(1.0, 1.0, 1.0, 1.0)):
    if isinstance(v, str) and v.startswith("#"):
        h = v[1:]
        try:
            if len(h) == 6:
                return (int(h[0:2], 16) / 255, int(h[2:4], 16) / 255, int(h[4:6], 16) / 255, 1.0)
            if len(h) == 8:
                return (int(h[0:2], 16) / 255, int(h[2:4], 16) / 255, int(h[4:6], 16) / 255, int(h[6:8], 16) / 255)
        except ValueError:
            return default
    if isinstance(v, (list, tuple)) and len(v) in (3, 4):
        return tuple(float(x) for x in v) + ((1.0,) if len(v) == 3 else ())
    return default


def _conv(kind, v, default):
    if v is None:
        return default
    if kind == "num":
        return float(v) if isinstance(v, (int, float)) else default
    if kind == "color":
        return parse_color(v, default)
    if isinstance(v, (list, tuple)):
        n = len(default)
        vals = [float(x) for x in v[:n]]
        return tuple(vals + list(default[len(vals):]))
    if isinstance(v, (int, float)):  # a single number for a vector (uniform scale, etc.)
        return tuple(float(v) for _ in default)
    return default


def _lerp(a, b, t):
    if isinstance(a, tuple):
        return tuple(x + (y - x) * t for x, y in zip(a, b))
    return a + (b - a) * t


class Notes:
    """Collects what the preview approximated or skipped (deduplicated, in order)."""

    def __init__(self):
        self.items = []

    def add(self, msg):
        if msg not in self.items:
            self.items.append(msg)


NOTES = Notes()


def anim(raw, kind, default, t):
    """Samples an animatable property (static value or {keyframes, loop, value, expression})."""
    if not isinstance(raw, dict):
        return _conv(kind, raw, default)
    if raw.get("expression") and raw.get("expressionEnabled", True):
        NOTES.add("expressões JavaScript não são avaliadas no preview (usa o valor/keyframes de base)")
    kfs = raw.get("keyframes")
    if not kfs:
        return _conv(kind, raw.get("value"), default)
    kfs = sorted(kfs, key=lambda k: k.get("time", 0))
    vals = [_conv(kind, k.get("value"), default) for k in kfs]
    if len(kfs) == 1:
        return vals[0]
    # loop
    loop = raw.get("loop", "none")
    first, last = kfs[0].get("time", 0), kfs[-1].get("time", 0)
    span = last - first
    if loop in ("cycle", "pingpong") and span > 0 and t > last:
        elapsed = t - first
        cycle = math.floor(elapsed / span)
        local = elapsed - cycle * span
        t = last - local if (loop == "pingpong" and int(cycle) % 2 == 1) else first + local
    if t <= first:
        return vals[0]
    if t >= last:
        return vals[-1]
    i = 0
    while i < len(kfs) - 2 and kfs[i + 1].get("time", 0) <= t:
        i += 1
    t0, t1 = kfs[i].get("time", 0), kfs[i + 1].get("time", 0)
    if t1 - t0 <= 0:
        return vals[i + 1]
    u = (t - t0) / (t1 - t0)
    return _lerp(vals[i], vals[i + 1], ease(kfs[i].get("easing", "linear"), u))


def num(raw, default, t):
    return anim(raw, "num", default, t)


def v2(raw, default, t):
    return anim(raw, "vec", default, t)


def v3(raw, default, t):
    return anim(raw, "vec", default, t)


def col(raw, default, t):
    return anim(raw, "color", default, t)


# ---------------------------------------------------------------------------------------------------------- easing

PRESETS = {
    "linear": (0, 0, 1, 1), "ease": (0.25, 0.1, 0.25, 1), "easeIn": (0.42, 0, 1, 1), "easeOut": (0, 0, 0.58, 1),
    "easeInOut": (0.42, 0, 0.58, 1),
    "easeInSine": (0.12, 0, 0.39, 0), "easeOutSine": (0.61, 1, 0.88, 1), "easeInOutSine": (0.37, 0, 0.63, 1),
    "easeInQuad": (0.11, 0, 0.5, 0), "easeOutQuad": (0.5, 1, 0.89, 1), "easeInOutQuad": (0.45, 0, 0.55, 1),
    "easeInCubic": (0.32, 0, 0.67, 0), "easeOutCubic": (0.33, 1, 0.68, 1), "easeInOutCubic": (0.65, 0, 0.35, 1),
    "easeInQuart": (0.5, 0, 0.75, 0), "easeOutQuart": (0.25, 1, 0.5, 1), "easeInOutQuart": (0.76, 0, 0.24, 1),
    "easeInQuint": (0.64, 0, 0.78, 0), "easeOutQuint": (0.22, 1, 0.36, 1), "easeInOutQuint": (0.83, 0, 0.17, 1),
    "easeInExpo": (0.7, 0, 0.84, 0), "easeOutExpo": (0.16, 1, 0.3, 1), "easeInOutExpo": (0.87, 0, 0.13, 1),
    "easeInCirc": (0.55, 0, 1, 0.45), "easeOutCirc": (0, 0.55, 0.45, 1), "easeInOutCirc": (0.85, 0, 0.15, 1),
    "easeInBack": (0.36, 0, 0.66, -0.56), "easeOutBack": (0.34, 1.56, 0.64, 1), "easeInOutBack": (0.68, -0.6, 0.32, 1.6),
}


def _bezier(x1, y1, x2, y2, x):
    cx = 3 * x1
    bx = 3 * (x2 - x1) - cx
    ax = 1 - cx - bx
    cy = 3 * y1
    by = 3 * (y2 - y1) - cy
    ay = 1 - cy - by

    def sx(t):
        return ((ax * t + bx) * t + cx) * t

    t2 = x
    for _ in range(8):
        x2_ = sx(t2) - x
        if abs(x2_) < 1e-6:
            return ((ay * t2 + by) * t2 + cy) * t2
        d = (3 * ax * t2 + 2 * bx) * t2 + cx
        if abs(d) < 1e-6:
            break
        t2 -= x2_ / d
    lo, hi, t2 = 0.0, 1.0, x
    while lo < hi:
        v = sx(t2)
        if abs(v - x) < 1e-6:
            break
        if x > v:
            lo = t2
        else:
            hi = t2
        t2 = (hi - lo) * 0.5 + lo
        if hi - lo < 1e-9:
            break
    return ((ay * t2 + by) * t2 + cy) * t2


def _bounce_out(x):
    n1, d1 = 7.5625, 2.75
    if x < 1 / d1:
        return n1 * x * x
    if x < 2 / d1:
        x -= 1.5 / d1
        return n1 * x * x + 0.75
    if x < 2.5 / d1:
        x -= 2.25 / d1
        return n1 * x * x + 0.9375
    x -= 2.625 / d1
    return n1 * x * x + 0.984375


def ease(spec, u):
    if spec == "hold":
        return 1.0 if u >= 1 else 0.0
    if u <= 0:
        return 0.0
    if u >= 1:
        return 1.0
    if isinstance(spec, dict):
        if "cubicBezier" in spec:
            b = spec["cubicBezier"]
            return _bezier(*[float(x) for x in b[:4]], u)
        if "cyclic" in spec:
            c = spec["cyclic"] or {}
            offset = float(c.get("offset", 0))
            cycles = float(c.get("cycles", 3.5))
            phase = float(c.get("phase", 0))
            peak = 0.5 + 0.45 * offset

            def warp(t):
                return 0.5 * t / peak if t < peak else 0.5 + 0.5 * (t - peak) / (1 - peak)

            x = cycles * (u - phase)
            k = math.floor(x)
            f = x - k
            whole = math.floor(cycles)
            tail = cycles - whole
            g = tail * warp(f / tail) if (k == whole and tail > 1e-6 and phase == 0) else warp(f)
            return 0.5 - 0.5 * math.cos(2 * math.pi * g)
        return u
    if spec in PRESETS:
        return _bezier(*PRESETS[spec], u)
    x = u
    if spec == "easeOutBounce":
        return _bounce_out(x)
    if spec == "easeInBounce":
        return 1 - _bounce_out(1 - x)
    if spec == "easeInOutBounce":
        return (1 - _bounce_out(1 - 2 * x)) / 2 if x < 0.5 else (1 + _bounce_out(2 * x - 1)) / 2
    if spec == "easeInElastic":
        return -(2 ** (10 * x - 10)) * math.sin((x * 10 - 10.75) * 2 * math.pi / 3)
    if spec == "easeOutElastic":
        return 2 ** (-10 * x) * math.sin((x * 10 - 0.75) * 2 * math.pi / 3) + 1
    if spec == "easeInOutElastic":
        c5 = 2 * math.pi / 4.5
        if x < 0.5:
            return -(2 ** (20 * x - 10) * math.sin((20 * x - 11.125) * c5)) / 2
        return 2 ** (-20 * x + 10) * math.sin((20 * x - 11.125) * c5) / 2 + 1
    return u


# ---------------------------------------------------------------------------------------------------------- 2D math
# Affine (a, b, c, d, tx, ty): (x, y) → (a·x + c·y + tx, b·x + d·y + ty). Degrees clockwise (+y down).

IDENT = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def amul(m, o):
    a, b, c, d, tx, ty = m
    oa, ob, oc, od, otx, oty = o
    return (a * oa + c * ob, b * oa + d * ob, a * oc + c * od, b * oc + d * od, a * otx + c * oty + tx, b * otx + d * oty + ty)


def aapply(m, p):
    return (m[0] * p[0] + m[2] * p[1] + m[4], m[1] * p[0] + m[3] * p[1] + m[5])


def atrans(x, y):
    return (1.0, 0.0, 0.0, 1.0, x, y)


def arot(deg):
    r = math.radians(deg)
    cs, sn = math.cos(r), math.sin(r)
    return (cs, sn, -sn, cs, 0.0, 0.0)


def ascale(sx, sy):
    return (sx, 0.0, 0.0, sy, 0.0, 0.0)


def alayer(pos, anchor, scale, rot):
    m = amul(atrans(*pos), arot(rot))
    m = amul(m, ascale(*scale))
    return amul(m, atrans(-anchor[0], -anchor[1]))


def ainv(m):
    a, b, c, d, tx, ty = m
    det = a * d - b * c
    if abs(det) < 1e-12:
        return None
    i = 1 / det
    return (d * i, -b * i, -c * i, a * i, (c * ty - d * tx) * i, (b * tx - a * ty) * i)


# ---------------------------------------------------------------------------------------------------------- 3D math
# 4×4 row-major nested tuples; points are column vectors.


def m4mul(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)) for i in range(4))


M4I = ((1.0, 0, 0, 0), (0, 1.0, 0, 0), (0, 0, 1.0, 0), (0, 0, 0, 1.0))


def m4t(v):
    return ((1.0, 0, 0, v[0]), (0, 1.0, 0, v[1]), (0, 0, 1.0, v[2]), (0, 0, 0, 1.0))


def m4s(v):
    return ((v[0], 0, 0, 0), (0, v[1], 0, 0), (0, 0, v[2], 0), (0, 0, 0, 1.0))


def m4rx(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return ((1.0, 0, 0, 0), (0, c, -s, 0), (0, s, c, 0), (0, 0, 0, 1.0))


def m4ry(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return ((c, 0, s, 0), (0, 1.0, 0, 0), (-s, 0, c, 0), (0, 0, 0, 1.0))


def m4rz(d):
    c, s = math.cos(math.radians(d)), math.sin(math.radians(d))
    return ((c, -s, 0, 0), (s, c, 0, 0), (0, 0, 1.0, 0), (0, 0, 0, 1.0))


def m4rxyz(r):
    return m4mul(m4rz(r[2]), m4mul(m4ry(r[1]), m4rx(r[0])))


def m4layer(pos, anchor, scale, rot):
    return m4mul(m4t(pos), m4mul(m4rxyz(rot), m4mul(m4s(scale), m4t((-anchor[0], -anchor[1], -anchor[2])))))


def m4point(m, p):
    return tuple(m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3] for i in range(3))


def m4vec(m, p):
    return tuple(m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] for i in range(3))


def vsub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def vadd(a, b):
    return (a[0] + b[0], a[1] + b[1], a[2] + b[2])


def vmul(a, k):
    return (a[0] * k, a[1] * k, a[2] * k)


def vdot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def vcross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def vnorm(a):
    n = math.sqrt(vdot(a, a))
    return (a[0] / n, a[1] / n, a[2] / n) if n > 1e-9 else (0.0, 0.0, 0.0)


def look_at(eye, target, up):
    f = vnorm(vsub(target, eye))
    if vdot(f, f) < 1e-12:
        f = (0.0, 0.0, 1.0)
    s = vnorm(vcross(f, up))
    if vdot(s, s) < 1e-12:
        s = vnorm(vcross(f, (0.0, 0.0, 1.0)))
    if vdot(s, s) < 1e-12:
        s = (1.0, 0.0, 0.0)
    u = vcross(s, f)
    return ((s[0], s[1], s[2], -vdot(s, eye)), (u[0], u[1], u[2], -vdot(u, eye)),
            (-f[0], -f[1], -f[2], vdot(f, eye)), (0, 0, 0, 1.0))


def perspective(fov, aspect, near, far):
    f = 1 / math.tan(math.radians(fov) / 2)
    nf = 1 / (near - far)
    return ((f / aspect, 0, 0, 0), (0, f, 0, 0), (0, 0, (far + near) * nf, 2 * far * near * nf), (0, 0, -1.0, 0))


NEAR, FAR = 10.0, 200000.0
DEFAULT_FOV = 40.0


def default_distance(height, fov):
    return (height / 2) / math.tan(math.radians(fov / 2))


# ---------------------------------------------------------------------------------------------------------- layers

VISUAL_2D = {"text", "shape", "image", "video", "group"}
RENDERED_3D = {"object3d", "model3d"}


def is_3d_layer(l):
    return l.get("type") in ("object3d", "model3d", "camera", "light") or (
        l.get("type") == "null" and (l.get("null") or {}).get("threeD", False))


def has_t2(l):
    return l.get("type") in VISUAL_2D or (l.get("type") == "null" and not is_3d_layer(l))


def has_t3(l):
    return l.get("type") in RENDERED_3D or (l.get("type") == "null" and is_3d_layer(l))


def start(l):
    return float(l.get("start", 0.0))


def duration(l):
    return float(l.get("duration", 5.0))


def active(l, ct):
    return l.get("visible", True) and start(l) <= ct < start(l) + duration(l)


def local2d(l, t):
    tr = l.get("transform") or {}
    return alayer(v2(tr.get("position"), (0.0, 0.0), t), v2(tr.get("anchor"), (0.0, 0.0), t),
                  v2(tr.get("scale"), (1.0, 1.0), t), num(tr.get("rotation"), 0.0, t))


def local3d(l, t):
    tr = l.get("transform3d") or {}
    return m4layer(v3(tr.get("position"), (0.0, 0.0, 0.0), t), v3(tr.get("anchor"), (0.0, 0.0, 0.0), t),
                   v3(tr.get("scale"), (1.0, 1.0, 1.0), t), v3(tr.get("rotation"), (0.0, 0.0, 0.0), t))


def parent_chain(l, sib):
    chain, seen = [], {l.get("id")}
    cur = sib.get(l.get("parent")) if l.get("parent") else None
    while cur is not None and cur.get("id") not in seen:
        seen.add(cur.get("id"))
        chain.append(cur)
        cur = sib.get(cur.get("parent")) if cur.get("parent") else None
    return chain


def parent2d(l, sib, ct):
    m = IDENT
    for p in parent_chain(l, sib):
        if not has_t2(p):
            break
        m = amul(local2d(p, ct - start(p)), m)
    return m


def parent3d(l, sib, ct):
    m = M4I
    for p in parent_chain(l, sib):
        if not has_t3(p):
            break
        m = m4mul(local3d(p, ct - start(p)), m)
    return m


# ---------------------------------------------------------------------------------------------------------- transitions


def transition_state(l, t, comp):
    st = {"opacity": 1.0, "offset": (0.0, 0.0), "scale": 1.0, "rotation": 0.0, "blur": 0.0, "wipe": None}

    def combine(o):
        st["opacity"] *= o.get("opacity", 1.0)
        off = o.get("offset", (0.0, 0.0))
        st["offset"] = (st["offset"][0] + off[0], st["offset"][1] + off[1])
        st["scale"] *= o.get("scale", 1.0)
        st["rotation"] += o.get("rotation", 0.0)
        st["blur"] += o.get("blur", 0.0)
        if st["wipe"] is None:
            st["wipe"] = o.get("wipe")

    def state_for(tr, p, entering):
        away = 1 - p
        sign = 1 if entering else -1
        dx = tr.get("distance") or comp["width"] * 0.25
        dy = tr.get("distance") or comp["height"] * 0.25
        ty = tr.get("type", "fade")
        if ty == "fade":
            return {"opacity": p}
        if ty == "slideLeft":
            return {"opacity": p, "offset": (sign * dx * away, 0.0)}
        if ty == "slideRight":
            return {"opacity": p, "offset": (-sign * dx * away, 0.0)}
        if ty == "slideUp":
            return {"opacity": p, "offset": (0.0, sign * dy * away)}
        if ty == "slideDown":
            return {"opacity": p, "offset": (0.0, -sign * dy * away)}
        if ty == "zoomIn":
            return {"opacity": p, "scale": _lerp(0.5, 1, p) if entering else _lerp(1.5, 1, p)}
        if ty == "zoomOut":
            return {"opacity": p, "scale": _lerp(1.5, 1, p) if entering else _lerp(0.5, 1, p)}
        if ty == "spin":
            return {"opacity": p, "scale": _lerp(0.3, 1, p), "rotation": sign * -180 * away}
        if ty == "blur":
            return {"opacity": p, "blur": 40 * away}
        if ty == "wipeLeft":
            return {"wipe": (p, entering)}
        if ty == "wipeRight":
            return {"wipe": (p, not entering)}
        return {}

    tin, tout = l.get("transitionIn"), l.get("transitionOut")
    easing = lambda tr: tr.get("easing", "easeOutCubic")  # noqa: E731
    if tin:
        d = float(tin.get("duration", 0.5))
        if d > 0 and t < d:
            combine(state_for(tin, ease(easing(tin), max(0.0, min(1.0, t / d))), True))
    if tout:
        d = float(tout.get("duration", 0.5))
        s0 = duration(l) - d
        if d > 0 and t > s0:
            q = max(0.0, min(1.0, (t - s0) / d))
            combine(state_for(tout, 1 - ease(easing(tout), q), False))
    return st


def transition_matrix(st, pivot):
    off, sc, rot = st["offset"], st["scale"], st["rotation"]
    if off == (0.0, 0.0) and sc == 1.0 and rot == 0.0:
        return IDENT
    m = amul(atrans(pivot[0] + off[0], pivot[1] + off[1]), arot(rot))
    m = amul(m, ascale(sc, sc))
    return amul(m, atrans(-pivot[0], -pivot[1]))


# ---------------------------------------------------------------------------------------------------------- effects

_REGISTRY = None


def registry():
    global _REGISTRY
    if _REGISTRY is None:
        import json
        import os
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "effects.json"), encoding="utf-8") as f:
            _REGISTRY = {e["id"]: e for e in json.load(f)["effects"]}
    return _REGISTRY


def resolve_effect(e, t):
    spec = registry().get(e.get("type"))
    if spec is None:
        return None
    vals = {}
    for p in spec["params"]:
        raw = e.get(p["name"], p.get("default"))
        ty = p["type"]
        if ty in ("number", "integer"):
            v = num(raw, float(p.get("default", 0) or 0), t)
            if "min" in p:
                v = max(float(p["min"]), v)
            if "max" in p:
                v = min(float(p["max"]), v)
            vals[p["name"]] = round(v) if ty == "integer" else v
        elif ty == "color":
            vals[p["name"]] = col(raw, parse_color(p.get("default")), t)
        elif ty == "point":
            vals[p["name"]] = v2(raw, tuple(p.get("default") or (0, 0)), t)
        else:
            vals[p["name"]] = raw
    return vals


def _hash(seed, n):
    def i32(x):
        x &= 0xFFFFFFFF
        return x - 0x100000000 if x >= 0x80000000 else x

    h = i32(n * 0x27d4eb2d + seed * 0x165667b1)
    h ^= (h & 0xFFFFFFFF) >> 15
    h = i32(h * 0x2c1b3c6d)
    h ^= (h & 0xFFFFFFFF) >> 12
    h = i32(h * 0x297a2d39)
    h ^= (h & 0xFFFFFFFF) >> 15
    return ((h & 0xFFFFFFFF) >> 8) / 8388607.5 - 1


def _noise(seed, x):
    i = math.floor(x)
    f = x - i
    a, b = _hash(seed, int(i)), _hash(seed, int(i) + 1)
    s = f * f * (3 - 2 * f)
    return a + (b - a) * s


def _around(pivot, rot, sc, off):
    m = amul(atrans(*off), atrans(*pivot))
    m = amul(m, arot(rot))
    m = amul(m, ascale(sc, sc))
    return amul(m, atrans(-pivot[0], -pivot[1]))


def motion_matrix(kind, v, t, origin, to_comp):
    """`EffectMotion.transform` in composition pixels."""
    if kind == "autoShake":
        x = v["frequency"] * t
        seed, amp, axes = int(v["seed"]), v["amplitude"], v.get("axes", "xy")
        dx = 0.0 if axes == "y" else amp * _noise(seed * 3, x)
        dy = 0.0 if axes == "x" else amp * _noise(seed * 3 + 1, x)
        return _around(origin, v["rotation"] * _noise(seed * 3 + 2, x), 1.0, (dx, dy))
    if kind == "spin":
        return _around(aapply(to_comp, v["center"]), v["speed"] * t, 1.0, (0.0, 0.0))
    if kind in ("oscillate", "float", "pulse"):
        w = 2 * math.pi * v["frequency"] * t + math.radians(v.get("phase", 0))
        if kind == "oscillate":
            a = v["amount"]
            return _around(origin, v["rotationAmount"] * math.sin(w * 1.27 + 0.6), 1 + v["scaleAmount"] * math.sin(w * 0.91 + 2.1),
                           (a * math.sin(w), a * math.sin(w * 1.618 + 1.3)))
        if kind == "float":
            a = v["amplitude"]
            return _around(origin, a * 0.05 * math.sin(w * 0.5 + 0.8), 1.0, (a * 0.3 * math.sin(w * 0.5), -a * math.sin(w)))
        return _around(origin, 0.0, 1 + v["amount"] * (0.5 + 0.5 * math.sin(w)), (0.0, 0.0))
    return None


MOTION = {"autoShake", "spin", "oscillate", "float", "pulse"}


def effects_of(l, t, to_comp):
    out = []
    origin = aapply(to_comp, (0.0, 0.0))
    for e in l.get("effects") or []:
        if not e.get("enabled", True):
            continue
        v = resolve_effect(e, t)
        if v is None:
            continue
        kind = e["type"]
        m = motion_matrix(kind, v, t, origin, to_comp) if kind in MOTION else None
        out.append({"type": kind, "v": v, "matrix": m, "origin": origin, "to_comp": to_comp})
    return out


def posterized(l, ct):
    for e in l.get("effects") or []:
        if e.get("enabled", True) and e.get("type") == "posterizeTime":
            t = ct - start(l)
            fps = max(1.0, min(60.0, num(e.get("fps"), 8.0, t)))
            q = math.floor(t * fps + 1e-6) / fps
            return ct - (t - q)
    return ct


# ---------------------------------------------------------------------------------------------------------- text animators


def _unit_indices(text, unit):
    idx = [0] * len(text)
    if unit == "word":
        n, in_word = -1, False
        for i, ch in enumerate(text):
            ws = ch.isspace()
            if not ws and not in_word:
                n += 1
            in_word = not ws
            idx[i] = max(n, 0)
        return idx, n + 1
    if unit == "line":
        line = 0
        for i, ch in enumerate(text):
            idx[i] = line
            if ch == "\n":
                line += 1
        return idx, line + 1
    n = 0
    for i, ch in enumerate(text):
        idx[i] = n
        if not ch.isspace():
            n += 1
    for i, ch in enumerate(text):
        if ch.isspace():
            idx[i] = min(idx[i], max(n - 1, 0))
    return idx, n


def _random_perm(n):
    p = list(range(n))
    seed = (0x9E3779B9 ^ n) & 0xFFFFFFFFFFFFFFFF
    for i in range(n - 1, 0, -1):
        seed = (seed * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        j = (seed >> 33) % (i + 1)
        p[i], p[j] = p[j], p[i]
    return p


def _order(i, n, order):
    if order == "backward":
        return n - 1 - i
    if order == "center":
        return int(abs(i - (n - 1) / 2) + 0.001)
    if order == "random":
        return _random_perm(n)[i]
    return i


def glyph_states(text, animators, time):
    """Per character: [dx, dy, scale, rotation, opacity]."""
    states = [[0.0, 0.0, 1.0, 0.0, 1.0] for _ in text]
    for a in animators or []:
        unit_of, count = _unit_indices(text, a.get("unit", "character"))
        if count == 0:
            continue
        for c in range(len(text)):
            k = _order(max(0, min(count - 1, unit_of[c])), count, a.get("order", "forward"))
            dx, dy, sc, rot, op = _anim_state(a, k, time)
            s = states[c]
            s[0] += dx
            s[1] += dy
            s[2] *= sc
            s[3] += rot
            s[4] *= op
    return states


def _anim_state(a, k, time):
    ty = a.get("type", "fade")
    a_start = float(a.get("start", 0))
    stagger = float(a.get("stagger", 0.05))
    unit_dur = float(a.get("unitDuration", 0.4))
    amount = float(a.get("amount", 60))
    unit_start = a_start + k * stagger
    if ty == "wave":
        if time < a_start:
            return 0, 0, 1, 0, 1
        phase = (time - unit_start) / max(unit_dur, 0.01)
        return 0, -amount * math.sin(2 * math.pi * phase), 1, 0, 1
    u = max(0.0, min(1.0, (time - unit_start) / max(unit_dur, 1e-6)))
    entering = a.get("mode", "in") == "in"
    if ty == "typewriter":
        shown = time >= unit_start if entering else time < unit_start
        return 0, 0, 1, 0, 1.0 if shown else 0.0
    e = ease(a.get("easing", "easeOutCubic"), u)
    v = e if entering else 1 - e
    away = 1 - v
    sign = 1 if entering else -1
    op = max(0.0, min(1.0, v))
    if ty == "fade":
        return 0, 0, 1, 0, op
    if ty == "slideUp":
        return 0, sign * amount * away, 1, 0, op
    if ty == "slideDown":
        return 0, -sign * amount * away, 1, 0, op
    if ty == "slideLeft":
        return sign * amount * away, 0, 1, 0, op
    if ty == "slideRight":
        return -sign * amount * away, 0, 1, 0, op
    if ty == "scale":
        return 0, 0, _lerp(max(amount / 100, 0), 1, v), 0, op
    if ty == "rotate":
        return 0, 0, 1, -sign * amount * away, op
    return 0, 0, 1, 0, 1


# ---------------------------------------------------------------------------------------------------------- scene


def composition(project):
    c = project.get("composition") or {}
    return {
        "width": int(c.get("width", 1920)), "height": int(c.get("height", 1080)), "fps": float(c.get("fps", 30)),
        "duration": float(c.get("duration", 10)), "background": parse_color(c.get("background", "#000000")),
    }


def _active_of_type(layers, time, kind, out):
    sib = {l.get("id"): l for l in layers}
    for l in layers:
        if not active(l, time):
            continue
        if l.get("type") == kind:
            out.append((l, time, sib))
        if l.get("type") == "group":
            _active_of_type(l.get("layers") or [], time - start(l), kind, out)
    return out


def evaluate_camera(project, comp, time):
    cams = _active_of_type(project.get("layers") or [], time, "camera", [])
    fov = DEFAULT_FOV
    pos = (0.0, 0.0, -default_distance(comp["height"], fov))
    target = (0.0, 0.0, 0.0)
    forward = None
    up = (0.0, -1.0, 0.0)
    if cams:
        l, ct, sib = cams[-1]
        cam = l.get("camera") or {}
        t = ct - start(l)
        fov = max(1.0, min(170.0, num(cam.get("fov"), DEFAULT_FOV, t)))
        parent = parent3d(l, sib, ct)
        pos = m4point(parent, v3(cam.get("position"), (0.0, 0.0, -default_distance(comp["height"], fov)), t))
        if cam.get("aim") == "rotation":
            r = m4rxyz(v3(cam.get("rotation"), (0.0, 0.0, 0.0), t))
            forward = vnorm(m4vec(parent, m4vec(r, (0.0, 0.0, 1.0))))
            up = vnorm(m4vec(parent, m4vec(r, (0.0, -1.0, 0.0))))
            target = vadd(pos, vmul(forward, default_distance(comp["height"], fov)))
        else:
            target = m4point(parent, v3(cam.get("target"), (0.0, 0.0, 0.0), t))
        if (cam.get("dof") or {}).get("enabled", True) and cam.get("dof"):
            NOTES.add("profundidade de campo (DOF) não é simulada")
    view = look_at(pos, vadd(pos, forward) if forward else target, up)
    proj = perspective(fov, comp["width"] / comp["height"], NEAR, FAR)
    return {"position": pos, "fov": fov, "view": view, "proj": proj, "vp": m4mul(proj, view)}


def project_point(cam, comp, p):
    vp = cam["vp"]
    h = [vp[i][0] * p[0] + vp[i][1] * p[1] + vp[i][2] * p[2] + vp[i][3] for i in range(4)]
    if h[3] <= 1e-4:
        return None
    return (h[0] / h[3] * comp["width"] / 2, -h[1] / h[3] * comp["height"] / 2)


def _srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


DEFAULT_AMBIENT = (0.22, 0.23, 0.26)
DEFAULT_LIGHTS = [
    {"kind": "directional", "color": (1.0, 0.97, 0.92), "intensity": 1.1, "position": (-800.0, -1000.0, -1200.0),
     "direction": vnorm((800.0, 1000.0, 1200.0))},
    {"kind": "directional", "color": (0.6, 0.7, 0.85), "intensity": 0.45, "position": (1000.0, 200.0, -600.0),
     "direction": vnorm((-1000.0, -200.0, 600.0))},
]


def evaluate_lights(project, time):
    found = _active_of_type(project.get("layers") or [], time, "light", [])
    if not found:
        return DEFAULT_AMBIENT, DEFAULT_LIGHTS
    amb = [0.0, 0.0, 0.0]
    lights = []
    for l, ct, sib in found:
        lt = l.get("light") or {}
        t = ct - start(l)
        c = col(lt.get("color"), (1.0, 1.0, 1.0, 1.0), t)
        inten = max(0.0, num(lt.get("intensity"), 1.0, t))
        parent = parent3d(l, sib, ct)
        kind = lt.get("kind", "directional")
        if kind == "ambient":
            for i in range(3):
                amb[i] += _srgb_to_linear(c[i]) * inten
            continue
        if lt.get("castShadows"):
            NOTES.add("sombras projetadas não são simuladas")
        p = m4point(parent, v3(lt.get("position"), (-800.0, -1000.0, -1200.0), t))
        tg = m4point(parent, v3(lt.get("target"), (0.0, 0.0, 0.0), t))
        lights.append({"kind": kind, "color": c[:3], "intensity": inten, "position": p, "direction": vnorm(vsub(tg, p)),
                       "radius": max(1.0, num(lt.get("radius"), 4000.0, t))})
    return tuple(amb), lights[:4]


class Ctx:
    def __init__(self, project, comp, cam):
        self.project = project
        self.comp = comp
        self.cam = cam


def evaluate(project, time):
    comp = composition(project)
    cam = evaluate_camera(project, comp, time)
    ambient, lights = evaluate_lights(project, time)
    ctx = Ctx(project, comp, cam)
    nodes = _container(ctx, project.get("layers") or [], time, IDENT)
    return {"time": time, "comp": comp, "camera": cam, "ambient": ambient, "lights": lights, "nodes": nodes}


def _container(ctx, layers, ct, cmat):
    sib = {l.get("id"): l for l in layers}
    matte_sources = {(l.get("matte") or {}).get("layer") for l in layers if l.get("matte") and l.get("visible", True)}
    out = []
    for l in layers:
        ty = l.get("type")
        if ty in ("audio", "camera", "light", "null") or not active(l, ct) or l.get("id") in matte_sources:
            continue
        matte = None
        m = l.get("matte")
        if m:
            src = sib.get(m.get("layer"))
            mode = m.get("mode", "alpha")
            inverted = mode.endswith("Inverted")
            node = _layer(ctx, src, sib, ct, cmat) if src is not None and start(src) <= ct < start(src) + duration(src) else None
            if node is None and not inverted:
                continue
            if node is not None:
                matte = {"node": node, "mode": mode}
        n = _layer(ctx, l, sib, ct, cmat)
        if n is not None:
            n["matte"] = matte
            out.append(n)
    return out


def _masks(l, t):
    out = []
    for m in (l.get("masks") or [])[:4]:
        if not m.get("enabled", True):
            continue
        sz = v2(m.get("size"), (400.0, 400.0), t)
        out.append({"shape": m.get("shape", "rectangle"), "mode": m.get("mode", "add"),
                    "center": v2(m.get("position"), (0.0, 0.0), t), "size": (max(0.0, sz[0]), max(0.0, sz[1])),
                    "roundness": max(0.0, num(m.get("roundness"), 0.0, t)), "feather": max(0.0, num(m.get("feather"), 0.0, t)),
                    "opacity": max(0.0, min(1.0, num(m.get("opacity"), 1.0, t))), "inverted": bool(m.get("inverted", False))})
    return out


def _layer(ctx, l, sib, ct0, cmat):
    ct = posterized(l, ct0)
    t = ct - start(l)
    ty = l.get("type")
    tr = transition_state(l, t, ctx.comp)
    base = {"id": l.get("id"), "type": ty, "blend": l.get("blendMode", "normal"), "motionBlur": l.get("motionBlur", False),
            "transition_blur": tr["blur"]}
    for e in l.get("effects") or []:
        if e.get("enabled", True) and e.get("type") in ("echo", "timeSlice"):
            NOTES.add(f"efeito '{e['type']}' não é simulado")
    if ty in RENDERED_3D:
        world = m4mul(parent3d(l, sib, ct), local3d(l, t))
        mat = None
        if ty == "object3d":
            o = l.get("object3d") or {}
            prim = o.get("primitive", "cube")
            if prim == "empty":
                return None
            size = 1.0 if prim == "text" else num(o.get("size"), 300.0, t)
            mat = o.get("material") or {}
            asset = None
            text3d = (o.get("text") or {}) if prim == "text" else None
        else:
            o = l.get("model3d") or {}
            prim, asset, text3d = None, o.get("asset"), None
            size = num(o.get("size"), 400.0, t)
            mat = o.get("material")
        model = m4mul(world, m4s((size, size, size)))
        pivot = project_point(ctx.cam, ctx.comp, m4point(world, (0.0, 0.0, 0.0))) or (0.0, 0.0)
        wipe = tr["wipe"][0] if tr["wipe"] else 1.0
        tr3 = l.get("transform3d") or {}
        return dict(base, kind="3d", model=model, primitive=prim, asset=asset, text3d=text3d,
                    material=_material(mat, t) if mat is not None else None,
                    opacity=max(0.0, min(1.0, num(tr3.get("opacity"), 1.0, t) * tr["opacity"] * wipe)),
                    effects=effects_of(l, t, amul(cmat, atrans(*pivot))),
                    post=amul(cmat, transition_matrix(tr, pivot)))
    tf = l.get("transform") or {}
    world = amul(cmat, amul(parent2d(l, sib, ct), local2d(l, t)))
    pivot = aapply(world, v2(tf.get("anchor"), (0.0, 0.0), t))
    matrix = amul(transition_matrix(tr, pivot), world)
    opacity = num(tf.get("opacity"), 1.0, t) * tr["opacity"]
    common = dict(base, matrix=matrix, effects=effects_of(l, t, matrix), masks=_masks(l, t))
    if ty == "group":
        wipe = tr["wipe"][0] if tr["wipe"] else 1.0
        children = _container(ctx, l.get("layers") or [], t, matrix)
        return dict(common, kind="group", opacity=max(0.0, min(1.0, opacity * wipe)), children=children)
    content = _content2d(ctx, l, t)
    if content is None:
        return None
    return dict(common, kind="2d", opacity=max(0.0, min(1.0, opacity)), wipe=tr["wipe"], content=content)


def _material(m, t):
    return {"color": col(m.get("color"), (0.82, 0.82, 0.85, 1.0), t), "metallic": max(0.0, min(1.0, num(m.get("metallic"), 0.0, t))),
            "roughness": max(0.02, min(1.0, num(m.get("roughness"), 0.5, t))), "emissive": col(m.get("emissive"), (0.0, 0.0, 0.0, 1.0), t),
            "texture": m.get("texture"), "shading": m.get("shading", "pbr")}


def _content2d(ctx, l, t):
    ty = l.get("type")
    if ty == "text":
        c = l.get("text") or {}
        if c.get("propertyAnimators"):
            NOTES.add("animadores de propriedade de texto (propertyAnimators) não são simulados")
        stroke = c.get("stroke")
        on = isinstance(stroke, dict) and stroke.get("enabled", True)
        return {"kind": "text", "content": c.get("content", "Text"), "font": c.get("font") or {},
                "fontSize": max(1.0, num(c.get("fontSize"), 96.0, t)), "color": col(c.get("color"), (1.0, 1.0, 1.0, 1.0), t),
                "align": c.get("align", "center"), "lineHeight": float(c.get("lineHeight", 1.2)),
                "letterSpacing": num(c.get("letterSpacing"), 0.0, t), "maxWidth": float(c.get("maxWidth", 0)),
                "strokeColor": col(stroke.get("color"), (1.0, 1.0, 1.0, 1.0), t) if on else None,
                "strokeWidth": max(0.0, num(stroke.get("width"), 8.0, t)) if on else 0.0,
                "glyphs": glyph_states(c.get("content", "Text"), c.get("animators"), t) if c.get("animators") else None}
    if ty == "shape":
        s = l.get("shape") or {}
        fill = s.get("fill") or {}
        stroke = s.get("stroke") or {}
        trim = s.get("trim") or {}
        sz = v2(s.get("size"), (400.0, 400.0), t)
        return {"kind": "shape", "shape": s.get("kind", "rectangle"), "size": (max(0.0, sz[0]), max(0.0, sz[1])),
                "roundness": max(0.0, num(s.get("roundness"), 0.0, t)),
                "sides": max(3, min(64, int(round(num(s.get("sides"), 5.0, t))))),
                "innerRatio": max(0.0, min(1.0, num(s.get("innerRatio"), 0.5, t))), "path": s.get("path"),
                "fillEnabled": fill.get("enabled", True), "fillColor": col(fill.get("color"), (1.0, 1.0, 1.0, 1.0), t),
                "gradient": fill.get("gradient"),
                "strokeEnabled": bool(stroke) and stroke.get("enabled", True),
                "strokeColor": col(stroke.get("color"), (1.0, 1.0, 1.0, 1.0), t),
                "strokeWidth": max(0.0, num(stroke.get("width"), 8.0, t)),
                "cap": stroke.get("cap", "round"), "join": stroke.get("join", "round"),
                "trimStart": max(0.0, min(1.0, num(trim.get("start"), 0.0, t))),
                "trimEnd": max(0.0, min(1.0, num(trim.get("end"), 1.0, t))), "trimOffset": num(trim.get("offset"), 0.0, t)}
    if ty == "image":
        img = l.get("image") or {}
        return {"kind": "image", "asset": img.get("asset")} if img.get("asset") else None
    if ty == "video":
        v = l.get("video") or {}
        if not v.get("asset"):
            return None
        if v.get("roto"):
            NOTES.add("recorte do Roto Brush não é aplicado (mostra o vídeo inteiro)")
        src_t = max(0.0, float(v.get("sourceStart", 0)) + t * float(v.get("speed", 1.0)))
        return {"kind": "video", "asset": v["asset"], "time": src_t, "alphaPacked": bool(v.get("alphaPacked", False))}
    return None

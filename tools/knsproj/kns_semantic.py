"""Semantic checks that JSON Schema cannot express.

Mirrors core/src/main/kotlin/dev/kinesis/core/format/ProjectValidator.kt: the issue codes are the same,
so problems reported by this CLI match what the Android app reports when importing.
Runs on the raw JSON (after schema validation) and applies the format defaults itself.
"""

import json
import os

KEYFRAME_EPSILON = 1e-4
MAX_MASKS = 4
MAX_LIGHTS = 4

CONTENT_KEY = {
    "text": "text", "shape": "shape", "image": "image", "video": "video", "audio": "audio",
    "object3d": "object3d", "model3d": "model3d", "camera": "camera", "light": "light",
}
IS_3D = {"object3d", "model3d", "camera", "light"}


def is_3d_layer(layer):
    """3D objects, cameras, lights and null layers with `null.threeD`."""
    return layer.get("type") in IS_3D or (
        layer.get("type") == "null" and (layer.get("null") or {}).get("threeD") is True)

IS_VISUAL = {"text", "shape", "image", "video", "group", "object3d", "model3d"}
IS_2D_VISUAL = {"text", "shape", "image", "video", "group"}

# Effect registry exported from the core (EffectRegistry.kt) by RegistryArtifactsTest.
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "effects.json"), encoding="utf-8") as _f:
    REGISTRY = json.load(_f)
EFFECTS = {e["id"]: e for e in REGISTRY["effects"]}


class Issue:
    def __init__(self, severity, code, path, message):
        self.severity = severity
        self.code = code
        self.path = path
        self.message = message

    def as_dict(self):
        return {"severity": self.severity, "code": self.code, "path": self.path, "message": self.message}

    def __repr__(self):
        return f"{self.severity} [{self.code}] {self.path}: {self.message}"


def _normalize_entry(name):
    name = name.replace("\\", "/")
    if not name or name.startswith("/") or ":" in name:
        return None
    parts = [p for p in name.split("/") if p not in ("", ".")]
    if not parts or any(p == ".." for p in parts):
        return None
    return "/".join(parts)


def _is_animated(v):
    return isinstance(v, dict) and "keyframes" in v


MAX_EXPRESSION = 20000


def check_expression_text(src):
    """Cheap structural check of an expression (balanced brackets and quotes, closed comments).

    The Python CLI has no JavaScript engine; the app and the Kotlin core parse the code for real and report the exact
    line. This catches the typical mistakes of hand-written or generated code.
    """
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    i, n, line = 0, len(src), 1
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
        elif c in "\"'`":
            q, start = c, line
            i += 1
            while i < n and src[i] != q:
                if src[i] == "\\":
                    i += 1
                if i < n and src[i] == "\n":
                    if q != "`":
                        return f"line {start}: unterminated string"
                    line += 1
                i += 1
            if i >= n:
                return f"line {start}: unterminated string"
        elif c == "/" and src[i:i + 2] == "//":
            while i < n and src[i] != "\n":
                i += 1
            continue
        elif c == "/" and src[i:i + 2] == "/*":
            end = src.find("*/", i + 2)
            if end < 0:
                return f"line {line}: unterminated comment"
            line += src.count("\n", i, end)
            i = end + 1
        elif c in "([{":
            stack.append((c, line))
        elif c in ")]}":
            if not stack or stack[-1][0] != pairs[c]:
                return f"line {line}: unexpected '{c}'"
            stack.pop()
        i += 1
    if stack:
        return f"line {stack[-1][1]}: '{stack[-1][0]}' is never closed"
    return None


def _static_value(v, default=None):
    if v is None:
        return default
    if _is_animated(v):
        kfs = v.get("keyframes") or []
        return kfs[0].get("value", default) if kfs else default
    if isinstance(v, dict) and "expression" in v:
        return v.get("value", default)
    return v


def validate(project, available_files=None):
    """Returns a list of Issue. `available_files` is the set of entry names present, or None to skip file checks."""
    issues = []

    def err(code, path, msg):
        issues.append(Issue("error", code, path, msg))

    def warn(code, path, msg):
        issues.append(Issue("warning", code, path, msg))

    comp = project.get("composition", {}) or {}
    width = comp.get("width", 1080)
    height = comp.get("height", 1920)
    fps = comp.get("fps", 30)
    duration = comp.get("duration", 10)
    if not (16 <= width <= 8192 and 16 <= height <= 8192):
        err("composition.size", "composition", "width and height must be between 16 and 8192 pixels")
    if width % 2 or height % 2:
        warn("composition.oddSize", "composition", "odd width/height will be rounded down to even numbers when exporting MP4")
    if not (1 <= fps <= 120):
        err("composition.fps", "composition.fps", "fps must be between 1 and 120")
    if not (0 < duration <= 3600):
        err("composition.duration", "composition.duration", "duration must be between 0 and 3600 seconds")
    mb = comp.get("motionBlur", {}) or {}
    if not (1 <= mb.get("samples", 8) <= 64):
        err("composition.motionBlur", "composition.motionBlur.samples", "samples must be between 1 and 64")
    if not (0 <= mb.get("shutterAngle", 180) <= 720):
        err("composition.motionBlur", "composition.motionBlur.shutterAngle", "shutterAngle must be between 0 and 720")

    assets_by_id = {}
    for i, asset in enumerate(project.get("assets", []) or []):
        path = f"assets[{i}]"
        aid = asset.get("id", "")
        if not str(aid).strip():
            err("asset.id", f"{path}.id", "asset id must not be empty")
        if aid in assets_by_id:
            err("asset.duplicateId", f"{path}.id", f"duplicate asset id '{aid}'")
        assets_by_id[aid] = asset.get("type")
        apath = asset.get("path", "")
        normalized = _normalize_entry(apath)
        if normalized is None or not normalized.startswith("assets/") or normalized != apath:
            err("asset.path", f"{path}.path", f"asset path must be a relative path inside 'assets/' (got '{apath}')")
        elif available_files is not None and apath not in available_files:
            err("asset.missingFile", f"{path}.path", f"file '{apath}' is not present in the project")

    seen_ids = set()
    counts = {"camera": 0, "light": 0}

    def check_anim(value, path, layer_duration):
        if isinstance(value, dict) and "expression" in value:
            src = value["expression"]
            if not isinstance(src, str):
                err("expression.syntax", f"{path}.expression", "expression must be a string")
            elif len(src) > MAX_EXPRESSION:
                err("expression.length", f"{path}.expression", f"expression is longer than {MAX_EXPRESSION} characters")
            elif src.strip():
                problem = check_expression_text(src)
                if problem:
                    err("expression.syntax", f"{path}.expression", problem)
        if not _is_animated(value):
            return
        times = [k.get("time", 0) for k in value.get("keyframes", [])]
        for i in range(1, len(times)):
            if times[i] - times[i - 1] < KEYFRAME_EPSILON:
                err("keyframe.order", f"{path}.keyframes[{i}]", "keyframe times must be strictly increasing")
        if times and (times[0] > layer_duration + KEYFRAME_EPSILON or times[-1] < -KEYFRAME_EPSILON):
            warn("keyframe.outOfRange", f"{path}.keyframes",
                 f"all keyframes are outside the layer's time range (0..{layer_duration} s); times are relative to the layer start")

    def check_asset_ref(aid, expected, path):
        if aid is None:
            return
        t = assets_by_id.get(aid)
        if t is None:
            err("layer.missingAsset", path, f"asset '{aid}' does not exist")
        elif t != expected:
            err("layer.assetType", path, f"asset '{aid}' is a {t}, expected {expected}")

    def check_effect(e, ep, ltype, d):
        spec = EFFECTS.get(e.get("type"))
        if spec is None:
            return
        if spec["target"] == "layer_3d" and ltype in IS_VISUAL and ltype not in ("object3d", "model3d"):
            warn("effect.layerKind", f"{ep}.type", f"{spec['id']} only works on object3d and model3d layers; it is ignored here")
        for param in spec["params"]:
            name = param["name"]
            v = e.get(name)
            if v is None:
                continue
            if param["type"] in ("number", "integer", "color", "point"):
                check_anim(v, f"{ep}.{name}", d)
            if param["type"] not in ("number", "integer"):
                continue
            lo, hi = param.get("min"), param.get("max")

            def outside(x):
                return isinstance(x, (int, float)) and ((lo is not None and x < lo) or (hi is not None and x > hi))

            rng = f"{lo if lo is not None else '-inf'}..{hi if hi is not None else 'inf'}"
            if _is_animated(v):
                for k, kf in enumerate(v.get("keyframes", [])):
                    if outside(kf.get("value")):
                        err("effect.range", f"{ep}.{name}.keyframes[{k}].value", f"{name} must be in {rng}")
            elif outside(v):
                err("effect.range", f"{ep}.{name}", f"{name} must be in {rng}")

    def visit(layers, base, depth):
        if depth > 16:
            err("group.depth", base, "groups are nested too deeply (max 16 levels)")
            return
        siblings = {l.get("id"): l for l in layers}
        for i, layer in enumerate(layers):
            p = f"{base}[{i}]"
            lid = layer.get("id", "")
            ltype = layer.get("type")
            start = layer.get("start", 0)
            d = layer.get("duration", 5)
            if not str(lid).strip():
                err("layer.id", f"{p}.id", "layer id must not be empty")
            if lid in seen_ids:
                err("layer.duplicateId", f"{p}.id", f"duplicate layer id '{lid}'")
            seen_ids.add(lid)
            if start + d <= 0:
                warn("layer.start", f"{p}.start", "the layer ends before time 0 and is never visible")
            if not d > 0:
                err("layer.duration", f"{p}.duration", "duration must be > 0")

            if ltype in CONTENT_KEY and CONTENT_KEY[ltype] not in layer:
                err("layer.missingContent", p, f"a '{ltype}' layer needs a \"{ltype}\" object")
            if ltype != "group" and layer.get("layers"):
                err("layer.children", f"{p}.layers", "only group layers can have child layers")

            parent_id = layer.get("parent")
            if parent_id is not None:
                parent = siblings.get(parent_id)
                if parent_id == lid:
                    err("layer.parentSelf", f"{p}.parent", "a layer cannot be its own parent")
                elif parent is None:
                    err("layer.parentMissing", f"{p}.parent", f"parent '{parent_id}' must be a layer in the same group")
                elif is_3d_layer(layer) != is_3d_layer(parent):
                    err("layer.parentKind", f"{p}.parent", "2D layers can only be parented to 2D layers and 3D layers to 3D layers")
                elif parent.get("type") in ("camera", "light"):
                    err("layer.parentKind", f"{p}.parent", "cameras and lights cannot be parents")
                elif parent.get("type") == "audio":
                    err("layer.parentKind", f"{p}.parent", "audio layers cannot be parents")

            matte = layer.get("matte")
            if matte is not None:
                src_id = matte.get("layer")
                src = siblings.get(src_id)
                if src_id == lid:
                    err("layer.matteSelf", f"{p}.matte.layer", "a layer cannot be its own matte")
                elif src is None:
                    err("layer.matteMissing", f"{p}.matte.layer", f"matte layer '{src_id}' must be in the same group")
                elif src.get("type") not in IS_VISUAL:
                    err("layer.matteKind", f"{p}.matte.layer", "matte source must be a visual layer")
                elif (src.get("matte") or {}).get("layer") == lid:
                    err("layer.matteCycle", f"{p}.matte.layer", "two layers cannot be each other's matte")
                if ltype not in IS_2D_VISUAL:
                    err("layer.matteKind", f"{p}.matte", "track mattes apply to 2D visual layers only")

            for key in ("anchor", "position", "scale", "rotation", "opacity"):
                check_anim((layer.get("transform") or {}).get(key), f"{p}.transform.{key}", d)
                check_anim((layer.get("transform3d") or {}).get(key), f"{p}.transform3d.{key}", d)

            text = layer.get("text")
            if text is not None:
                for key in ("fontSize", "color", "letterSpacing"):
                    check_anim(text.get(key), f"{p}.text.{key}", d)
                font = text.get("font") or {}
                check_asset_ref(font.get("asset"), "font", f"{p}.text.font.asset")
                if not 100 <= font.get("weight", 700) <= 900:
                    err("text.weight", f"{p}.text.font.weight", "font weight must be between 100 and 900")
                for ai, a in enumerate(text.get("propertyAnimators", []) or []):
                    ap = f"{p}.text.propertyAnimators[{ai}]"
                    for key in ("amount", "position", "scale", "rotation", "opacity"):
                        check_anim(a.get(key), f"{ap}.{key}", d)
                    if not any(k in a for k in ("position", "scale", "rotation", "opacity")):
                        warn("text.propertyAnimator", ap,
                             "the animator has no property (position, scale, rotation or opacity), so it changes nothing")
                for ai, a in enumerate(text.get("animators", []) or []):
                    if a.get("unitDuration", 0.4) <= 0:
                        err("text.animator", f"{p}.text.animators[{ai}].unitDuration", "unitDuration must be > 0")
                    if a.get("stagger", 0.05) < 0:
                        err("text.animator", f"{p}.text.animators[{ai}].stagger", "stagger must be >= 0")

            shape = layer.get("shape")
            if shape is not None:
                for key in ("size", "roundness"):
                    check_anim(shape.get(key), f"{p}.shape.{key}", d)
                check_anim((shape.get("fill") or {}).get("color"), f"{p}.shape.fill.color", d)
                check_anim((shape.get("stroke") or {}).get("color"), f"{p}.shape.stroke.color", d)
                check_anim((shape.get("stroke") or {}).get("width"), f"{p}.shape.stroke.width", d)
                check_anim((shape.get("trim") or {}).get("start"), f"{p}.shape.trim.start", d)
                check_anim((shape.get("trim") or {}).get("end"), f"{p}.shape.trim.end", d)
                if shape.get("kind") == "path" and len((shape.get("path") or {}).get("points", []) or []) < 2:
                    err("shape.path", f"{p}.shape.path", "path shapes need at least 2 points")
                gradient = (shape.get("fill") or {}).get("gradient")
                if gradient is not None and len(gradient.get("stops", []) or []) < 2:
                    err("shape.gradient", f"{p}.shape.fill.gradient.stops", "a gradient needs at least 2 stops")

            image = layer.get("image")
            if image is not None:
                check_asset_ref(image.get("asset"), "image", f"{p}.image.asset")
            for key, expected in (("video", "video"), ("audio", "audio")):
                media = layer.get(key)
                if media is None:
                    continue
                check_asset_ref(media.get("asset"), expected, f"{p}.{key}.asset")
                if not media.get("speed", 1) > 0:
                    err("media.speed", f"{p}.{key}.speed", "speed must be > 0")
                if media.get("sourceStart", 0) < 0:
                    err("media.sourceStart", f"{p}.{key}.sourceStart", "sourceStart must be >= 0")
                check_anim(media.get("volume"), f"{p}.{key}.volume", d)
            obj3d = layer.get("object3d")
            if obj3d is not None:
                check_anim(obj3d.get("size"), f"{p}.object3d.size", d)
                check_asset_ref((obj3d.get("material") or {}).get("texture"), "image", f"{p}.object3d.material.texture")
            model = layer.get("model3d")
            if model is not None:
                check_asset_ref(model.get("asset"), "model", f"{p}.model3d.asset")
                check_asset_ref((model.get("material") or {}).get("texture"), "image", f"{p}.model3d.material.texture")
            camera = layer.get("camera")
            if camera is not None:
                for key in ("position", "target", "rotation", "fov"):
                    check_anim(camera.get(key), f"{p}.camera.{key}", d)
            if layer.get("null") is not None and ltype != "null":
                warn("layer.nullContent", f"{p}.null", 'the "null" object is only used by null layers')
            if ltype in counts:
                counts[ltype] += 1

            masks = layer.get("masks", []) or []
            if len(masks) > MAX_MASKS:
                warn("layer.masks", f"{p}.masks", f"only the first {MAX_MASKS} masks are rendered")
            if masks and ltype not in IS_2D_VISUAL:
                warn("layer.masks", f"{p}.masks", "masks apply to 2D visual layers only")
            effects = layer.get("effects", []) or []
            if ltype not in IS_VISUAL and effects:
                warn("layer.effects", f"{p}.effects", f"effects have no visible result on {ltype} layers")
            for ei, e in enumerate(effects):
                check_effect(e, f"{p}.effects[{ei}]", ltype, d)
            for name in ("transitionIn", "transitionOut"):
                t = layer.get(name)
                if t is not None and not t.get("duration", 0.5) > 0:
                    err("layer.transition", f"{p}.{name}.duration", "transition duration must be > 0")

            if ltype == "group":
                visit(layer.get("layers", []) or [], f"{p}.layers", depth + 1)

        for i, layer in enumerate(layers):
            seen = set()
            cur = layer
            while cur is not None and cur.get("parent") is not None:
                if cur.get("id") in seen:
                    err("layer.parentCycle", f"{base}[{i}].parent", f"parent chain of '{layer.get('id')}' contains a cycle")
                    break
                seen.add(cur.get("id"))
                cur = siblings.get(cur.get("parent"))

    visit(project.get("layers", []) or [], "layers", 0)

    if counts["camera"] > 1:
        warn("camera.multiple", "layers", "several camera layers: the top-most active camera is used")
    if counts["light"] > MAX_LIGHTS:
        warn("light.count", "layers", f"only {MAX_LIGHTS} lights are used at the same time")
    return issues

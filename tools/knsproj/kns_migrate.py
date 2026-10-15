"""Format migrations, mirroring `Migrations` in core/src/main/kotlin/dev/kinesis/core/format/KnsprojJson.kt."""

import copy


def v1_to_v2(doc):
    """Version 2 replaces `colorAdjust` by `hueShift`, `brightnessContrast` and `saturation` (in the order v1
    applied them), keeping static values and keyframes. Every other effect is unchanged."""
    doc = copy.deepcopy(doc)

    def split(effect):
        if not isinstance(effect, dict) or effect.get("type") != "colorAdjust":
            return [effect]
        parts = []
        for new_type, keys in (("hueShift", ("hue",)), ("brightnessContrast", ("brightness", "contrast")), ("saturation", ("saturation",))):
            present = [k for k in keys if k in effect]
            if not present:
                continue
            part = {"type": new_type}
            if "enabled" in effect:
                part["enabled"] = effect["enabled"]
            for k in present:
                part[k] = effect[k]
            parts.append(part)
        if not parts:
            # A colorAdjust with only defaults keeps a neutral entry so the effect list keeps its length.
            part = {"type": "brightnessContrast"}
            if "enabled" in effect:
                part["enabled"] = effect["enabled"]
            parts.append(part)
        return parts

    def layer(l):
        if not isinstance(l, dict):
            return l
        if isinstance(l.get("effects"), list):
            l["effects"] = [p for e in l["effects"] for p in split(e)]
        if isinstance(l.get("layers"), list):
            l["layers"] = [layer(c) for c in l["layers"]]
        return l

    if isinstance(doc.get("layers"), list):
        doc["layers"] = [layer(l) for l in doc["layers"]]
    doc["formatVersion"] = 2
    return doc


def v2_to_v3(doc):
    """Version 3 only adds optional fields (expressions, text property animators); v2 documents are valid as they are."""
    doc["formatVersion"] = 3
    return doc


STEPS = {1: v1_to_v2, 2: v2_to_v3}


def migrate(doc, to_version):
    """Upgrades `doc` (any older version) to `to_version`, one step at a time."""
    v = doc.get("formatVersion")
    while v < to_version:
        doc = STEPS[v](doc)
        v += 1
    return doc

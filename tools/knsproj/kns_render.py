"""Approximate software renderer for the `preview` command: draws the nodes produced by kns_eval into an image.

Needs Pillow and numpy (`pip install pillow numpy`). ffmpeg is optional (video frames). Timing, transforms, parenting,
transitions, text animators, the camera and the 3D layout come from kns_eval and match the app; the pixels are an
approximation of the GPU renderer (simplified PBR, a subset of the effects, no shadows, no DOF, no motion blur).
"""

import io
import math
import os
import shutil
import struct
import subprocess
import sys
import json

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

import kns_eval as ev

SS = 3  # supersampling for vector shapes and masks


# ---------------------------------------------------------------------------------------------------------- helpers


def premul(img):
    """PIL image → premultiplied float32 RGBA array."""
    a = np.asarray(img.convert("RGBA"), dtype=np.float32) / 255.0
    a[..., :3] *= a[..., 3:4]
    return a


def to_pil(arr):
    """Premultiplied float RGBA → straight PIL RGBA."""
    a = np.clip(arr, 0, 1)
    alpha = a[..., 3:4]
    rgb = np.where(alpha > 1e-6, a[..., :3] / np.maximum(alpha, 1e-6), 0)
    out = np.concatenate([rgb, alpha], axis=-1)
    return Image.fromarray((out * 255 + 0.5).astype(np.uint8), "RGBA")


def to_linear(c):
    c = np.asarray(c, dtype=np.float32)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def composite(dst, src, mode="normal"):
    sa = src[..., 3:4]
    if mode == "add":
        dst += src
        np.clip(dst, 0, 1, out=dst)
    elif mode == "multiply":
        da = dst[..., 3:4]
        rgb = src[..., :3] * dst[..., :3] + src[..., :3] * (1 - da) + dst[..., :3] * (1 - sa)
        dst[..., :3] = rgb
        dst[..., 3:4] = sa + da * (1 - sa)
    elif mode == "screen":
        rgb = src[..., :3] + dst[..., :3] - src[..., :3] * dst[..., :3]
        dst[..., 3:4] = sa + dst[..., 3:4] * (1 - sa)
        dst[..., :3] = rgb
    else:
        dst *= (1 - sa)
        dst += src


def bbox_of(points, w, h, pad=2):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    x0 = max(0, int(math.floor(min(xs))) - pad)
    y0 = max(0, int(math.floor(min(ys))) - pad)
    x1 = min(w, int(math.ceil(max(xs))) + pad)
    y1 = min(h, int(math.ceil(max(ys))) + pad)
    if x1 <= x0 or y1 <= y0:
        return None
    return x0, y0, x1, y1


def mat_scale(m):
    return math.sqrt(abs(m[0] * m[3] - m[1] * m[2]))


# ---------------------------------------------------------------------------------------------------------- geometry


def _cubic(p0, p1, p2, p3, n):
    out = []
    for i in range(1, n + 1):
        t = i / n
        mt = 1 - t
        out.append((mt ** 3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t ** 3 * p3[0],
                    mt ** 3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t ** 3 * p3[1]))
    return out


def ellipse_pts(cx, cy, hw, hh, n=72):
    return [(cx + math.cos(2 * math.pi * i / n) * hw, cy + math.sin(2 * math.pi * i / n) * hh) for i in range(n)]


def round_rect_pts(cx, cy, hw, hh, r):
    r = min(r, hw, hh)
    if r <= 0.01:
        return [(cx - hw, cy - hh), (cx + hw, cy - hh), (cx + hw, cy + hh), (cx - hw, cy + hh)]
    pts = []
    for (ox, oy, a0) in ((hw - r, -hh + r, -90), (hw - r, hh - r, 0), (-hw + r, hh - r, 90), (-hw + r, -hh + r, 180)):
        for i in range(9):
            a = math.radians(a0 + 90 * i / 8)
            pts.append((cx + ox + math.cos(a) * r, cy + oy + math.sin(a) * r))
    return pts


def shape_contour(c):
    """Local outline of a shape as (points, closed)."""
    hw, hh = c["size"][0] / 2, c["size"][1] / 2
    k = c["shape"]
    if k == "rectangle":
        return round_rect_pts(0, 0, hw, hh, c["roundness"]), True
    if k == "ellipse":
        return ellipse_pts(0, 0, hw, hh), True
    if k in ("polygon", "star"):
        star = k == "star"
        count = c["sides"] * 2 if star else c["sides"]
        pts = []
        for i in range(count):
            a = -math.pi / 2 + 2 * math.pi * i / count
            kk = c["innerRatio"] if star and i % 2 == 1 else 1
            pts.append((math.cos(a) * hw * kk, math.sin(a) * hh * kk))
        return pts, True
    path = c.get("path") or {}
    pts_raw = path.get("points") or []
    if not pts_raw:
        return [], False
    closed = path.get("closed", True)

    def P(p, key):
        v = p.get(key) or [0, 0]
        return (float(v[0]), float(v[1]))

    pts = [P(pts_raw[0], "point")]
    segs = len(pts_raw) if closed else len(pts_raw) - 1
    for i in range(segs):
        a, b = pts_raw[i], pts_raw[(i + 1) % len(pts_raw)]
        ap, bp = P(a, "point"), P(b, "point")
        ao, bi = P(a, "out"), P(b, "in")
        if ao == (0, 0) and bi == (0, 0):
            pts.append(bp)
        else:
            pts += _cubic(ap, (ap[0] + ao[0], ap[1] + ao[1]), (bp[0] + bi[0], bp[1] + bi[1]), bp, 24)
    if closed and len(pts) > 1 and pts[-1] == pts[0]:
        pts.pop()
    return pts, closed


def trim(pts, closed, a, b, off):
    """Trim path over the polyline length (start/end/offset in 0..1)."""
    if not pts:
        return []
    seq = pts + [pts[0]] if closed else list(pts)
    lens = [math.dist(seq[i], seq[i + 1]) for i in range(len(seq) - 1)]
    total = sum(lens)
    lo, hi = min(a, b), max(a, b)
    if total <= 0 or hi - lo >= 0.9999:
        return [(pts, closed)]
    o = off - math.floor(off)
    lo, hi = lo + o, hi + o
    ranges = [(lo - 1, hi - 1)] if lo >= 1 else ([(lo, 1), (0, hi - 1)] if hi > 1 else [(lo, hi)])
    out = []
    for ra, rb in ranges:
        s0, s1 = ra * total, rb * total
        cur, acc = [], 0.0
        for i, L in enumerate(lens):
            p, q = seq[i], seq[i + 1]
            e0, e1 = acc, acc + L
            if e1 >= s0 and e0 <= s1 and L > 0:
                u0 = max(0.0, (s0 - e0) / L)
                u1 = min(1.0, (s1 - e0) / L)
                pa = (p[0] + (q[0] - p[0]) * u0, p[1] + (q[1] - p[1]) * u0)
                pb = (p[0] + (q[0] - p[0]) * u1, p[1] + (q[1] - p[1]) * u1)
                if not cur:
                    cur.append(pa)
                cur.append(pb)
            acc = e1
        if len(cur) > 1:
            out.append((cur, False))
    return out


def raster_poly(contours, w, h, fill=True, width=0.0, cap="round"):
    """Anti-aliased coverage (float32 H×W) of contours given in canvas pixels. Only the bounding box is rasterized."""
    allpts = [p for c, _ in contours for p in c]
    if not allpts:
        return None, None
    box = bbox_of(allpts, w, h, pad=int(width / 2) + 3)
    if box is None:
        return None, None
    x0, y0, x1, y1 = box
    img = Image.new("L", ((x1 - x0) * SS, (y1 - y0) * SS), 0)
    d = ImageDraw.Draw(img)
    for pts, closed in contours:
        sp = [((x - x0) * SS, (y - y0) * SS) for x, y in pts]
        if fill and closed and len(sp) >= 3:
            d.polygon(sp, fill=255)
        if width > 0 and len(sp) >= 2:
            lw = max(1, int(round(width * SS)))
            line = sp + [sp[0]] if closed else sp
            d.line(line, fill=255, width=lw, joint="curve")
            if cap in ("round", "square") or closed:
                r = lw / 2
                ends = line if closed else [line[0], line[-1]]
                for (px, py) in (ends if not closed else line):
                    d.ellipse([px - r, py - r, px + r, py + r], fill=255)
    img = img.resize((x1 - x0, y1 - y0), Image.BOX)
    return np.asarray(img, dtype=np.float32) / 255.0, box


# ---------------------------------------------------------------------------------------------------------- assets


class Assets:
    def __init__(self, project, base_dir):
        self.base = base_dir
        self.by_id = {a.get("id"): a for a in project.get("assets") or []}
        self.images = {}
        self.frames = {}
        self.models = {}
        self.has_ffmpeg = shutil.which("ffmpeg") is not None

    def path(self, aid):
        a = self.by_id.get(aid)
        if not a or not self.base:
            return None
        p = os.path.join(self.base, *str(a.get("path", "")).split("/"))
        return p if os.path.isfile(p) else None

    def image(self, aid):
        if aid not in self.images:
            p = self.path(aid)
            try:
                self.images[aid] = Image.open(p).convert("RGBA") if p else None
            except OSError:
                self.images[aid] = None
            if self.images[aid] is None:
                ev.NOTES.add(f"imagem '{aid}' não encontrada")
        return self.images[aid]

    def video_frame(self, aid, t, fps):
        key = (aid, round(t * fps))
        if key in self.frames:
            return self.frames[key]
        p = self.path(aid)
        img = None
        if p and self.has_ffmpeg:
            try:
                out = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t:.3f}", "-i", p, "-frames:v", "1", "-f", "image2pipe",
                                      "-vcodec", "png", "-"], capture_output=True, timeout=60)
                if out.stdout:
                    img = Image.open(io.BytesIO(out.stdout)).convert("RGBA")
                elif t > 0.05:  # past the end of the clip: hold the last frame
                    out = subprocess.run(["ffmpeg", "-v", "error", "-sseof", "-0.1", "-i", p, "-frames:v", "1", "-f", "image2pipe",
                                          "-vcodec", "png", "-"], capture_output=True, timeout=60)
                    if out.stdout:
                        img = Image.open(io.BytesIO(out.stdout)).convert("RGBA")
            except (OSError, subprocess.SubprocessError):
                img = None
        if img is None:
            a = self.by_id.get(aid) or {}
            w, h = int(a.get("width") or 640), int(a.get("height") or 360)
            img = Image.new("RGBA", (w, h), (60, 60, 70, 255))
            d = ImageDraw.Draw(img)
            d.rectangle([0, 0, w - 1, h - 1], outline=(200, 200, 210, 255), width=max(2, w // 120))
            d.line([0, 0, w, h], fill=(120, 120, 130, 255), width=max(2, w // 200))
            d.line([0, h, w, 0], fill=(120, 120, 130, 255), width=max(2, w // 200))
            ev.NOTES.add("vídeo desenhado como placeholder (ffmpeg ausente ou arquivo não encontrado)" if not self.has_ffmpeg or not p
                         else f"não consegui extrair quadro do vídeo '{aid}'")
        self.frames[key] = img
        return img

    def model(self, aid):
        if aid not in self.models:
            p = self.path(aid)
            self.models[aid] = load_glb(p) if p else None
            if self.models[aid] is None:
                ev.NOTES.add(f"modelo '{aid}' não pôde ser lido (usa um cubo no lugar)")
        return self.models[aid]


# ---------------------------------------------------------------------------------------------------------- fonts

_FONT_CACHE = {}
_REPO_FONTS = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "res", "font"))


def _font_file(font, assets):
    if font.get("asset"):
        p = assets.path(font["asset"])
        if p:
            return p
    family = str(font.get("family", "sans-serif"))
    weight = int(font.get("weight", 700))
    italic = bool(font.get("italic", False))
    key = (family, weight >= 600, italic)
    if key in _FONT_CACHE:
        return _FONT_CACHE[key]
    found = None
    if shutil.which("fc-match"):
        style = ("bold" if weight >= 600 else "regular") + (":italic" if italic else "")
        fam = {"sans-serif": "sans-serif", "serif": "serif", "monospace": "monospace"}.get(family, family)
        try:
            r = subprocess.run(["fc-match", f"{fam}:{style}", "--format=%{file}"], capture_output=True, text=True, timeout=10)
            if r.returncode == 0 and os.path.isfile(r.stdout.strip()):
                found = r.stdout.strip()
        except (OSError, subprocess.SubprocessError):
            pass
    if not found:
        bold = weight >= 600
        cands = []
        if family == "monospace":
            cands += [os.path.join(_REPO_FONTS, "jetbrains_mono_600.ttf" if bold else "jetbrains_mono_400.ttf")]
        cands += [os.path.join(_REPO_FONTS, "manrope_800.ttf" if weight >= 800 else ("manrope_700.ttf" if bold else "manrope_500.ttf"))]
        win = os.environ.get("WINDIR", "C:\\Windows")
        cands += [os.path.join(win, "Fonts", "arialbd.ttf" if bold else "arial.ttf"),
                  os.path.join(win, "Fonts", "segoeuib.ttf" if bold else "segoeui.ttf"),
                  "/System/Library/Fonts/Supplemental/Arial Bold.ttf" if bold else "/System/Library/Fonts/Supplemental/Arial.ttf",
                  "/System/Library/Fonts/Helvetica.ttc", "/Library/Fonts/Arial.ttf",
                  "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"]
        found = next((c for c in cands if c and os.path.isfile(c)), None)
    _FONT_CACHE[key] = found
    return found


def load_font(font, size, assets):
    path = _font_file(font, assets)
    size = max(1, int(round(size)))
    try:
        if path:
            return ImageFont.truetype(path, size)
    except OSError:
        pass
    ev.NOTES.add("nenhuma fonte TrueType encontrada: texto com a fonte padrão do Pillow")
    try:
        return ImageFont.load_default(size)
    except TypeError:
        return ImageFont.load_default()


# ---------------------------------------------------------------------------------------------------------- text


def _wrap(text, font, max_w, ls_px):
    lines = []
    for para in text.split("\n"):
        if max_w <= 0:
            lines.append(para)
            continue
        cur = ""
        for word in para.split(" "):
            cand = word if not cur else cur + " " + word
            if _line_width(cand, font, ls_px) <= max_w or not cur:
                cur = cand
            else:
                lines.append(cur)
                cur = word
        lines.append(cur)
    return lines


def _line_width(s, font, ls_px):
    if not s:
        return 0.0
    if ls_px == 0:
        return font.getlength(s)
    return sum(font.getlength(ch) + ls_px for ch in s) - ls_px


def text_raster(c, rs, assets):
    """Text block as a premultiplied array; returns (array, x0, y0) in local pixels (block centred on the origin)."""
    fs = c["fontSize"] * rs
    font = load_font(c["font"], fs, assets)
    ls_px = c["letterSpacing"] * fs
    text = c["content"] or " "
    lines = _wrap(text, font, c["maxWidth"] * rs, ls_px)
    lh = c["lineHeight"] * fs
    widths = [_line_width(l, font, ls_px) for l in lines]
    block_w = max(c["maxWidth"] * rs if c["maxWidth"] > 0 else 0, max(widths) if widths else 0)
    pad = int(c["strokeWidth"] * rs + fs * 0.6 + 4)
    if c["glyphs"]:
        pad += int(max((abs(g[0]) + abs(g[1]) for g in c["glyphs"]), default=0) * rs + fs)
    W = int(block_w + 2 * pad) + 1
    H = int(lh * len(lines) + 2 * pad) + 1
    if W * H > 40_000_000:
        return None
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    color = tuple(int(round(v * 255)) for v in c["color"])
    stroke = tuple(int(round(v * 255)) for v in c["strokeColor"]) if c["strokeColor"] else None
    sw = int(round(c["strokeWidth"] * rs))
    ascent, descent = font.getmetrics()
    # Map each source character to its glyph state (wrapping drops the spaces it breaks on; close enough here).
    gi = 0
    glyphs = c["glyphs"]
    for li, line in enumerate(lines):
        w = widths[li]
        x = pad + {"left": 0, "right": block_w - w}.get(c["align"], (block_w - w) / 2)
        top = pad + li * lh + (lh - (ascent + descent)) / 2
        if not glyphs and ls_px == 0:
            if stroke and sw > 0:
                draw.text((x, top), line, font=font, fill=stroke, stroke_width=sw, stroke_fill=stroke)
            draw.text((x, top), line, font=font, fill=color)
            continue
        for ch in line:
            while glyphs and gi < len(text) and text[gi] != ch:
                gi += 1
            st = glyphs[gi] if glyphs and gi < len(glyphs) else (0, 0, 1, 0, 1)
            gi += 1
            cw = font.getlength(ch)
            if not ch.isspace() and st[4] > 0.002:
                _draw_glyph(img, ch, font, x + st[0] * rs, top + st[1] * rs, cw, st, color, stroke, sw, ascent, descent)
            x += cw + ls_px
        gi += 1  # newline / wrapped space
    return premul(img), -W / 2 / rs, -H / 2 / rs


def _draw_glyph(img, ch, font, x, top, cw, st, color, stroke, sw, ascent, descent):
    scale, rot, op = st[2], st[3], st[4]
    if scale == 1 and rot == 0 and op >= 0.999:
        d = ImageDraw.Draw(img)
        if stroke and sw > 0:
            d.text((x, top), ch, font=font, fill=stroke, stroke_width=sw, stroke_fill=stroke)
        d.text((x, top), ch, font=font, fill=color)
        return
    gw, gh = int(cw + 2 * sw + 4), int(ascent + descent + 2 * sw + 4)
    g = Image.new("RGBA", (gw, gh), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    if stroke and sw > 0:
        d.text((sw + 2, sw + 2), ch, font=font, fill=stroke, stroke_width=sw, stroke_fill=stroke)
    d.text((sw + 2, sw + 2), ch, font=font, fill=color)
    if scale != 1 and scale > 0:
        g = g.resize((max(1, int(gw * scale)), max(1, int(gh * scale))), Image.BICUBIC)
    elif scale <= 0:
        return
    if rot:
        g = g.rotate(-rot, resample=Image.BICUBIC, expand=True)
    if op < 0.999:
        a = g.getchannel("A").point(lambda v: int(v * op))
        g.putalpha(a)
    cx, cy = x + cw / 2, top + (ascent + descent) / 2
    img.alpha_composite(g, (int(cx - g.width / 2), int(cy - g.height / 2)))


# ---------------------------------------------------------------------------------------------------------- warping


def warp(dst_shape, src, R, M, P):
    """Samples premultiplied `src` (raster px → local via R, local → comp via M, comp → canvas via P) into a canvas-sized
    array (only its bounding box is touched). Returns (patch, box) or (None, None)."""
    h, w = dst_shape
    full = ev.amul(P, ev.amul(M, R))
    inv = ev.ainv(full)
    if inv is None:
        return None, None
    sh, sw = src.shape[:2]
    corners = [ev.aapply(full, p) for p in ((0, 0), (sw, 0), (sw, sh), (0, sh))]
    box = bbox_of(corners, w, h)
    if box is None:
        return None, None
    x0, y0, x1, y1 = box
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    xs += 0.5
    ys += 0.5
    u = inv[0] * xs + inv[2] * ys + inv[4] - 0.5
    v = inv[1] * xs + inv[3] * ys + inv[5] - 0.5
    # Bilinear sampling with transparent outside.
    pad = np.zeros((sh + 2, sw + 2, 4), np.float32)
    pad[1:-1, 1:-1] = src
    u += 1
    v += 1
    ui = np.floor(u).astype(np.int64)
    vi = np.floor(v).astype(np.int64)
    fu = (u - ui)[..., None]
    fv = (v - vi)[..., None]
    valid = (ui >= 0) & (vi >= 0) & (ui < sw + 1) & (vi < sh + 1)
    ui = np.clip(ui, 0, sw)
    vi = np.clip(vi, 0, sh)
    c00 = pad[vi, ui]
    c10 = pad[vi, ui + 1]
    c01 = pad[vi + 1, ui]
    c11 = pad[vi + 1, ui + 1]
    out = (c00 * (1 - fu) + c10 * fu) * (1 - fv) + (c01 * (1 - fu) + c11 * fu) * fv
    out[~valid] = 0
    return out, box


def paste(canvas, patch, box):
    x0, y0, x1, y1 = box
    canvas[y0:y1, x0:x1] = patch


# ---------------------------------------------------------------------------------------------------------- 2D content


def draw_shape(c, M, P, shape):
    h, w = shape
    layer = np.zeros((h, w, 4), np.float32)
    full = ev.amul(P, M)
    sc = mat_scale(full)
    pts, closed = shape_contour(c)
    if not pts:
        return layer, None
    if c["trimStart"] > 0 or c["trimEnd"] < 1:
        parts = trim(pts, closed, c["trimStart"], c["trimEnd"], c["trimOffset"])
    else:
        parts = [(pts, closed)]
    to_px = lambda cs: [([ev.aapply(full, p) for p in ps], cl) for ps, cl in cs]  # noqa: E731
    if c["fillEnabled"]:
        cov, box = raster_poly([(ps, True) for ps, _ in to_px(parts)], w, h, fill=True)
        if cov is not None:
            x0, y0, x1, y1 = box
            g = c.get("gradient")
            if g and len(g.get("stops") or []) >= 2:
                rgba = _gradient(g, full, box) * c["fillColor"][3]
            else:
                col = np.array(c["fillColor"], np.float32)
                rgba = np.broadcast_to(np.concatenate([col[:3] * col[3], col[3:]]), (y1 - y0, x1 - x0, 4))
            layer[y0:y1, x0:x1] = rgba * cov[..., None]
    if c["strokeEnabled"] and c["strokeWidth"] > 0:
        cov, box = raster_poly(to_px(parts), w, h, fill=False, width=c["strokeWidth"] * sc, cap=c["cap"])
        if cov is not None:
            x0, y0, x1, y1 = box
            col = np.array(c["strokeColor"], np.float32)
            src = np.concatenate([col[:3] * col[3], col[3:]]) * cov[..., None]
            region = layer[y0:y1, x0:x1]
            region *= (1 - src[..., 3:4])
            region += src
    hw, hh = c["size"][0] / 2, c["size"][1] / 2
    if c["shape"] == "path":
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        return layer, (min(xs), min(ys), max(xs), max(ys))
    return layer, (-hw, -hh, hw, hh)


def _gradient(g, full, box):
    x0, y0, x1, y1 = box
    inv = ev.ainv(full)
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32) + 0.5
    lx = inv[0] * xs + inv[2] * ys + inv[4]
    ly = inv[1] * xs + inv[3] * ys + inv[5]
    s = g.get("start") or [-200, 0]
    e = g.get("end") or [200, 0]
    if g.get("type") == "radial":
        r = max(1.0, math.dist(s, e))
        t = np.sqrt((lx - s[0]) ** 2 + (ly - s[1]) ** 2) / r
    else:
        dx, dy = e[0] - s[0], e[1] - s[1]
        L2 = max(dx * dx + dy * dy, 1e-6)
        t = ((lx - s[0]) * dx + (ly - s[1]) * dy) / L2
    t = np.clip(t, 0, 1)
    stops = sorted(g["stops"], key=lambda q: q.get("offset", 0))
    offs = [float(q.get("offset", 0)) for q in stops]
    cols = [ev.parse_color(q.get("color")) for q in stops]
    out = np.zeros(t.shape + (4,), np.float32)
    for ch in range(4):
        out[..., ch] = np.interp(t, offs, [c[ch] for c in cols])
    out[..., :3] *= out[..., 3:4]
    return out


def draw_raster(src, x0, y0, units, M, P, shape):
    h, w = shape
    layer = np.zeros((h, w, 4), np.float32)
    R = (units, 0.0, 0.0, units, x0, y0)
    patch, box = warp(shape, src, R, M, P)
    if patch is not None:
        paste(layer, patch, box)
    return layer


# ---------------------------------------------------------------------------------------------------------- masks & effects


def mask_alpha(masks, M, P, shape):
    h, w = shape
    full = ev.amul(P, M)
    sc = mat_scale(full)
    acc = None
    for i, m in enumerate(masks):
        cx, cy = m["center"]
        hw, hh = m["size"][0] / 2, m["size"][1] / 2
        pts = ellipse_pts(cx, cy, hw, hh) if m["shape"] == "ellipse" else round_rect_pts(cx, cy, hw, hh, m["roundness"])
        cov, box = raster_poly([([ev.aapply(full, p) for p in pts], True)], w, h)
        a = np.zeros((h, w), np.float32)
        if cov is not None:
            x0, y0, x1, y1 = box
            a[y0:y1, x0:x1] = cov
        if m["feather"] > 0:
            img = Image.fromarray((a * 255).astype(np.uint8), "L").filter(ImageFilter.GaussianBlur(m["feather"] * sc / 2))
            a = np.asarray(img, np.float32) / 255
        if m["inverted"]:
            a = 1 - a
        a *= m["opacity"]
        if acc is None:
            acc = a if m["mode"] != "subtract" else 1 - a
            if m["mode"] == "intersect":
                acc = a
            continue
        if m["mode"] == "add":
            acc = np.maximum(acc, a)
        elif m["mode"] == "subtract":
            acc = acc * (1 - a)
        else:
            acc = np.minimum(acc, a)
    return acc


def blur(arr, radius):
    if radius <= 0.3:
        return arr
    img = Image.fromarray((np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")
    out = img.filter(ImageFilter.GaussianBlur(radius))
    return np.asarray(out, np.float32) / 255


SIMULATED = {"blur", "glow", "deepGlow", "dropShadow", "fill", "tint", "saturation", "brightnessContrast", "exposure", "invert",
             "posterize", "pixelate", "vignette", "hueShift", "posterizeTime", "echo", "timeSlice"} | ev.MOTION


def apply_effects(layer, effects, s):
    for e in effects:
        k, v = e["type"], e["v"]
        if k not in SIMULATED:
            ev.NOTES.add(f"efeito '{k}' não é simulado no preview")
            continue
        a = layer[..., 3:4]
        rgb = np.where(a > 1e-6, layer[..., :3] / np.maximum(a, 1e-6), 0)
        changed = True
        if k == "blur":
            layer[:] = blur(layer, v["radius"] * s / 2)
            changed = False
        elif k in ("glow", "deepGlow"):
            radius = v["radius"] * s / 2
            g = blur(layer, radius)
            colr = np.array(v["color"][:3], np.float32)
            inten = v["intensity"]
            glow = g[..., 3:4] * colr * inten if k == "glow" else g[..., :3] * colr * inten
            layer[..., :3] = np.clip(layer[..., :3] + glow * (1 - layer[..., :3]), 0, 1)
            layer[..., 3:4] = np.clip(np.maximum(layer[..., 3:4], g[..., 3:4] * min(1, inten)), 0, 1)
            changed = False
        elif k == "dropShadow":
            ang = math.radians(v["angle"])
            dx, dy = int(round(math.cos(ang) * v["distance"] * s)), int(round(math.sin(ang) * v["distance"] * s))
            sh = blur(layer, v["softness"] * s / 2)[..., 3:4] * v["opacity"]
            sh = np.roll(np.roll(sh, dy, axis=0), dx, axis=1)
            colr = np.array(v["color"][:3], np.float32)
            under = np.concatenate([colr * sh, sh], axis=-1)
            layer[:] = layer + under * (1 - layer[..., 3:4])
            changed = False
        elif k == "fill":
            rgb = rgb + (np.array(v["color"][:3], np.float32) - rgb) * v["amount"]
        elif k == "tint":
            l = (rgb @ np.array([0.2126, 0.7152, 0.0722], np.float32))[..., None]
            rgb = rgb + (l * np.array(v["color"][:3], np.float32) - rgb) * v["amount"]
        elif k == "saturation":
            l = (rgb @ np.array([0.2126, 0.7152, 0.0722], np.float32))[..., None]
            rgb = l + (rgb - l) * (1 + v["saturation"])
        elif k == "brightnessContrast":
            rgb = (rgb + v["brightness"] - 0.5) * (1 + v["contrast"]) + 0.5
        elif k == "exposure":
            rgb = np.power(np.clip(rgb * (2 ** v["exposure"]) + v["offset"], 0, None), 1 / max(v["gamma"], 0.1))
        elif k == "invert":
            rgb = rgb + (1 - 2 * rgb) * v["amount"]
        elif k == "posterize":
            n = max(2, int(v["levels"]))
            rgb = np.round(rgb * (n - 1)) / (n - 1)
        elif k == "hueShift":
            ang = math.radians(v["hue"])
            cs, sn = math.cos(ang), math.sin(ang)
            k3 = 1 / 3
            sq = math.sqrt(k3)
            m = np.array([[cs + (1 - cs) * k3, k3 * (1 - cs) - sq * sn, k3 * (1 - cs) + sq * sn],
                          [k3 * (1 - cs) + sq * sn, cs + k3 * (1 - cs), k3 * (1 - cs) - sq * sn],
                          [k3 * (1 - cs) - sq * sn, k3 * (1 - cs) + sq * sn, cs + k3 * (1 - cs)]], np.float32)
            rgb = rgb @ m.T
        elif k == "pixelate":
            n = max(1, int(v["size"] * s))
            hh, ww = layer.shape[:2]
            img = to_pil(layer).resize((max(1, ww // n), max(1, hh // n)), Image.BOX).resize((ww, hh), Image.NEAREST)
            layer[:] = premul(img)
            changed = False
        elif k == "vignette":
            hh, ww = layer.shape[:2]
            ys, xs = np.mgrid[0:hh, 0:ww].astype(np.float32)
            d = np.sqrt(((xs / ww - 0.5) * 2) ** 2 + ((ys / hh - 0.5) * 2) ** 2) / math.sqrt(2)
            f = 1 - v["amount"] * np.clip((d - (1 - v["softness"]) * 0.7) / max(v["softness"], 0.05), 0, 1)
            rgb = rgb * f[..., None]
        else:
            changed = False
        if changed:
            layer[..., :3] = np.clip(rgb, 0, 1) * a
    return layer


def motion_of(node):
    m = ev.IDENT
    for e in node.get("effects") or []:
        if e.get("matrix") is not None:
            m = ev.amul(e["matrix"], m)
    return m


# ---------------------------------------------------------------------------------------------------------- 3D


def mesh_for(prim):
    """Unit primitive (matches core/geom/Mesh.kt): positions (N,3), uvs (N,2), triangles (M,3)."""
    if prim == "plane":
        p = np.array([[-.5, -.5, 0], [.5, -.5, 0], [.5, .5, 0], [-.5, .5, 0]], np.float32)
        return p, np.array([[0, 0], [1, 0], [1, 1], [0, 1]], np.float32), np.array([[0, 1, 2], [0, 2, 3]])
    if prim == "sphere":
        rings, segs = 20, 32
        pos, uv, tri = [], [], []
        for r in range(rings + 1):
            th = math.pi * r / rings
            for s_ in range(segs + 1):
                ph = 2 * math.pi * s_ / segs
                pos.append((math.sin(th) * math.cos(ph) * 0.5, -math.cos(th) * 0.5, math.sin(th) * math.sin(ph) * 0.5))
                uv.append((s_ / segs, r / rings))
        for r in range(rings):
            for s_ in range(segs):
                a = r * (segs + 1) + s_
                b = a + segs + 1
                tri += [(a, b, a + 1), (a + 1, b, b + 1)]
        return np.array(pos, np.float32), np.array(uv, np.float32), np.array(tri)
    if prim in ("cylinder", "cone"):
        segs = 32
        top = 0.5 if prim == "cylinder" else 0.0
        pos, uv, tri = [], [], []
        for s_ in range(segs + 1):
            a = 2 * math.pi * s_ / segs
            cx, cz = math.cos(a), math.sin(a)
            pos += [(cx * top, -0.5, cz * top), (cx * 0.5, 0.5, cz * 0.5)]
            uv += [(s_ / segs, 0), (s_ / segs, 1)]
        for s_ in range(segs):
            i = 2 * s_
            tri += [(i, i + 1, i + 2), (i + 2, i + 1, i + 3)]
        for y, rad in ((-0.5, top), (0.5, 0.5)):
            if rad <= 0:
                continue
            c = len(pos)
            pos.append((0, y, 0))
            uv.append((0.5, 0.5))
            for s_ in range(segs + 1):
                a = 2 * math.pi * s_ / segs
                pos.append((math.cos(a) * rad, y, math.sin(a) * rad))
                uv.append((0.5 + math.cos(a) * 0.5, 0.5 + math.sin(a) * 0.5))
            for s_ in range(segs):
                tri.append((c, c + 1 + s_, c + 2 + s_))
        return np.array(pos, np.float32), np.array(uv, np.float32), np.array(tri)
    if prim == "torus":
        maj, mino, ns, nm = 0.35, 0.15, 40, 16
        pos, uv, tri = [], [], []
        for i in range(ns + 1):
            u = 2 * math.pi * i / ns
            for j in range(nm + 1):
                v = 2 * math.pi * j / nm
                r = maj + mino * math.cos(v)
                pos.append((r * math.cos(u), r * math.sin(u), mino * math.sin(v)))
                uv.append((i / ns, j / nm))
        for i in range(ns):
            for j in range(nm):
                a = i * (nm + 1) + j
                b = a + nm + 1
                tri += [(a, b, a + 1), (a + 1, b, b + 1)]
        return np.array(pos, np.float32), np.array(uv, np.float32), np.array(tri)
    # cube: 6 faces with their own vertices (flat normals)
    pos, uv, tri = [], [], []
    for n, u_, v_ in (((0, 0, -1), (1, 0, 0), (0, 1, 0)), ((0, 0, 1), (-1, 0, 0), (0, 1, 0)), ((1, 0, 0), (0, 0, 1), (0, 1, 0)),
                      ((-1, 0, 0), (0, 0, -1), (0, 1, 0)), ((0, -1, 0), (1, 0, 0), (0, 0, 1)), ((0, 1, 0), (1, 0, 0), (0, 0, -1))):
        c = np.array(n, np.float32) * 0.5
        base = len(pos)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            pos.append(tuple(c + np.array(u_, np.float32) * 0.5 * su + np.array(v_, np.float32) * 0.5 * sv))
            uv.append(((su + 1) / 2, (sv + 1) / 2))
        tri += [(base, base + 1, base + 2), (base, base + 2, base + 3)]
    return np.array(pos, np.float32), np.array(uv, np.float32), np.array(tri)


_MESHES = {}


def unit_mesh(prim):
    if prim not in _MESHES:
        _MESHES[prim] = mesh_for(prim)
    return _MESHES[prim]


def load_glb(path):
    """Minimal glTF 2.0 binary reader: triangles of every mesh primitive, node transforms, base colour factor/texture.
    Normalised like `GltfModel.normalization` (centred, largest side = 1, rotated 180° around X)."""
    try:
        with open(path, "rb") as f:
            data = f.read()
        if data[:4] != b"glTF":
            return None
        off = 12
        js, binc = None, b""
        while off < len(data):
            ln, kind = struct.unpack_from("<II", data, off)
            chunk = data[off + 8: off + 8 + ln]
            if kind == 0x4E4F534A:
                js = json.loads(chunk)
            elif kind == 0x004E4942:
                binc = chunk
            off += 8 + ln
        if js is None:
            return None
        comp_n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
        dt = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8, 5122: np.int16, 5120: np.int8}

        def accessor(i):
            a = js["accessors"][i]
            bv = js["bufferViews"][a["bufferView"]]
            n = comp_n[a["type"]]
            dtype = np.dtype(dt[a["componentType"]])
            start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
            stride = bv.get("byteStride") or n * dtype.itemsize
            raw = np.frombuffer(binc, dtype=np.uint8, count=stride * (a["count"] - 1) + n * dtype.itemsize, offset=start)
            out = np.lib.stride_tricks.as_strided(raw, shape=(a["count"], n * dtype.itemsize), strides=(stride, 1)).copy()
            arr = out.view(dtype).reshape(a["count"], n).astype(np.float32)
            if a.get("normalized"):
                arr /= float(np.iinfo(dtype).max)
            return arr

        def image_of(mat_i):
            if mat_i is None:
                return (0.82, 0.82, 0.85, 1.0), None
            m = js["materials"][mat_i]
            pbr = m.get("pbrMetallicRoughness") or {}
            fac = tuple(pbr.get("baseColorFactor", (1, 1, 1, 1)))
            tex = pbr.get("baseColorTexture")
            img = None
            if tex is not None:
                try:
                    src = js["textures"][tex["index"]]["source"]
                    bv = js["bufferViews"][js["images"][src]["bufferView"]]
                    o = bv.get("byteOffset", 0)
                    img = premul(Image.open(io.BytesIO(binc[o:o + bv["byteLength"]])))
                except (KeyError, OSError, IndexError):
                    img = None
            return fac, img

        def node_matrix(n):
            if "matrix" in n:
                return np.array(n["matrix"], np.float32).reshape(4, 4).T
            t = np.eye(4, dtype=np.float32)
            t[:3, 3] = n.get("translation", (0, 0, 0))
            x, y, z, w = n.get("rotation", (0, 0, 0, 1))
            r = np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0],
                          [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0],
                          [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0], [0, 0, 0, 1]], np.float32)
            sc = np.diag(list(n.get("scale", (1, 1, 1))) + [1]).astype(np.float32)
            return t @ r @ sc

        parts = []

        def visit(ni, parent):
            n = js["nodes"][ni]
            m = parent @ node_matrix(n)
            if "mesh" in n:
                for prim in js["meshes"][n["mesh"]].get("primitives", []):
                    if prim.get("mode", 4) != 4 or "POSITION" not in prim["attributes"]:
                        continue
                    pos = accessor(prim["attributes"]["POSITION"])
                    pos = (np.c_[pos, np.ones(len(pos))] @ m.T)[:, :3]
                    uv = accessor(prim["attributes"]["TEXCOORD_0"]) if "TEXCOORD_0" in prim["attributes"] else np.zeros((len(pos), 2), np.float32)
                    idx = accessor(prim["indices"]).astype(np.int64).reshape(-1, 3) if "indices" in prim else np.arange(len(pos)).reshape(-1, 3)
                    fac, img = image_of(prim.get("material"))
                    parts.append((pos.astype(np.float32), uv, idx, fac, img))
            for c in n.get("children", []):
                visit(c, m)

        scene = js.get("scenes", [{}])[js.get("scene", 0)] if js.get("scenes") else {"nodes": list(range(len(js.get("nodes", []))))}
        for ni in scene.get("nodes", []):
            visit(ni, np.eye(4, dtype=np.float32))
        if not parts:
            return None
        allp = np.concatenate([p[0] for p in parts])
        lo, hi = allp.min(0), allp.max(0)
        center = (lo + hi) / 2
        largest = max(float((hi - lo).max()), 1e-6)
        out = []
        for pos, uv, idx, fac, img in parts:
            p = (pos - center) / largest
            p[:, 1] *= -1
            p[:, 2] *= -1
            out.append((p, uv, idx, fac, img))
        return out
    except (OSError, KeyError, ValueError, IndexError, struct.error):
        return None


def text3d_mesh(t3, assets):
    """Flat stand-in for extruded 3D text: a quad textured with the text."""
    c = {"content": t3.get("content", "Kinesis"), "font": t3.get("font") or {}, "fontSize": float(t3.get("fontSize", 200)),
         "color": (1, 1, 1, 1), "align": t3.get("align", "center"), "lineHeight": float(t3.get("lineHeight", 1.2)),
         "letterSpacing": 0.0, "maxWidth": 0.0, "strokeColor": None, "strokeWidth": 0.0, "glyphs": None}
    r = text_raster(c, 1.0, assets)
    if r is None:
        return None
    img, x0, y0 = r
    h, w = img.shape[:2]
    p = np.array([[x0, y0, 0], [x0 + w, y0, 0], [x0 + w, y0 + h, 0], [x0, y0 + h, 0]], np.float32)
    return p, np.array([[0, 0], [1, 0], [1, 1], [0, 1]], np.float32), np.array([[0, 1, 2], [0, 2, 3]]), img


def render_3d(nodes, scene, P, shape, assets):
    """Z-buffered rasterization of consecutive 3D nodes into one premultiplied layer (deferred shading)."""
    h, w = shape
    s3 = 2  # supersample
    H, W = h * s3, w * s3
    comp = scene["comp"]
    cam = scene["camera"]
    vp = np.array(cam["vp"], np.float32)
    sc = P[0] * s3
    zbuf = np.full((H, W), np.inf, np.float32)
    obj = np.full((H, W), -1, np.int32)
    wpos = np.zeros((H, W, 3), np.float32)
    nrm = np.zeros((H, W, 3), np.float32)
    uvb = np.zeros((H, W, 2), np.float32)
    mats = []
    wire = []
    for node in nodes:
        model = np.array(node["model"], np.float32)
        parts = []
        if node["primitive"] == "text":
            m = text3d_mesh(node["text3d"] or {}, assets)
            if m:
                parts.append((m[0], m[1], m[2], None, m[3]))
            ev.NOTES.add("texto 3D aparece plano (sem extrusão e chanfro)")
        elif node["primitive"]:
            p, uv, tri = unit_mesh(node["primitive"])
            parts.append((p, uv, tri, None, None))
        else:
            glb = assets.model(node["asset"])
            if glb:
                parts += glb
            else:
                p, uv, tri = unit_mesh("cube")
                parts.append((p, uv, tri, None, None))
        for pos, uv, tri, fac, tex in parts:
            mat = dict(node["material"] or {"color": fac or (0.82, 0.82, 0.85, 1), "metallic": 0, "roughness": 0.5,
                                             "emissive": (0, 0, 0, 1), "texture": None, "shading": "pbr"})
            texture = tex
            if mat.get("texture"):
                im = assets.image(mat["texture"])
                texture = premul(im) if im is not None else None
            mat["tex"] = texture
            mat["opacity"] = node["opacity"]
            mid = len(mats)
            mats.append(mat)
            wp = (np.c_[pos, np.ones(len(pos), np.float32)] @ model.T)[:, :3]
            clip = np.c_[wp, np.ones(len(wp), np.float32)] @ vp.T
            cw = clip[:, 3]
            sx = (clip[:, 0] / np.where(cw > 1e-4, cw, 1) * comp["width"] / 2) * sc + W / 2
            sy = (-clip[:, 1] / np.where(cw > 1e-4, cw, 1) * comp["height"] / 2) * sc + H / 2
            if mat.get("shading") == "wireframe":
                wire.append((sx / s3, sy / s3, cw, tri, mat))
                continue
            vn = np.zeros_like(wp)
            fn = np.cross(wp[tri[:, 1]] - wp[tri[:, 0]], wp[tri[:, 2]] - wp[tri[:, 0]])
            for k in range(3):
                np.add.at(vn, tri[:, k], fn)
            vn /= np.maximum(np.linalg.norm(vn, axis=1, keepdims=True), 1e-12)
            _raster_tris(tri, sx, sy, cw, wp, uv, vn, mat.get("shading") == "flat", mid, zbuf, obj, wpos, nrm, uvb)
    color = _shade(obj, wpos, nrm, uvb, mats, scene)
    alpha = np.zeros((H, W), np.float32)
    for mid, m in enumerate(mats):
        sel = obj == mid
        a = m["opacity"] * m["color"][3]
        if m.get("tex") is not None and m.get("_alpha") is not None:
            alpha[sel] = m["_alpha"] * a
        else:
            alpha[sel] = a
    out = np.concatenate([color * alpha[..., None], alpha[..., None]], axis=-1)
    img = Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA").resize((w, h), Image.BOX)
    layer = np.asarray(img, np.float32) / 255
    if wire:
        wimg = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        d = ImageDraw.Draw(wimg)
        for sx, sy, cw, tri, mat in wire:
            c = mat["color"]
            fill = tuple(int(v * 255) for v in (c[0], c[1], c[2], c[3] * mat["opacity"]))
            for a, b, cc in tri:
                if min(cw[a], cw[b], cw[cc]) <= 1e-3:
                    continue
                d.line([(sx[a], sy[a]), (sx[b], sy[b]), (sx[cc], sy[cc]), (sx[a], sy[a])], fill=fill, width=1)
        wl = premul(wimg)
        composite(layer, wl)
    return layer


def _raster_tris(tri, sx, sy, cw, wp, uv, vn, flat, mid, zbuf, obj, wpos, nrm, uvb):
    H, W = zbuf.shape
    for a, b, c in tri:
        if cw[a] <= 1e-3 or cw[b] <= 1e-3 or cw[c] <= 1e-3:
            continue
        x0 = max(0, int(math.floor(min(sx[a], sx[b], sx[c]))))
        x1 = min(W, int(math.ceil(max(sx[a], sx[b], sx[c]))) + 1)
        y0 = max(0, int(math.floor(min(sy[a], sy[b], sy[c]))))
        y1 = min(H, int(math.ceil(max(sy[a], sy[b], sy[c]))) + 1)
        if x1 <= x0 or y1 <= y0:
            continue
        ax, ay, bx, by, cx, cy = sx[a], sy[a], sx[b], sy[b], sx[c], sy[c]
        area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if abs(area) < 1e-9:
            continue
        ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32) + 0.5
        w0 = ((bx - xs) * (cy - ys) - (by - ys) * (cx - xs)) / area
        w1 = ((cx - xs) * (ay - ys) - (cy - ys) * (ax - xs)) / area
        w2 = 1 - w0 - w1
        inside = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not inside.any():
            continue
        # perspective-correct weights
        p0, p1, p2 = w0 / cw[a], w1 / cw[b], w2 / cw[c]
        ps = p0 + p1 + p2
        p0, p1, p2 = p0 / ps, p1 / ps, p2 / ps
        depth = p0 * cw[a] + p1 * cw[b] + p2 * cw[c]
        zb = zbuf[y0:y1, x0:x1]
        win = inside & (depth < zb)
        if not win.any():
            continue
        zb[win] = depth[win]
        obj[y0:y1, x0:x1][win] = mid
        if flat:
            n = np.cross(wp[b] - wp[a], wp[c] - wp[a])
            nl = np.linalg.norm(n)
            n = n / nl if nl > 1e-12 else np.array([0, 0, -1], np.float32)
        else:
            n = p0[..., None] * vn[a] + p1[..., None] * vn[b] + p2[..., None] * vn[c]
            n = n[win]
        pw = p0[..., None] * wp[a] + p1[..., None] * wp[b] + p2[..., None] * wp[c]
        wpos[y0:y1, x0:x1][win] = pw[win]
        nrm[y0:y1, x0:x1][win] = n
        pu = p0[..., None] * uv[a] + p1[..., None] * uv[b] + p2[..., None] * uv[c]
        uvb[y0:y1, x0:x1][win] = pu[win]


def _aces(x):
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    return np.clip((x * (a * x + b)) / (x * (c * x + d) + e), 0, 1)


def _shade(obj, wpos, nrm, uvb, mats, scene):
    H, W = obj.shape
    out = np.zeros((H, W, 3), np.float32)
    cam_pos = np.array(scene["camera"]["position"], np.float32)
    amb = np.array(scene["ambient"], np.float32)
    lights = scene["lights"]
    for mid, m in enumerate(mats):
        sel = obj == mid
        if not sel.any():
            continue
        P = wpos[sel]
        N = nrm[sel]
        N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-6)
        V = cam_pos - P
        V /= np.maximum(np.linalg.norm(V, axis=1, keepdims=True), 1e-6)
        flip = (N * V).sum(1) < 0
        N[flip] *= -1
        base = np.array(m["color"][:3], np.float32)
        albedo = np.broadcast_to(to_linear(base), P.shape).copy()
        if m.get("tex") is not None:
            tex = m["tex"]
            th, tw = tex.shape[:2]
            uv = uvb[sel]
            ui = np.clip((uv[:, 0] % 1.0001) * (tw - 1), 0, tw - 1).astype(np.int64)
            vi = np.clip((uv[:, 1] % 1.0001) * (th - 1), 0, th - 1).astype(np.int64)
            t = tex[vi, ui]
            ta = t[:, 3:4]
            trgb = np.where(ta > 1e-6, t[:, :3] / np.maximum(ta, 1e-6), 0)
            albedo *= to_linear(trgb)
            m["_alpha"] = ta[:, 0]
        shading = m.get("shading", "pbr")
        metallic, rough = m.get("metallic", 0.0), max(0.03, m.get("roughness", 0.5))
        f0 = 0.04 * (1 - metallic) + albedo * metallic
        color = np.zeros_like(albedo)
        NdotV = np.maximum((N * V).sum(1, keepdims=True), 1e-3)
        for L in lights:
            lc = to_linear(np.array(L["color"], np.float32)) * L["intensity"]
            if L["kind"] == "point" or L["kind"] == "spot":
                tl = np.array(L["position"], np.float32) - P
                dist = np.maximum(np.linalg.norm(tl, axis=1, keepdims=True), 1e-4)
                Ld = tl / dist
                x = np.clip(dist / L.get("radius", 4000.0), 0, 1)
                att = (1 - x * x) ** 2
            else:
                Ld = -np.array(L["direction"], np.float32)[None, :]
                att = 1.0
            NdotL = np.maximum((N * Ld).sum(1, keepdims=True), 0)
            if shading == "toon":
                band = np.where(NdotL > 0.55, 1.0, np.where(NdotL > 0.2, 0.55, 0.25)) * (NdotL > 0)
                color += albedo * band * lc * att
                continue
            Hh = Ld + V
            Hh /= np.maximum(np.linalg.norm(Hh, axis=1, keepdims=True), 1e-6)
            NdotH = np.maximum((N * Hh).sum(1, keepdims=True), 0)
            a2 = (rough * rough) ** 2
            D = a2 / (math.pi * (NdotH * NdotH * (a2 - 1) + 1) ** 2)
            k = (rough + 1) ** 2 / 8
            G = (NdotV / (NdotV * (1 - k) + k)) * (NdotL / (NdotL * (1 - k) + k))
            VdotH = np.maximum((Hh * V).sum(1, keepdims=True), 0)
            F = f0 + (1 - f0) * (1 - VdotH) ** 5
            spec = D * G * F / (4 * NdotV * NdotL + 1e-4)
            kd = (1 - F) * (1 - metallic)
            color += (kd * albedo / math.pi + spec) * lc * att * math.pi * NdotL
        color += amb * albedo * (1 - metallic)
        # Sky/floor reflection stand-in for the environment (mesh.frag's default gradient).
        R = 2 * (N * V).sum(1, keepdims=True) * N - V
        up = np.clip(-R[:, 1:2], -1, 1)
        sky = np.where(up > 0, 0.18 + (0.95 - 0.18) * np.clip(up / 0.9, 0, 1), 0.05 + 0.17 * np.clip(1 + up / 0.6, 0, 1))
        refl = sky * (f0 * (1 - rough) + 0.0)
        if shading != "toon":
            color += refl
        else:
            color = color + albedo * 0.08
            color *= np.where(1 - NdotV > 0.67, 0.25, 1.0)
        color += to_linear(np.array(m.get("emissive", (0, 0, 0))[:3], np.float32)) * 2
        out[sel] = to_srgb(_aces(color))
    return out


# ---------------------------------------------------------------------------------------------------------- frame


class Renderer:
    def __init__(self, project, base_dir, scale):
        self.project = project
        self.assets = Assets(project, base_dir)
        self.scale = scale
        comp = ev.composition(project)
        self.comp = comp
        self.w = max(1, int(round(comp["width"] * scale)))
        self.h = max(1, int(round(comp["height"] * scale)))
        self.P = ev.amul(ev.atrans(self.w / 2, self.h / 2), ev.ascale(scale, scale))

    def frame(self, time):
        scene = ev.evaluate(self.project, time)
        canvas = np.zeros((self.h, self.w, 4), np.float32)
        bg = scene["comp"]["background"]
        canvas[...] = (bg[0] * bg[3], bg[1] * bg[3], bg[2] * bg[3], bg[3])
        self._draw_list(canvas, scene["nodes"], scene)
        return to_pil(canvas).convert("RGB")

    def _draw_list(self, canvas, nodes, scene):
        run = []
        for n in nodes + [None]:
            if n is not None and n["kind"] == "3d":
                run.append(n)
                continue
            if run:
                layer = render_3d(run, scene, self.P, (self.h, self.w), self.assets)
                for r in run:
                    if r.get("effects"):
                        layer = apply_effects(layer, r["effects"], self.scale)
                composite(canvas, layer, run[0]["blend"])
                run = []
            if n is not None:
                layer = self._node(n, scene)
                if layer is not None:
                    composite(canvas, layer, n["blend"])

    def _node(self, n, scene):
        shape = (self.h, self.w)
        M = ev.amul(motion_of(n), n["matrix"])
        bounds = None
        if n["kind"] == "group":
            layer = np.zeros((self.h, self.w, 4), np.float32)
            # Children carry the group matrix already; the effects' motion is applied by re-evaluating with it folded in.
            self._draw_list(layer, n["children"], scene)
            mo = motion_of(n)
            if mo != ev.IDENT:
                patch, box = warp(shape, layer, ev.ainv(self.P), mo, self.P)
                layer = np.zeros_like(layer)
                if patch is not None:
                    paste(layer, patch, box)
        else:
            c = n["content"]
            if c["kind"] == "shape":
                layer, bounds = draw_shape(c, M, self.P, shape)
            elif c["kind"] == "text":
                rs = min(4.0, max(0.05, mat_scale(ev.amul(self.P, M)) * 1.0))
                r = text_raster(c, rs, self.assets)
                if r is None:
                    return None
                img, x0, y0 = r
                bounds = (x0, y0, -x0, -y0)
                layer = draw_raster(img, x0, y0, 1 / rs, M, self.P, shape)
            elif c["kind"] in ("image", "video"):
                im = self.assets.image(c["asset"]) if c["kind"] == "image" else self.assets.video_frame(c["asset"], c["time"], self.comp["fps"])
                if im is None:
                    return None
                if c["kind"] == "video" and c.get("alphaPacked"):
                    half = im.width // 2
                    rgb = im.crop((0, 0, half, im.height))
                    a = im.crop((half, 0, half * 2, im.height)).convert("L")
                    rgb.putalpha(a)
                    im = rgb
                # Downscale big media to roughly what the canvas needs.
                need = mat_scale(ev.amul(self.P, M))
                units = 1.0
                if need < 0.9 and im.width * need > 8:
                    nw, nh = max(1, int(im.width * need)), max(1, int(im.height * need))
                    units = im.width / nw
                    im = im.resize((nw, nh), Image.BILINEAR)
                arr = premul(im)
                x0, y0 = -im.width * units / 2, -im.height * units / 2
                bounds = (x0, y0, -x0, -y0)
                layer = draw_raster(arr, x0, y0, units, M, self.P, shape)
            else:
                return None
        if n.get("wipe") and bounds:
            p, anchored_right = n["wipe"]
            x0, y0, x1, y1 = bounds
            w = (x1 - x0) * p
            rect = (x1 - w, y0, x1, y1) if anchored_right else (x0, y0, x0 + w, y1)
            pts = [ev.aapply(ev.amul(self.P, M), q) for q in ((rect[0], rect[1]), (rect[2], rect[1]), (rect[2], rect[3]), (rect[0], rect[3]))]
            cov, box = raster_poly([(pts, True)], self.w, self.h)
            mask = np.zeros(shape, np.float32)
            if cov is not None:
                bx0, by0, bx1, by1 = box
                mask[by0:by1, bx0:bx1] = cov
            layer *= mask[..., None]
        if n.get("masks"):
            m = mask_alpha(n["masks"], M, self.P, shape)
            if m is not None:
                layer *= m[..., None]
        if n.get("effects"):
            layer = apply_effects(layer, n["effects"], self.scale)
        if n.get("transition_blur", 0) > 0.3:
            layer = blur(layer, n["transition_blur"] * self.scale / 2)
        if n.get("motionBlur"):
            ev.NOTES.add("motion blur não é simulado")
        if n.get("matte"):
            mn = n["matte"]["node"]
            ml = render_3d([mn], scene, self.P, shape, self.assets) if mn["kind"] == "3d" else self._node(dict(mn, matte=None), scene)
            mode = n["matte"]["mode"]
            if ml is None:
                k = np.zeros(shape, np.float32)
            elif mode.startswith("luma"):
                k = (ml[..., :3] @ np.array([0.2126, 0.7152, 0.0722], np.float32))
            else:
                k = ml[..., 3]
            if mode.endswith("Inverted"):
                k = 1 - k
            layer *= k[..., None]
        layer *= n["opacity"]
        return layer


# ---------------------------------------------------------------------------------------------------------- outputs


def contact_sheet(frames, times, comp, cell_w):
    cols = min(len(frames), 3 if len(frames) > 4 else len(frames))
    if len(frames) == 4:
        cols = 2
    rows = math.ceil(len(frames) / cols)
    fw, fh = frames[0].size
    k = cell_w / fw
    cw, ch = int(fw * k), int(fh * k)
    label_h = max(18, cw // 14)
    gap = 8
    sheet = Image.new("RGB", (cols * cw + (cols + 1) * gap, rows * (ch + label_h) + (rows + 1) * gap), (24, 24, 28))
    d = ImageDraw.Draw(sheet)
    font = load_font({"family": "monospace", "weight": 600}, label_h * 0.7, Assets({}, None))
    for i, (f, t) in enumerate(zip(frames, times)):
        r, c = divmod(i, cols)
        x = gap + c * (cw + gap)
        y = gap + r * (ch + label_h + gap)
        frame_no = int(round(t * comp["fps"]))
        d.text((x + 2, y + 1), f"t={t:.2f}s  f{frame_no}", font=font, fill=(220, 220, 230))
        sheet.paste(f.resize((cw, ch), Image.LANCZOS), (x, y + label_h))
    return sheet

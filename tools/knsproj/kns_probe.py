"""Reads basic metadata (type, size, duration) from media files without third-party libraries."""

import os
import struct

EXTENSION_TYPES = {
    "png": ("image", "image/png"), "jpg": ("image", "image/jpeg"), "jpeg": ("image", "image/jpeg"),
    "webp": ("image", "image/webp"), "gif": ("image", "image/gif"),
    "mp4": ("video", "video/mp4"), "m4v": ("video", "video/mp4"), "mov": ("video", "video/quicktime"),
    "webm": ("video", "video/webm"), "3gp": ("video", "video/3gpp"), "mkv": ("video", "video/x-matroska"),
    "m4a": ("audio", "audio/mp4"), "mp3": ("audio", "audio/mpeg"), "aac": ("audio", "audio/aac"),
    "wav": ("audio", "audio/wav"), "ogg": ("audio", "audio/ogg"), "opus": ("audio", "audio/ogg"), "flac": ("audio", "audio/flac"),
    "glb": ("model", "model/gltf-binary"), "gltf": ("model", "model/gltf+json"),
    "ttf": ("font", "font/ttf"), "otf": ("font", "font/otf"),
}


def guess(path):
    ext = path.rsplit(".", 1)[-1].lower() if "." in path else ""
    return EXTENSION_TYPES.get(ext, (None, None))


def image_size(path):
    """(width, height) for PNG, JPEG, GIF and WebP, else None."""
    try:
        with open(path, "rb") as f:
            head = f.read(64)
            if head.startswith(b"\x89PNG\r\n\x1a\n") and head[12:16] == b"IHDR":
                return struct.unpack(">II", head[16:24])
            if head[:6] in (b"GIF87a", b"GIF89a"):
                return struct.unpack("<HH", head[6:10])
            if head.startswith(b"RIFF") and head[8:12] == b"WEBP":
                chunk = head[12:16]
                if chunk == b"VP8X":
                    w = int.from_bytes(head[24:27], "little") + 1
                    h = int.from_bytes(head[27:30], "little") + 1
                    return w, h
                if chunk == b"VP8 ":
                    w, h = struct.unpack("<HH", head[26:30])
                    return w & 0x3FFF, h & 0x3FFF
                if chunk == b"VP8L":
                    b = head[21:25]
                    bits = int.from_bytes(b, "little")
                    return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
            if head.startswith(b"\xff\xd8"):
                f.seek(2)
                while True:
                    marker = f.read(2)
                    if len(marker) < 2 or marker[0] != 0xFF:
                        return None
                    if marker[1] in (0xD8, 0x01) or 0xD0 <= marker[1] <= 0xD7:
                        continue
                    length = struct.unpack(">H", f.read(2))[0]
                    if marker[1] in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                        f.read(1)
                        h, w = struct.unpack(">HH", f.read(4))
                        return w, h
                    f.seek(length - 2, os.SEEK_CUR)
    except (OSError, struct.error):
        return None
    return None


def mp4_duration(path):
    """Duration in seconds from the 'mvhd' box of MP4/MOV/M4A files, else None."""
    try:
        with open(path, "rb") as f:
            return _find_mvhd(f, os.path.getsize(path))
    except (OSError, struct.error):
        return None


def _find_mvhd(f, end):
    while f.tell() + 8 <= end:
        start = f.tell()
        size, kind = struct.unpack(">I4s", f.read(8))
        header = 8
        if size == 1:
            size = struct.unpack(">Q", f.read(8))[0]
            header = 16
        elif size == 0:
            size = end - start
        if size < header:
            return None
        if kind == b"moov":
            return _find_mvhd(f, start + size)
        if kind == b"mvhd":
            version = f.read(1)[0]
            f.read(3)
            if version == 1:
                f.read(16)
                timescale, duration = struct.unpack(">IQ", f.read(12))
            else:
                f.read(8)
                timescale, duration = struct.unpack(">II", f.read(8))
            return duration / timescale if timescale else None
        f.seek(start + size)
    return None


def is_glb(path):
    try:
        with open(path, "rb") as f:
            return f.read(4) == b"glTF"
    except OSError:
        return False

"""Reading and writing the .knsproj ZIP container (same rules as KnsprojArchive.kt)."""

import io
import json
import os
import zipfile

PROJECT_ENTRY = "project.json"
THUMBNAIL_ENTRY = "thumbnail.jpg"
MAX_PROJECT_JSON_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024 * 1024
MAX_ENTRIES = 10_000
STORED_EXTENSIONS = {"png", "jpg", "jpeg", "webp", "gif", "mp4", "m4v", "mov", "webm", "mkv", "3gp",
                     "m4a", "mp3", "aac", "ogg", "opus", "flac"}
# 1980-01-01 00:00:00: fixed timestamps make archives reproducible.
FIXED_DATE = (1980, 1, 1, 0, 0, 0)


class ArchiveError(Exception):
    pass


def normalize_entry_name(raw):
    name = raw.replace("\\", "/")
    if not name or name.startswith("/") or ":" in name:
        return None
    parts = [p for p in name.split("/") if p not in ("", ".")]
    if not parts or any(p == ".." for p in parts):
        return None
    return "/".join(parts)


def dumps_project(project):
    """Serializes project.json like the app: 2-space indent, numeric arrays on one line."""
    return _pretty(project, 0) + "\n"


def _pretty(value, indent):
    pad = "  " * indent
    if isinstance(value, dict):
        if not value:
            return "{}"
        items = [f'{pad}  {json.dumps(k, ensure_ascii=False)}: {_pretty(v, indent + 1)}' for k, v in value.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(value, list):
        if not value:
            return "[]"
        if all(not isinstance(v, (dict, list)) for v in value):
            return "[" + ", ".join(_pretty(v, indent) for v in value) + "]"
        items = [f"{pad}  {_pretty(v, indent + 1)}" for v in value]
        return "[\n" + ",\n".join(items) + "\n" + pad + "]"
    if isinstance(value, float) and value.is_integer() and abs(value) < 1e15:
        return str(int(value))
    return json.dumps(value, ensure_ascii=False)


def write_archive(project, asset_dir_root, out_path, thumbnail=None):
    """Writes project + files referenced by project['assets'] (resolved relative to asset_dir_root)."""
    tmp_path = out_path + ".tmp"
    written = set()
    with zipfile.ZipFile(tmp_path, "w") as z:
        info = zipfile.ZipInfo(PROJECT_ENTRY, FIXED_DATE)
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, dumps_project(project).encode("utf-8"))
        for asset in project.get("assets", []) or []:
            path = normalize_entry_name(asset.get("path", ""))
            if path is None:
                raise ArchiveError(f"asset '{asset.get('id')}' has an invalid path '{asset.get('path')}'")
            if path in written:
                continue
            src = os.path.join(asset_dir_root, *path.split("/"))
            if not os.path.isfile(src):
                raise ArchiveError(f"missing file for asset '{asset.get('id')}': {path}")
            ext = path.rsplit(".", 1)[-1].lower() if "." in path else ""
            info = zipfile.ZipInfo(path, FIXED_DATE)
            info.compress_type = zipfile.ZIP_STORED if ext in STORED_EXTENSIONS else zipfile.ZIP_DEFLATED
            with open(src, "rb") as f:
                z.writestr(info, f.read())
            written.add(path)
        if thumbnail is not None:
            info = zipfile.ZipInfo(THUMBNAIL_ENTRY, FIXED_DATE)
            z.writestr(info, thumbnail)
    os.replace(tmp_path, out_path)


def read_archive(path, extract_to=None):
    """Returns (project_dict, entry_names). Extracts every entry to `extract_to` when given."""
    try:
        z = zipfile.ZipFile(path)
    except zipfile.BadZipFile as e:
        raise ArchiveError(f"not a valid .knsproj (ZIP) archive: {e}")
    with z:
        infos = z.infolist()
        if len(infos) > MAX_ENTRIES:
            raise ArchiveError("too many entries in archive")
        total = sum(i.file_size for i in infos)
        if total > MAX_TOTAL_BYTES:
            raise ArchiveError("archive is too large")
        names = set()
        project = None
        for info in infos:
            if info.is_dir():
                continue
            name = normalize_entry_name(info.filename)
            if name is None:
                raise ArchiveError(f"unsafe entry name in archive: '{info.filename}'")
            names.add(name)
            if name == PROJECT_ENTRY:
                if info.file_size > MAX_PROJECT_JSON_BYTES:
                    raise ArchiveError("project.json is too large")
                text = z.read(info).decode("utf-8-sig")
                try:
                    project = json.loads(text)
                except json.JSONDecodeError as e:
                    raise ArchiveError(f"project.json is not valid JSON: {e}")
            if extract_to is not None:
                target = os.path.join(extract_to, *name.split("/"))
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(info) as src, open(target, "wb") as dst:
                    dst.write(src.read())
        if project is None:
            raise ArchiveError("project.json not found in archive")
        return project, names


def list_dir_files(root):
    """Relative POSIX paths of every file under root (used to check asset presence for directories)."""
    out = set()
    for dirpath, _, files in os.walk(root):
        for f in files:
            rel = os.path.relpath(os.path.join(dirpath, f), root).replace(os.sep, "/")
            out.add(rel)
    return out


def bytes_io(data):
    return io.BytesIO(data)

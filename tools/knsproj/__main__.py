"""knsproj — validate, pack and inspect Kinesis projects (.knsproj).

Usage:
  python3 tools/knsproj validate <project-dir | project.json | file.knsproj> [--json] [--strict]
  python3 tools/knsproj pack <project-dir> [-o out.knsproj] [--force]
  python3 tools/knsproj unpack <file.knsproj> [-o dir]
  python3 tools/knsproj info <project-dir | project.json | file.knsproj>
  python3 tools/knsproj new <project-dir> [--title T] [--width W] [--height H] [--fps F] [--duration S] [--background #RRGGBB]
  python3 tools/knsproj add-asset <project-dir> <file> [--id ID] [--type image|video|audio|model|font]
  python3 tools/knsproj schema

Only the Python standard library is required. Exit status: 0 = ok, 1 = invalid project, 2 = usage/IO error.
"""

import argparse
import datetime
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import kns_archive  # noqa: E402
import kns_probe  # noqa: E402
import kns_schema  # noqa: E402
import kns_semantic  # noqa: E402

VERSION = "1.0.0"
FORMAT_ID = "knsproj"
FORMAT_VERSION = 1
SCHEMA_PATH = os.path.normpath(os.path.join(HERE, "..", "..", "schema", f"knsproj-v{FORMAT_VERSION}.schema.json"))


class Result:
    def __init__(self):
        self.issues = []  # dicts: severity, code, path, message

    def add(self, severity, code, path, message):
        self.issues.append({"severity": severity, "code": code, "path": path, "message": message})

    @property
    def errors(self):
        return [i for i in self.issues if i["severity"] == "error"]

    @property
    def warnings(self):
        return [i for i in self.issues if i["severity"] == "warning"]


def load_schema():
    with open(SCHEMA_PATH, encoding="utf-8") as f:
        return json.load(f)


def load_target(path):
    """Returns (project_dict, available_files or None, base_dir or None)."""
    if os.path.isdir(path):
        pj = os.path.join(path, "project.json")
        if not os.path.isfile(pj):
            raise FileNotFoundError(f"{path} has no project.json")
        with open(pj, encoding="utf-8-sig") as f:
            project = json.load(f)
        return project, kns_archive.list_dir_files(path), path
    if path.lower().endswith(".knsproj"):
        project, names = kns_archive.read_archive(path)
        return project, names, None
    with open(path, encoding="utf-8-sig") as f:
        project = json.load(f)
    base = os.path.dirname(os.path.abspath(path))
    return project, kns_archive.list_dir_files(base), base


def validate_project(project, available_files, engine="builtin"):
    result = Result()
    if not isinstance(project, dict):
        result.add("error", "format.header", "$", "project.json must contain a JSON object")
        return result
    if project.get("format") != FORMAT_ID:
        result.add("error", "format.header", "$.format", f'"format" must be "{FORMAT_ID}"')
        return result
    version = project.get("formatVersion")
    if not isinstance(version, int) or isinstance(version, bool):
        result.add("error", "format.header", "$.formatVersion", '"formatVersion" must be an integer')
        return result
    if version > FORMAT_VERSION:
        result.add("error", "format.version", "$.formatVersion",
                   f"format version {version} is newer than this tool supports ({FORMAT_VERSION})")
        return result

    schema = load_schema()
    if engine == "jsonschema":
        import jsonschema  # optional dependency
        validator = jsonschema.Draft202012Validator(schema)
        for e in validator.iter_errors(project):
            loc = "$" + "".join(f"[{p}]" if isinstance(p, int) else f".{p}" for p in e.absolute_path)
            result.add("error", "schema", loc, e.message)
    else:
        for e in kns_schema.Validator(schema).validate(project):
            result.add("error", "schema", e.path, e.message)
    if result.errors:
        # Semantic checks assume a structurally valid document.
        return result
    for issue in kns_semantic.validate(project, available_files):
        result.add(issue.severity, issue.code, issue.path, issue.message)
    return result


def print_result(name, result, as_json):
    if as_json:
        print(json.dumps({"target": name, "valid": not result.errors, "issues": result.issues}, indent=2))
        return
    status = "VALID" if not result.errors else "INVALID"
    print(f"{status}: {name} ({len(result.errors)} error(s), {len(result.warnings)} warning(s))")
    for i in result.issues:
        print(f"  {i['severity']:7} [{i['code']}] {i['path']}: {i['message']}")


# ----------------------------------------------------------------------------- commands

def cmd_validate(args):
    project, files, _ = load_target(args.path)
    result = validate_project(project, files, args.engine)
    print_result(args.path, result, args.json)
    failed = bool(result.errors) or (args.strict and bool(result.warnings))
    return 1 if failed else 0


def fill_asset_metadata(project, root):
    """Adds mimeType/width/height/duration to assets that lack them (never overwrites)."""
    for asset in project.get("assets", []) or []:
        src = os.path.join(root, *asset.get("path", "").split("/"))
        if not os.path.isfile(src):
            continue
        _, mime = kns_probe.guess(src)
        if mime and "mimeType" not in asset:
            asset["mimeType"] = mime
        if asset.get("type") == "image" and ("width" not in asset or "height" not in asset):
            size = kns_probe.image_size(src)
            if size:
                asset.setdefault("width", int(size[0]))
                asset.setdefault("height", int(size[1]))
        if asset.get("type") in ("video", "audio") and "duration" not in asset:
            d = kns_probe.mp4_duration(src)
            if d:
                asset["duration"] = round(d, 3)


def cmd_pack(args):
    root = args.dir
    project, files, _ = load_target(root)
    fill_asset_metadata(project, root)
    result = validate_project(project, kns_archive.list_dir_files(root), args.engine)
    print_result(root, result, False)
    if result.errors and not args.force:
        print("Not packed: fix the errors above (or use --force).", file=sys.stderr)
        return 1
    out = args.output or (os.path.normpath(root).rstrip(os.sep) + ".knsproj")
    thumb = None
    thumb_path = os.path.join(root, kns_archive.THUMBNAIL_ENTRY)
    if os.path.isfile(thumb_path):
        with open(thumb_path, "rb") as f:
            thumb = f.read()
    kns_archive.write_archive(project, root, out, thumbnail=thumb)
    size = os.path.getsize(out)
    print(f"Packed {out} ({size / 1024:.1f} KiB, {len(project.get('assets', []) or [])} asset(s))")
    return 0


def cmd_unpack(args):
    out = args.output or os.path.splitext(args.file)[0]
    if os.path.exists(out) and os.listdir(out):
        print(f"{out} already exists and is not empty", file=sys.stderr)
        return 2
    os.makedirs(out, exist_ok=True)
    kns_archive.read_archive(args.file, extract_to=out)
    print(f"Unpacked to {out}")
    return 0


def _fmt_time(t):
    return f"{t:.2f}s"


def cmd_info(args):
    project, files, _ = load_target(args.path)
    comp = project.get("composition", {})
    meta = project.get("meta", {})
    print(f"{meta.get('title', 'Untitled')}  —  {comp.get('width', 1080)}x{comp.get('height', 1920)} "
          f"@ {comp.get('fps', 30)} fps, {comp.get('duration', 10)} s, background {comp.get('background', '#0D0D12')}")
    assets = project.get("assets", []) or []
    if assets:
        print(f"Assets ({len(assets)}):")
        for a in assets:
            present = "" if files is None or a.get("path") in files else "  [MISSING]"
            print(f"  {a.get('id')}: {a.get('type')} {a.get('path')}{present}")
    print("Layers (bottom to top):")

    def walk(layers, depth, offset):
        for l in layers:
            start = l.get("start", 0)
            dur = l.get("duration", 5)
            extra = []
            if l.get("parent"):
                extra.append(f"parent={l['parent']}")
            if l.get("matte"):
                extra.append(f"matte={l['matte'].get('layer')}")
            if l.get("visible") is False:
                extra.append("hidden")
            content = l.get(l.get("type"), {}) if isinstance(l.get(l.get("type")), dict) else {}
            if l.get("type") == "text":
                extra.append(repr(content.get("content", "Text"))[:40])
            elif l.get("type") == "object3d":
                extra.append(content.get("primitive", "cube"))
            elif l.get("type") == "shape":
                extra.append(content.get("kind", "rectangle"))
            print(f"  {'  ' * depth}- {l.get('id')} [{l.get('type')}] {l.get('name', '')!r} "
                  f"{_fmt_time(offset + start)}–{_fmt_time(offset + start + dur)} {' '.join(extra)}")
            if l.get("type") == "group":
                walk(l.get("layers", []) or [], depth + 1, offset + start)

    walk(project.get("layers", []) or [], 0, 0.0)
    return 0


def _now_iso():
    return datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def cmd_new(args):
    root = args.dir
    if os.path.exists(os.path.join(root, "project.json")):
        print(f"{root}/project.json already exists", file=sys.stderr)
        return 2
    if not re.fullmatch(r"#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})", args.background):
        print("background must be #RRGGBB or #RRGGBBAA", file=sys.stderr)
        return 2
    os.makedirs(os.path.join(root, "assets"), exist_ok=True)
    now = _now_iso()
    project = {
        "format": FORMAT_ID,
        "formatVersion": FORMAT_VERSION,
        "generator": f"knsproj CLI {VERSION}",
        "meta": {"title": args.title, "createdAt": now, "modifiedAt": now},
        "composition": {"width": args.width, "height": args.height, "fps": args.fps, "duration": args.duration,
                        "background": args.background.upper()},
        "assets": [],
        "layers": [],
    }
    with open(os.path.join(root, "project.json"), "w", encoding="utf-8") as f:
        f.write(kns_archive.dumps_project(project))
    print(f"Created {root}/project.json")
    return 0


def cmd_add_asset(args):
    root = args.dir
    pj = os.path.join(root, "project.json")
    with open(pj, encoding="utf-8-sig") as f:
        project = json.load(f)
    if not os.path.isfile(args.file):
        print(f"{args.file} does not exist", file=sys.stderr)
        return 2
    guessed_type, mime = kns_probe.guess(args.file)
    atype = args.type or guessed_type
    if atype not in ("image", "video", "audio", "model", "font"):
        print("cannot infer the asset type; pass --type", file=sys.stderr)
        return 2
    taken = {a.get("id") for a in project.get("assets", []) or []}
    base_id = args.id or re.sub(r"[^a-zA-Z0-9_-]+", "_", os.path.splitext(os.path.basename(args.file))[0]).strip("_").lower() or atype
    aid = base_id
    n = 2
    while aid in taken:
        aid = f"{base_id}_{n}"
        n += 1
    ext = args.file.rsplit(".", 1)[-1].lower() if "." in args.file else "bin"
    rel = f"assets/{aid}.{ext}"
    os.makedirs(os.path.join(root, "assets"), exist_ok=True)
    shutil.copyfile(args.file, os.path.join(root, "assets", f"{aid}.{ext}"))
    entry = {"id": aid, "type": atype, "path": rel, "name": os.path.basename(args.file)}
    if mime:
        entry["mimeType"] = mime
    project.setdefault("assets", []).append(entry)
    fill_asset_metadata(project, root)
    with open(pj, "w", encoding="utf-8") as f:
        f.write(kns_archive.dumps_project(project))
    print(aid)
    return 0


def cmd_schema(_args):
    print(SCHEMA_PATH)
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="knsproj", description="Validate, pack and inspect Kinesis projects (.knsproj).")
    parser.add_argument("--version", action="version", version=f"knsproj {VERSION} (format v{FORMAT_VERSION})")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("validate", help="validate a project directory, project.json or .knsproj")
    p.add_argument("path")
    p.add_argument("--json", action="store_true", help="machine-readable output")
    p.add_argument("--strict", action="store_true", help="treat warnings as errors")
    p.add_argument("--engine", choices=["builtin", "jsonschema"], default="builtin")
    p.set_defaults(func=cmd_validate)

    p = sub.add_parser("pack", help="validate a project directory and write a .knsproj")
    p.add_argument("dir")
    p.add_argument("-o", "--output")
    p.add_argument("--force", action="store_true", help="pack even if validation fails")
    p.add_argument("--engine", choices=["builtin", "jsonschema"], default="builtin")
    p.set_defaults(func=cmd_pack)

    p = sub.add_parser("unpack", help="extract a .knsproj into a directory")
    p.add_argument("file")
    p.add_argument("-o", "--output")
    p.set_defaults(func=cmd_unpack)

    p = sub.add_parser("info", help="summarize a project")
    p.add_argument("path")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("new", help="create an empty project directory")
    p.add_argument("dir")
    p.add_argument("--title", default="Untitled")
    p.add_argument("--width", type=int, default=1080)
    p.add_argument("--height", type=int, default=1920)
    p.add_argument("--fps", type=float, default=30)
    p.add_argument("--duration", type=float, default=10)
    p.add_argument("--background", default="#0D0D12")
    p.set_defaults(func=cmd_new)

    p = sub.add_parser("add-asset", help="copy a media file into a project and register it")
    p.add_argument("dir")
    p.add_argument("file")
    p.add_argument("--id")
    p.add_argument("--type", choices=["image", "video", "audio", "model", "font"])
    p.set_defaults(func=cmd_add_asset)

    p = sub.add_parser("schema", help="print the path of the official JSON Schema")
    p.set_defaults(func=cmd_schema)

    args = parser.parse_args(argv)
    if getattr(args, "fps", None) is not None and float(args.fps).is_integer():
        args.fps = int(args.fps)
    if getattr(args, "duration", None) is not None and float(args.duration).is_integer():
        args.duration = int(args.duration)
    try:
        return args.func(args)
    except json.JSONDecodeError as e:
        print(f"INVALID: project.json is not valid JSON: {e}", file=sys.stderr)
        return 1
    except kns_archive.ArchiveError as e:
        print(f"INVALID: {e}", file=sys.stderr)
        return 1
    except OSError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

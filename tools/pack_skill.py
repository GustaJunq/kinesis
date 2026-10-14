"""Builds .claude/skills/knsproj/knsproj-skill.zip, a self-contained, installable copy of the /knsproj skill.

The zip holds the skill folder (SKILL.md and examples/) plus the CLI (tools/knsproj), the JSON Schema and the format
specification, laid out so that `python3 tools/knsproj ...` from SKILL.md works after unzipping into ~/.claude/skills/.
The archive is deterministic (fixed timestamps, sorted entries), so the test suite can check that it is up to date.

Usage: python3 tools/pack_skill.py [--check]
"""

import os
import sys
import zipfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SKILL_DIR = os.path.join(ROOT, ".claude", "skills", "knsproj")
ZIP_PATH = os.path.join(SKILL_DIR, "knsproj-skill.zip")
PREFIX = "knsproj/"
FIXED_DATE = (1980, 1, 1, 0, 0, 0)
SKIP_DIRS = {"__pycache__"}
STORED_EXT = {".mp4", ".m4a", ".mp3", ".png", ".jpg", ".glb", ".zip"}


def _walk(base, rel_prefix):
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name.endswith(".pyc"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            yield rel_prefix + rel, full


def entries():
    """Sorted (archive name, absolute path) pairs that go into the zip."""
    items = [(PREFIX + "SKILL.md", os.path.join(SKILL_DIR, "SKILL.md"))]
    items += list(_walk(os.path.join(SKILL_DIR, "examples"), PREFIX + "examples/"))
    items += list(_walk(os.path.join(ROOT, "tools", "knsproj"), PREFIX + "tools/knsproj/"))
    items.append((PREFIX + "schema/knsproj-v1.schema.json", os.path.join(ROOT, "schema", "knsproj-v1.schema.json")))
    items.append((PREFIX + "docs/knsproj.md", os.path.join(ROOT, "docs", "knsproj.md")))
    return sorted(items)


def build(out_path=ZIP_PATH):
    with zipfile.ZipFile(out_path, "w") as z:
        for name, full in entries():
            info = zipfile.ZipInfo(name, FIXED_DATE)
            info.external_attr = 0o644 << 16
            stored = os.path.splitext(name)[1].lower() in STORED_EXT
            info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
            with open(full, "rb") as f:
                z.writestr(info, f.read())
    return out_path


def is_up_to_date(zip_path=ZIP_PATH):
    if not os.path.isfile(zip_path):
        return False
    with zipfile.ZipFile(zip_path) as z:
        if z.namelist() != [n for n, _ in entries()]:
            return False
        for name, full in entries():
            with open(full, "rb") as f:
                if z.read(name) != f.read():
                    return False
    return True


if __name__ == "__main__":
    if "--check" in sys.argv:
        ok = is_up_to_date()
        print("up to date" if ok else "OUT OF DATE: run python3 tools/pack_skill.py")
        sys.exit(0 if ok else 1)
    print("wrote", build())

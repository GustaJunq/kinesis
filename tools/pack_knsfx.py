#!/usr/bin/env python3
"""Packs examples/knsfx/src/<name>/ into examples/knsfx/<name>.knsfx (deterministic ZIP: fixed dates, sorted)."""
import os, sys, zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "examples", "knsfx")

def pack(name):
    src = os.path.join(ROOT, "src", name)
    out = os.path.join(ROOT, name + ".knsfx")
    files = sorted(f for f in os.listdir(src) if os.path.isfile(os.path.join(src, f)))
    files.remove("manifest.json")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for f in ["manifest.json"] + files:  # manifest first: file managers and the app spot it quickly
            info = zipfile.ZipInfo(f, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(os.path.join(src, f), "rb") as fh:
                z.writestr(info, fh.read())
    print("wrote", os.path.relpath(out))

if __name__ == "__main__":
    for n in sys.argv[1:] or sorted(os.listdir(os.path.join(ROOT, "src"))):
        pack(n)

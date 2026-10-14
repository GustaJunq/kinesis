#!/usr/bin/env python3
"""Compiles every GLSL ES shader of the Android app with glslangValidator.

Expands `//#include <file>` lines exactly like ShaderLibrary.kt does at runtime.
Usage: python3 tools/check_shaders.py   (requires glslangValidator, e.g. `apt install glslang-tools`)
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SHADERS = os.path.join(ROOT, "app", "src", "main", "assets", "shaders")
INCLUDE = re.compile(r"^//#include\s+(\S+)\s*$", re.M)


def expand(name):
    with open(os.path.join(SHADERS, name), encoding="utf-8") as f:
        text = f.read()
    return INCLUDE.sub(lambda m: expand(m.group(1)), text)


def main():
    tool = shutil.which("glslangValidator")
    if tool is None:
        print("glslangValidator not found (apt install glslang-tools)", file=sys.stderr)
        return 2
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name in sorted(os.listdir(SHADERS)):
            if not name.endswith((".vert", ".frag")):
                continue
            path = os.path.join(tmp, name)
            with open(path, "w", encoding="utf-8") as f:
                f.write(expand(name))
            result = subprocess.run([tool, path], capture_output=True, text=True)
            ok = result.returncode == 0
            print(f"{'OK  ' if ok else 'FAIL'} {name}")
            if not ok:
                failed += 1
                print(result.stdout.replace(path, name))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

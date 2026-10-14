"""Tests for the knsproj CLI: schema validator, semantic checks (parity with the Kotlin validator),
container round trips and the CLI commands.

Run: python3 -m unittest discover -s tools/tests -v
"""

import io
import json
import os
import shutil
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CLI_DIR = os.path.join(ROOT, "tools", "knsproj")
sys.path.insert(0, CLI_DIR)

import kns_archive  # noqa: E402
import kns_schema  # noqa: E402
import kns_semantic  # noqa: E402

sys.path.insert(0, os.path.join(ROOT, "tools"))
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("knsproj_main", os.path.join(CLI_DIR, "__main__.py"))
cli = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cli)

FIXTURES = os.path.join(ROOT, "tools", "tests", "fixtures")
EXAMPLES = os.path.join(ROOT, ".claude", "skills", "knsproj", "examples")


def run_cli(*args):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = cli.main(list(args))
    return code, out.getvalue(), err.getvalue()


def minimal(**overrides):
    p = {"format": "knsproj", "formatVersion": 1,
         "composition": {"width": 1080, "height": 1920, "fps": 30, "duration": 10}, "layers": []}
    p.update(overrides)
    return p


class SchemaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = cli.load_schema()
        cls.validator = kns_schema.Validator(cls.schema)

    def test_schema_only_uses_supported_keywords(self):
        used = kns_schema.collect_keywords(self.schema)
        # Property names inside "properties"/"$defs" are not keywords; collect_keywords only walks keyword positions.
        unsupported = used - kns_schema.SUPPORTED_KEYWORDS
        self.assertEqual(set(), unsupported)

    def test_examples_are_valid(self):
        for name in sorted(os.listdir(EXAMPLES)):
            path = os.path.join(EXAMPLES, name)
            if not os.path.isdir(path):
                continue
            with self.subTest(example=name):
                project, files, _ = cli.load_target(path)
                result = cli.validate_project(project, files)
                self.assertEqual([], result.errors, result.errors)

    def test_kotlin_output_is_valid(self):
        """full-kotlin.json is written by the Kotlin serializer (see CrossLanguageTest.kt)."""
        with open(os.path.join(FIXTURES, "full-kotlin.json")) as f:
            project = json.load(f)
        result = cli.validate_project(project, None)
        self.assertEqual([], result.errors, result.errors)

    def test_typos_and_types_are_reported_with_paths(self):
        doc = minimal(layers=[{"id": "t", "type": "text", "text": {"content": "x", "fontSzie": 10}}])
        errors = self.validator.validate(doc)
        self.assertTrue(any("fontSzie" in e.path and "fontSize" in e.message for e in errors), errors)

        doc = minimal(layers=[{"id": "t", "type": "text", "text": {"color": "red"}}])
        errors = self.validator.validate(doc)
        self.assertTrue(any(e.path == "$.layers[0].text.color" for e in errors), errors)

        doc = minimal(layers=[{"id": "t", "type": "shape", "shape": {},
                               "transform": {"opacity": {"keyframes": [{"time": 0, "value": 1, "easing": "bouncy"}]}}}])
        errors = self.validator.validate(doc)
        self.assertTrue(any("easing" in e.path for e in errors), errors)

    def test_layer_type_requires_matching_content(self):
        errors = self.validator.validate(minimal(layers=[{"id": "c", "type": "object3d"}]))
        self.assertTrue(any("object3d" in e.message for e in errors), errors)

    def test_effects_are_discriminated_by_type(self):
        ok = minimal(layers=[{"id": "s", "type": "shape", "shape": {}, "effects": [{"type": "blur", "radius": 4}]}])
        self.assertEqual([], self.validator.validate(ok))
        bad = minimal(layers=[{"id": "s", "type": "shape", "shape": {}, "effects": [{"type": "blur", "amount": 4}]}])
        self.assertNotEqual([], self.validator.validate(bad))
        unknown = minimal(layers=[{"id": "s", "type": "shape", "shape": {}, "effects": [{"type": "sparkle"}]}])
        self.assertNotEqual([], self.validator.validate(unknown))


class SemanticParityTests(unittest.TestCase):
    """Same fixtures and expected codes as core/src/test/.../CrossLanguageTest.kt."""

    def test_invalid_fixtures_report_expected_codes(self):
        with open(os.path.join(FIXTURES, "invalid", "expected.json")) as f:
            expected = json.load(f)
        schema_validator = kns_schema.Validator(cli.load_schema())
        for name, codes in sorted(expected.items()):
            with self.subTest(fixture=name):
                with open(os.path.join(FIXTURES, "invalid", name)) as f:
                    project = json.load(f)
                self.assertEqual([], schema_validator.validate(project), "fixtures must be schema-valid")
                found = sorted({i.code for i in kns_semantic.validate(project, None) if i.severity == "error"})
                self.assertEqual(sorted(codes), found)


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def _project_with_asset(self):
        d = os.path.join(self.tmp, "proj")
        os.makedirs(os.path.join(d, "assets"))
        # 1x1 PNG
        png = bytes.fromhex("89504e470d0a1a0a0000000d4948445200000001000000010806000000"
                            "1f15c4890000000d49444154789c6360000002000100e221bc330000000049454e44ae426082")
        with open(os.path.join(d, "assets", "dot.png"), "wb") as f:
            f.write(png)
        project = minimal(assets=[{"id": "dot", "type": "image", "path": "assets/dot.png"}],
                          layers=[{"id": "img", "type": "image", "image": {"asset": "dot"}}])
        with open(os.path.join(d, "project.json"), "w") as f:
            json.dump(project, f)
        return d

    def test_pack_fills_metadata_and_round_trips(self):
        d = self._project_with_asset()
        out = os.path.join(self.tmp, "p.knsproj")
        code, stdout, _ = run_cli("pack", d, "-o", out)
        self.assertEqual(0, code, stdout)
        with zipfile.ZipFile(out) as z:
            names = z.namelist()
            self.assertEqual("project.json", names[0])
            self.assertEqual(zipfile.ZIP_STORED, z.getinfo("assets/dot.png").compress_type)
            packed = json.loads(z.read("project.json"))
        asset = packed["assets"][0]
        self.assertEqual((1, 1), (asset["width"], asset["height"]))
        self.assertEqual("image/png", asset["mimeType"])

        # Deterministic output.
        out2 = os.path.join(self.tmp, "p2.knsproj")
        run_cli("pack", d, "-o", out2)
        with open(out, "rb") as a, open(out2, "rb") as b:
            self.assertEqual(a.read(), b.read())

        code, stdout, _ = run_cli("validate", out)
        self.assertEqual(0, code, stdout)
        unpacked = os.path.join(self.tmp, "unpacked")
        self.assertEqual(0, run_cli("unpack", out, "-o", unpacked)[0])
        self.assertTrue(os.path.isfile(os.path.join(unpacked, "assets", "dot.png")))

    def test_pack_refuses_invalid_projects(self):
        d = self._project_with_asset()
        os.remove(os.path.join(d, "assets", "dot.png"))
        code, stdout, _ = run_cli("pack", d, "-o", os.path.join(self.tmp, "x.knsproj"))
        self.assertEqual(1, code)
        self.assertIn("asset.missingFile", stdout)

    def test_unsafe_archives_are_rejected(self):
        bad = os.path.join(self.tmp, "evil.knsproj")
        with zipfile.ZipFile(bad, "w") as z:
            z.writestr("project.json", json.dumps(minimal()))
            z.writestr("../escape.txt", "x")
        with self.assertRaises(kns_archive.ArchiveError):
            kns_archive.read_archive(bad, extract_to=os.path.join(self.tmp, "out"))
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "escape.txt")))

        nojson = os.path.join(self.tmp, "empty.knsproj")
        with zipfile.ZipFile(nojson, "w") as z:
            z.writestr("assets/a.png", "x")
        code, _, err = run_cli("validate", nojson)
        self.assertEqual(1, code)
        self.assertIn("project.json not found", err)

    def test_new_add_asset_info(self):
        d = os.path.join(self.tmp, "fresh")
        self.assertEqual(0, run_cli("new", d, "--title", "Fresh", "--width", "1920", "--height", "1080")[0])
        src = os.path.join(self.tmp, "song.mp3")
        with open(src, "wb") as f:
            f.write(b"ID3fake")
        code, stdout, _ = run_cli("add-asset", d, src)
        self.assertEqual(0, code)
        self.assertEqual("song", stdout.strip())
        with open(os.path.join(d, "project.json")) as f:
            project = json.load(f)
        self.assertEqual("audio", project["assets"][0]["type"])
        self.assertEqual(1920, project["composition"]["width"])
        code, stdout, _ = run_cli("validate", d)
        self.assertEqual(0, code, stdout)
        code, stdout, _ = run_cli("info", os.path.join(EXAMPLES, "basicos", "3d-camera-orbita"))
        self.assertEqual(0, code)
        self.assertIn("cube [object3d]", stdout)

    def test_version_and_header_checks(self):
        result = cli.validate_project(minimal(formatVersion=2), None)
        self.assertEqual(["format.version"], [i["code"] for i in result.errors])
        result = cli.validate_project({"format": "zip"}, None)
        self.assertEqual(["format.header"], [i["code"] for i in result.errors])

    def test_pretty_printer_matches_kotlin_style(self):
        text = kns_archive.dumps_project({"a": [1.0, 2.5], "b": {"c": []}, "d": [{"e": 1}]})
        self.assertEqual('{\n  "a": [1, 2.5],\n  "b": {\n    "c": []\n  },\n  "d": [\n    {\n      "e": 1\n    }\n  ]\n}\n', text)


if __name__ == "__main__":
    unittest.main()

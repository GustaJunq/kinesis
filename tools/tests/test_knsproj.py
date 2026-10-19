"""Tests for the knsproj CLI: schema validator, semantic checks (parity with the Kotlin validator),
container round trips and the CLI commands.

Run: python3 -m unittest discover -s tools/tests -v
"""

import io
import json
import os
import re
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
import kns_migrate  # noqa: E402
import kns_schema  # noqa: E402
import kns_semantic  # noqa: E402

sys.path.insert(0, os.path.join(ROOT, "tools"))
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location("knsproj_main", os.path.join(CLI_DIR, "__main__.py"))
cli = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cli)

FIXTURES = os.path.join(ROOT, "tools", "tests", "fixtures")
SKILL_DIR = os.path.join(ROOT, ".claude", "skills", "knsproj")
EXAMPLES = os.path.join(SKILL_DIR, "examples")


def run_cli(*args):
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = cli.main(list(args))
    return code, out.getvalue(), err.getvalue()


def minimal(**overrides):
    p = {"format": "knsproj", "formatVersion": cli.FORMAT_VERSION,
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
        for name, codes in sorted(expected.items()):
            with self.subTest(fixture=name):
                with open(os.path.join(FIXTURES, "invalid", name)) as f:
                    project = json.load(f)
                # The fixtures are format v1: schema of their version, semantics after migration (like the app).
                schema_validator = kns_schema.Validator(cli.load_schema(project["formatVersion"]))
                self.assertEqual([], schema_validator.validate(project), "fixtures must be schema-valid")
                migrated = kns_migrate.migrate(project, cli.FORMAT_VERSION)
                found = sorted({i.code for i in kns_semantic.validate(migrated, None) if i.severity == "error"})
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
        result = cli.validate_project(minimal(formatVersion=cli.FORMAT_VERSION + 1), None)
        self.assertEqual(["format.version"], [i["code"] for i in result.errors])
        result = cli.validate_project({"format": "zip"}, None)
        self.assertEqual(["format.header"], [i["code"] for i in result.errors])

    def test_pretty_printer_matches_kotlin_style(self):
        text = kns_archive.dumps_project({"a": [1.0, 2.5], "b": {"c": []}, "d": [{"e": 1}]})
        self.assertEqual('{\n  "a": [1, 2.5],\n  "b": {\n    "c": []\n  },\n  "d": [\n    {\n      "e": 1\n    }\n  ]\n}\n', text)


class EffectTests(unittest.TestCase):
    """The effect registry exported from the core drives the schema and the semantic checks."""

    def setUp(self):
        self.validator = kns_schema.Validator(cli.load_schema())

    def layer(self, *effects, ltype="shape"):
        content = {"shape": {}} if ltype == "shape" else {"object3d": {}}
        return minimal(layers=[dict({"id": "s", "type": ltype, "effects": list(effects)}, **content)])

    def test_every_registry_effect_with_defaults_is_valid(self):
        for spec in kns_semantic.REGISTRY["effects"]:
            with self.subTest(effect=spec["id"]):
                values = {p["name"]: p["default"] for p in spec["params"]}
                doc = self.layer(dict({"type": spec["id"]}, **values), ltype="object3d" if spec["target"] == "layer_3d" else "shape")
                result = cli.validate_project(doc, None)
                self.assertEqual([], result.errors + result.warnings)

    def test_thirteen_categories_in_order(self):
        names = [c["pt"] for c in kns_semantic.REGISTRY["categories"]]
        self.assertEqual(13, len(names))
        self.assertEqual(["Todos os efeitos", "Cor e luz"], names[:2])
        self.assertEqual("Utilitário e expressão", names[-1])

    def test_ranges_choices_and_layer_kind(self):
        codes = lambda doc: [i["code"] for i in cli.validate_project(doc, None).issues]
        self.assertIn("effect.range", codes(self.layer({"type": "pinchBulge", "amount": 1.5})))
        animated = {"type": "vignette", "amount": {"keyframes": [{"time": 0, "value": 0}, {"time": 1, "value": 3}]}}
        self.assertIn("effect.range", codes(self.layer(animated)))
        self.assertEqual(["schema"], codes(self.layer({"type": "warp", "mode": "spiral"}))[:1])
        self.assertIn("effect.layerKind", codes(self.layer({"type": "depthMap"})))
        self.assertEqual([], codes(self.layer({"type": "depthMap"}, ltype="object3d")))
        self.assertEqual(["schema"], codes(self.layer({"type": "colorAdjust"}))[:1], "colorAdjust no longer exists in v2")

    def test_v1_color_adjust_is_migrated(self):
        v1 = minimal(formatVersion=1, layers=[{"id": "s", "type": "shape", "shape": {}, "effects": [
            {"type": "colorAdjust", "enabled": False, "hue": {"keyframes": [{"time": 0, "value": 0}, {"time": 1, "value": 90}]}, "contrast": 0.3},
            {"type": "blur"}]}])
        self.assertEqual([], cli.validate_project(v1, None).issues)
        v2 = kns_migrate.migrate(v1, 2)
        effects = v2["layers"][0]["effects"]
        self.assertEqual(["hueShift", "brightnessContrast", "blur"], [e["type"] for e in effects])
        self.assertEqual({"type": "brightnessContrast", "enabled": False, "contrast": 0.3}, effects[1])
        self.assertEqual(90, effects[0]["hue"]["keyframes"][1]["value"])
        self.assertEqual([], cli.validate_project(v2, None).issues)
        self.assertEqual(1, v1["formatVersion"], "migration must not modify its input")


def example_dirs(group):
    base = os.path.join(EXAMPLES, group)
    return sorted(os.path.join(base, n) for n in os.listdir(base) if os.path.isfile(os.path.join(base, n, "project.json")))


def all_layers(layers):
    for layer in layers:
        yield layer
        yield from all_layers(layer.get("layers", []) or [])


class NullAndCameraTests(unittest.TestCase):
    """Format v4: null layers and cameras aimed by rotation (parity with the Kotlin validator)."""

    def codes(self, layers):
        result = cli.validate_project(minimal(layers=layers), None)
        return [i["code"] for i in result.issues]

    def test_null_parent_kinds_follow_the_dimension(self):
        shape = {"id": "c", "type": "shape", "shape": {}, "parent": "n"}
        cube = {"id": "c", "type": "object3d", "object3d": {}, "parent": "n"}
        cam = {"id": "c", "type": "camera", "camera": {}, "parent": "n"}
        n2 = {"id": "n", "type": "null"}
        n3 = {"id": "n", "type": "null", "null": {"threeD": True}}
        self.assertEqual([], self.codes([shape, n2]))
        self.assertEqual([], self.codes([cube, n3]))
        self.assertEqual([], self.codes([cam, n3]))
        self.assertEqual(["layer.parentKind"], self.codes([shape, n3]))
        self.assertEqual(["layer.parentKind"], self.codes([cube, n2]))
        self.assertEqual(["layer.parentKind"], self.codes([{"id": "x", "type": "object3d", "object3d": {}, "parent": "cam"}, {"id": "cam", "type": "camera", "camera": {}}]))

    def test_camera_rotation_and_aim_are_valid_and_checked(self):
        cam = {"id": "cam", "type": "camera", "camera": {"aim": "rotation", "position": [0, 0, -2000], "rotation": [0, 20, 0]}}
        self.assertEqual([], self.codes([cam]))
        bad = {"id": "cam", "type": "camera", "camera": {"aim": "orbit"}}
        self.assertEqual(["schema"], self.codes([bad])[:1])
        keys = {"id": "cam", "type": "camera", "camera": {"rotation": {"keyframes": [{"time": 1, "value": [0, 0, 0]}, {"time": 1, "value": [0, 9, 0]}]}}}
        self.assertIn("keyframe.order", self.codes([keys]))

    def test_null_object_on_another_layer_is_a_warning(self):
        self.assertIn("layer.nullContent", self.codes([{"id": "s", "type": "shape", "shape": {}, "null": {}}]))

    def test_version_3_documents_migrate_to_4(self):
        v3 = minimal(formatVersion=3, layers=[{"id": "c", "type": "camera", "camera": {"target": [0, 0, 0]}}])
        migrated = kns_migrate.migrate(v3, 4)
        self.assertEqual(4, migrated["formatVersion"])
        self.assertEqual([], [i["code"] for i in cli.validate_project(v3, None).errors])


class CyclicEasingTests(unittest.TestCase):
    """Format v5: the cyclic (wave) easing."""

    def doc(self, easing):
        return minimal(layers=[{"id": "s", "type": "shape", "shape": {}, "transform": {"rotation": {"keyframes": [
            {"time": 0, "value": 0, "easing": easing}, {"time": 2, "value": 90}]}}}])

    def codes(self, easing):
        return [i["code"] for i in cli.validate_project(self.doc(easing), None).issues]

    def test_cyclic_easing_is_valid_and_optional_fields_work(self):
        self.assertEqual([], self.codes({"cyclic": {"offset": 0.1, "cycles": 2.5}}))
        self.assertEqual([], self.codes({"cyclic": {}}))

    def test_cyclic_easing_ranges_and_typos_are_rejected(self):
        self.assertEqual("schema", self.codes({"cyclic": {"cycles": 0.2}})[0])
        self.assertEqual("schema", self.codes({"cyclic": {"cycles": 100}})[0])
        self.assertEqual("schema", self.codes({"cyclic": {"offset": 2}})[0])
        self.assertEqual("schema", self.codes({"cyclic": {"cycle": 3}})[0])

    def test_version_4_documents_migrate_to_5(self):
        v4 = minimal(formatVersion=4)
        self.assertEqual(5, kns_migrate.migrate(v4, 5)["formatVersion"])
        self.assertEqual([], [i["code"] for i in cli.validate_project(v4, None).errors])


class Format6Tests(unittest.TestCase):
    """Format v6: shading, shadows, depth of field, environment, 3D text and packed alpha (parity with Kotlin)."""

    def issues(self, layers, composition=None, assets=None):
        doc = minimal(layers=layers)
        if composition:
            doc["composition"].update(composition)
        if assets:
            doc["assets"] = assets
        return [i["code"] for i in cli.validate_project(doc, None).issues]

    def test_new_fields_are_valid(self):
        layers = [
            {"id": "t", "type": "object3d", "object3d": {"primitive": "text", "text": {"content": "Oi", "depth": 40, "bevel": 4},
                                                         "material": {"shading": "toon"}}},
            {"id": "c", "type": "camera", "camera": {"dof": {"focusDistance": 2000, "blur": 20}}},
            {"id": "l", "type": "light", "light": {"castShadows": True, "shadowDarkness": 0.5, "shadowSoftness": 2}},
        ]
        assets = [{"id": "pano", "type": "image", "path": "assets/pano.jpg"}]
        self.assertEqual([], self.issues(layers, {"environment": {"asset": "pano", "intensity": 2, "rotation": 45}}, assets))

    def test_ranges_and_references(self):
        bad_text = {"id": "t", "type": "object3d", "object3d": {"primitive": "text", "text": {"depth": 10, "bevel": 6}}}
        self.assertIn("text3d.range", self.issues([bad_text]))
        unused = {"id": "t", "type": "object3d", "object3d": {"primitive": "cube", "text": {}}}
        self.assertIn("object3d.text", self.issues([unused]))
        self.assertIn("layer.missingAsset", self.issues([], {"environment": {"asset": "nope"}}))
        self.assertIn("schema", self.issues([], {"environment": {"intensity": 20}}))
        soft = {"id": "l", "type": "light", "light": {"shadowSoftness": 20}}
        self.assertIn("schema", self.issues([soft]))
        self.assertEqual("schema", self.issues([{"id": "s", "type": "object3d", "object3d": {"material": {"shading": "cel"}}}])[0])

    def test_version_5_documents_migrate_to_6(self):
        self.assertEqual(6, kns_migrate.migrate(minimal(formatVersion=5), 6)["formatVersion"])


class Format7Tests(unittest.TestCase):
    """Format v7: cyclic.offset is the skew of each cycle; the old shift is cyclic.phase."""

    def test_v6_offset_becomes_phase(self):
        layer = {"id": "a", "type": "shape", "shape": {}, "transform": {"rotation": {"keyframes": [
            {"time": 0, "value": 0, "easing": {"cyclic": {"offset": 0.1, "cycles": 2.5}}},
            {"time": 1, "value": 90, "easing": {"cyclic": {"offset": 0}}}]}}}
        out = kns_migrate.migrate(minimal(formatVersion=6, layers=[layer]), 7)
        kf = out["layers"][0]["transform"]["rotation"]["keyframes"]
        self.assertEqual({"cyclic": {"cycles": 2.5, "phase": 0.1}}, kf[0]["easing"])
        self.assertEqual({"cyclic": {}}, kf[1]["easing"])
        self.assertEqual(7, out["formatVersion"])

    def test_skew_and_phase_are_valid(self):
        layer = {"id": "a", "type": "shape", "shape": {}, "transform": {"rotation": {"keyframes": [
            {"time": 0, "value": 0, "easing": {"cyclic": {"offset": -0.8, "cycles": 3.5, "phase": 0.1}}},
            {"time": 1, "value": 90}]}}}
        self.assertEqual([], [i["code"] for i in cli.validate_project(minimal(layers=[layer]), None).issues])


class Format8Tests(unittest.TestCase):
    """Format v8: timeline markers and the equalizer of audio/video clips (parity with Kotlin)."""

    def codes(self, doc):
        return [i["code"] for i in cli.validate_project(doc, None).issues]

    def test_markers_and_eq_are_valid(self):
        doc = minimal(markers=[{"id": "m1", "time": 1.5, "name": "Verso", "color": "#FFC857"}, {"id": "m2", "time": 8}])
        self.assertEqual([], self.codes(doc))
        audio = {"id": "a", "type": "audio", "audio": {"asset": "x", "eq": [{"type": "lowShelf", "frequency": 80, "gain": -6, "q": 0.7}]}}
        doc = minimal(layers=[audio], assets=[{"id": "x", "type": "audio", "path": "assets/x.m4a"}])
        self.assertEqual([], self.codes(doc))

    def test_invalid_markers_and_bands(self):
        self.assertEqual(["marker.duplicateId"], self.codes(minimal(markers=[{"id": "x", "time": 1}, {"id": "x", "time": 2}])))
        self.assertIn("schema", self.codes(minimal(markers=[{"id": "x", "time": -2}])))
        bad = {"id": "a", "type": "audio", "audio": {"asset": "x", "eq": [{"frequency": 5, "gain": 40, "q": 50}]}}
        doc = minimal(layers=[bad], assets=[{"id": "x", "type": "audio", "path": "assets/x.m4a"}])
        self.assertIn("schema", self.codes(doc))  # the schema bounds the ranges; Kotlin reports audio.eq for the same input

    def test_version_7_documents_migrate_to_8(self):
        self.assertEqual(8, kns_migrate.migrate(minimal(formatVersion=7), 8)["formatVersion"])


class Format9Tests(unittest.TestCase):
    """Format v9: the editor view of the viewport and the TimeSlice effect."""

    def codes(self, doc):
        return [i["code"] for i in cli.validate_project(doc, None).issues]

    def test_editor_view_is_valid(self):
        view = {"mode": "editor", "target": [0, 0, 0], "yaw": 28, "pitch": 18, "distance": 2600, "fov": 40}
        self.assertEqual([], self.codes(minimal(editorView=view)))
        self.assertEqual([], self.codes(minimal(editorView={})))

    def test_invalid_editor_view(self):
        self.assertIn("schema", self.codes(minimal(editorView={"mode": "orbit"})))
        self.assertIn("schema", self.codes(minimal(editorView={"distance": 5})))
        self.assertIn("schema", self.codes(minimal(editorView={"pitch": 120})))

    def test_time_slice_effect(self):
        layer = {"id": "s", "type": "shape", "shape": {"kind": "rectangle"}, "effects": [
            {"type": "timeSlice", "slices": 8, "angle": 0, "offset": 3, "order": "center", "smooth": 0.5}]}
        self.assertEqual([], self.codes(minimal(layers=[layer])))
        layer["effects"][0]["order"] = "sideways"
        self.assertIn("schema", self.codes(minimal(layers=[layer])))

    def roto_project(self, roto):
        assets = [{"id": "src", "type": "video", "path": "assets/a.mp4"}, {"id": "cut", "type": "video", "path": "assets/b.mp4"}]
        layer = {"id": "v", "type": "video", "video": {"asset": "cut", "alphaPacked": True, "roto": roto}}
        return minimal(assets=assets, layers=[layer])

    def test_roto_brush(self):
        good = {"source": "src", "sourceStart": 0.5, "duration": 3, "frames": [
            {"time": 0, "strokes": [{"mode": "add", "size": 0.05, "points": [[0.4, 0.5], [0.5, 0.55]]}]}]}
        self.assertEqual([], self.codes(self.roto_project(good)))
        self.assertEqual(["roto.source"], self.codes(self.roto_project({**good, "source": "cut2"})))
        self.assertEqual(["roto.time"], self.codes(self.roto_project({**good, "frames": [{"time": 9, "strokes": []}]})))
        # The schema bounds the ranges and reports first; Kotlin reports roto.stroke / roto.range for the same input.
        self.assertIn("schema", self.codes(self.roto_project({**good, "frames": [{"time": 0, "strokes": [{"points": [[2, 0.5]]}]}]})))
        self.assertIn("schema", self.codes(self.roto_project({**good, "frames": [{"time": 0, "strokes": [{"mode": "paint", "points": [[0.5, 0.5]]}]}]})))
        self.assertIn("schema", self.codes(self.roto_project({**good, "duration": 0.5, "sourceStart": -1})))

    def test_version_8_documents_migrate_to_9(self):
        migrated = kns_migrate.migrate(minimal(formatVersion=8), 9)
        self.assertEqual(9, migrated["formatVersion"])
        self.assertNotIn("editorView", migrated)


class FormatV10Tests(unittest.TestCase):
    """Format v10: the retro and glitch effects. Only effects were added, so v9 documents open unchanged."""

    def test_version_9_documents_migrate_to_10(self):
        migrated = kns_migrate.migrate(minimal(formatVersion=9), 10)
        self.assertEqual(10, migrated["formatVersion"])

    def test_current_version_is_10(self):
        self.assertEqual(10, cli.FORMAT_VERSION)


class ExampleTests(unittest.TestCase):
    """The skill's examples: basicos/ and receitas/ must be clean, erros/ must fail with the documented code."""

    VALID_GROUPS = ("basicos", "receitas", "efeitos")
    README_SECTIONS = ("## O que demonstra", "## Quando usar", "## Armadilhas")
    EXPECTED = re.compile(r"\*\*C\u00f3digo esperado:\*\* `([A-Za-z.]+)`")

    def test_expected_examples_exist(self):
        self.assertEqual(30, len(example_dirs("basicos")))
        self.assertGreaterEqual(len(example_dirs("receitas")), 5)
        self.assertGreaterEqual(len(example_dirs("erros")), 5)
        self.assertEqual(12, len(example_dirs("efeitos")))

    def test_effect_examples_cover_every_category(self):
        category_of = {e["id"]: e["category"] for e in kns_semantic.REGISTRY["effects"]}
        covered = set()
        for path in example_dirs("efeitos"):
            project, _, _ = cli.load_target(path)
            for layer in all_layers(project.get("layers", [])):
                covered.update(category_of[e["type"]] for e in layer.get("effects", []) or [])
        self.assertEqual(set(category_of.values()), covered)

    def test_basicos_and_receitas_validate_without_errors_or_warnings(self):
        for group in self.VALID_GROUPS:
            for path in example_dirs(group):
                with self.subTest(example=f"{group}/{os.path.basename(path)}"):
                    project, files, _ = cli.load_target(path)
                    result = cli.validate_project(project, files)
                    self.assertEqual([], result.errors, result.errors)
                    self.assertEqual([], result.warnings, result.warnings)
                    code, stdout, _ = run_cli("validate", path)
                    self.assertEqual(0, code, stdout)

    def test_every_example_has_a_readme_and_readable_layer_ids(self):
        for group in self.VALID_GROUPS + ("erros",):
            for path in example_dirs(group):
                name = f"{group}/{os.path.basename(path)}"
                with self.subTest(example=name):
                    with open(os.path.join(path, "README.md"), encoding="utf-8") as f:
                        readme = f.read()
                    if group != "erros":
                        for section in self.README_SECTIONS:
                            self.assertIn(section, readme)
                    project, _, _ = cli.load_target(path)
                    for layer in all_layers(project.get("layers", [])):
                        self.assertIsNone(re.fullmatch(r"(layer|camada|l)[-_]?\d+", layer["id"]), f"generic layer id {layer['id']!r}")

    def test_error_examples_fail_with_exactly_the_documented_code(self):
        for path in example_dirs("erros"):
            name = os.path.basename(path)
            with self.subTest(example=name):
                with open(os.path.join(path, "README.md"), encoding="utf-8") as f:
                    match = self.EXPECTED.search(f.read())
                self.assertIsNotNone(match, "README must state **C\u00f3digo esperado:** `code`")
                project, files, _ = cli.load_target(path)
                result = cli.validate_project(project, files)
                self.assertEqual([match.group(1)], [i["code"] for i in result.errors], result.errors)
                code, _, _ = run_cli("validate", path)
                self.assertEqual(1, code)

    def test_skill_zip_is_up_to_date(self):
        sys.path.insert(0, os.path.join(ROOT, "tools"))
        import pack_skill
        self.assertTrue(pack_skill.is_up_to_date(), "run: python3 tools/pack_skill.py")
        with zipfile.ZipFile(pack_skill.ZIP_PATH) as z:
            names = z.namelist()
        self.assertIn("SKILL.md", names)
        self.assertIn("tools/knsproj/__main__.py", names)
        self.assertIn("schema/knsproj-v1.schema.json", names)
        self.assertIn("schema/knsproj-v3.schema.json", names)
        self.assertIn("docs/expressoes.md", names)
        self.assertFalse([n for n in names if n.startswith("knsproj/")], "the zip must not wrap everything in a knsproj/ folder")
        self.assertFalse([n for n in names if "__pycache__" in n or n.endswith(".pyc")])


try:
    import numpy  # noqa: F401
    import PIL  # noqa: F401
    HAVE_PREVIEW_DEPS = True
except ImportError:
    HAVE_PREVIEW_DEPS = False


class EvalTests(unittest.TestCase):
    """The pure-Python evaluator used by `preview` mirrors the Kotlin core."""

    def setUp(self):
        import kns_eval
        self.ev = kns_eval

    def test_easing_matches_kotlin_presets(self):
        ease = self.ev.ease
        self.assertEqual(ease("hold", 0.99), 0.0)
        self.assertAlmostEqual(ease("linear", 0.3), 0.3, places=4)
        self.assertAlmostEqual(ease("easeOutBounce", 0.5), 0.765625, places=4)
        self.assertGreater(ease("easeOutBack", 0.6), 1.0)
        cyc = {"cyclic": {"offset": 0, "cycles": 3.5}}
        self.assertAlmostEqual(ease(cyc, 1 / 3.5 * 0.5), 1.0, places=4)
        self.assertAlmostEqual(ease(cyc, 1 / 3.5), 0.0, places=4)

    def test_keyframes_loop_and_relative_time(self):
        raw = {"keyframes": [{"time": 0, "value": [0, 0]}, {"time": 2, "value": [100, 50]}], "loop": "pingpong"}
        self.assertEqual(self.ev.v2(raw, (0.0, 0.0), 1), (50.0, 25.0))
        self.assertEqual(self.ev.v2(raw, (0.0, 0.0), 3), (50.0, 25.0))
        self.assertEqual(self.ev.v2(raw, (0.0, 0.0), 4), (0.0, 0.0))

    def test_parenting_and_layer_time(self):
        p = minimal(layers=[
            {"id": "pai", "type": "null", "start": 1, "duration": 9,
             "transform": {"position": {"keyframes": [{"time": 0, "value": [0, 0]}, {"time": 1, "value": [100, 0]}]}}},
            {"id": "filho", "type": "shape", "start": 0, "duration": 10, "parent": "pai", "transform": {"position": [0, -50]},
             "shape": {"kind": "ellipse", "size": [10, 10]}},
        ])
        scene = self.ev.evaluate(p, 1.5)
        node = scene["nodes"][0]
        self.assertEqual(node["id"], "filho")
        self.assertAlmostEqual(node["matrix"][4], 50.0, places=3)
        self.assertAlmostEqual(node["matrix"][5], -50.0, places=3)

    def test_default_camera_projects_z0_at_composition_scale(self):
        p = minimal()
        comp = self.ev.composition(p)
        cam = self.ev.evaluate_camera(p, comp, 0)
        x, y = self.ev.project_point(cam, comp, (200.0, -100.0, 0.0))
        self.assertAlmostEqual(x, 200.0, places=2)
        self.assertAlmostEqual(y, -100.0, places=2)


@unittest.skipUnless(HAVE_PREVIEW_DEPS, "preview needs Pillow and numpy")
class PreviewTests(unittest.TestCase):
    def test_every_example_renders(self):
        tmp = tempfile.mkdtemp()
        try:
            for group in ("basicos", "receitas"):
                for name in sorted(os.listdir(os.path.join(EXAMPLES, group))):
                    d = os.path.join(EXAMPLES, group, name)
                    if not os.path.isdir(d):
                        continue
                    out = os.path.join(tmp, f"{name}.png")
                    code, stdout, err = run_cli("preview", d, "--frames", "2", "--scale", "0.15", "-o", out, "--json")
                    self.assertEqual(code, 0, f"{name}: {err}")
                    self.assertTrue(os.path.isfile(out), name)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    def test_shape_lands_where_the_transform_puts_it(self):
        from PIL import Image
        tmp = tempfile.mkdtemp()
        try:
            p = minimal(composition={"width": 400, "height": 200, "fps": 30, "duration": 2, "background": "#000000"}, layers=[
                {"id": "q", "type": "shape", "duration": 2, "transform": {"position": [100, 0]},
                 "shape": {"kind": "rectangle", "size": [40, 40], "fill": {"color": "#FF0000"}}}])
            pj = os.path.join(tmp, "project.json")
            with open(pj, "w") as f:
                json.dump(p, f)
            out = os.path.join(tmp, "f.png")
            code, _, err = run_cli("preview", tmp, "-t", "1", "--scale", "1", "-o", out)
            self.assertEqual(code, 0, err)
            im = Image.open(out).convert("RGB")
            self.assertEqual(im.size, (400, 200))
            self.assertGreater(im.getpixel((300, 100))[0], 240)
            self.assertLess(im.getpixel((200, 100))[0], 10)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()

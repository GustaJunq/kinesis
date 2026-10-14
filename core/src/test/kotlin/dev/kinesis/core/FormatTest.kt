package dev.kinesis.core

import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.format.KnsprojArchive
import dev.kinesis.core.format.KnsprojFormatException
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.format.SafeFile
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.FORMAT_VERSION
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Matte
import dev.kinesis.core.model.Object3DContent
import dev.kinesis.core.model.ShapeContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FormatTest {

    @Test
    fun jsonRoundTripPreservesEverything() {
        val project = TestProjects.full()
        val text = KnsprojJson.encode(project)
        val back = KnsprojJson.decode(text)
        assertEquals(project, back)
        // Encoding is deterministic.
        assertEquals(text, KnsprojJson.encode(back))
    }

    @Test
    fun staticValuesAreWrittenPlainAndAnimatedOnesAsKeyframes() {
        val json = KnsprojJson.encodeToElement(TestProjects.full()).jsonObject
        val star = json["layers"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == "star" }.jsonObject
        val transform = star["transform"]!!.jsonObject
        assertTrue("rotation is animated", transform["rotation"] is JsonObject)
        val pos = transform["position"]!!.jsonObject["keyframes"]!!.jsonArray[0].jsonObject
        assertEquals("easeInOutCubic", pos["easing"]!!.jsonPrimitive.content)
        val shape = star["shape"]!!.jsonObject
        assertEquals("6", shape["sides"]!!.jsonPrimitive.content)
        assertEquals("star", shape["kind"]!!.jsonPrimitive.content)
        // Defaults are omitted to keep files small.
        assertFalse("blendMode default omitted", star.containsKey("blendMode"))
        assertTrue(json.containsKey("format"))
        assertEquals(FORMAT_VERSION.toString(), json["formatVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun readsHandWrittenDocument() {
        val text = """
            {
              "format": "knsproj", "formatVersion": 1,
              "composition": {"width": 1080, "height": 1920, "fps": 30, "duration": 10, "background": "#0A1A3F"},
              "layers": [
                {"id": "t", "type": "text", "start": 0, "duration": 4,
                 "transform": {"position": [0, -200], "opacity": {"keyframes": [{"time": 0, "value": 0, "easing": "easeOutCubic"}, {"time": 1, "value": 1}]}},
                 "text": {"content": "Hi", "fontSize": 140, "color": "#FFC857"}},
                {"id": "c", "type": "object3d", "duration": 10,
                 "transform3d": {"rotation": {"keyframes": [{"time": 0, "value": [0, 0, 0]}, {"time": 10, "value": [0, 720, 0], "easing": {"cubicBezier": [0.3, 0, 0.7, 1]}}], "loop": "cycle"}},
                 "object3d": {"primitive": "cube", "size": 400, "material": {"color": "#C0C4CC", "metallic": 1, "roughness": 0.2}},
                 "futureField": {"ignored": true}}
              ]
            }
        """.trimIndent()
        val p = KnsprojJson.decode(text)
        assertEquals(Color.parse("#0A1A3F"), p.composition.background)
        val t = p.layers[0]
        assertEquals(Vec2(0f, -200f), t.transform.position.value)
        assertEquals(Easing.Preset(dev.kinesis.core.anim.EasingPreset.EASE_OUT_CUBIC), t.transform.opacity.keyframes[0].easing)
        val c = p.layers[1]
        assertEquals(LoopMode.CYCLE, c.transform3d.rotation.loop)
        assertEquals(Easing.CubicBezier(0.3f, 0f, 0.7f, 1f), c.transform3d.rotation.keyframes[1].easing)
        assertEquals(1f, c.object3d!!.material.metallic.value, 0f)
        assertTrue(ProjectValidator.validate(p).isValid)
    }

    @Test
    fun rejectsNewerVersionsAndForeignDocuments() {
        expectFormatError("""{"format":"knsproj","formatVersion":99,"composition":{}}""", "version 99")
        expectFormatError("""{"format":"other","formatVersion":1}""", "Not a Kinesis project")
        expectFormatError("""[1,2]""", "JSON object")
        expectFormatError("""{"format":"knsproj","formatVersion":1,"composition":{"background":"blue"}}""", "invalid colour")
        expectFormatError(
            """{"format":"knsproj","formatVersion":1,"layers":[{"id":"a","type":"text","transform":{"opacity":{"keyframes":[{"time":0,"value":1,"easing":"bouncy"}]}}}]}""",
            "unknown easing",
        )
        expectFormatError(
            """{"format":"knsproj","formatVersion":1,"layers":[{"id":"a","type":"text","transform":{"opacity":{"keyframes":[{"time":2,"value":1},{"time":1,"value":0}]}}}]}""",
            "sorted",
        )
    }

    private fun expectFormatError(json: String, fragment: String) {
        try {
            KnsprojJson.decode(json)
            fail("expected failure for $json")
        } catch (e: KnsprojFormatException) {
            assertTrue("'${e.message}' should mention '$fragment'", e.message!!.contains(fragment, ignoreCase = true))
        }
    }

    @Test
    fun archiveRoundTripWithAssets() {
        val project = TestProjects.full()
        val files = TestProjects.fakeAssetFiles(project)
        val out = ByteArrayOutputStream()
        KnsprojArchive.write(project, { a -> files[a.path]?.let(::ByteArrayInputStream) }, out, thumbnailJpeg = byteArrayOf(1, 2, 3))

        // Media is stored, JSON is deflated.
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            val methods = generateSequence { zip.nextEntry }.associate { it.name to it.method }
            assertEquals(ZipEntry.DEFLATED, methods["project.json"])
            assertEquals(ZipEntry.STORED, methods["assets/vid.mp4"])
            assertTrue("thumbnail.jpg" in methods)
        }

        val received = HashMap<String, ByteArray>()
        val result = KnsprojArchive.read(ByteArrayInputStream(out.toByteArray())) { path, data -> received[path] = data.readBytes() }
        assertEquals(project, result.project)
        for ((path, bytes) in files) assertTrue(path, bytes.contentEquals(received[path]))
        assertTrue(ProjectValidator.validate(result.project, result.entries).isValid)

        // Archives are reproducible byte for byte.
        val again = ByteArrayOutputStream()
        KnsprojArchive.write(project, { a -> files[a.path]?.let(::ByteArrayInputStream) }, again, thumbnailJpeg = byteArrayOf(1, 2, 3))
        assertTrue(out.toByteArray().contentEquals(again.toByteArray()))
    }

    @Test
    fun archiveRejectsPathTraversalAndMissingProject() {
        fun zipOf(vararg entries: Pair<String, String>): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
            return bos.toByteArray()
        }
        try {
            KnsprojArchive.read(ByteArrayInputStream(zipOf("../evil.txt" to "x", "project.json" to "{}"))) { _, _ -> }
            fail("zip slip accepted")
        } catch (e: KnsprojFormatException) {
            assertTrue(e.message!!.contains("Unsafe"))
        }
        try {
            KnsprojArchive.read(ByteArrayInputStream(zipOf("assets/a.png" to "x"))) { _, _ -> }
            fail("archive without project.json accepted")
        } catch (e: KnsprojFormatException) {
            assertTrue(e.message!!.contains("project.json"))
        }
        try {
            KnsprojArchive.read(ByteArrayInputStream("not a zip at all".toByteArray())) { _, _ -> }
            fail("garbage accepted")
        } catch (e: KnsprojFormatException) {
            // ok
        }
        assertEquals(null, KnsprojArchive.normalizeEntryName("/abs/path"))
        assertEquals(null, KnsprojArchive.normalizeEntryName("C:/x"))
        assertEquals("assets/a.png", KnsprojArchive.normalizeEntryName("./assets//a.png"))
    }

    @Test
    fun missingAssetFileFailsExport() {
        val project = TestProjects.full()
        try {
            KnsprojArchive.write(project, { null }, ByteArrayOutputStream())
            fail("export with missing files must fail")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Missing file"))
        }
    }

    @Test
    fun validatorAcceptsTheFullFixture() {
        val report = ProjectValidator.validate(TestProjects.full(), TestProjects.fakeAssetFiles(TestProjects.full()).keys)
        assertTrue(report.issues.joinToString("\n"), report.isValid)
    }

    @Test
    fun validatorFindsSemanticErrors() {
        val base = TestProjects.full()
        fun codes(p: KnsProject, files: Set<String>? = null) = ProjectValidator.validate(p, files).errors.map { it.code }.toSet()

        val dup = base.copy(layers = base.layers + base.layers[1])
        assertTrue("layer.duplicateId" in codes(dup))

        val cycle = base.copy(
            layers = listOf(
                Layer("a", LayerType.SHAPE, parent = "b", shape = ShapeContent()),
                Layer("b", LayerType.SHAPE, parent = "a", shape = ShapeContent()),
            ),
        )
        assertTrue("layer.parentCycle" in codes(cycle))

        val mixed = base.copy(
            layers = listOf(
                Layer("cube", LayerType.OBJECT3D, object3d = Object3DContent()),
                Layer("s", LayerType.SHAPE, parent = "cube", shape = ShapeContent()),
            ),
        )
        assertTrue("layer.parentKind" in codes(mixed))

        val badAsset = base.copy(layers = listOf(Layer("i", LayerType.IMAGE, image = dev.kinesis.core.model.ImageContent("nope"))))
        assertTrue("layer.missingAsset" in codes(badAsset))

        val wrongType = base.copy(layers = listOf(Layer("i", LayerType.IMAGE, image = dev.kinesis.core.model.ImageContent("snd"))))
        assertTrue("layer.assetType" in codes(wrongType))

        val noContent = base.copy(layers = listOf(Layer("t", LayerType.TEXT)))
        assertTrue("layer.missingContent" in codes(noContent))

        val badMatte = base.copy(layers = listOf(Layer("t", LayerType.SHAPE, shape = ShapeContent(), matte = Matte("t"))))
        assertTrue("layer.matteSelf" in codes(badMatte))

        assertTrue("asset.missingFile" in codes(base, emptySet()))

        val badPath = base.copy(assets = base.assets.map { it.copy(path = "../../etc/passwd") })
        assertTrue("asset.path" in codes(badPath))

        val badComp = base.copy(composition = base.composition.copy(fps = 0.0, width = 4))
        assertTrue(codes(badComp).containsAll(listOf("composition.fps", "composition.size")))

        val badEffect = base.copy(layers = listOf(Layer("s", LayerType.SHAPE, shape = ShapeContent(), effects = listOf(Effect.Blur(radius = dev.kinesis.core.anim.anim(-3f))))))
        assertTrue("effect.range" in codes(badEffect))
    }

    @Test
    fun safeFileReplacesAtomicallyAndKeepsBackup() {
        val dir = Files.createTempDirectory("kns").toFile()
        val f = dir.resolve("project.json")
        SafeFile.writeText(f, "v1")
        SafeFile.writeText(f, "v2")
        assertEquals("v2", f.readText())
        assertEquals("v1", SafeFile.backupOf(f).readText())
        // A failing writer leaves the previous version untouched.
        try {
            SafeFile.write(f) { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            // expected
        }
        assertEquals("v2", f.readText())
        assertNotNull(dir.listFiles())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        dir.deleteRecursively()
    }
}

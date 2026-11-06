package dev.kinesis.core

import dev.kinesis.core.anim.at
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.TransitionType
import dev.kinesis.core.plugin.PluginArchive
import dev.kinesis.core.plugin.PluginException
import dev.kinesis.core.plugin.PluginHost
import dev.kinesis.core.plugin.PluginManifest
import dev.kinesis.core.plugin.PluginPermission
import dev.kinesis.core.plugin.PluginResult
import dev.kinesis.core.plugin.PluginScriptError
import dev.kinesis.core.plugin.Presets
import dev.kinesis.core.plugin.ScriptLimits
import dev.kinesis.core.plugin.ScriptSandbox
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginTest {
    private val root = File(System.getProperty("kinesis.repoRoot"), "plugins/examples")

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun pack(dir: String): ByteArray {
        val base = File(root, dir)
        return zip(base.walkTopDown().filter { it.isFile }.associate { it.relativeTo(base).invariantSeparatorsPath to it.readBytes() })
    }

    private fun project(): KnsProject {
        val p = Factory.newProject("t", 1080, 1080, 30.0, 10.0, Color.BLACK, null, "test")
        val a = Factory.shape(p, 0.0, ShapeKind.RECTANGLE, "a")
        val p1 = p.copy(layers = listOf(a))
        val b = Factory.shape(p1, 1.0, ShapeKind.ELLIPSE, "b")
        val p2 = p1.copy(layers = listOf(a, b))
        val t = Factory.text(p2, 0.0, "Olá")
        return p2.copy(layers = listOf(a, b, t))
    }

    @Test
    fun manifestRejectsNetworkAndOfficialOnlyPermissions() {
        val base = File(root, "escalonar/manifest.json").readText()
        val net = PluginManifest.parse(base.replace("\"project.write\"", "\"network\""))
        assertTrue(net is PluginResult.Err && net.errors.any { it.contains("offline") })
        val speech = PluginManifest.parse(base.replace("\"project.write\"", "\"speech\""))
        assertTrue(speech is PluginResult.Err && speech.errors.any { it.contains("oficiais") })
        val bad = PluginManifest.parse("""{"format":"kinesis-plugin","formatVersion":1}""")
        assertTrue(bad is PluginResult.Err && bad.errors.size >= 5)
    }

    @Test
    fun versionsCompare() {
        val m = (PluginManifest.parse(File(root, "escalonar/manifest.json").readText()) as PluginResult.Ok).value
        assertTrue(m.supports("0.2.0"))
        assertTrue(m.supports("0.10.1"))
        assertTrue(!m.supports("0.1.8-hotfix-2"))
    }

    @Test
    fun presetPackageInstallsAndApplies() {
        val pkg = PluginArchive.read(pack("presets-basicos").inputStream())
        assertEquals(4, pkg.presets.size)
        val p = project()
        val rise = pkg.presets.single { it.id == "subirSurgindo" }
        val layer = Presets.apply(rise, p.layers[0], at = 1.0)
        assertEquals(0f, layer.transform.opacity.at(1.0), 1e-4f)
        assertEquals(1f, layer.transform.opacity.at(2.0), 1e-4f)
        assertEquals(80f, layer.transform.position.at(1.0).y - p.layers[0].transform.position.value.y, 1e-3f)
        val style = Presets.apply(pkg.presets.single { it.id == "tituloAmarelo" }, p.layers[2])
        assertEquals(140f, style.text!!.fontSize.value, 0f)
        assertEquals("Olá", style.text!!.content)
        val tr = Presets.apply(pkg.presets.single { it.id == "entraESaiDeslizando" }, p.layers[0])
        assertEquals(TransitionType.SLIDE_UP, tr.transitionIn!!.type)
        assertTrue(!Presets.appliesTo(pkg.presets.single { it.id == "tituloAmarelo" }, p.layers[0]))
    }

    @Test
    fun scriptCommandRunsAndIsValidated() {
        val pkg = PluginArchive.read(pack("escalonar").inputStream())
        val m = pkg.manifest
        val p = project()
        val cmd = m.commands.single()
        val log = mutableListOf<String>()
        val input = PluginHost.input(m, p, listOf(p.layers[0].id, p.layers[1].id, p.layers[2].id), 0.0, mapOf("intervalo" to JsonPrimitive(0.5)))
        val out = ScriptSandbox.run(pkg.script!!, cmd.function, input, pkg.texts) { log += it }
        val outcome = PluginHost.outcome(m, p, out, log)
        val changed = outcome.project!!
        assertEquals(listOf(0.0, 0.5, 1.0), changed.layers.map { it.start })
        assertEquals("3 camadas escalonadas", outcome.message)
        assertEquals(listOf("camadas escalonadas: 3"), log)
    }

    @Test
    fun writingWithoutPermissionOrBreakingTheProjectIsRefused() {
        val m = (PluginManifest.parse(File(root, "escalonar/manifest.json").readText()) as PluginResult.Ok).value
        val readOnly = m.copy(permissions = listOf(PluginPermission.PROJECT_READ))
        val p = project()
        val json = """{"project": ${dev.kinesis.core.format.KnsprojJson.encode(p)}}"""
        try { PluginHost.outcome(readOnly, p, json); fail() } catch (e: PluginException) { assertTrue(e.message!!.contains("project.write")) }
        val broken = """{"project": ${dev.kinesis.core.format.KnsprojJson.encode(p.copy(layers = listOf(p.layers[0].copy(duration = -3.0))))}}"""
        try { PluginHost.outcome(m, p, broken); fail() } catch (e: PluginException) { assertTrue(e.message!!.contains("inválido")) }
        // Without project.read the script sees no project.
        val noRead = m.copy(permissions = emptyList())
        assertTrue(!PluginHost.input(noRead, p, emptyList(), 0.0, emptyMap()).contains("\"project\""))
        assertNull(PluginHost.outcome(m, p, "null").project)
    }

    @Test
    fun sandboxHasNoJavaAndEnforcesLimits() {
        fun err(src: String, limits: ScriptLimits = ScriptLimits()) = try {
            ScriptSandbox.run(src, "f", "{}", limits = limits); null
        } catch (e: PluginScriptError) { e }
        assertNotNull(err("function f(){ return java.lang.System.exit(0) }"))
        assertNotNull(err("function f(){ return Packages.java.io.File }"))
        assertTrue(err("function f(){ while(true){} }", ScriptLimits(maxMillis = 300))!!.message!!.contains("limite"))
        assertTrue(err("function f(){ for(;;){} }", ScriptLimits(maxInstructions = 100_000))!!.message!!.contains("limite"))
        assertTrue(err("function f(){ return kinesis.readText('../../etc/passwd') }")!!.message!!.contains("não existe"))
        assertNotNull(err("function f(){ return f() }"))
        assertNotNull(err("function f(){ syntax error here"))
        assertEquals("{\"a\":2}", ScriptSandbox.run("function f(x){ return {a: x.v * 2} }", "f", "{\"v\":1}"))
    }

    @Test
    fun unsafePackagesAreRejected() {
        val manifest = File(root, "presets-basicos/manifest.json").readBytes()
        val presets = File(root, "presets-basicos/presets/basicos.json").readBytes()
        fun refused(files: Map<String, ByteArray>, what: String) = try {
            PluginArchive.read(zip(files).inputStream()); fail("accepted: $what")
        } catch (e: PluginException) { Unit }
        refused(mapOf("manifest.json" to manifest, "presets/basicos.json" to presets, "../evil.js" to byteArrayOf(1)), "parent path")
        refused(mapOf("manifest.json" to manifest), "missing preset file")
        refused(mapOf("presets/basicos.json" to presets), "no manifest")
        refused(mapOf("manifest.json" to manifest, "presets/basicos.json" to "{\"presets\":[{\"id\":\"x\",\"name\":\"x\",\"kind\":\"animation\",\"properties\":{\"color\":[]}}]}".toByteArray()), "bad preset")
        assertEquals(Vec2.ZERO, Vec2.ZERO)
    }
}

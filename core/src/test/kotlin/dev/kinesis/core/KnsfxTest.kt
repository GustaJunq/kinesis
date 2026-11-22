package dev.kinesis.core

import dev.kinesis.core.anim.anim
import dev.kinesis.core.format.KnsprojArchive
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.knsfx.KnsfxException
import dev.kinesis.core.knsfx.KnsfxManifest
import dev.kinesis.core.knsfx.KnsfxPackage
import dev.kinesis.core.knsfx.KnsfxParams
import dev.kinesis.core.knsfx.KnsfxRegistry
import dev.kinesis.core.knsfx.KnsfxScripts
import dev.kinesis.core.knsfx.KnsfxShader
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Composition
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.ParamType
import dev.kinesis.core.model.ParamUnit
import dev.kinesis.core.model.ShapeContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KnsfxTest {
    private val examples = File(TestProjects.repoRoot, "examples/knsfx")

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(extra: String = "", script: Boolean = true) =
        """{"format":"knsfx","formatVersion":1,"id":"dev.test.fx","name":"Test"${if (script) ",\"script\":\"a.js\"" else ""}$extra}"""

    private val js = "function animate(env, el, p) { return el; }"

    private fun expectError(contains: String, block: () -> Unit) {
        try {
            block()
            fail("expected an error containing \"$contains\"")
        } catch (e: KnsfxException) {
            assertTrue("\"${e.message}\" should mention \"$contains\"", e.message!!.contains(contains, ignoreCase = true))
        }
    }

    // ------------------------------------------------------------------ manifest

    @Test
    fun examplesAreValid() {
        val scale = KnsfxPackage.read(File(examples, "scaleassist.knsfx").readBytes(), "0.2.6")
        assertEquals("dev.kinesis.examples.scaleassist", scale.id)
        assertNotNull(scale.script)
        assertNull(scale.shader)
        assertEquals(listOf("axis", "scale", "damp"), scale.params.map { it.name })
        val chroma = KnsfxPackage.read(File(examples, "chromatic.knsfx").readBytes(), "0.2.6")
        assertNotNull(chroma.shader)
        assertEquals(listOf("intensity", "angle", "radial"), chroma.params.map { it.name })
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val m = KnsfxManifest.parse(manifest(""","futureField":{"x":1},"tags":["a","b"]"""), "0.2.6")
        assertEquals("dev.test.fx", m.id)
        assertEquals(listOf("a", "b"), m.tags)
    }

    @Test
    fun requiredFields() {
        expectError("format") { KnsfxManifest.parse("""{"format":"other","formatVersion":1,"id":"dev.a.b","name":"x","script":"a.js"}""") }
        expectError("formatVersion") { KnsfxManifest.parse("""{"format":"knsfx","id":"dev.a.b","name":"x","script":"a.js"}""") }
        expectError("name") { KnsfxManifest.parse("""{"format":"knsfx","formatVersion":1,"id":"dev.a.b","script":"a.js"}""") }
        expectError("script") { KnsfxManifest.parse(manifest(script = false)) }
        expectError("domínio reverso") { KnsfxManifest.parse(manifest().replace("dev.test.fx", "semponto")) }
        expectError("JSON") { KnsfxManifest.parse("not json") }
    }

    @Test
    fun minKinesisIsChecked() {
        expectError("0.3.0") { KnsfxManifest.parse(manifest(""","minKinesis":"0.3.0""""), "0.2.6") }
        assertEquals("0.2.6", KnsfxManifest.parse(manifest(""","minKinesis":"0.2.6""""), "0.2.6").minKinesis)
        assertNotNull(KnsfxManifest.parse(manifest(""","minKinesis":"0.2.5""""), "0.2.10"))
    }

    @Test
    fun zipSlipIsRejected() {
        expectError("inseguro") { KnsfxPackage.read(zip("manifest.json" to manifest(), "a.js" to js, "../evil.js" to js)) }
        expectError("inseguro") { KnsfxPackage.read(zip("manifest.json" to manifest(), "/abs/a.js" to js)) }
        expectError("inseguro") { KnsfxPackage.read(zip("manifest.json" to manifest().replace("\"a.js\"", "\"../a.js\""))) }
        expectError("inseguro") { KnsfxPackage.read(zip("manifest.json" to manifest().replace("\"a.js\"", "\"dir\\\\a.js\""))) }
    }

    @Test
    fun missingFilesAndBadScripts() {
        expectError("não está no .knsfx") { KnsfxPackage.read(zip("manifest.json" to manifest())) }
        expectError("manifest.json") { KnsfxPackage.read(zip("a.js" to js)) }
        expectError("animate") { KnsfxPackage.read(zip("manifest.json" to manifest(), "a.js" to "function other() {}")) }
        expectError("linha") { KnsfxPackage.read(zip("manifest.json" to manifest(), "a.js" to "function animate( {")) }
        expectError("manifest.json") { KnsfxPackage.read("not a zip at all".toByteArray()) }
    }

    // ------------------------------------------------------------------ params.xml

    @Test
    fun paramTypes() {
        val p = KnsfxParams.parse(
            """<params>
              <selector id="mode" default="b" style="dropdown"><choice label="A" value="a"/><choice label="@am:string/b" value="b"/></selector>
              <spinner id="s" default="1" min="0" max="1000" step="0.01" type="percent" ticks="0.5,1,2" label="S"/>
              <slider id="v" default="2" min="0" max="10" label="V"/>
              <checkbox id="on" default="true" label="On"/>
              <color id="tint" default="#FF000080" label="Cor"/>
              <point id="center" default="10,-5" label="Centro"/>
              <angle id="turn" default="45" label="Giro"/>
            </params>""",
        )
        assertEquals(listOf(ParamType.CHOICE, ParamType.NUMBER, ParamType.NUMBER, ParamType.BOOLEAN, ParamType.COLOR, ParamType.POINT, ParamType.NUMBER), p.map { it.type })
        assertEquals("b", p[0].choices[1].namePt) // @am:string labels fall back to the id/value
        assertEquals(ParamUnit.PERCENT, p[1].unit)
        assertEquals(listOf(0.5f, 1f, 2f), p[1].ticks)
        assertEquals(0.01f, p[1].step)
        assertEquals(true, p[3].defaultBoolean)
        assertEquals(0.5f, p[4].defaultColor.a, 0.01f)
        assertEquals(Vec2(10f, -5f), p[5].defaultPoint)
        assertEquals(ParamUnit.DEG, p[6].unit)
    }

    @Test
    fun invalidParams() {
        expectError("repetido") { KnsfxParams.parse("""<params><slider id="a" default="0"/><checkbox id="a"/></params>""") }
        expectError("fora de min/max") { KnsfxParams.parse("""<params><spinner id="a" default="5" min="0" max="1"/></params>""") }
        expectError("não é um número") { KnsfxParams.parse("""<params><spinner id="a" default="x"/></params>""") }
        expectError("não é um dos values") { KnsfxParams.parse("""<params><selector id="a" default="9"><choice label="A" value="1"/></selector></params>""") }
        expectError("não suportado") { KnsfxParams.parse("""<params><knob id="a"/></params>""") }
        expectError("id inválido") { KnsfxParams.parse("""<params><slider id="1bad"/></params>""") }
        expectError("reservado") { KnsfxParams.parse("""<params><slider id="uTime"/></params>""") }
        expectError("DOCTYPE") { KnsfxParams.parse("""<!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><params><slider id="a" label="&e;"/></params>""") }
        expectError("não existe") { KnsfxParams.parse("""<params><selector id="s"><choice label="A" value="1" enableTargets="ghost"/></selector></params>""") }
        expectError("cor") { KnsfxParams.parse("""<params><color id="c" default="vermelho"/></params>""") }
    }

    @Test
    fun enableTargets() {
        val params = KnsfxPackage.read(File(examples, "scaleassist.knsfx").readBytes(), "0.2.6").params
        val damp = params.first { it.name == "damp" }
        assertEquals("axis" to setOf("2", "3"), damp.enabledWhen)
        assertTrue(damp.isEnabled { "3" })
        assertTrue(damp.isEnabled { "2" })
        assertFalse(damp.isEnabled { "0" })
        assertFalse(damp.isEnabled { "1" })
        assertNull(params.first { it.name == "scale" }.enabledWhen)
        // enabled="false" without anyone enabling it: never editable.
        val off = KnsfxParams.parse("""<params><slider id="a" default="0" enabled="false"/></params>""").single()
        assertFalse(off.isEnabled { "" })
    }

    // ------------------------------------------------------------------ shader source

    @Test
    fun legacyShadersAreTranslated() {
        val src = "precision mediump float;\nuniform sampler2D uTexture;\nvarying vec2 vTexCoord;\nvoid main() { gl_FragColor = texture2D(uTexture, vTexCoord); }"
        val out = KnsfxShader.prepare(src)
        assertTrue(out.startsWith("#version 300 es\n"))
        assertTrue(out.contains("in vec2 vTexCoord"))
        assertTrue(out.contains("texture(uTexture"))
        assertTrue(out.contains("out vec4 ${KnsfxShader.OUTPUT};"))
        assertFalse(out.contains("gl_FragColor"))
        val modern = "#version 300 es\nuniform sampler2D uTexture;\nin vec2 vTexCoord;\nout vec4 o;\nvoid main() { o = texture(uTexture, vTexCoord); }"
        assertTrue(KnsfxShader.prepare(modern).contains("precision highp float;"))
    }

    // ------------------------------------------------------------------ script runtime + project round trip

    private fun installScaleAssist(): KnsfxPackage {
        val pkg = KnsfxPackage.read(File(examples, "scaleassist.knsfx").readBytes(), "0.2.6")
        KnsfxRegistry.install(pkg)
        return pkg
    }

    private fun shape(effect: Effect) = Layer(
        "s", LayerType.SHAPE, duration = 10.0, shape = ShapeContent(),
        transform = Transform2D(scale = anim(Vec2(1f, 1f))), effects = listOf(effect),
    )

    @Test
    fun scaleAssistScalesTheLayer() {
        val pkg = installScaleAssist()
        val comp = Composition(1000, 1000, 30.0, 10.0)
        fun scaleWith(axis: String, scale: Float, damp: Float): Vec2 {
            val e = Effect(pkg.id, params = mapOf("axis" to EffectValue.Choice(axis), "scale" to EffectValue.Num(anim(scale)), "damp" to EffectValue.Num(anim(damp))))
            return KnsfxScripts.apply(shape(e), 1.0, comp).transform.scale.value
        }
        assertEquals(Vec2(1.5f, 1f), scaleWith("0", 1.5f, 1f))
        assertEquals(Vec2(1f, 1.5f), scaleWith("1", 1.5f, 1f))
        assertEquals(Vec2(2f, 1f), scaleWith("2", 2f, 0.5f))
        assertEquals(Vec2(1.5f, 0.75f), scaleWith("3", 1.5f, 0.5f))
        // And through the scene evaluator, which renders it.
        val project = KnsProject(composition = comp, layers = listOf(shape(Effect.create(pkg.id).with("scale", EffectValue.Num(anim(2f))))))
        val node = SceneEvaluator.evaluate(project, 1.0).nodes.single() as Node2D
        assertEquals(2f, node.matrix.a, 1e-4f)
    }

    @Test
    fun aRunawayScriptTurnsTheEffectOff() {
        val bytes = zip("manifest.json" to manifest(), "a.js" to "function animate(env, el, p) { while (true) {} }")
        val pkg = KnsfxPackage.read(bytes)
        KnsfxRegistry.install(pkg)
        var reported: String? = null
        KnsfxScripts.onFailure = { _, id, _ -> reported = id }
        try {
            val layer = shape(Effect.create(pkg.id)).copy(id = "loop")
            val comp = Composition(100, 100, 30.0, 10.0)
            repeat(KnsfxScripts.MAX_TIMEOUTS + 1) { KnsfxScripts.apply(layer, 0.5, comp) }
            assertEquals(pkg.id, reported)
            // Off from now on: the layer comes back untouched.
            assertEquals(layer.transform.scale.value, KnsfxScripts.apply(layer, 0.5, comp).transform.scale.value)
        } finally {
            KnsfxScripts.onFailure = null
            KnsfxScripts.reset()
            KnsfxRegistry.uninstall(pkg.id)
        }
    }

    @Test
    fun projectsEmbedTheirCustomEffects() {
        val pkg = installScaleAssist()
        val project = KnsProject(composition = Composition(100, 100, 30.0, 2.0), layers = listOf(shape(Effect.create(pkg.id).with("scale", EffectValue.Num(anim(3f))))))
        val out = ByteArrayOutputStream()
        KnsprojArchive.write(project, { null }, out)
        // Another device: the effect is not installed.
        KnsfxRegistry.uninstall(pkg.id)
        assertNull(EffectRegistry.get(pkg.id))
        val entries = ArrayList<String>()
        val read = KnsprojArchive.read(out.toByteArray().inputStream()) { path, _ -> entries += path }
        assertTrue(entries.contains("effects/${pkg.id}.knsfx"))
        assertNotNull(EffectRegistry.get(pkg.id))
        assertEquals(3f, read.project.layers.single().effects.single().number("scale").value)
        KnsfxRegistry.uninstall(pkg.id)
    }

    @Test
    fun missingCustomEffectsAreKeptAsIs() {
        val json = KnsprojJson.encode(KnsProject(composition = Composition(100, 100, 30.0, 2.0), layers = listOf(shape(Effect("blur")))))
            .replace("\"type\": \"blur\"", "\"type\": \"dev.someone.missing\", \"amount\": 0.5")
            .replace("\"type\":\"blur\"", "\"type\":\"dev.someone.missing\",\"amount\":0.5")
        val p = KnsprojJson.decode(json)
        val e = p.layers.single().effects.single()
        assertEquals("dev.someone.missing", e.type)
        assertNotNull(e.raw)
        // Saved back unchanged, and the scene skips it.
        assertTrue(KnsprojJson.encode(p).contains("dev.someone.missing"))
        assertTrue(KnsprojJson.encode(p).contains("0.5"))
        assertNotNull(SceneEvaluator.evaluate(p, 0.5))
    }
}

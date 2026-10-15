package dev.kinesis.core.expr

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.TestProjects
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.Content2D
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.SceneEvaluator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

class ExpressionSceneTest {
    @After
    fun resetSink() {
        ExpressionResolver.onError = null
    }

    private fun project(vararg layers: String, width: Int = 1080, height: Int = 1920, duration: Double = 10.0) = KnsprojJson.decode(
        """{"format":"knsproj","formatVersion":3,"composition":{"width":$width,"height":$height,"fps":30,"duration":$duration},
            "layers":[${layers.joinToString(",")}]}""",
    )

    private fun shape(id: String, transform: String = "{}", extra: String = "") =
        """{"id":"$id","type":"shape","duration":10,"shape":{},"transform":$transform$extra}"""

    private fun node(p: KnsProject, t: Double, id: String) = SceneEvaluator.evaluate(p, t).nodes.first { it.layerId == id } as Node2D

    @Test
    fun opacityExpressionUsesPercent() {
        val p = project(shape("a", """{"opacity":{"value":1,"expression":"linear(time, 0, 2, 0, 100)"}}"""))
        assertEquals(0.0f, node(p, 0.0, "a").opacity, 1e-6f)
        assertEquals(0.5f, node(p, 1.0, "a").opacity, 1e-6f)
        assertEquals(1.0f, node(p, 3.0, "a").opacity, 1e-6f)
    }

    @Test
    fun positionIsMeasuredFromTheTopLeftCorner() {
        // AE centre [w/2, h/2] is the origin of the Kinesis composition.
        val p = project(shape("a", """{"position":{"value":[0,0],"expression":"[thisComp.width / 2 + 100, thisComp.height / 2]"}}"""), width = 1000, height = 600)
        val m = node(p, 0.0, "a").matrix
        assertEquals(100f, m.tx, 1e-3f)
        assertEquals(0f, m.ty, 1e-3f)
        // `value` is expressed the same way: the default position reads as the centre.
        val q = project(shape("a", """{"position":{"value":[40,-20],"expression":"[value[0] + 10, value[1]]"}}"""), width = 1000, height = 600)
        val m2 = node(q, 0.0, "a").matrix
        assertEquals(50f, m2.tx, 1e-3f)
        assertEquals(-20f, m2.ty, 1e-3f)
    }

    @Test
    fun scaleUsesPercentAndKeepsTheBaseValue() {
        val p = project(shape("a", """{"scale":{"value":[2,2],"expression":"[value[0] * 0.5, value[1] * 0.5]"}}"""))
        assertEquals(1f, node(p, 0.0, "a").matrix.a, 1e-5f)
    }

    @Test
    fun loopOutMatchesTheBuiltInLoop() {
        val keys = """[{"time":0,"value":0},{"time":1,"value":100,"easing":"easeInOut"},{"time":2,"value":20}]"""
        val withExpr = project(shape("a", """{"rotation":{"keyframes":$keys,"expression":"loopOut(\"cycle\")"}}"""))
        val builtIn = project(shape("a", """{"rotation":{"keyframes":$keys,"loop":"cycle"}}"""))
        val pp = project(shape("a", """{"rotation":{"keyframes":$keys,"expression":"loopOut(\"pingpong\")"}}"""))
        val builtInPp = project(shape("a", """{"rotation":{"keyframes":$keys,"loop":"pingpong"}}"""))
        for (t in listOf(0.0, 0.7, 1.9, 2.0, 2.4, 3.3, 4.0, 5.2, 7.77)) {
            assertEquals("cycle t=$t", rotation(builtIn, t), rotation(withExpr, t), 1e-3f)
            assertEquals("pingpong t=$t", rotation(builtInPp, t), rotation(pp, t), 1e-3f)
        }
        assertEquals(LoopMode.CYCLE, KnsprojJson.decode(KnsprojJson.encode(builtIn)).layers[0].transform.rotation.loop)
    }

    private fun rotation(p: KnsProject, t: Double): Float {
        val m = node(p, t, "a").matrix
        return Math.toDegrees(Math.atan2(m.b.toDouble(), m.a.toDouble())).toFloat()
    }

    @Test
    fun wiggleIsDeterministicWhateverTheFrameOrder() {
        val p = project(shape("a", """{"position":{"value":[0,0],"expression":"wiggle(3, 40)"}}"""), width = 1000, height = 1000)
        val times = (0..30).map { it / 30.0 }
        val forward = times.map { node(p, it, "a").matrix.tx }
        val backward = times.reversed().map { node(p, it, "a").matrix.tx }.reversed()
        val again = times.map { node(p, it, "a").matrix.tx }
        assertEquals(forward, backward)
        assertEquals(forward, again)
        assertTrue(forward.max() - forward.min() > 5f)
    }

    // ------------------------------------------------------------------ text animators

    private val bounce = """
        delay = 0.030;
        myDelay = delay * textIndex;
        t = (time - inPoint) - myDelay;

        if (t >= 0) {
            freq = 2;
            amplitude = 50;
            decay = 8.0;

            s = amplitude * Math.cos(freq * t * 2 * Math.PI)
                / Math.exp(decay * t);

            [s, s]
        } else {
            value
        }
    """.trimIndent()

    private fun jsonString(s: String) = ObjectMapper().writeValueAsString(s)

    private fun textLayer(id: String, content: String, animators: String, start: Double = 0.0, duration: Double = 5.0) =
        """{"id":"$id","type":"text","start":$start,"duration":$duration,"text":{"content":${jsonString(content)},"propertyAnimators":[$animators]}}"""

    private fun bounceAnimator() = """{"name":"Bounce","scale":[0,0],"amount":{"value":100,"expression":${jsonString(bounce)}}}"""

    private fun glyphs(p: KnsProject, t: Double, id: String = "t") =
        ((node(p, t, id).content as Content2D.Text).glyphStates)!!

    @Test
    fun bounceTextScalesCharactersInSequence() {
        val text = "Bounce text"
        val p = project(textLayer("t", text, bounceAnimator()))
        // Scale animator property 0 %, selector amount 100 % before a character's turn: the character is collapsed.
        val before = glyphs(p, 0.0)
        for (i in text.indices) assertEquals("char $i hidden", 0f, before[i].scale, 1e-6f)
        // Character n (textIndex = n + 1) starts at 0.03·textIndex; there the amount drops to 50 → scale 0.5.
        for ((time, n) in listOf(0.5 to 0, 0.5 to 4, 0.9 to 10, 0.2 to 2)) {
            val t = time - 0.030 * (n + 1)
            val amount = 50.0 * cos(2 * t * 2 * PI) / exp(8.0 * t)
            val expected = (1.0 + (0.0 / 100 - 1.0) * amount / 100).toFloat()
            assertEquals("char $n at $time", expected, glyphs(p, time)[n].scale, 1e-5f)
        }
        // Later characters wait longer: at 0.1 s the first is moving while the last is still collapsed.
        val g = glyphs(p, 0.2)
        assertTrue(g[0].scale > 0f)
        assertEquals(0f, g[text.length - 1].scale, 1e-6f)
        // After the oscillation has decayed everything is back to full size.
        val after = glyphs(p, 4.5)
        for (i in text.indices) assertEquals(1f, after[i].scale, 1e-3f)
    }

    @Test
    fun textIndexIsPerCharacterNotPerLayer() {
        // Three layers in the composition: the layer index must not leak into textIndex.
        val animator = """{"position":[100,0],"amount":{"value":100,"expression":"textIndex"}}"""
        val p = project(shape("low"), textLayer("t", "Hello", animator), shape("top"))
        val g = glyphs(p, 0.5)
        // dx = position.x · amount / 100 = 100 · textIndex / 100.
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), g.map { it.dx })
        val total = project(textLayer("t", "Hello", """{"position":[100,0],"amount":{"value":100,"expression":"textTotal"}}"""))
        assertEquals(listOf(5f, 5f, 5f, 5f, 5f), glyphs(total, 0.5).map { it.dx })
        val index = project(shape("low"), textLayer("t", "ab", """{"position":[100,0],"amount":{"value":100,"expression":"index"}}"""))
        // AE layer index: the text layer is in the middle of three → 2 from the top... here two layers: it is the top one.
        assertEquals(listOf(1f, 1f), glyphs(index, 0.5).map { it.dx })
    }

    @Test
    fun selectorValueAndInPointFollowTheLayer() {
        val animator = """{"opacity":0,"amount":{"value":100,"expression":"linear(time - inPoint, 0, 1, selectorValue, 0)"}}"""
        val p = project(textLayer("t", "ab", animator, start = 2.0, duration = 3.0))
        assertEquals(0f, glyphs(p, 2.0)[0].opacity, 1e-6f)   // amount 100 → opacity 0
        assertEquals(0.5f, glyphs(p, 2.5)[0].opacity, 1e-5f) // amount 50 → opacity 0.5
        assertEquals(1f, glyphs(p, 4.0)[0].opacity, 1e-6f)   // amount 0 → unchanged
    }

    @Test
    fun wordsAndLinesAreCountedAsUnits() {
        val animator = """{"unit":"word","position":[10,0],"amount":{"value":100,"expression":"textIndex * 100 + textTotal"}}"""
        val p = project(textLayer("t", "aa bb cc", animator))
        val g = glyphs(p, 0.5)
        assertEquals(1f * 10 + 0.3f, g[0].dx, 1e-4f) // first word: (100 + 3) / 100 · 10 = 10.3
        assertEquals(g[0].dx, g[1].dx, 0f)
        assertEquals(20.3f, g[3].dx, 1e-4f)
        assertEquals(30.3f, g[6].dx, 1e-4f)
    }

    @Test
    fun animatorWithoutExpressionAndDisabledAnimator() {
        val plain = project(textLayer("t", "ab", """{"rotation":90,"amount":50}"""))
        assertEquals(45f, glyphs(plain, 0.0)[0].rotation, 1e-5f)
        val off = project(textLayer("t", "ab", """{"enabled":false,"rotation":90}"""))
        assertNull((node(off, 0.0, "t").content as Content2D.Text).glyphStates)
    }

    @Test
    fun failingExpressionsFallBackToTheValueAndAreReported() {
        val errors = ArrayList<ExpressionError>()
        ExpressionResolver.onError = { _, _, e -> errors += e }
        val p = project(
            shape("a", """{"rotation":{"value":30,"expression":"while (true) { }"}}"""),
            shape("b", """{"rotation":{"value":30,"expression":"nope + 1"}}"""),
        )
        val started = System.nanoTime()
        assertEquals(30f, rotation(p, 1.0), 1e-3f)
        assertEquals(30f, Math.toDegrees(Math.atan2(node(p, 1.0, "b").matrix.b.toDouble(), node(p, 1.0, "b").matrix.a.toDouble())).toFloat(), 1e-3f)
        assertTrue("must stop quickly", (System.nanoTime() - started) / 1e9 < 5)
        assertTrue(errors.any { it.reason.contains("too long") })
        assertTrue(errors.any { it.reason.contains("nope") && it.line == 1 })
    }

    @Test
    fun switchedOffExpressionKeepsItsCode() {
        val p = project(shape("a", """{"rotation":{"value":30,"expression":"90","expressionEnabled":false}}"""))
        assertEquals(30f, rotation(p, 0.0), 1e-3f)
        val back = KnsprojJson.decode(KnsprojJson.encode(p))
        val rot = back.layers[0].transform.rotation
        assertEquals("90", rot.expression)
        assertFalse(rot.expressionEnabled)
    }

    // ------------------------------------------------------------------ format

    @Test
    fun serializationRoundTripKeepsTheOriginalCode() {
        val code = bounce + "\n// trailing comment with \"quotes\" and ünïcode\n"
        val p = project(
            shape("a", """{"opacity":{"value":1,"expression":${jsonString("wiggle(2,10)")}},"rotation":{"keyframes":[{"time":0,"value":0},{"time":1,"value":90}],"expression":"loopOut()"}}"""),
            textLayer("t", "Hi", """{"name":"Bounce","scale":[0,0],"amount":{"value":100,"expression":${jsonString(code)}}}"""),
        )
        val json = KnsprojJson.encode(p)
        val back = KnsprojJson.decode(json)
        assertEquals(json, KnsprojJson.encode(back))
        assertEquals(code, back.layers[1].text!!.propertyAnimators[0].amount.expression)
        assertEquals("loopOut()", back.layers[0].transform.rotation.expression)
        assertTrue(back.layers[0].transform.rotation.isAnimated)
        assertEquals("Bounce", back.layers[1].text!!.propertyAnimators[0].name)
        // Valid against the official schema, and the format version is 5.
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(File(TestProjects.repoRoot, "schema/knsproj-v5.schema.json").readText())
        val errors = schema.validate(ObjectMapper().readTree(json)).map { it.message }
        assertEquals(emptyList<String>(), errors)
        assertTrue(json.contains("\"formatVersion\": 5"))
    }

    @Test
    fun projectsWithoutExpressionsAreWrittenAsBefore() {
        val p = project(shape("a", """{"opacity":0.5}"""))
        val json = KnsprojJson.encode(p)
        assertFalse(json.contains("expression"))
        assertFalse(json.contains("propertyAnimators"))
    }

    @Test
    fun version2DocumentsStillOpen() {
        val v2 = """{"format":"knsproj","formatVersion":2,"layers":[{"id":"a","type":"shape","shape":{},"transform":{"opacity":{"keyframes":[{"time":0,"value":0},{"time":1,"value":1}]}}}]}"""
        val p = KnsprojJson.decode(v2)
        assertEquals(5, p.formatVersion)
        assertNull(p.layers[0].transform.opacity.expression)
        assertEquals(2, p.layers[0].transform.opacity.keyframes.size)
    }

    @Test
    fun validatorReportsSyntaxErrors() {
        val bad = project(shape("a", """{"opacity":{"value":1,"expression":"a = ;"}}"""))
        val codes = ProjectValidator.validate(bad).errors.map { it.code }
        assertTrue("expression.syntax" in codes)
        val good = project(
            shape("a", """{"opacity":{"value":1,"expression":"wiggle(1, 5)"}}"""),
            textLayer("t", "x", bounceAnimator()),
        )
        assertEquals(emptyList<String>(), ProjectValidator.validate(good).errors.map { it.code })
        val empty = project(textLayer("t", "x", """{"name":"nothing"}"""))
        assertTrue(ProjectValidator.validate(empty).warnings.any { it.code == "text.propertyAnimator" })
        assertNotNull(KnsprojJson.decode(KnsprojJson.encode(bad)))
    }

    // ------------------------------------------------------------------ preview / export consistency

    @Test
    fun theSameFrameIsIdenticalNoMatterHowItIsReached() {
        val p = project(
            shape("a", """{"position":{"value":[0,0],"expression":"wiggle(2, 30)"},"rotation":{"value":0,"expression":"random(-45, 45)"}}"""),
            textLayer("t", "Consistency", bounceAnimator()),
        )
        fun fingerprint(t: Double): List<Float> {
            val scene = SceneEvaluator.evaluate(p, t)
            val a = scene.nodes[0] as Node2D
            val txt = scene.nodes[1] as Node2D
            val g = (txt.content as Content2D.Text).glyphStates!!
            return listOf(a.matrix.tx, a.matrix.ty, a.matrix.a, a.matrix.b) + g.map { it.scale }
        }
        val frames = (0..60).map { it / 30.0 }
        val sequential = frames.map(::fingerprint)                  // preview: playing forward
        val scrubbed = frames.shuffled(java.util.Random(4)).associateWith(::fingerprint) // export / scrubbing: any order
        val threaded = frames.associateWith { t ->
            var r: List<Float>? = null
            val th = Thread { r = fingerprint(t) }
            th.start(); th.join()
            r!!
        }
        for ((i, t) in frames.withIndex()) {
            assertEquals("t=$t scrub", sequential[i], scrubbed[t])
            assertEquals("t=$t thread", sequential[i], threaded[t])
        }
    }

    @Test
    fun evaluationIsFastEnoughForPlayback() {
        val text = "The quick brown fox jumps over the lazy dog 0123456789"
        val p = project(textLayer("t", text, bounceAnimator()))
        glyphs(p, 0.0) // warm up
        val started = System.nanoTime()
        for (f in 0 until 60) glyphs(p, f / 30.0)
        val perFrameMs = (System.nanoTime() - started) / 1e6 / 60
        println("bounce text, ${text.length} chars: $perFrameMs ms per frame")
        assertTrue("took $perFrameMs ms per frame", perFrameMs < 60.0)
    }
}

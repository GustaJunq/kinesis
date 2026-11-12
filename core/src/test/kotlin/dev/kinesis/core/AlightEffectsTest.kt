package dev.kinesis.core

import dev.kinesis.core.alight.AlightConverter
import dev.kinesis.core.alight.AlightEffectsMap
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.anim.at
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.LayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Step 7 of the Alight Motion importer: effects, following alight/effects_map.json. */
class AlightEffectsTest {
    private fun fixture() = AlightParser.scan(javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()).scene
    private fun scene(effects: String, extra: String = "") = AlightParser.scan(
        ("<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff000000\">" +
            "<shape id=\"a\" label=\"S\" startTime=\"0\" endTime=\"2000\" fillType=\"color\" mediaFillMode=\"fill\" s=\".rect\">" +
            "<transform><location value=\"500,500,0\"/>$extra</transform>$effects<property name=\"size\" type=\"vec2\" value=\"50,50\"/></shape></scene>").toByteArray(),
    ).scene
    private fun fx(name: String, props: String) = """<effect id="com.alightcreative.effects.$name" locallyApplied="true">$props</effect>"""
    private fun prop(n: String, t: String, v: String) = """<property name="$n" type="$t" value="$v"/>"""

    @Test
    fun theTableKnowsEveryEffectOfTheFixture() {
        val names = LayerTree.let { fixture() }.let { s ->
            fun walk(ls: List<dev.kinesis.core.alight.AlightLayer>): List<String> = ls.flatMap { l -> l.effects.map { it.name } + (l.scene?.let { walk(it.layers) } ?: emptyList()) }
            walk(s.layers).toSet()
        }
        assertEquals(13, names.size)
        for (n in names) assertNotNull("no rule for $n", AlightEffectsMap.rule(n))
        assertEquals("map", AlightEffectsMap.rule("bendwarp")!!.action)
        assertEquals("map", AlightEffectsMap.rule("swing2")!!.action)
        assertEquals("bake", AlightEffectsMap.rule("oscillate3")!!.action)
        assertEquals("skip", AlightEffectsMap.rule("motionblur4")!!.action)
    }

    @Test
    fun fixtureEffectsAreConvertedOrReported() {
        val s = fixture(); val r = AlightConverter.convert(s, s.media.map { it.uri }.toSet()); val p = r.project
        assertTrue(ProjectValidator.validate(p).errors.toString(), ProjectValidator.validate(p).isValid)
        val all = LayerTree.all(p)
        val types = all.flatMap { l -> l.effects.map { it.type } }
        assertEquals(1, types.count { it == "blur" }); assertEquals(2, types.count { it == "hueShift" })
        assertEquals(1, types.count { it == "invert" }); assertEquals(1, types.count { it == "pixelate" })
        assertEquals(2, types.count { it == "fill" })
        assertEquals(20, types.count { it == "swing" }); assertEquals(10, types.count { it == "scaleAssist" }); assertEquals(2, types.count { it == "colorize" })
        // The motion blur of the file is left off on purpose (it makes rendering much slower) and the report says so.
        assertEquals(0, all.count { it.motionBlur })
        assertTrue(r.report.entries.any { it.kind == ReportKind.WARNING && it.where.contains("motionblur4") })
        // The sliding picture (offset 0 -> 5000 px) ends far to the right of where it started.
        val png = LayerTree.all(p).first { it.name.startsWith("file_0000") }
        val pos = png.transform.position
        assertTrue(pos.at(png.duration).x - pos.at(0.0).x > 4000f)
        assertEquals(1, r.report.count(ReportKind.UNSUPPORTED)) // only threshold is left
    }

    @Test
    fun swingBecomesTheSwingEffectAndPlaysAtTheLayersSpeed() {
        val r = AlightConverter.convert(scene(fx("swing2", prop("freq", "float", "2") + prop("a1", "float", "30") + prop("a2", "float", "-30") + prop("phase", "float", "0") + prop("type", "int", "0"))))
        val e = r.project.layers.single().effects.single()
        assertEquals("swing", e.type)
        assertEquals(30f, e.number("angleA").at(0.0), 0f); assertEquals(-30f, e.number("angleB").at(0.0), 0f)
        assertEquals(2f, e.number("frequency").at(0.0), 0f)
        // Nothing is baked into the rotation any more.
        assertTrue(r.project.layers.single().transform.rotation.keyframes.isEmpty())
        // Inside a pre-composition played at double speed the swing is twice as fast.
        val inner = AlightConverter.convert(AlightParser.scan((
            "<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff000000\">" +
                "<embedScene id=\"e\" label=\"E\" startTime=\"0\" endTime=\"4000\" fillType=\"intrinsic\" mediaFillMode=\"fill\" speed=\"2.0\">" +
                "<scene title=\"\" width=\"400\" height=\"300\" fps=\"30\" totalTime=\"9000\" bgcolor=\"#00000000\">" +
                "<shape id=\"a\" label=\"S\" startTime=\"0\" endTime=\"4000\" fillType=\"color\" mediaFillMode=\"fill\" s=\".rect\">" +
                fx("swing2", prop("freq", "float", "2") + prop("a1", "float", "10") + prop("a2", "float", "-10")) +
                "<property name=\"size\" type=\"vec2\" value=\"5,5\"/></shape></scene></embedScene></scene>").toByteArray()).scene).project
        assertEquals(4f, inner.layers.single().layers.single().effects.single().number("frequency").at(0.0), 0f)
    }

    @Test
    fun swingAndScaleAssistMoveTheLayerAroundItsOrigin() {
        fun node(vararg e: dev.kinesis.core.model.Effect, t: Double) = dev.kinesis.core.scene.SceneEvaluator.evaluate(
            dev.kinesis.core.model.KnsProject(layers = listOf(dev.kinesis.core.model.Layer("s", dev.kinesis.core.model.LayerType.SHAPE, duration = 4.0, shape = dev.kinesis.core.model.ShapeContent(), effects = e.toList()))), t,
        ).nodes.single().effects
        // A quarter period in, the swing is at angle A (the sine at its peak); a half period in it is back at the middle.
        val sw = dev.kinesis.core.model.Effect.of("swing", "angleA" to 30f, "angleB" to -10f, "frequency" to 1f)
        val peak = node(sw, t = 0.25)[0].transform!!
        val back = node(sw, t = 0.5)[0].transform!!
        assertEquals(30f, Math.toDegrees(Math.atan2(peak.b.toDouble(), peak.a.toDouble())).toFloat(), 1e-2f)
        assertEquals(10f, Math.toDegrees(Math.atan2(back.b.toDouble(), back.a.toDouble())).toFloat(), 1e-2f)  // middle of 30 and -10
        val sa = dev.kinesis.core.model.Effect.of("scaleAssist", "scale" to 2f, "axis" to "x")
        val m = node(sa, t = 0.0)[0].transform!!
        assertEquals(2f, m.a, 1e-5f); assertEquals(1f, m.d, 1e-5f)
    }

    @Test
    fun scaleAssistKeepsItsAnimationAndAxis() {
        val r = AlightConverter.convert(scene(fx("scaleassist", prop("axis", "int", "1") + """<property name="scale" type="float"><kf t="0.0" v="1.0"/><kf t="1.0" v="3.0"/></property>"""), "<scale value=\"2,2\"/>"))
        val l = r.project.layers.single()
        val e = l.effects.single()
        assertEquals("scaleAssist", e.type); assertEquals("x", e.choice("axis"))
        assertEquals(2, e.number("scale").keyframes.size)
        assertEquals(2f, l.transform.scale.at(0.0).x, 1e-4f) // the layer's own scale is untouched
    }

    @Test
    fun colorizeReadsTheTintAsHueSaturationLightness() {
        val r = AlightConverter.convert(scene(fx("colorize", prop("tint", "vec3", "-0.25,0.5,0.1"))))
        val e = r.project.layers.single().effects.single()
        assertEquals("colorize", e.type)
        assertEquals(270f, e.number("hue").at(0.0), 1e-3f)   // -0.25 turns
        assertEquals(0.5f, e.number("saturation").at(0.0), 1e-5f); assertEquals(0.1f, e.number("lightness").at(0.0), 1e-5f)
        assertTrue(r.report.entries.any { it.kind == ReportKind.APPROXIMATED && it.where.contains("colorize") })
    }

    @Test
    fun oscillateMovesAlongItsAngleAndKeepsTheBase() {
        val r = AlightConverter.convert(scene(fx("oscillate3", prop("direction", "int", "0") + prop("angle", "float", "0") + prop("freq", "float", "1") + prop("mag", "float", "40") + prop("type", "int", "0") + prop("phase", "float", "0"))))
        val pos = r.project.layers.single().transform.position
        // Centre of a 1000 x 1000 scene is the origin; angle 0 = along x; a quarter period later it peaks at +40.
        assertEquals(40f, pos.at(0.25).x, 0.6f); assertEquals(0f, pos.at(0.25).y, 1e-3f)
        assertEquals(0f, pos.at(0.0).x, 1e-3f)
    }

    @Test
    fun offsetIsAddedToThePosition() {
        val r = AlightConverter.convert(scene(fx("offset", """<property name="offset" type="vec2"><kf t="0.0" v="0,0"/><kf t="1.0" v="100,-50"/></property>""" + prop("feather", "float", "0") + prop("mask", "bool", "false"))))
        val pos = r.project.layers.single().transform.position
        assertEquals(0f, pos.at(0.0).x, 1e-3f); assertEquals(100f, pos.at(2.0).x, 1e-3f); assertEquals(-50f, pos.at(2.0).y, 1e-3f)
    }

    @Test
    fun mappedEffectsAndPartialInvert() {
        val r = AlightConverter.convert(scene(
            fx("gaussianblur", prop("strength", "float", "0.5")) + fx("mosaic", prop("size", "float", "8")) +
                fx("invert", prop("invertRed", "bool", "true") + prop("invertGreen", "bool", "false") + prop("invertBlue", "bool", "true")),
        ))
        val types = r.project.layers.single().effects.map { it.type }
        assertEquals(listOf("blur", "pixelate"), types)
        assertEquals(8f, r.project.layers.single().effects[1].number("size").at(0.0), 0f)
        assertTrue(r.report.entries.any { it.kind == ReportKind.UNSUPPORTED && it.where.contains("invert") })
    }

    @Test
    fun unknownEffectsAreReported() {
        val r = AlightConverter.convert(scene(fx("nothingLikeIt", "")))
        assertTrue(r.project.layers.single().effects.isEmpty())
        assertTrue(r.report.entries.any { it.kind == ReportKind.UNSUPPORTED && it.message.contains("desconhecido") })
        assertFalse(abs(1.0) < 0)
    }

    @Test
    fun bendWarpKeepsTheAngleAnimationAndTheLimits() {
        val r = AlightConverter.convert(scene(fx("bendwarp",
            """<property name="amount" type="float"><kf t="0.0" v="45"/><kf t="1.0" v="-45"/></property>""" +
                prop("highLimit", "float", "0.75") + prop("lowLimit", "float", "0.25") + prop("originType", "int", "3"))))
        val e = r.project.layers.single().effects.single()
        assertEquals("bendWarp", e.type)
        assertEquals(2, e.number("angle").keyframes.size)
        assertEquals(45f, e.number("angle").at(0.0), 1e-4f); assertEquals(-45f, e.number("angle").at(2.0), 1e-4f)
        assertEquals(50f, e.number("length").at(0.0), 1e-3f)   // (0.75 - 0.25) of the 100 px tall box
    }
}

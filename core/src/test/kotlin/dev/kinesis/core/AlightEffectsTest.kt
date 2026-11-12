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
        assertEquals("bake", AlightEffectsMap.rule("swing2")!!.action)
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
        assertEquals(2, all.count { it.motionBlur })
        // The sliding picture (offset 0 -> 5000 px) ends far to the right of where it started.
        val png = LayerTree.all(p).first { it.name.startsWith("file_0000") }
        val pos = png.transform.position
        assertTrue(pos.at(png.duration).x - pos.at(0.0).x > 4000f)
        assertEquals(3, r.report.count(ReportKind.UNSUPPORTED))
    }

    @Test
    fun swingBecomesASineOnTheRotation() {
        val r = AlightConverter.convert(scene(fx("swing2", prop("freq", "float", "2") + prop("a1", "float", "30") + prop("a2", "float", "-30") + prop("phase", "float", "0") + prop("type", "int", "0"))))
        val rot = r.project.layers.single().transform.rotation
        assertTrue(rot.keyframes.size > 20)
        for (t in listOf(0.0, 0.1, 0.125, 0.3, 1.0)) assertEquals(30 * sin(2 * Math.PI * 2 * t), rot.at(t).toDouble(), 1.2)
        assertTrue(r.report.entries.any { it.kind == ReportKind.APPROXIMATED && it.where.contains("swing2") })
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
    fun scaleAssistMultipliesTheLayerScale() {
        val r = AlightConverter.convert(scene(fx("scaleassist", prop("axis", "int", "3") + """<property name="scale" type="float"><kf t="0.0" v="1.0"/><kf t="1.0" v="3.0"/></property>"""), "<scale value=\"2,2\"/>"))
        val sc = r.project.layers.single().transform.scale
        assertEquals(2f, sc.at(0.0).x, 1e-4f); assertEquals(6f, sc.at(2.0).y, 1e-4f)
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
}

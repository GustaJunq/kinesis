package dev.kinesis.core

import dev.kinesis.core.alight.AlightConverter
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.anim.at
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 6 of the Alight Motion importer: shapes and `<shadow>`. */
class AlightShapesTest {
    private fun fixture() = AlightParser.scan(javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()).scene
    private fun scene(body: String) = AlightParser.scan(
        "<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff000000\">$body</scene>".toByteArray(),
    ).scene
    private fun shape(extra: String, s: String = ".rect") =
        """<shape id="a" label="S" startTime="0" endTime="2000" fillType="color" mediaFillMode="fill" s="$s">
             <transform><location value="500,500,0"/></transform>$extra<property name="size" type="vec2" value="50,50"/></shape>"""

    @Test
    fun fixtureShadowsBecomeDropShadows() {
        val s = fixture(); val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        val withShadow = LayerTree.all(p).filter { l -> l.effects.any { it.type == "dropShadow" } }
        assertEquals(3, withShadow.size)
        val video = p.layers.first { it.name.endsWith(".mp4") }
        val fx = video.effects.single { it.type == "dropShadow" }
        // Animated opacity and size keep their two keyframes; no offset means no distance.
        assertEquals(2, fx.number("opacity").keyframes.size)
        assertEquals(2, fx.number("softness").keyframes.size)
        assertEquals(0f, fx.number("distance").at(0.0), 0f)
        assertEquals(1f, fx.color("color").at(0.0).r, 0f)
        assertTrue(ProjectValidator.validate(p).errors.toString(), ProjectValidator.validate(p).isValid)
    }

    @Test
    fun shadowOffsetBecomesDistanceAndAngle() {
        val r = AlightConverter.convert(scene(shape("""<shadow direction="outside"><color value="#ff102030"/><size value="12"/><offset value="0,30"/></shadow>""")))
        val fx = r.project.layers.single().effects.single()
        assertEquals(30f, fx.number("distance").at(0.0), 1e-4f)
        assertEquals(90f, fx.number("angle").at(0.0), 1e-4f)   // 90° = down, like Alight's +y
        assertEquals(12f, fx.number("softness").at(0.0), 0f)
        assertTrue(r.report.entries.any { it.kind == ReportKind.APPROXIMATED && it.where.contains("shadow") })
        val right = AlightConverter.convert(scene(shape("""<shadow direction="outside"><color value="#ff000000"/><size value="4"/><offset value="10,0"/></shadow>""")))
        assertEquals(0f, right.project.layers.single().effects.single().number("angle").at(0.0), 1e-4f)
    }

    @Test
    fun innerShadowIsReportedNotConverted() {
        val r = AlightConverter.convert(scene(shape("""<shadow direction="inside"><color value="#ff000000"/><size value="4"/></shadow>""")))
        assertTrue(r.project.layers.single().effects.isEmpty())
        assertEquals(1, r.report.count(ReportKind.UNSUPPORTED))
    }

    @Test
    fun otherShapeTypesBecomeRectanglesAndSaySo() {
        val r = AlightConverter.convert(scene(shape("", s = ".oval")))
        assertEquals(1, r.project.layers.size)
        assertTrue(r.report.entries.any { it.kind == ReportKind.APPROXIMATED && it.message.contains(".oval") })
        assertTrue(AlightConverter.convert(scene(shape(""))).report.entries.none { it.message.contains("convertida em retângulo") })
    }

    @Test
    fun theFlashesCoverTheFrame() {
        val s = fixture(); val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        val flash = p.layers.last()
        assertEquals("Retângulo 1", flash.name)
        // Box 200 x 200 at scale 7.2 x 5.4 = 1440 x 1080: the whole frame.
        assertEquals(200f * 7.2f, flash.shape!!.size.value.x * flash.transform.scale.value.x, 1e-2f)
        assertEquals(200f * 5.4f, flash.shape!!.size.value.y * flash.transform.scale.value.y, 1e-2f)
    }
}

package dev.kinesis.core

import dev.kinesis.core.alight.AlightConverter
import dev.kinesis.core.alight.AlightLayerKind
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 of the Alight Motion importer: media and colour layers with a static transform. */
class AlightConvertTest {
    private fun fixture() = AlightParser.scan(javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()).scene

    @Test
    fun rootLayersAreRead() {
        val s = fixture()
        assertEquals(11, s.layers.size)
        assertEquals(7, s.layers.count { it.kind == AlightLayerKind.SHAPE })
        assertEquals(3, s.layers.count { it.kind == AlightLayerKind.EMBED })
        assertEquals(1, s.layers.count { it.kind == AlightLayerKind.AUDIO })
        // Keyframes come sorted by time even though the file lists them out of order.
        val kfs = s.layers.first().transform.opacity!!.keyframes
        assertEquals(kfs.map { it.t }.sorted(), kfs.map { it.t })
    }

    @Test
    fun withAllMediaTheLayersKeepOrderAndGeometry() {
        val s = fixture()
        val r = AlightConverter.convert(s, available = s.media.map { it.uri }.toSet())
        val p = r.project
        assertTrue(ProjectValidator.validate(p).errors.toString(), ProjectValidator.validate(p).isValid)
        assertEquals(p, KnsprojJson.decode(KnsprojJson.encode(p)))
        assertEquals(1440, p.composition.width); assertEquals(19.865, p.composition.duration, 1e-9)
        // First layer of the file at the back, the four white flashes last (on top).
        assertEquals(7, p.layers.size)
        assertTrue(p.layers.first().name.startsWith("Novo projeto 185"))
        assertTrue(p.layers.takeLast(4).all { it.name == "Retângulo 1" && it.type == LayerType.SHAPE })
        // The flash: size 100 is the half-extent, so the box is 200 px and scale 7.2 x 5.4 covers the 1440 x 1080 frame.
        val flash = p.layers.last()
        assertEquals(200f, flash.shape!!.size.value.x, 0f)
        assertEquals(1440f, flash.shape!!.size.value.x * flash.transform.scale.value.x, 0.01f)
        assertEquals(1080f, flash.shape!!.size.value.y * flash.transform.scale.value.y, 0.01f)
        assertEquals(0f, flash.transform.position.value.x, 1e-3f)
        // The four flashes start at 1533, 4966, 8400 and 11733 ms and last 866 ms each.
        assertEquals(listOf(1.533, 4.966, 8.4, 11.733), p.layers.takeLast(4).map { it.start })
        assertEquals(0.866, flash.duration, 1e-9)
        // First picture: a 1440 x 1080 file in a 600 x 1080 box. Covering keeps the scale and cuts the sides with a mask.
        val pic = p.layers.first()
        assertEquals(LayerType.IMAGE, pic.type)
        assertEquals(2.4f, pic.transform.scale.value.x, 1e-4f)
        assertEquals(600f, pic.masks.single().size.value.x, 1e-3f)
        assertEquals(1080f, pic.masks.single().size.value.y, 1e-3f)
        // Assets: the media that layers use, with the pointer to fill them from.
        assertTrue(p.assets.isNotEmpty() && p.assets.size == r.media.size)
        for (a in p.assets) assertNotNull(r.media[a.path])
        assertEquals(0, r.report.count(ReportKind.MISSING_MEDIA))
        // Embedded scenes and audio are reported until their steps exist; nothing is dropped silently.
        assertEquals(4, r.report.count(ReportKind.UNSUPPORTED))
    }

    @Test
    fun missingMediaBecomesPlaceholders() {
        val r = AlightConverter.convert(fixture(), available = emptySet())
        assertEquals(3, r.report.count(ReportKind.MISSING_MEDIA))
        assertEquals(7, r.project.layers.size)
        assertTrue(r.project.layers.all { it.type == LayerType.SHAPE })
        assertTrue(r.project.assets.isEmpty() && r.media.isEmpty())
        // The placeholder keeps the box of the original layer.
        val first = r.project.layers.first()
        assertEquals(600f, first.shape!!.size.value.x, 1e-3f)
        assertEquals(1080f, first.shape!!.size.value.y, 1e-3f)
        assertTrue(ProjectValidator.validate(r.project).isValid)
    }

    private fun scene(body: String, media: String = "") = AlightParser.scan(
        "<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"2000\" bgcolor=\"#ff202020\">$media$body</scene>".toByteArray(),
    ).scene

    /** `location` is where the centre goes; `pivot` only moves the axis: the anchor lands at location + R·S·pivot. */
    @Test
    fun pivotRotationAndScaleKeepTheCentreAtLocation() {
        val s = scene(
            """<shape id="1" label="r" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect">
                 <transform><location value="500,500,0"/><scale value="2,2"/><rotation value="90"/><pivot value="100,0"/></transform>
                 <property name="size" type="vec2" value="50,50"/></shape>""",
        )
        val l = AlightConverter.convert(s).project.layers.single()
        assertEquals(100f, l.transform.anchor.value.x, 1e-3f)
        // R(90° clockwise, y down)·S(2)·(100,0) = (0,200): the anchor is 200 below the centre.
        assertEquals(0f, l.transform.position.value.x, 1e-3f)
        assertEquals(200f, l.transform.position.value.y, 1e-3f)
        assertEquals(90f, l.transform.rotation.value, 1e-4f)
    }

    /** A child is placed in the parent's space (origin at its centre), measured in the parent file's pixels. */
    @Test
    fun childOffsetsAreInTheParentsFilePixels() {
        val m = "<media uri=\"u1\" filename=\"a.png\" type=\"image/png\" width=\"2000\" height=\"2000\"/>"
        val s = scene(
            """<shape id="p" label="parent" startTime="0" endTime="1000" fillType="media" fillImage="u1" mediaFillMode="fill" s=".rect">
                 <transform><location value="500,500,0"/></transform><property name="size" type="vec2" value="540,540"/></shape>
               <shape id="c" parent="p" label="child" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect">
                 <transform><location value="10,20,0"/></transform><property name="size" type="vec2" value="10,10"/></shape>""",
            m,
        )
        val p = AlightConverter.convert(s, available = setOf("u1")).project
        val parent = p.layers[0]; val child = p.layers[1]
        assertEquals(0.54f, parent.transform.scale.value.x, 1e-4f) // 1080 box / 2000 file
        assertEquals(parent.id, child.parent)
        assertEquals(10f / 0.54f, child.transform.position.value.x, 1e-2f)
        assertEquals(20f / 0.54f, child.transform.position.value.y, 1e-2f)
        assertNull(parent.parent)
        assertTrue(p.layers[0].masks.isEmpty()) // square file in a square box: no crop
    }
}

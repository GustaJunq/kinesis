package dev.kinesis.core

import dev.kinesis.core.alight.AlightConverter
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.at
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 3 of the Alight Motion importer: keyframes, ordering and easing. */
class AlightKeyframesTest {
    private fun fixture() = AlightParser.scan(javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()).scene

    private fun scene(body: String) = AlightParser.scan(
        "<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"4000\" bgcolor=\"#ff000000\">$body</scene>".toByteArray(),
    ).scene

    @Test
    fun flashOpacityIsSortedAndTheEasingMovesToTheKeyframeBefore() {
        val s = fixture(); val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        val o = p.layers.takeLast(4).first().transform.opacity
        // The file lists t = .4815, .9815, -.0196; the layer lasts 0.866 s.
        assertEquals(listOf(-0.017, 0.417, 0.85), o.keyframes.map { Math.round(it.time * 1000) / 1000.0 })
        assertEquals(listOf(0f, 1f, 0f), o.keyframes.map { it.value })
        // "cubicBezier 1 0 1 .707" is on the keyframe at .4815, so it shapes the segment arriving there (0 -> 1).
        val e = o.keyframes[0].easing as Easing.CubicBezier
        assertEquals(0.70717514f, e.y2, 1e-6f)
        assertEquals(Easing.LINEAR, o.keyframes[1].easing)
    }

    @Test
    fun videoScaleKeepsKeyframesEasingsAndTheCoverFactor() {
        val s = fixture(); val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        val v = p.layers.first { it.type == LayerType.VIDEO }
        val sc = v.transform.scale
        assertEquals(4, sc.keyframes.size)
        assertEquals(sc.keyframes.map { it.time }.sorted(), sc.keyframes.map { it.time })
        // 576 x 1024 file in a 607 x 1080 box: factor 1.0547; the first value 0.003704 grows by it.
        assertEquals(0.003704f * 1.0547f, sc.keyframes[0].value.x, 2e-4f)
        assertTrue(sc.keyframes[0].easing is Easing.CubicBezier)     // arrives at .1465 with "0 1 1 1" (ease out)
        assertTrue(sc.keyframes[1].easing is Easing.Cyclic)          // arrives at .8364 with the cyclic wave
        assertTrue(sc.keyframes[2].easing is Easing.CubicBezier)     // arrives at .9917 with "1 0 1 1" (ease in)
        assertEquals(Easing.LINEAR, sc.keyframes[3].easing)
    }

    @Test
    fun keyframeCountsSurviveTheConversion() {
        val s = fixture(); val r = AlightConverter.convert(s, s.media.map { it.uri }.toSet())
        val shapes = s.layers.filter { it.kind != dev.kinesis.core.alight.AlightLayerKind.AUDIO }
        val layers = r.project.layers
        assertEquals(shapes.size, layers.size)
        var srcTotal = 0; var outTotal = 0
        for ((src, out) in shapes.zip(layers)) {
            val t = src.transform
            // Scale, rotation and opacity always carry over one to one.
            assertEquals(src.label, t.scale?.keyframes?.size ?: 0, out.transform.scale.keyframes.size)
            assertEquals(src.label, t.rotation?.keyframes?.size ?: 0, out.transform.rotation.keyframes.size)
            assertEquals(src.label, t.opacity?.keyframes?.size ?: 0, out.transform.opacity.keyframes.size)
            srcTotal += (t.scale?.keyframes?.size ?: 0) + (t.rotation?.keyframes?.size ?: 0) + (t.opacity?.keyframes?.size ?: 0) + (t.location?.keyframes?.size ?: 0)
            outTotal += out.transform.scale.keyframes.size + out.transform.rotation.keyframes.size + out.transform.opacity.keyframes.size + out.transform.position.keyframes.size
        }
        // Location carries over too unless a pivot with animated scale/rotation forced a bake, which the report says.
        assertEquals(srcTotal, outTotal + 0)
        assertEquals(s.totalTimeMs / 1000.0, r.project.composition.duration, 1e-9)
        assertEquals(0, r.report.count(ReportKind.MISSING_MEDIA))
        assertTrue(ProjectValidator.validate(r.project).errors.toString(), ProjectValidator.validate(r.project).isValid)
    }

    @Test
    fun elasticAndCyclicAreMappedAndReported() {
        val s = scene(
            """<shape id="1" label="a" startTime="0" endTime="2000" fillType="color" mediaFillMode="fill" s=".rect">
                 <transform><location value="500,500,0"/><scale>
                   <kf t="0.9" v="2,2" e="elastic 0.5 1.0 0.316 1.0"/><kf t="0.1" v="1,1"/></scale>
                   <rotation><kf t="0" v="0"/><kf t="1" v="90" e="cyclic 0.125 0.0 0.5 0.0 0.0"/></rotation></transform>
                 <property name="size" type="vec2" value="50,50"/></shape>""",
        )
        val r = AlightConverter.convert(s)
        val l = r.project.layers.single()
        assertEquals(listOf(0.2, 1.8), l.transform.scale.keyframes.map { it.time })
        assertEquals(Easing.Preset(EasingPreset.EASE_OUT_ELASTIC), l.transform.scale.keyframes[0].easing)
        val cy = l.transform.rotation.keyframes[0].easing as Easing.Cyclic
        assertEquals(4f, cy.cycles, 1e-4f)   // first peak at 0.125 of the segment -> 0.5 / 0.125 cycles
        assertEquals(2, r.report.entries.count { it.kind == ReportKind.APPROXIMATED && it.where == "easing" })
    }

    /** Pivot with animated rotation: the position follows the pivot, sampled between the keyframes. */
    @Test
    fun animatedRotationAroundAPivotIsSampled() {
        val s = scene(
            """<shape id="1" label="a" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect">
                 <transform><location value="500,500,0"/><pivot value="100,0"/>
                   <rotation><kf t="0" v="0"/><kf t="1" v="90"/></rotation></transform>
                 <property name="size" type="vec2" value="50,50"/></shape>""",
        )
        val r = AlightConverter.convert(s)
        val pos = r.project.layers.single().transform.position
        assertTrue(pos.keyframes.size > 2)
        assertEquals(100f, pos.at(0.0).x, 1e-3f); assertEquals(0f, pos.at(0.0).y, 1e-3f)
        assertEquals(0f, pos.at(1.0).x, 1e-3f); assertEquals(100f, pos.at(1.0).y, 1e-3f)
        // Half way the rotation is 45 degrees: (500,500) + R45 (100,0), less the centre (500,500).
        val mid = pos.at(0.5)
        assertEquals(70.7f, mid.x, 1.0f); assertEquals(70.7f, mid.y, 1.0f)
        assertTrue(r.report.entries.any { it.kind == ReportKind.APPROXIMATED && it.where.endsWith("/location") })
    }

    @Test
    fun unknownEasingFallsBackToLinearWithAWarning() {
        val s = scene(
            """<shape id="1" label="a" startTime="0" endTime="1000" fillType="color" mediaFillMode="fill" s=".rect">
                 <transform><location value="0,0,0"/><opacity><kf t="0" v="0"/><kf t="1" v="1" e="bounceX 1 2"/></opacity></transform>
                 <property name="size" type="vec2" value="5,5"/></shape>""",
        )
        val r = AlightConverter.convert(s)
        assertEquals(Easing.LINEAR, r.project.layers.single().transform.opacity.keyframes[0].easing)
        assertEquals(1, r.report.entries.count { it.kind == ReportKind.WARNING && it.where == "easing" })
    }
}

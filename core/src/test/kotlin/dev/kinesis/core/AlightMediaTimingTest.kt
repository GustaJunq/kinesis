package dev.kinesis.core

import dev.kinesis.core.alight.AlightConverter
import dev.kinesis.core.anim.at
import dev.kinesis.core.alight.AlightParser
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.LayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 5 of the Alight Motion importer: audio layers, video retime and gain. */
class AlightMediaTimingTest {
    private fun fixture() = AlightParser.scan(javaClass.getResourceAsStream("/alight/novo-projeto-185.xml")!!.readBytes()).scene

    @Test
    fun theFixtureVideoKeepsItsInPointSpeedAndSilence() {
        val s = fixture(); val p = AlightConverter.convert(s, s.media.map { it.uri }.toSet()).project
        val v = p.layers.first { it.type == LayerType.VIDEO }.video!!
        assertEquals(4.166, v.sourceStart, 1e-9)
        assertEquals(1.220736, v.speed, 1e-9)
        assertTrue("gain 0 is a muted video", v.muted)
        // (outTime - inTime) / speed is the length of the layer on the timeline, as the file says (1932 ms).
        assertEquals(1.932, (6.524 - 4.166) / v.speed, 1e-3)
    }

    @Test
    fun theFixtureAudioLayerPlaysTheWholeFile() {
        val s = fixture(); val r = AlightConverter.convert(s, s.media.map { it.uri }.toSet()); val p = r.project
        val a = p.layers.first { it.type == LayerType.AUDIO }
        assertEquals(0.0, a.start, 1e-9); assertEquals(19.865, a.duration, 1e-9)
        assertEquals(0.0, a.audio!!.sourceStart, 1e-9); assertEquals(1.0, a.audio!!.speed, 1e-9); assertEquals(false, a.audio!!.muted)
        // The sound of an .mp4 is an audio asset (here the track kept in db8765….mp4, not the shopping video).
        val asset = p.assets.first { it.id == a.audio!!.asset }
        assertEquals(AssetType.AUDIO, asset.type)
        assertEquals("content://media/external/video/media/127922", r.media[asset.path])
        assertTrue(ProjectValidator.validate(p).errors.toString(), ProjectValidator.validate(p).isValid)
    }

    @Test
    fun missingAudioIsReportedNotInvented() {
        val s = fixture(); val r = AlightConverter.convert(s)
        assertTrue(r.project.layers.none { it.type == LayerType.AUDIO })
        assertTrue(r.report.entries.any { it.kind == ReportKind.MISSING_MEDIA && it.message.contains("áudio") })
    }

    private fun scene(body: String, media: String = "") = AlightParser.scan(
        "<scene title=\"t\" width=\"1000\" height=\"1000\" fps=\"30\" totalTime=\"10000\" bgcolor=\"#ff000000\">$media$body</scene>".toByteArray(),
    ).scene

    private val mp3 = "<media uri=\"c://a\" filename=\"a.mp3\" type=\"audio/mpeg\" size=\"10\"/>"

    @Test
    fun animatedGainBecomesAVolumeCurveAndSpeedsMultiplyInsidePrecompositions() {
        val s = scene(
            """<embedScene id="e" label="E" startTime="0" endTime="6000" fillType="intrinsic" mediaFillMode="fill" speed="2.0">
                 <scene title="" width="400" height="300" fps="30" totalTime="9000" bgcolor="#00000000">
                   <audio id="a" label="A" startTime="2000" endTime="6000" src="c://a" inTime="500" speed="1.5">
                     <gain><kf t="0.0" v="1.0"/><kf t="1.0" v="0.0"/></gain></audio>
                 </scene></embedScene>""",
            mp3,
        )
        val g = AlightConverter.convert(s, setOf("c://a")).project.layers.single()
        val a = g.layers.single()
        assertEquals(1.0, a.start, 1e-9); assertEquals(2.0, a.duration, 1e-9)       // 2 s .. 6 s at speed 2
        assertEquals(0.5, a.audio!!.sourceStart, 1e-9); assertEquals(3.0, a.audio!!.speed, 1e-9) // 1.5 x 2
        assertEquals(2, a.audio!!.volume.keyframes.size)
        assertEquals(0f, a.audio!!.volume.at(2.0), 1e-6f)
    }
}

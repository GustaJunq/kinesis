package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.edit.CaptionSegment
import dev.kinesis.core.edit.CaptionStyle
import dev.kinesis.core.edit.Captions
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.SpeechAudio
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.scene.AudioClip
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.core.scene.Node2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer

class CaptionsTest {
    private val project = Factory.newProject("t", 1080, 1920, 30.0, 10.0, Color.parse("#000000"), null, "test")
    private val segs = listOf(CaptionSegment(0.5, 2.0, "Olá, mundo"), CaptionSegment(2.0, 4.5, "isto é uma legenda"))

    @Test
    fun addsAGroupWithOneTextLayerPerCaptionAndSurvivesTheFormat() {
        val (p, id) = Captions.add(project, segs)
        assertEquals(listOf(id), Captions.groupIds(p))
        assertEquals(segs, Captions.segments(p, id))
        val group = LayerTree.find(p, id)!!
        assertEquals(LayerType.GROUP, group.type)
        assertTrue(group.layers.all { it.type == LayerType.TEXT })
        // Opens again exactly as saved: nothing here needs a new file format.
        val back = KnsprojJson.decode(KnsprojJson.encode(p))
        assertEquals(segs, Captions.segments(back, id))
        assertEquals(1, back.layers.size)
    }

    @Test
    fun captionsShowOnlyDuringTheirInterval() {
        val (p, _) = Captions.add(project, segs)
        fun flat(n: dev.kinesis.core.scene.RenderNode): List<dev.kinesis.core.scene.RenderNode> =
            if (n is dev.kinesis.core.scene.GroupNode) n.children.flatMap(::flat) else listOf(n)
        fun texts(t: Double) = SceneEvaluator.evaluate(p, t).nodes.flatMap(::flat).filterIsInstance<Node2D>().map { it.layerId }
        assertEquals(0, texts(0.2).size)
        assertEquals(1, texts(1.0).size)
        assertEquals(1, texts(3.0).size)
        assertEquals(0, texts(6.0).size)
    }

    @Test
    fun styleAppliesToAllCaptionsAndIsReadBack() {
        val (p, id) = Captions.add(project, segs)
        val style = CaptionStyle(size = 0.08f, color = Color.parse("#FFD400"), outline = null, bottom = 0.2f)
        val q = Captions.applyStyle(p, id, style)
        for (l in LayerTree.find(q, id)!!.layers) {
            assertEquals(1080 * 0.08f, l.text!!.fontSize.value, 1e-3f)
            assertEquals(Color.parse("#FFD400"), l.text!!.color.value)
            assertEquals(null, l.text!!.stroke)
        }
        val read = Captions.style(q, id)
        assertEquals(0.08f, read.size, 1e-4f); assertEquals(0.2f, read.bottom, 1e-4f)
        assertEquals(segs, Captions.segments(q, id))
    }

    @Test
    fun editingTextAndIntervalsKeepsThemSortedWithoutOverlapOrGaps() {
        val (p, id) = Captions.add(project, segs)
        val edited = Captions.segments(p, id).toMutableList()
        edited[0] = edited[0].copy(text = "  Novo texto  ", end = 3.0)   // overlaps the next one
        val q = Captions.replaceSegments(p, id, edited)
        val s = Captions.segments(q, id)
        assertEquals("Novo texto", s[0].text)
        assertTrue(s[0].end <= s[1].start + 1e-9)
        assertTrue(s.all { it.end - it.start >= Captions.MIN_DURATION - 1e-9 || it.end == 10.0 })
        // empty text removes the caption
        assertEquals(1, Captions.segments(Captions.replaceSegments(q, id, s.map { if (it === s[1]) it.copy(text = " ") else it }), id).size)
    }

    @Test
    fun splitAndMerge() {
        val one = listOf(CaptionSegment(0.0, 4.0, "uma frase longa para dividir"))
        val two = Captions.split(one, 0, 2.0)
        assertEquals(2, two.size)
        assertEquals("uma frase longa para dividir", two.joinToString(" ") { it.text })
        assertEquals(2.0, two[0].end, 0.0); assertEquals(2.0, two[1].start, 0.0)
        assertEquals(one, Captions.merge(two, 0).map { it.copy(text = one[0].text) })
        assertEquals(one, Captions.split(one, 0, 0.01))
    }

    @Test
    fun srtRoundTrips() {
        val srt = Captions.toSrt(segs)
        assertTrue(srt.startsWith("1\n00:00:00,500 --> 00:00:02,000\nOlá, mundo"))
        assertEquals(segs, Captions.parseSrt(srt))
        assertEquals(segs, Captions.parseSrt(srt.replace("\n", "\r\n")))
    }

    @Test
    fun recognisedTimesFollowTheClipOnTheTimeline() {
        // The layer starts at 5 s, plays the media from 10 s at double speed, and ends at 7 s.
        val clip = AudioClip("v", "a", compStart = 5.0, compEnd = 7.0, layerOrigin = 5.0, sourceStart = 10.0, speed = 2.0,
            volume = Animatable(1f), fadeIn = 0.0, fadeOut = 0.0, layerEnd = 7.0)
        val r = listOf(CaptionSegment(9.0, 10.5, "antes"), CaptionSegment(11.0, 12.0, "dentro"), CaptionSegment(13.0, 20.0, "corta no fim"), CaptionSegment(30.0, 31.0, "fora"))
        val m = Captions.fromRecognised(clip, r)
        assertEquals(listOf("antes", "dentro", "corta no fim"), m.map { it.text })
        assertEquals(5.0, m[0].start, 1e-9); assertEquals(5.25, m[0].end, 1e-9)   // clipped to the clip start
        assertEquals(5.5, m[1].start, 1e-9); assertEquals(6.0, m[1].end, 1e-9)
        assertEquals(6.5, m[2].start, 1e-9); assertEquals(7.0, m[2].end, 1e-9)
    }

    @Test
    fun speechAudioIsDownmixedAndResampledTo16kMono() {
        // 1 s of 48 kHz stereo: left = +16384, right = -16384 cancel out; then a second with both +16384.
        val frames = 48_000
        val pcm = ShortBuffer.allocate(frames * 2 * 2)
        for (i in 0 until frames) { pcm.put(16384); pcm.put(-16384) }
        for (i in 0 until frames) { pcm.put(16384); pcm.put(16384) }
        pcm.flip()
        val a = SpeechAudio.to16kMono(pcm, 48_000, 0.0, 1.0)
        assertEquals(16_000, a.size)
        assertEquals(0f, a[100], 1e-6f)
        val b = SpeechAudio.to16kMono(pcm, 48_000, 1.0, 2.0)
        assertEquals(16_000, b.size)
        assertEquals(0.5f, b[100], 1e-4f)
        assertEquals(8_000, SpeechAudio.to16kMono(pcm, 48_000, 1.5, 99.0).size)
    }
}

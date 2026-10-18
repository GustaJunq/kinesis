package dev.kinesis.core

import dev.kinesis.core.audio.BiquadCoefficients
import dev.kinesis.core.audio.EqPresets
import dev.kinesis.core.audio.StereoEq
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.Markers
import dev.kinesis.core.edit.TimelineOps
import dev.kinesis.core.edit.TimelineScale
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.EqBand
import dev.kinesis.core.model.EqBandType
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.Marker
import dev.kinesis.core.model.MediaContent
import dev.kinesis.core.scene.AudioPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Markers, the shared timeline scale and the equalizer. */
class TimelineFeaturesTest {
    private val project = Factory.newProject("t", 1080, 1920, 30.0, 10.0, Color.parse("#000000"), null, "test")

    // ------------------------------------------------------------------ markers

    @Test
    fun markersAreAddedMovedRenamedAndRemoved() {
        var (p, a) = Markers.add(project, 2.0, "Intro")
        val (p2, b) = Markers.add(p, 5.5)
        p = p2
        assertEquals(listOf(2.0, 5.5), Markers.sorted(p).map { it.time })
        // Adding on top of an existing marker does not duplicate it.
        val (same, again) = Markers.add(p, 2.004)
        assertEquals(a, again); assertEquals(2, same.markers.size)
        // Times are kept inside the composition.
        assertEquals(10.0, Markers.find(Markers.move(p, b, 99.0), b)!!.time, 0.0)
        assertEquals(0.0, Markers.find(Markers.move(p, b, -3.0), b)!!.time, 0.0)
        assertEquals("Drop", Markers.find(Markers.rename(p, a, "  Drop "), a)!!.name)
        assertEquals(listOf(b), Markers.remove(p, a).markers.map { it.id })
        assertNull(Markers.at(p, 3.0))
        assertEquals(a, Markers.at(p, 2.005)!!.id)
    }

    @Test
    fun previousAndNextMixMarkersWithKeyframes() {
        val p = project.copy(markers = listOf(Marker("a", 1.0), Marker("b", 4.0), Marker("c", 7.0)))
        val keys = listOf(2.5, 4.0)
        assertEquals(4.0, Markers.next(p, 2.5, keys)!!, 0.0)
        assertEquals(2.5, Markers.next(p, 1.0, keys)!!, 0.0)
        assertEquals(7.0, Markers.next(p, 4.0, keys)!!, 0.0)
        assertNull(Markers.next(p, 7.0, keys))
        assertEquals(4.0, Markers.previous(p, 7.0, keys)!!, 0.0)
        assertEquals(1.0, Markers.previous(p, 2.5)!!, 0.0)
        assertNull(Markers.previous(p, 1.0))
        // Standing on a marker (within a hair) jumps to the next one, not to itself.
        assertEquals(7.0, Markers.next(p, 4.00001)!!, 0.0)
    }

    @Test
    fun markersSurviveTheFormatAreValidatedAndSnapClipsToThem() {
        val p = project.copy(markers = listOf(Marker("m1", 1.5, "Verso", "#FFC857"), Marker("m2", 8.0)))
        val text = KnsprojJson.encode(p)
        assertTrue(text.contains("\"markers\""))
        assertEquals(p.markers, KnsprojJson.decode(text).markers)
        assertFalse(KnsprojJson.encode(project).contains("markers"))   // projects without markers are written as before
        assertTrue(ProjectValidator.validate(p).errors.isEmpty())
        val bad = p.copy(markers = listOf(Marker("x", 1.0), Marker("x", -2.0)))
        assertEquals(setOf("marker.duplicateId", "marker.time"), ProjectValidator.validate(bad).errors.map { it.code }.toSet())
        assertTrue(1.5 in TimelineOps.snapPoints(p))
    }

    // ------------------------------------------------------------------ timeline scale

    @Test
    fun zoomChangesClipWidthsProportionallyAndScrollNeverDoes() {
        val viewport = 700f
        val widths = HashMap<Float, Float>()
        for (px in listOf(8f, 20f, 41f, 72f, 142f, 500f, 2000f)) for (scroll in listOf(0f, 33f, 653f, 5000f)) {
            val s = TimelineScale(px, scroll).span(start = 0.0, duration = 13.3, viewport = viewport)
            // The real width is duration × zoom whatever the scroll or the size of the viewport.
            assertEquals(13.3 * px, s.trueWidth.toDouble(), 1e-2)
            widths[px] = s.trueWidth
            // Only the part inside the track is drawn.
            assertTrue(s.left >= 0f && s.left + s.width <= viewport + 1e-3f)
        }
        assertEquals(widths.getValue(142f) / widths.getValue(72f), 142f / 72f, 1e-4f)
    }

    @Test
    fun aClipWiderThanTheViewportKeepsItsRealSizeWhenPartlyVisible() {
        // The two screenshots of the bug: 13.3 s clip, track 734 px wide. Zoomed in (142 px/s) and scrolled to 4.6 s the
        // clip must fill the whole track, not shrink to a stub at the left.
        val s = TimelineScale(142f, 4.6f * 142f).span(0.0, 13.3, 734f)
        assertEquals(0f, s.left, 1e-3f)
        assertEquals(734f, s.width, 1e-3f)
        assertTrue(s.cutStart && s.cutEnd)
        assertEquals(13.3f * 142f, s.trueWidth, 1e-2f)
        // And zoomed out it is the whole clip, uncut.
        val out = TimelineScale(41f, 0f).span(0.0, 13.3, 734f)
        assertEquals(13.3f * 41f, out.width, 1e-2f)
        assertFalse(out.cutStart || out.cutEnd)
    }

    @Test
    fun partiallyVisibleAndOffscreenClips() {
        val scale = TimelineScale(100f, 250f)          // shows 2.5 s .. 9.5 s on a 700 px track
        val left = scale.span(1.0, 3.0, 700f)           // 1..4 s: starts off-screen
        assertTrue(left.visible && left.cutStart && !left.cutEnd)
        assertEquals(0f, left.left, 0f); assertEquals(150f, left.width, 1e-3f); assertEquals(150f, left.shift, 1e-3f)
        val right = scale.span(8.0, 4.0, 700f)          // 8..12 s: ends off-screen
        assertTrue(right.visible && !right.cutStart && right.cutEnd)
        assertEquals(550f, right.left, 1e-3f); assertEquals(150f, right.width, 1e-3f)
        assertFalse(scale.span(0.0, 2.0, 700f).visible)  // ends before the visible area
        assertFalse(scale.span(10.0, 2.0, 700f).visible) // starts after it
        // A tiny clip keeps a grab-able width.
        assertEquals(12f, scale.span(5.0, 0.001, 700f, minWidth = 12f).width, 1e-3f)
    }

    @Test
    fun rulerClipsKeyframesAndPlayheadShareOneMapping() {
        val scale = TimelineScale(97f, 321f)
        for (t in listOf(0.0, 1.25, 4.0, 9.99)) assertEquals(t, scale.timeAt(scale.xOf(t)), 1e-4)
        // A keyframe at local 1.5 s of a clip starting at 2 s sits at xOf(3.5), whichever way it is computed.
        val span = scale.span(2.0, 5.0, 2000f)
        assertEquals(scale.xOf(3.5), span.trueLeft + 1.5f * 97f, 1e-3f)
        assertEquals(scale.xOf(3.5), (span.left - span.shift) + 1.5f * 97f, 1e-3f)
    }

    @Test
    fun zoomingAroundAPointKeepsThatTimeUnderTheFinger() {
        var s = TimelineScale(72f, 100f)
        val focus = 300f
        val t = s.timeAt(focus)
        for (factor in listOf(1.5f, 2f, 0.6f, 1.2f)) {
            s = s.zoomedAround(focus, factor)
            if (s.scrollPx > 0f) assertEquals(t, s.timeAt(focus), 1e-3)
        }
        assertEquals(TimelineScale.MAX_PX, TimelineScale(1900f).zoomedAround(0f, 5f).pxPerSecond, 0f)
        assertEquals(TimelineScale.MIN_PX, TimelineScale(10f).zoomedAround(0f, 0.01f).pxPerSecond, 0f)
        assertEquals(0f, TimelineScale(72f, 0f).zoomedAround(0f, 0.5f).scrollPx, 0f)
        val fit = TimelineScale.fit(13.3, 734f)
        assertEquals(0f, fit.scrollPx, 0f)
        assertTrue(13.3 * fit.pxPerSecond <= 734.0)
    }

    // ------------------------------------------------------------------ equalizer

    private fun tone(freq: Float, seconds: Double = 0.5, rate: Int = 48_000): FloatArray {
        val n = (seconds * rate).toInt()
        return FloatArray(n * 2) { i -> sin(2.0 * PI * freq * (i / 2) / rate).toFloat() * 0.5f }
    }

    private fun rms(buf: FloatArray, from: Int = buf.size / 2): Double {
        var s = 0.0
        for (i in from until buf.size) s += buf[i] * buf[i]
        return sqrt(s / (buf.size - from))
    }

    @Test
    fun peakBandBoostsAroundItsFrequencyAndLeavesTheRestAlone() {
        val band = EqBand(EqBandType.PEAK, 1000f, 12f, 2f)
        val c = BiquadCoefficients.of(band, 48_000)
        assertEquals(12f, c.magnitudeDb(1000f, 48_000), 0.05f)
        assertTrue(abs(c.magnitudeDb(100f, 48_000)) < 0.6f)
        assertTrue(abs(c.magnitudeDb(10_000f, 48_000)) < 1.0f)
        // Higher Q = narrower.
        val narrow = BiquadCoefficients.of(band.copy(q = 8f), 48_000)
        assertTrue(narrow.magnitudeDb(1400f, 48_000) < c.magnitudeDb(1400f, 48_000))
        // The filter really changes the level of a tone by about the gain.
        val a = tone(1000f); val ref = rms(a)
        StereoEq(listOf(band), 48_000).process(a, 0, a.size / 2)
        assertEquals(12.0, 20 * Math.log10(rms(a) / ref), 0.3)
    }

    @Test
    fun shelvesAndCutsAndFlatIsFree() {
        assertEquals(6f, BiquadCoefficients.of(EqBand(EqBandType.LOW_SHELF, 200f, 6f, 0.7f), 48_000).magnitudeDb(30f, 48_000), 0.3f)
        assertEquals(6f, BiquadCoefficients.of(EqBand(EqBandType.HIGH_SHELF, 5000f, 6f, 0.7f), 48_000).magnitudeDb(18_000f, 48_000), 0.3f)
        assertTrue(BiquadCoefficients.of(EqBand(EqBandType.PEAK, 300f, -9f, 1f), 48_000).magnitudeDb(300f, 48_000) < -8.9f)
        assertFalse(StereoEq(EqPresets.flat(), 48_000).isActive)
        val x = tone(440f); val y = x.copyOf()
        StereoEq(EqPresets.flat(), 48_000).process(y, 0, y.size / 2)
        assertTrue(x.contentEquals(y))
        // Presets do what they say.
        fun at(bands: List<EqBand>, f: Float) = StereoEq.responseDb(bands, f, 48_000)
        assertTrue(at(EqPresets.bassBoost(), 60f) > 4f && at(EqPresets.bassBoost(), 8000f) < 1f)
        assertTrue(at(EqPresets.trebleBoost(), 14_000f) > 4f && at(EqPresets.trebleBoost(), 100f) < 1f)
        assertTrue(at(EqPresets.vocal(), 3500f) > 2f && at(EqPresets.vocal(), 60f) < -2f)
        assertTrue(EqPresets.normalize(EqPresets.flat()).isEmpty())
        assertEquals(5, EqPresets.editable(emptyList()).size)
    }

    @Test
    fun blocksGiveTheSameResultAsOneBigBuffer() {
        val bands = EqPresets.vocal()
        val whole = tone(300f, 0.2) .also { StereoEq(bands, 48_000).process(it, 0, it.size / 2) }
        val parts = tone(300f, 0.2)
        val eq = StereoEq(bands, 48_000)
        var f = 0
        while (f < parts.size / 2) { val n = minOf(256, parts.size / 2 - f); eq.process(parts, f, n); f += n }
        for (i in whole.indices) assertEquals(whole[i], parts[i], 1e-5f)
        // After reset the memory is gone.
        eq.reset()
        val silent = FloatArray(512)
        eq.process(silent, 0, 256)
        assertTrue(silent.all { it == 0f })
    }

    @Test
    fun eqIsStoredWithTheClipAndReachesTheAudioPlan() {
        val asset = dev.kinesis.core.model.Asset("a", dev.kinesis.core.model.AssetType.AUDIO, "assets/a.m4a", duration = 4.0)
        val base = Factory.audio(project.copy(assets = listOf(asset)), 0.0, asset, "voice")
        val bands = EqPresets.bassBoost()
        val clip = base.copy(audio = MediaContent("a", eq = EqPresets.normalize(bands)))
        val p = project.copy(assets = listOf(asset), layers = listOf(clip))
        val back = KnsprojJson.decode(KnsprojJson.encode(p))
        assertEquals(bands, back.layers[0].audio!!.eq)
        assertEquals(bands, AudioPlan.clips(back).single().eq)
        assertTrue(ProjectValidator.validate(back).errors.isEmpty())
        val bad = clip.copy(audio = MediaContent("a", eq = listOf(EqBand(frequency = 5f, gain = 40f, q = 50f))))
        assertEquals(listOf("audio.eq"), ProjectValidator.validate(p.copy(layers = listOf(bad))).errors.map { it.code })
        assertEquals(LayerType.AUDIO, back.layers[0].type)
    }

    @Test
    fun version7ProjectsOpenUnchangedInVersion8() {
        val v7 = """{"format":"knsproj","formatVersion":7,"layers":[]}"""
        val p = KnsprojJson.decode(v7)
        assertEquals(dev.kinesis.core.model.FORMAT_VERSION, p.formatVersion)
        assertTrue(p.markers.isEmpty())
    }
}

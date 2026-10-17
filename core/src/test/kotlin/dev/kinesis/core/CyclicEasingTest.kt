package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.at
import dev.kinesis.core.format.EasingSerializer
import dev.kinesis.core.format.KnsprojJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The cyclic (wave) easing: a cosine wave whose cycles are skewed by `offset`. */
class CyclicEasingTest {

    @Test
    fun defaultWaveMatchesTheCurveInTheEditor() {
        // Neutral offset = the symmetric wave of v0.1.2.
        val e = Easing.Cyclic()
        assertEquals(0f, e.offset, 0f)
        assertEquals(3.5f, e.cycles, 0f)
        // 3.5 waves: crests at 1/7, 3/7, 5/7, 1; troughs at 2/7, 4/7, 6/7; it starts at 0 and ends at 1.
        for (k in 0..2) assertEquals("crest $k", 1f, e.transform((1 + 2 * k) / 7f), 1e-4f)
        assertEquals(1f, e.wave(1f), 1e-4f)
        for (k in 1..3) assertEquals("trough $k", 0f, e.transform(2 * k / 7f), 1e-4f)
        assertEquals(0f, e.transform(0f), 0f)
        assertEquals(1f, e.transform(1f), 0f)
        assertEquals(0.5f, e.transform(1f / 14f), 1e-4f)
        // The two handles of the editor: the first crest and the trough after it.
        assertEquals(1f / 7f, e.peak, 1e-6f)
        assertEquals(2f / 7f, e.trough, 1e-6f)
    }

    @Test
    fun offsetSkewsEachCycleAndKeepsTheRest() {
        val fast = Easing.Cyclic(offset = -0.8f, cycles = 3.5f)
        val slow = Easing.Cyclic(offset = 0.8f, cycles = 3.5f)
        val sym = Easing.Cyclic()
        // Negative: the crest comes early in the cycle (fast rise, slow fall); positive: late; neutral: in the middle.
        assertTrue("peaks ${fast.peak} ${sym.peak} ${slow.peak}", fast.peak < sym.peak && sym.peak < slow.peak)
        assertEquals(0.5f + 0.45f * -0.8f, fast.peakFraction, 1e-6f)
        for (e in listOf(fast, sym, slow)) {
            // The crest of the first cycle is exactly 1 and the trough exactly 0 (min/max kept).
            assertEquals(1f, e.transform(e.peak), 1e-4f)
            assertEquals(0f, e.transform(e.trough), 1e-4f)
            // The cycle length does not change: troughs stay at k / cycles for every skew.
            for (k in 1..3) assertEquals("trough $k", 0f, e.transform(k / 3.5f), 1e-4f)
            var lo = 1f; var hi = 0f
            for (i in 0..2000) { val v = e.transform(i / 2000f); lo = minOf(lo, v); hi = maxOf(hi, v) }
            assertEquals(0f, lo, 1e-4f)
            assertEquals(1f, hi, 1e-4f)
        }
        // A quarter into the first cycle the fast-rise wave is near its crest, the slow-rise one has barely started.
        val quarter = 0.25f / 3.5f
        assertTrue(fast.transform(quarter) > sym.transform(quarter))
        assertTrue(slow.transform(quarter) < sym.transform(quarter))
        assertEquals(0.5f, sym.transform(quarter), 1e-4f)
        // The offset is continuous: a tiny change of it moves the curve a tiny bit.
        for (i in 0..500) {
            val u = i / 500f
            assertEquals(Easing.Cyclic(0.30f).transform(u), Easing.Cyclic(0.3001f).transform(u), 1e-2f)
        }
    }

    @Test
    fun theCurveHasNoJumpsAndEndsOnTheKeyframeValues() {
        for (offset in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (cycles in listOf(0.5f, 1.5f, 2.5f, 3.5f, 8.5f)) {
                val e = Easing.Cyclic(offset, cycles)
                var prev = e.transform(0f)
                for (i in 1..4000) {
                    val v = e.transform(i / 4000f)
                    // 4000 samples: steepest part is bounded by the crest/skew; a real jump would be ~1.
                    assertTrue("jump at ${i / 4000f} (offset $offset, cycles $cycles): $prev -> $v", kotlin.math.abs(v - prev) < 0.35f)
                    assertTrue(v in -1e-4f..1.0001f)
                    prev = v
                }
                assertEquals(1f, e.transform(1f), 0f)
                // Just before the end the half-integer wave is at its crest too: no jump at the segment end.
                assertEquals("end (offset $offset, cycles $cycles)", 1f, e.transform(0.99999f), 1e-3f)
            }
        }
    }

    @Test
    fun legacyPhaseStillShiftsTheWholeWave() {
        val shifted = Easing.Cyclic(offset = 0f, cycles = 2f, phase = 0.1f)
        assertEquals(0.1f + 0.25f, shifted.peak, 1e-6f)
        assertEquals(1f, shifted.transform(shifted.peak), 1e-4f)
        assertEquals(0f, shifted.transform(shifted.trough), 1e-4f)
        assertTrue(Easing.Cyclic(0f, 8f).peak < Easing.Cyclic(0f, 2f).peak)
        assertTrue(shifted.wave(0f) > 0.01f)
        assertEquals(0f, shifted.transform(0f), 0f)
        assertEquals(1f, shifted.transform(1f), 0f)
    }

    @Test
    fun version6ProjectsKeepTheirLookThroughTheMigration() {
        val v6 = """{"cyclic":{"offset":0.1,"cycles":2.5}}"""
        val doc = """{"format":"knsproj","formatVersion":6,"layers":[{"id":"a","type":"shape","shape":{},"transform":{"rotation":{"keyframes":[
            {"time":0,"value":0,"easing":$v6},{"time":1,"value":90}]}}}]}"""
        val easing = KnsprojJson.decode(doc).layers[0].transform.rotation.keyframes[0].easing
        // v6 `offset` (a shift) became `phase`; the new offset (skew) is neutral.
        assertEquals(Easing.Cyclic(offset = 0f, cycles = 2.5f, phase = 0.1f), easing)
        val old = { u: Float -> (0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * 2.5 * (u - 0.1))).toFloat() }
        for (i in 1 until 100) assertEquals(old(i / 100f), easing.transform(i / 100f), 1e-5f)
    }

    @Test
    fun animatesBetweenKeyframeValuesAndHitsThem() {
        val a = Animatable(0f, listOf(Keyframe(0.0, 0f, Easing.Cyclic(0f, 1.5f)), Keyframe(3.0, 90f)))
        // 1.5 cycles: up at 1/3, down at 2/3, up again at the end.
        assertEquals(0f, a.at(0.0), 1e-3f)
        assertEquals(90f, a.at(1.0), 1e-3f)
        assertEquals(0f, a.at(2.0), 1e-3f)
        assertEquals(90f, a.at(3.0), 1e-3f)
    }

    @Test
    fun hasNoBezierHandles() {
        assertNull(Easing.Cyclic().bezierOrNull())
    }

    @Test
    fun serializesAsACyclicObjectAndRoundTrips() {
        val json = EasingSerializer.toJson(Easing.Cyclic(0.25f, 2.5f)).toString()
        assertEquals("""{"cyclic":{"offset":0.25,"cycles":2.5}}""", json)
        assertEquals(Easing.Cyclic(0.25f, 2.5f), EasingSerializer.fromJson(Json.parseToJsonElement(json)))
        // Both fields are optional.
        assertEquals(Easing.Cyclic(), EasingSerializer.fromJson(Json.parseToJsonElement("""{"cyclic":{}}""")))
        // The legacy phase is only written when it is not 0.
        assertEquals("""{"cyclic":{"offset":0,"cycles":3.5,"phase":0.1}}""", EasingSerializer.toJson(Easing.Cyclic(0f, 3.5f, 0.1f)).toString())
        val p = TestProjects.full()
        val text = KnsprojJson.encode(p)
        assertTrue(text.contains("\"cyclic\""))
        assertEquals(text, KnsprojJson.encode(KnsprojJson.decode(text)))
    }

    @Test
    fun rejectsValuesOutsideTheRanges() {
        for (bad in listOf("""{"cyclic":{"cycles":0.2}}""", """{"cyclic":{"cycles":100}}""", """{"cyclic":{"offset":2}}""", """{"cyclic":3}""")) {
            try {
                EasingSerializer.fromJson(Json.parseToJsonElement(bad))
                fail("should reject $bad")
            } catch (e: SerializationException) {
                // expected
            }
        }
    }
}

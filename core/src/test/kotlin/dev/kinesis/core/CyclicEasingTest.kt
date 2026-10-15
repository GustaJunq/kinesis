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

/** The cyclic (wave) easing: 0.5 − 0.5·cos(2π·cycles·(u − offset)). */
class CyclicEasingTest {

    @Test
    fun defaultWaveMatchesTheCurveInTheEditor() {
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
    fun offsetShiftsTheWaveAndCyclesSetsItsFrequency() {
        val shifted = Easing.Cyclic(offset = 0.1f, cycles = 2f)
        assertEquals(0.1f + 0.25f, shifted.peak, 1e-6f)
        assertEquals(1f, shifted.transform(shifted.peak), 1e-4f)
        assertEquals(0f, shifted.transform(shifted.trough), 1e-4f)
        // More cycles = faster: the first crest comes earlier.
        assertTrue(Easing.Cyclic(0f, 8f).peak < Easing.Cyclic(0f, 2f).peak)
        // A shifted wave does not start at 0, but the segment ends stay exact.
        assertTrue(shifted.wave(0f) > 0.01f)
        assertEquals(0f, shifted.transform(0f), 0f)
        assertEquals(1f, shifted.transform(1f), 0f)
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

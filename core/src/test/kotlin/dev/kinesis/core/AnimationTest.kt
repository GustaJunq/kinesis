package dev.kinesis.core

import dev.kinesis.core.TestProjects.keys
import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.Interpolators
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.anim.KeyframeOps
import dev.kinesis.core.anim.LoopMode
import dev.kinesis.core.anim.at
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTest {

    @Test
    fun everyPresetStartsAtZeroAndEndsAtOne() {
        for (p in EasingPreset.entries) {
            assertEquals("${p.id}(0)", 0f, p.transform(0f), 1e-4f)
            assertEquals("${p.id}(1)", 1f, p.transform(1f), 1e-4f)
        }
    }

    @Test
    fun cubicBezierMatchesCssReferenceValues() {
        // CSS "ease" at 50% is ~0.8024 (Chrome/WebKit reference).
        assertEquals(0.8024f, Easing.Preset(EasingPreset.EASE).transform(0.5f), 2e-3f)
        assertEquals(0.5f, Easing.Preset(EasingPreset.EASE_IN_OUT).transform(0.5f), 1e-3f)
        // Linear bezier is the identity.
        for (i in 0..10) assertEquals(i / 10f, Easing.CubicBezier(0f, 0f, 1f, 1f).transform(i / 10f), 1e-4f)
    }

    @Test
    fun monotonicPresetsNeverDecrease() {
        val monotonic = EasingPreset.entries.filter { it.bezier != null && !it.id.contains("Back") }
        for (p in monotonic) {
            var prev = 0f
            for (i in 1..100) {
                val v = p.transform(i / 100f)
                assertTrue("${p.id} decreases at ${i / 100f}", v >= prev - 1e-4f)
                prev = v
            }
        }
    }

    @Test
    fun backAndElasticOvershoot() {
        assertTrue(EasingPreset.EASE_OUT_BACK.transform(0.6f) > 1f)
        assertTrue((0..100).any { EasingPreset.EASE_OUT_ELASTIC.transform(it / 100f) > 1f })
        assertEquals(1f, EasingPreset.EASE_OUT_BOUNCE.transform(1f), 1e-4f)
    }

    @Test
    fun holdJumpsAtTheNextKeyframe() {
        val a = keys(0.0 to 0f, 1.0 to 10f, easing = Easing.HOLD)
        assertEquals(0f, a.at(0.99), 0f)
        assertEquals(10f, a.at(1.0), 0f)
    }

    @Test
    fun staticAndSingleKeyframeValues() {
        assertEquals(5f, Animatable(5f).at(123.0), 0f)
        val one = Animatable(0f, listOf(Keyframe(2.0, 7f)))
        assertEquals(7f, one.at(0.0), 0f)
        assertEquals(7f, one.at(10.0), 0f)
    }

    @Test
    fun interpolatesBetweenKeyframesAndClampsOutside() {
        val a = keys(1.0 to 0f, 3.0 to 100f, 4.0 to 50f)
        assertEquals(0f, a.at(0.0), 0f)
        assertEquals(50f, a.at(2.0), 1e-4f)
        assertEquals(100f, a.at(3.0), 1e-4f)
        assertEquals(75f, a.at(3.5), 1e-4f)
        assertEquals(50f, a.at(9.0), 0f)
    }

    @Test
    fun easingAppliesToTheOutgoingSegment() {
        val a = Animatable(0f, listOf(Keyframe(0.0, 0f, Easing.Preset(EasingPreset.EASE_IN_QUAD)), Keyframe(1.0, 1f)))
        val v = a.at(0.5)
        assertTrue("ease-in should be below linear at the midpoint, was $v", v < 0.4f)
    }

    @Test
    fun vectorsAndColorsInterpolatePerComponent() {
        val v = keys(0.0 to Vec2(0f, 100f), 2.0 to Vec2(200f, 0f))
        assertEquals(Vec2(100f, 50f), v.at(1.0))
        val c = keys(0.0 to Color(0f, 0f, 0f, 0f), 1.0 to Color(1f, 1f, 1f, 1f))
        assertEquals(0.25f, c.at(0.25).r, 1e-5f)
        assertEquals(0.25f, c.at(0.25).a, 1e-5f)
    }

    @Test
    fun loopCycleAndPingPong() {
        val cycle = keys(0.0 to 0f, 1.0 to 10f, loop = LoopMode.CYCLE)
        assertEquals(5f, cycle.at(2.5), 1e-4f)
        assertEquals(2.5f, cycle.at(10.25), 1e-3f)
        val pp = keys(0.0 to 0f, 1.0 to 10f, loop = LoopMode.PINGPONG)
        assertEquals(7.5f, pp.at(1.25), 1e-4f)
        assertEquals(2.5f, pp.at(2.25), 1e-4f)
    }

    @Test
    fun keyframeOperations() {
        var a = Animatable(1f)
        a = KeyframeOps.setValue(a, 2.0, 5f)
        assertFalse("setting a static value must not create keyframes", a.isAnimated)
        assertEquals(5f, a.value, 0f)

        a = KeyframeOps.toggle(a, 0.0, Interpolators.float)
        assertTrue(a.isAnimated)
        a = KeyframeOps.setValue(a, 2.0, 15f)
        assertEquals(2, a.keyframes.size)
        assertEquals(10f, a.at(1.0), 1e-4f)

        // Replacing a keyframe keeps its easing.
        a = KeyframeOps.setEasing(a, 0, Easing.HOLD)
        a = KeyframeOps.setValue(a, 0.0, 3f)
        assertEquals(Easing.HOLD, a.keyframes[0].easing)
        assertEquals(3f, a.keyframes[0].value, 0f)

        a = KeyframeOps.move(a, 1, 4.0)
        assertEquals(4.0, a.keyframes[1].time, 0.0)

        a = KeyframeOps.toggle(a, 4.0, Interpolators.float) // remove second
        a = KeyframeOps.toggle(a, 0.0, Interpolators.float) // remove last -> static
        assertFalse(a.isAnimated)
        assertEquals(3f, a.value, 0f)
    }

    @Test
    fun keyframesAreKeptSortedOnInsert() {
        var a = keys(0.0 to 0f, 2.0 to 2f)
        a = KeyframeOps.upsert(a, 1.0, 1f)
        assertEquals(listOf(0.0, 1.0, 2.0), a.keyframes.map { it.time })
    }
}

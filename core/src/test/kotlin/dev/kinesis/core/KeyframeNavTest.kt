package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.KeyframeNav
import dev.kinesis.core.math.Color
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectValue
import dev.kinesis.core.model.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation and removal of keyframes across every property of a layer. */
class KeyframeNavTest {
    private val project = Factory.newProject("t", 1080, 1920, 24.0, 10.0, Color.parse("#000000"), null, "test")
    private val base = Factory.shape(project, 0.0, ShapeKind.RECTANGLE, "r")

    private fun layerWithKeys(): dev.kinesis.core.model.Layer {
        val t = base.transform
        return base.copy(
            transform = t.copy(
                rotation = Animatable(0f, listOf(Keyframe(1.0, 0f), Keyframe(3.0, 90f))),
                opacity = Animatable(1f, listOf(Keyframe(1.0004, 1f), Keyframe(2.0, 0.5f))),   // 1.0004 ≈ 1.0: same stop as rotation's
            ),
            shape = base.shape!!.copy(roundness = Animatable(5f, listOf(Keyframe(2.5, 5f), Keyframe(4.0, 20f)))),
        )
    }

    @Test
    fun stopsCoverEveryPropertyAndMergeTheSameInstant() {
        val stops = KeyframeNav.stops(layerWithKeys())
        // 1.0 (rotation + opacity) is one stop; 2.0, 2.5 (shape), 3.0, 4.0.
        assertEquals(listOf(1.0, 2.0, 2.5, 3.0, 4.0), stops.map { Math.round(it * 10) / 10.0 })
        assertEquals(5, stops.size)
    }

    @Test
    fun theKeyframeCanBeRemovedWithoutBeingExactlyOnIt() {
        val layer = layerWithKeys()
        val half = 0.5 / 24.0
        // The playhead is a frame-aligned 2.5 s? No: 2.52 is within half a frame of the shape keyframe at 2.5.
        assertTrue(KeyframeNav.hasKeyframeNear(layer, 2.52, half))
        assertFalse(KeyframeNav.hasKeyframeNear(layer, 2.75, half))
        val removed = KeyframeNav.toggle(layer, 2.52, half)
        assertEquals(1, removed.shape!!.roundness.keyframes.size)
        assertEquals(4.0, removed.shape!!.roundness.keyframes[0].time, 0.0)
        // Nothing else changed.
        assertEquals(layer.transform, removed.transform)
    }

    @Test
    fun removingAStopRemovesTheKeyframesOfAllPropertiesAtThatInstant() {
        val layer = layerWithKeys()
        val removed = KeyframeNav.toggle(layer, 1.01, 0.5 / 24.0)
        assertEquals(listOf(3.0), removed.transform.rotation.keyframes.map { it.time })
        assertEquals(listOf(2.0), removed.transform.opacity.keyframes.map { it.time })
        assertEquals(2, removed.shape!!.roundness.keyframes.size)
    }

    @Test
    fun withoutAKeyframeNearTheButtonAddsOne() {
        val layer = layerWithKeys()
        val added = KeyframeNav.toggle(layer, 3.5, 0.5 / 24.0)
        // Added on the properties that are already animated, at the playhead.
        assertTrue(added.transform.rotation.keyframes.any { kotlin.math.abs(it.time - 3.5) < 1e-9 })
        assertTrue(added.shape!!.roundness.keyframes.any { kotlin.math.abs(it.time - 3.5) < 1e-9 })
        assertTrue(added.transform.position.keyframes.isEmpty())   // position was not animated
        // On a layer with no animation the first four transform properties get it.
        val fresh = KeyframeNav.toggle(base, 2.0, 0.02)
        assertTrue(fresh.transform.position.keyframes.size == 1)
        // Toggling twice returns to the starting point.
        assertTrue(KeyframeNav.stops(KeyframeNav.toggle(fresh, 2.0, 0.02)).isEmpty())
    }

    @Test
    fun effectParametersAreKeyframesOfTheLayerToo() {
        val animatedRadius = EffectValue.Num(Animatable(5f, listOf(Keyframe(6.0, 5f), Keyframe(7.0, 9f))))
        val layer = base.copy(effects = listOf(Effect("blur", params = mapOf("radius" to animatedRadius))))
        assertEquals(listOf(6.0, 7.0), KeyframeNav.stops(layer))
        val removed = KeyframeNav.toggle(layer, 6.0, 0.02)
        assertEquals(listOf(7.0), KeyframeNav.stops(removed))
    }
}

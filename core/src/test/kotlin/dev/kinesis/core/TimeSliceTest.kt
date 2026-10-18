package dev.kinesis.core

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.EffectRegistry
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.scene.Node2D
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.core.scene.TimeSlice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeSliceTest {
    @Test
    fun shiftsFollowTheOrder() {
        assertArrayEquals(intArrayOf(0, 3, 6, 9), TimeSlice.plan(4, TimeSlice.Order.FORWARD, 3).shifts)
        assertArrayEquals(intArrayOf(9, 6, 3, 0), TimeSlice.plan(4, TimeSlice.Order.BACKWARD, 3).shifts)
        // From the centre: the middle strips are the present.
        assertArrayEquals(intArrayOf(6, 2, 2, 6), TimeSlice.plan(4, TimeSlice.Order.CENTER, 4).shifts)
        val c = TimeSlice.plan(5, TimeSlice.Order.CENTER, 4).shifts
        assertArrayEquals(intArrayOf(8, 4, 0, 4, 8), c)
    }

    @Test
    fun budgetReducesStripsAndKeepsOnlySomeFrames() {
        val exact = TimeSlice.plan(24, TimeSlice.Order.FORWARD, 30)
        assertEquals(24, exact.strips)
        assertEquals(1, exact.stride)
        val weak = TimeSlice.plan(24, TimeSlice.Order.FORWARD, 30, TimeSlice.Budget(12, 100, 1))
        assertEquals(12, weak.strips)
        assertEquals(330, weak.maxShift)
        assertEquals(4, weak.stride)            // ceil(330 / 100)
        assertTrue(weak.maxShift / weak.stride + 1 <= 100)   // the cache holds at most 100 frames
    }

    @Test
    fun framesFollowTheCoarserGridOfTheOlderStrips() {
        assertEquals(40, TimeSlice.frameFor(40, 0, 4))      // the present is never snapped
        assertEquals(36, TimeSlice.frameFor(40, 4, 4))
        assertEquals(32, TimeSlice.frameFor(39, 6, 4))      // 33 → 32
        assertEquals(0, TimeSlice.frameFor(2, 10, 4))       // never before the first frame
        assertEquals(35, TimeSlice.frameFor(40, 5, 1))
        assertTrue(TimeSlice.isStored(8, 4) && !TimeSlice.isStored(9, 4) && TimeSlice.isStored(9, 1))
    }

    @Test
    fun weightsAlwaysSumToOne() {
        for (strips in listOf(2, 3, 8, 24)) for (smooth in listOf(0f, 0.25f, 0.5f, 1f)) {
            var s = 0f
            while (s <= 1f) {
                val total = (0 until strips).sumOf { TimeSlice.weight(it, strips, s, smooth).toDouble() }
                assertEquals("strips=$strips smooth=$smooth s=$s", 1.0, total, 1e-5)
                s += 0.013f
            }
        }
    }

    @Test
    fun stepsHaveHardEdgesAndSmoothMixesNeighbours() {
        assertEquals(1f, TimeSlice.weight(1, 4, 0.30f, 0f), 1e-6f)
        assertEquals(0f, TimeSlice.weight(2, 4, 0.30f, 0f), 1e-6f)
        // Just before the edge between strips 1 and 2 (s = 0.5), a smooth setting already lets strip 2 in.
        assertTrue(TimeSlice.weight(2, 4, 0.49f, 1f) > 0.3f)
        assertEquals(0.5f, TimeSlice.weight(1, 4, 0.5f, 1f), 1e-5f)
    }

    @Test
    fun positionAlongTheDirection() {
        assertEquals(0f, TimeSlice.position(-540f, 0f, 1080f, 1920f, 0f), 1e-6f)
        assertEquals(1f, TimeSlice.position(540f, 0f, 1080f, 1920f, 0f), 1e-6f)
        assertEquals(0.5f, TimeSlice.position(0f, 0f, 1080f, 1920f, 33f), 1e-6f)
        assertEquals(0f, TimeSlice.position(0f, -960f, 1080f, 1920f, 90f), 1e-6f)
        assertEquals(1f, TimeSlice.position(0f, 960f, 1080f, 1920f, 90f), 1e-6f)
    }

    @Test
    fun evaluatorGivesAccessToEarlierFramesOfTheLayerWithOnlyTheEarlierEffects() {
        val spec = EffectRegistry.require("timeSlice")
        assertTrue(spec.params.map { it.name }.containsAll(listOf("slices", "angle", "offset", "order", "smooth")))
        val p0 = Factory.newProject("t", 200, 200, 30.0, 4.0, Color.parse("#000000"), null, "test")
        val shape = Factory.shape(p0, 0.0, ShapeKind.RECTANGLE, "r")
        val moving = shape.copy(
            transform = shape.transform.copy(position = Animatable(Vec2.ZERO, listOf(Keyframe(0.0, Vec2(0f, 0f)), Keyframe(3.0, Vec2(300f, 0f))))),
            effects = listOf(Effect.create("blur"), Effect.create("timeSlice"), Effect.create("vignette")),
        )
        val p = p0.copy(layers = listOf(moving))
        val node = SceneEvaluator.evaluate(p, 1.0).nodes.single() as Node2D
        val e = node.effects.single { it.type == "timeSlice" }
        val src = e.slices
        assertNotNull(src)
        assertEquals(30, src!!.frame)
        val now = node.matrix.apply(Vec2.ZERO).x
        val earlier = (src.nodeAt(15) as Node2D)
        // Half a second earlier the rectangle was 50 px to the left, and only the effect before the TimeSlice is kept.
        assertEquals(now - 50f, earlier.matrix.apply(Vec2.ZERO).x, 0.5f)
        assertEquals(listOf("blur"), earlier.effects.map { it.type })
        assertNull(src.nodeAt(-1))
        // Before the layer starts there is nothing to show.
        assertNull(src.nodeAt(60))
    }
}

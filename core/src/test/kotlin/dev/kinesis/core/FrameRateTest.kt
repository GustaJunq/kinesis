package dev.kinesis.core

import dev.kinesis.core.edit.Factory
import dev.kinesis.core.edit.FrameRate
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.math.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateTest {
    @Test
    fun clampsToWholeNumbersInsideTheRange() {
        assertEquals(12.0, FrameRate.clamp(3.0), 0.0)
        assertEquals(60.0, FrameRate.clamp(120.0), 0.0)
        assertEquals(24.0, FrameRate.clamp(23.976), 0.0)
        assertEquals(37.0, FrameRate.clamp(36.6), 0.0)
        assertTrue(FrameRate.inRange(12.0) && FrameRate.inRange(60.0))
        assertFalse(FrameRate.inRange(11.9) || FrameRate.inRange(61.0))
    }

    @Test
    fun everyRateInTheRangeGivesAValidProjectWithConsistentFrames() {
        for (f in FrameRate.MIN..FrameRate.MAX) {
            val p = Factory.newProject("t", 1080, 1920, f.toDouble(), 2.0, Color.parse("#000000"), null, "test")
            assertTrue("fps $f", ProjectValidator.validate(p).isValid)
            assertEquals(f * 2, p.composition.frameCount)
            // The time of a frame snaps to itself.
            assertEquals(p.composition.frameTime(7), p.composition.snap(p.composition.frameTime(7)), 1e-9)
        }
    }
}

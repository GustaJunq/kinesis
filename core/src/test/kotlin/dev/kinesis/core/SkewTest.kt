package dev.kinesis.core

import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Test

class SkewTest {
    private fun Affine2D.apply(x: Float, y: Float) = Vec2(a * x + c * y + tx, b * x + d * y + ty)

    @Test
    fun skewXSlantsSideways() {
        val p = Affine2D.skew(45f, 0f).apply(0f, 10f)
        assertEquals(10f, p.x, 1e-4f); assertEquals(10f, p.y, 1e-4f)
    }

    @Test
    fun zeroSkewKeepsTheOldMatrix() {
        val a = Affine2D.layer(Vec2(5f, 6f), Vec2(1f, 2f), Vec2(2f, 3f), 30f)
        val b = Affine2D.layer(Vec2(5f, 6f), Vec2(1f, 2f), Vec2(2f, 3f), 30f, Vec2.ZERO)
        assertEquals(a, b)
    }
}

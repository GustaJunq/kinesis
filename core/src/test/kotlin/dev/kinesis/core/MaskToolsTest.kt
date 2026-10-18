package dev.kinesis.core

import dev.kinesis.core.video.MaskTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MaskToolsTest {
    @Test
    fun refineSharpensTheEdgeButStaysMonotonicAndInRange() {
        val m = FloatArray(101) { it / 100f }
        val r = MaskTools.refine(m)
        assertEquals(0f, r[0], 0f); assertEquals(0f, r[30], 1e-6f); assertEquals(1f, r[70], 1e-6f); assertEquals(1f, r[100], 0f)
        assertEquals(0.5f, r[50], 1e-6f)
        for (i in 1 until r.size) assertTrue(r[i] >= r[i - 1] - 1e-6f)
    }

    @Test
    fun smoothingAbsorbsNoiseOnASteadyEdgeButFollowsRealMotion() {
        val rnd = Random(7)
        // A steady edge pixel at 0.6 with +-0.08 network noise: raw flicker vs smoothed flicker.
        var prev: FloatArray? = null
        var rawSwing = 0.0; var smoothSwing = 0.0
        var lastRaw = 0.6f; var lastSmooth = 0.6f
        repeat(300) {
            val raw = floatArrayOf(0.6f + (rnd.nextFloat() - 0.5f) * 0.16f)
            val smooth = MaskTools.smoothInTime(prev, raw)
            rawSwing += kotlin.math.abs(raw[0] - lastRaw); smoothSwing += kotlin.math.abs(smooth[0] - lastSmooth)
            lastRaw = raw[0]; lastSmooth = smooth[0]; prev = smooth
        }
        assertTrue("smoothed flicker $smoothSwing should be well under raw $rawSwing", smoothSwing < rawSwing * 0.6)
        // A real change (pixel becomes subject): reached in one frame, no ghost.
        val moved = MaskTools.smoothInTime(floatArrayOf(0f), floatArrayOf(1f))
        assertEquals(1f, moved[0], 1e-6f)
        // First frame has nothing to smooth with.
        assertEquals(0.4f, MaskTools.smoothInTime(null, floatArrayOf(0.4f))[0], 0f)
        // No smoothing when steadiness is 0.
        assertEquals(0.3f, MaskTools.smoothInTime(floatArrayOf(0.1f), floatArrayOf(0.3f), steadiness = 0f)[0], 1e-6f)
    }

    @Test
    fun packedAlphaPutsColourOnTheLeftAndAlphaAsGreyOnTheRight() {
        val w = 3; val h = 2
        val color = IntArray(w * h) { 0xFF000000.toInt() or (it * 40 shl 16) or 0x1234 }
        val alpha = floatArrayOf(0f, 0.5f, 1f, 1f, 0.25f, 0f)
        val p = MaskTools.packAlpha(color, alpha, w, h)
        assertEquals(2 * w * h, p.size)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(color[y * w + x], p[y * 2 * w + x])
            val g = p[y * 2 * w + w + x] and 0xFF
            assertEquals((alpha[y * w + x] * 255f + 0.5f).toInt(), g)
            assertEquals(g, (p[y * 2 * w + w + x] shr 8) and 0xFF)
        }
        val cut = MaskTools.withAlpha(color, alpha)
        assertEquals(0, cut[0] ushr 24); assertEquals(255, cut[2] ushr 24)
        assertEquals(color[1] and 0xFFFFFF, cut[1] and 0xFFFFFF)
    }

    @Test
    fun rotationTurnsTheImageClockwise() {
        // 3 wide x 2 high:  1 2 3 / 4 5 6
        val src = intArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), MaskTools.rotateArgb(src, 3, 2, 90).toList())     // 2 wide x 3 high
        assertEquals(listOf(6, 5, 4, 3, 2, 1), MaskTools.rotateArgb(src, 3, 2, 180).toList())
        assertEquals(listOf(3, 6, 2, 5, 1, 4), MaskTools.rotateArgb(src, 3, 2, 270).toList())
        assertEquals(src.toList(), MaskTools.rotateArgb(src, 3, 2, 0).toList())
        assertEquals(2 to 3, MaskTools.rotatedSize(3, 2, 90)); assertEquals(3 to 2, MaskTools.rotatedSize(3, 2, 180))
        // four quarter turns are the identity
        var r = src; var w = 3; var h = 2
        repeat(4) { r = MaskTools.rotateArgb(r, w, h, 90); val s = MaskTools.rotatedSize(w, h, 90); w = s.first; h = s.second }
        assertEquals(src.toList(), r.toList())
    }

    @Test
    fun resizeKeepsCornersAndInterpolates() {
        val src = floatArrayOf(0f, 1f, 1f, 0f)           // 2x2
        val big = MaskTools.resize(src, 2, 2, 5, 5)
        assertEquals(0f, big[0], 1e-6f); assertEquals(1f, big[4], 1e-6f); assertEquals(1f, big[20], 1e-6f); assertEquals(0f, big[24], 1e-6f)
        assertEquals(0.5f, big[2], 1e-6f)
        assertEquals(0.5f, big[12], 1e-6f)               // centre = mean
    }
}

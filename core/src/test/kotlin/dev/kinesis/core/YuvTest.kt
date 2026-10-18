package dev.kinesis.core

import dev.kinesis.core.video.I420Frame
import dev.kinesis.core.video.Yuv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class YuvTest {
    private fun roundTrip(argb: IntArray, w: Int, h: Int): IntArray {
        val f = I420Frame(w, h)
        Yuv.argbToI420(argb, w, h, f)
        return Yuv.i420ToArgb(f.y, w, f.u, f.chromaWidth, f.v, f.chromaWidth, w, h)
    }

    private fun channels(c: Int) = intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)

    @Test
    fun flatColoursComeBackWithinAFewLevels() {
        for (c in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFFE03020.toInt(), 0xFF2060D0.toInt(), 0xFF30A050.toInt())) {
            val w = 8; val h = 8
            val back = roundTrip(IntArray(w * h) { c }, w, h)
            for (px in back) for (k in 0..2) assertTrue("colour %08X -> %08X".format(c, px), abs(channels(px)[k] - channels(c)[k]) <= 3)
        }
    }

    @Test
    fun greyAlphaSurvivesTheRoundTripExactlyEnoughToBeUsedAsAMask() {
        // Packed-alpha videos carry the alpha as grey: it must come back within ~1%.
        for (g in 0..255 step 5) {
            val c = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            val px = roundTrip(IntArray(16 * 16) { c }, 16, 16)[0]
            assertTrue("grey $g -> ${channels(px)[1]}", abs(channels(px)[1] - g) <= 3)
        }
    }

    @Test
    fun oddSizesAndDownSamplingWork() {
        val w = 7; val h = 5
        val f = I420Frame(w, h)
        assertEquals(4, f.chromaWidth); assertEquals(3, f.chromaHeight)
        Yuv.argbToI420(IntArray(w * h) { 0xFF336699.toInt() }, w, h, f)
        val half = Yuv.i420ToArgb(f.y, w, f.u, f.chromaWidth, f.v, f.chromaWidth, w, h, step = 2)
        assertEquals((w / 2) * (h / 2), half.size)
        assertTrue(abs(channels(half[0])[2] - 0x99) <= 3)
    }
}

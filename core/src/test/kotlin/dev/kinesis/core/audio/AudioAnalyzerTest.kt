package dev.kinesis.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioAnalyzerTest {
    private val sr = 44100

    private fun sine(freq: Double, seconds: Double, amp: Double = 1.0, channels: Int = 1, leftOnly: Boolean = false): FloatArray {
        val frames = (seconds * sr).toInt()
        val out = FloatArray(frames * channels)
        for (f in 0 until frames) {
            val v = (amp * sin(2 * PI * freq * f / sr)).toFloat()
            for (c in 0 until channels) out[f * channels + c] = if (leftOnly && c > 0) 0f else v
        }
        return out
    }

    private fun mid(a: FloatArray) = a[a.size / 2]

    @Test
    fun fullScaleSineReadsAboutZeroPointSevenInItsBandOnly() {
        for ((freq, band) in listOf(100.0 to "low", 1000.0 to "mid", 8000.0 to "high")) {
            val a = AudioAnalyzer.analyze(sine(freq, 1.0), 1, sr)
            val levels = mapOf("low" to mid(a.low), "mid" to mid(a.mid), "high" to mid(a.high))
            assertEquals("$band rms for $freq Hz", 0.707, levels.getValue(band).toDouble(), 0.05)
            for ((name, v) in levels) if (name != band) assertTrue("$name leaks $v at $freq Hz", v < 0.08f)
            assertEquals(0.707, mid(a.both).toDouble(), 0.03)
        }
    }

    @Test
    fun amplitudeScalesLinearly() {
        val loud = mid(AudioAnalyzer.analyze(sine(1000.0, 1.0, 0.8), 1, sr).both)
        val soft = mid(AudioAnalyzer.analyze(sine(1000.0, 1.0, 0.2), 1, sr).both)
        assertEquals(4.0, (loud / soft).toDouble(), 0.1)
    }

    @Test
    fun stereoChannelsAreSeparate() {
        val a = AudioAnalyzer.analyze(sine(500.0, 1.0, 1.0, channels = 2, leftOnly = true), 2, sr)
        assertEquals(0.707, mid(a.left).toDouble(), 0.03)
        assertEquals(0.0, mid(a.right).toDouble(), 1e-4)
        assertEquals(0.5, mid(a.both).toDouble(), 0.03) // sqrt((0.5 + 0) / 2)
    }

    @Test
    fun silenceAndEmptyInputAreZero() {
        val s = AudioAnalyzer.analyze(FloatArray(sr), 1, sr)
        assertEquals(0f, s.peak, 0f)
        val e = AudioAnalyzer.analyze(FloatArray(0), 1, sr)
        assertEquals(0, e.size)
        assertEquals(0f, e.at(e.both, 0.5), 0f)
    }

    @Test
    fun sizeDurationAndInterpolation() {
        val a = AudioAnalyzer.analyze(sine(1000.0, 2.0), 1, sr, rate = 30)
        assertEquals(61, a.size)
        assertEquals(2.0, a.duration, 1e-9)
        val t = 0.5 / 30
        val v0 = a.both[0]; val v1 = a.both[1]
        assertEquals((v0 + v1) / 2.0, a.at(a.both, t).toDouble(), 1e-6)
        assertEquals(0f, a.at(a.both, -1.0), 0f)
        assertEquals(0f, a.at(a.both, 3.0), 0f)
    }

    @Test
    fun sameInputGivesIdenticalOutput() {
        val pcm = sine(300.0, 0.5)
        val a = AudioAnalyzer.analyze(pcm, 1, sr).both
        val b = AudioAnalyzer.analyze(pcm, 1, sr).both
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun namedSeries() {
        val a = AudioAnalyzer.analyze(sine(100.0, 0.2), 1, sr)
        assertTrue(a.series("low") === a.low)
        assertEquals(null, a.series("nope"))
    }
}

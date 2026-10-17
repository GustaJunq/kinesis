package dev.kinesis.core.audio

import dev.kinesis.core.model.EqBand
import dev.kinesis.core.model.EqBandType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Equalizer presets (bands as the UI shows them). All gains 0 = flat = no EQ. */
object EqPresets {
    /** The five bands the panel starts with. */
    val DEFAULT_FREQUENCIES = floatArrayOf(80f, 250f, 1000f, 4000f, 12000f)

    fun flat(): List<EqBand> = DEFAULT_FREQUENCIES.mapIndexed { i, f ->
        EqBand(if (i == 0) EqBandType.LOW_SHELF else if (i == DEFAULT_FREQUENCIES.lastIndex) EqBandType.HIGH_SHELF else EqBandType.PEAK, f, 0f, if (i == 0 || i == DEFAULT_FREQUENCIES.lastIndex) 0.7f else 1f)
    }

    fun bassBoost(): List<EqBand> = flat().mapIndexed { i, b -> b.copy(gain = floatArrayOf(7f, 3f, 0f, 0f, 0f)[i]) }
    fun trebleBoost(): List<EqBand> = flat().mapIndexed { i, b -> b.copy(gain = floatArrayOf(0f, 0f, 0f, 3f, 6f)[i]) }

    /** Speech: less rumble and mud, more presence and clarity. */
    fun vocal(): List<EqBand> = flat().mapIndexed { i, b -> b.copy(gain = floatArrayOf(-6f, -2f, 1.5f, 4f, 1f)[i]) }

    /** What is stored: nothing when every gain is (almost) zero. */
    fun normalize(bands: List<EqBand>): List<EqBand> = if (bands.all { abs(it.gain) < 0.05f }) emptyList() else bands

    /** What the panel edits: the stored bands, or the flat defaults. */
    fun editable(stored: List<EqBand>): List<EqBand> = stored.ifEmpty { flat() }
}

/** Coefficients of one biquad section, normalised (a0 = 1): y = b0·x + b1·x1 + b2·x2 − a1·y1 − a2·y2. */
class BiquadCoefficients(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
    /** Magnitude of the response at [frequency] Hz for [sampleRate], in dB. */
    fun magnitudeDb(frequency: Float, sampleRate: Int): Float {
        val w = 2.0 * PI * frequency / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return (20.0 * log10(mag.coerceAtLeast(1e-9))).toFloat()
    }

    companion object {
        /** RBJ audio-EQ-cookbook filters (peaking, low shelf, high shelf). */
        fun of(band: EqBand, sampleRate: Int): BiquadCoefficients {
            val f0 = band.frequency.coerceIn(EqBand.MIN_FREQUENCY, minOf(EqBand.MAX_FREQUENCY, sampleRate * 0.45f)).toDouble()
            val gain = band.gain.coerceIn(-EqBand.MAX_GAIN, EqBand.MAX_GAIN).toDouble()
            val q = band.q.coerceIn(EqBand.MIN_Q, EqBand.MAX_Q).toDouble()
            val a = 10.0.pow(gain / 40.0)
            val w0 = 2.0 * PI * f0 / sampleRate
            val cosW = cos(w0); val sinW = sin(w0)
            val alpha = sinW / (2.0 * q)
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (band.type) {
                EqBandType.PEAK -> {
                    b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
                }
                EqBandType.LOW_SHELF -> {
                    val t = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cosW + t); b1 = 2 * a * ((a - 1) - (a + 1) * cosW); b2 = a * ((a + 1) - (a - 1) * cosW - t)
                    a0 = (a + 1) + (a - 1) * cosW + t; a1 = -2 * ((a - 1) + (a + 1) * cosW); a2 = (a + 1) + (a - 1) * cosW - t
                }
                EqBandType.HIGH_SHELF -> {
                    val t = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cosW + t); b1 = -2 * a * ((a - 1) + (a + 1) * cosW); b2 = a * ((a + 1) + (a - 1) * cosW - t)
                    a0 = (a + 1) - (a - 1) * cosW + t; a1 = 2 * ((a - 1) - (a + 1) * cosW); a2 = (a + 1) - (a - 1) * cosW - t
                }
            }
            return BiquadCoefficients((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())
        }
    }
}

/**
 * A cascade of biquads for interleaved stereo audio. Bands with (almost) no gain are skipped, so a flat equalizer
 * costs nothing. One instance per clip: it holds the filter memory between blocks.
 */
class StereoEq(bands: List<EqBand>, private val sampleRate: Int) {
    private val sections = bands.filter { abs(it.gain) >= 0.05f }.map { BiquadCoefficients.of(it, sampleRate) }
    private val x1 = FloatArray(sections.size * 2)
    private val x2 = FloatArray(sections.size * 2)
    private val y1 = FloatArray(sections.size * 2)
    private val y2 = FloatArray(sections.size * 2)

    val isActive: Boolean get() = sections.isNotEmpty()

    /** Forget the past (after a seek the old samples must not ring into the new position). */
    fun reset() {
        x1.fill(0f); x2.fill(0f); y1.fill(0f); y2.fill(0f)
    }

    /** Filters [frames] interleaved stereo frames of [buf] starting at frame [offset], in place. */
    fun process(buf: FloatArray, offset: Int, frames: Int) {
        for (s in sections.indices) {
            val c = sections[s]
            for (ch in 0..1) {
                val k = s * 2 + ch
                var px1 = x1[k]; var px2 = x2[k]; var py1 = y1[k]; var py2 = y2[k]
                var i = (offset * 2) + ch
                for (n in 0 until frames) {
                    val x = buf[i]
                    val y = c.b0 * x + c.b1 * px1 + c.b2 * px2 - c.a1 * py1 - c.a2 * py2
                    px2 = px1; px1 = x; py2 = py1; py1 = y
                    buf[i] = y
                    i += 2
                }
                x1[k] = px1; x2[k] = px2; y1[k] = py1; y2[k] = py2
            }
        }
    }

    /** Combined response at [frequency] Hz, in dB (what the panel draws). */
    fun responseDb(frequency: Float): Float = sections.sumOf { it.magnitudeDb(frequency, sampleRate).toDouble() }.toFloat()

    companion object {
        /** The combined response of [bands] without building a filter. */
        fun responseDb(bands: List<EqBand>, frequency: Float, sampleRate: Int): Float =
            bands.sumOf { BiquadCoefficients.of(it, sampleRate).magnitudeDb(frequency, sampleRate).toDouble() }.toFloat()
    }
}

package dev.kinesis.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Loudness curves of an audio clip, sampled at a fixed [rate] (values per second) from time 0 of the clip. Every
 * curve is an RMS-style level where a full-scale sine reads about 0.707, so 0..1 covers normal material.
 *
 * Computed once from decoded PCM and then only read, so previews and exports see the same numbers.
 */
class AudioAnalysis(
    val rate: Int,
    val left: FloatArray,
    val right: FloatArray,
    /** Both channels: `sqrt((left² + right²) / 2)` of the same window. */
    val both: FloatArray,
    val low: FloatArray,
    val mid: FloatArray,
    val high: FloatArray,
) {
    val size: Int get() = both.size
    val duration: Double get() = if (size == 0) 0.0 else (size - 1).toDouble() / rate

    /** [series] at [time] seconds (linear between samples; 0 outside the clip). */
    fun at(series: FloatArray, time: Double): Float {
        if (series.isEmpty() || time < 0.0) return 0f
        val x = time * rate
        val i = x.toInt()
        if (i >= series.size - 1) return if (i == series.size - 1) series[i] else 0f
        val f = (x - i).toFloat()
        return series[i] + (series[i + 1] - series[i]) * f
    }

    fun series(name: String): FloatArray? = when (name) {
        "left" -> left
        "right" -> right
        "both" -> both
        "low" -> low
        "mid" -> mid
        "high" -> high
        else -> null
    }

    /** Highest value of any curve, for normalising. */
    val peak: Float get() = listOf(left, right, both, low, mid, high).maxOf { s -> s.maxOrNull() ?: 0f }
}

object AudioAnalyzer {
    /** Edges of the bands in Hz: low = [LOW_FROM, LOW_TO), mid = [LOW_TO, MID_TO), high = [MID_TO, HIGH_TO). */
    const val LOW_FROM = 20.0
    const val LOW_TO = 250.0
    const val MID_TO = 4000.0
    const val HIGH_TO = 16000.0
    const val FFT_SIZE = 2048
    private const val RMS_WINDOW_SECONDS = 0.025
    private const val HANN_NOISE_BANDWIDTH = 1.5

    /**
     * Analyses interleaved [pcm] (`channels` ≥ 1, values -1..1). Mono audio fills both channels with the same curve;
     * with more than two channels only the first two are used. [rate] is the output values per second.
     */
    fun analyze(pcm: FloatArray, channels: Int, sampleRate: Int, rate: Int = 60): AudioAnalysis {
        require(channels >= 1 && sampleRate > 0 && rate > 0) { "invalid audio parameters" }
        val frames = pcm.size / channels
        val n = if (frames == 0) 0 else ((frames.toDouble() / sampleRate) * rate).toInt() + 1
        val left = FloatArray(n); val right = FloatArray(n); val both = FloatArray(n)
        val low = FloatArray(n); val mid = FloatArray(n); val high = FloatArray(n)
        if (n == 0) return AudioAnalysis(rate, left, right, both, low, mid, high)

        fun sample(frame: Int, ch: Int): Float {
            if (frame < 0 || frame >= frames) return 0f
            return pcm[frame * channels + min(ch, channels - 1)]
        }

        val half = max(1, (sampleRate * RMS_WINDOW_SECONDS / 2).toInt())
        val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / FFT_SIZE) }
        val re = DoubleArray(FFT_SIZE); val im = DoubleArray(FFT_SIZE)
        val binHz = sampleRate.toDouble() / FFT_SIZE
        // Amplitude of a sinusoid from its bin magnitude, with the Hann window's coherent gain (0.5) removed.
        val ampScale = 2.0 / (FFT_SIZE * 0.5)
        val lowBins = bins(LOW_FROM, LOW_TO, binHz); val midBins = bins(LOW_TO, MID_TO, binHz); val highBins = bins(MID_TO, min(HIGH_TO, sampleRate / 2.0), binHz)

        for (k in 0 until n) {
            val center = (k.toDouble() / rate * sampleRate).toInt()
            var sl = 0.0; var sr = 0.0; var count = 0
            for (f in max(0, center - half)..min(frames - 1, center + half)) {
                val l = sample(f, 0).toDouble(); val r = sample(f, 1).toDouble()
                sl += l * l; sr += r * r; count++
            }
            if (count > 0) {
                left[k] = sqrt(sl / count).toFloat(); right[k] = sqrt(sr / count).toFloat()
                both[k] = sqrt((sl + sr) / 2 / count).toFloat()
            }
            for (i in 0 until FFT_SIZE) {
                val f = center - FFT_SIZE / 2 + i
                re[i] = (sample(f, 0) + sample(f, 1)) / 2.0 * hann[i]; im[i] = 0.0
            }
            fft(re, im)
            low[k] = bandLevel(re, im, lowBins, ampScale); mid[k] = bandLevel(re, im, midBins, ampScale); high[k] = bandLevel(re, im, highBins, ampScale)
        }
        return AudioAnalysis(rate, left, right, both, low, mid, high)
    }

    private fun bins(fromHz: Double, toHz: Double, binHz: Double): IntRange {
        val a = max(1, kotlin.math.ceil(fromHz / binHz).toInt())
        val b = min(FFT_SIZE / 2 - 1, kotlin.math.ceil(toHz / binHz).toInt() - 1)
        return a..b
    }

    /**
     * RMS-equivalent level of the sinusoids in [range]: `sqrt(Σ (amplitude² / 2) / 1.5)`. The Hann window spreads a sinusoid
     * over about 1.5 bins (its noise bandwidth), so the sum of the bin powers is divided by 1.5.
     */
    private fun bandLevel(re: DoubleArray, im: DoubleArray, range: IntRange, ampScale: Double): Float {
        var s = 0.0
        for (i in range) {
            val a = sqrt(re[i] * re[i] + im[i] * im[i]) * ampScale
            s += a * a / 2
        }
        return sqrt(s / HANN_NOISE_BANDWIDTH).toFloat()
    }

    /** In-place radix-2 FFT; the size must be a power of two. */
    internal fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = i + k + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

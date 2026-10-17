package dev.kinesis.core.edit

import java.nio.ShortBuffer

/** Audio preparation for speech recognition: Whisper wants 16 kHz mono floats. */
object SpeechAudio {
    const val RATE = 16_000

    /**
     * Converts interleaved 48 kHz stereo 16-bit PCM ([pcm], as the app's audio cache stores it) between source
     * seconds [from] and [to] into 16 kHz mono floats in -1..1: each output sample is the mean of 3 frames × 2
     * channels (a box filter, enough for speech).
     */
    fun to16kMono(pcm: ShortBuffer, srcRate: Int, from: Double, to: Double): FloatArray {
        require(srcRate % RATE == 0) { "source rate must be a multiple of $RATE" }
        val ratio = srcRate / RATE
        val frames = pcm.limit() / 2
        val first = (from * srcRate).toLong().coerceIn(0, frames.toLong()).toInt()
        val last = (to * srcRate).toLong().coerceIn(first.toLong(), frames.toLong()).toInt()
        val n = (last - first) / ratio
        val out = FloatArray(n)
        val inv = 1f / (32768f * ratio * 2f)
        for (i in 0 until n) {
            var s = 0
            val base = (first + i * ratio) * 2
            for (k in 0 until ratio * 2) s += pcm.get(base + k).toInt()
            out[i] = s * inv
        }
        return out
    }
}

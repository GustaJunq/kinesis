package dev.kinesis.editor.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.AudioClip
import dev.kinesis.core.scene.AudioPlan
import dev.kinesis.editor.render.AssetFiles
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.tanh

const val MIX_RATE = 48_000
private const val TAG = "KinesisAudio"

/**
 * Decoded audio of each asset as 48 kHz stereo 16-bit PCM files in the cache directory, memory-mapped for
 * random access by the mixer. Decoding happens on background threads ([ensure]).
 */
class PcmCache(private val dir: File, private val files: AssetFiles) {
    private val mapped = ConcurrentHashMap<String, ShortBuffer>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    init {
        dir.mkdirs()
    }

    private fun cacheFile(assetId: String, source: File) =
        File(dir, "${assetId}_${source.length()}_${source.lastModified()}.pcm")

    /** PCM of [assetId] if it is ready, else null. */
    fun get(assetId: String): ShortBuffer? = mapped[assetId]

    /** Decodes (if needed) and maps the audio of [assetId]. Blocking; call off the main thread. */
    fun ensure(assetId: String): ShortBuffer? {
        mapped[assetId]?.let { return it }
        if (assetId in failed) return null
        val source = files.file(assetId)?.takeIf { it.isFile } ?: return null
        val out = cacheFile(assetId, source)
        if (!out.isFile) {
            val tmp = File(dir, out.name + ".tmp")
            val ok = try {
                decode(source, tmp)
            } catch (e: Exception) {
                Log.w(TAG, "cannot decode audio of $assetId", e)
                false
            }
            if (!ok || !tmp.renameTo(out)) {
                tmp.delete()
                failed += assetId
                return null
            }
        }
        val buffer = RandomAccessFile(out, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
        mapped[assetId] = buffer
        return buffer
    }

    /** Decodes every clip of [project] (call from a background thread). */
    fun prepare(project: KnsProject) {
        AudioPlan.clips(project).map { it.assetId }.distinct().forEach { ensure(it) }
    }

    fun isReady(project: KnsProject): Boolean = AudioPlan.clips(project).all { mapped.containsKey(it.assetId) || it.assetId in failed }

    /** Decodes the first audio track of [source] into 48 kHz stereo s16le at [target]. Returns false if there is no audio. */
    private fun decode(source: File, target: File): Boolean {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (track == null) {
            extractor.release()
            return false
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        var resampler = LinearResampler(rate, MIX_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
            val bytes = ByteArray(8192 * 4)
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    resampler = LinearResampler(rate, MIX_RATE)
                    continue
                }
                if (outIdx < 0) continue
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    val frames: Int
                    val stereo: FloatArray
                    if (floatPcm) {
                        val fb = buf.asFloatBuffer()
                        frames = fb.remaining() / channels
                        stereo = FloatArray(frames * 2)
                        for (i in 0 until frames) {
                            val l = fb.get(i * channels)
                            stereo[i * 2] = l
                            stereo[i * 2 + 1] = if (channels > 1) fb.get(i * channels + 1) else l
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        frames = sb.remaining() / channels
                        stereo = FloatArray(frames * 2)
                        for (i in 0 until frames) {
                            val l = sb.get(i * channels) / 32768f
                            stereo[i * 2] = l
                            stereo[i * 2 + 1] = if (channels > 1) sb.get(i * channels + 1) / 32768f else l
                        }
                    }
                    val resampled = resampler.process(stereo, frames)
                    var off = 0
                    while (off < resampled.size) {
                        val n = minOf(bytes.size / 2, resampled.size - off)
                        for (i in 0 until n) {
                            val s = (resampled[off + i].coerceIn(-1f, 1f) * 32767f).toInt()
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        out.write(bytes, 0, n * 2)
                        off += n
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return true
    }
}

/** Streaming linear-interpolation resampler for interleaved stereo floats. */
class LinearResampler(private val srcRate: Int, private val dstRate: Int) {
    private val step = srcRate.toDouble() / dstRate
    private var pos = 0.0
    private var prevL = 0f
    private var prevR = 0f
    private var hasPrev = false

    fun process(input: FloatArray, frames: Int): FloatArray {
        if (srcRate == dstRate) return input.copyOf(frames * 2)
        val out = ArrayList<Float>(((frames / step) * 2 + 4).toInt())
        // Virtual source: [prev] followed by input frames; index 0 = prev when available.
        val offset = if (hasPrev) 1 else 0
        val total = frames + offset
        fun sample(i: Int, ch: Int): Float = if (offset == 1 && i == 0) (if (ch == 0) prevL else prevR) else input[(i - offset) * 2 + ch]
        while (true) {
            val i0 = floor(pos).toInt()
            if (i0 + 1 >= total) break
            val frac = (pos - i0).toFloat()
            out += sample(i0, 0) + (sample(i0 + 1, 0) - sample(i0, 0)) * frac
            out += sample(i0, 1) + (sample(i0 + 1, 1) - sample(i0, 1)) * frac
            pos += step
        }
        if (frames > 0) {
            prevL = input[(frames - 1) * 2]
            prevR = input[(frames - 1) * 2 + 1]
            // Keep the position relative to the new "prev" frame (the last input frame).
            pos -= (total - 1)
            hasPrev = true
        }
        return out.toFloatArray()
    }
}

/** Mixes the project's audio clips into interleaved stereo floats at [MIX_RATE]. */
class AudioMixer(private val pcm: PcmCache) {
    @Volatile
    var clips: List<AudioClip> = emptyList()

    /** Equalizer memory of each clip, touched only by the thread that mixes (every mixer has its own). */
    private class EqState(val bands: List<dev.kinesis.core.model.EqBand>, val eq: dev.kinesis.core.audio.StereoEq, var nextTime: Double)

    private val eqStates = HashMap<String, EqState>()
    private val eqScratch = FloatArray(256 * 2)

    /** The equalizer of [clip] ready for a block starting at [start], or null when the clip has none. */
    private fun eqFor(clip: AudioClip, start: Double): dev.kinesis.core.audio.StereoEq? {
        if (clip.eq.isEmpty()) { eqStates.remove(clip.layerId); return null }
        var st = eqStates[clip.layerId]
        if (st == null || st.bands != clip.eq) {
            st = EqState(clip.eq, dev.kinesis.core.audio.StereoEq(clip.eq, MIX_RATE), start)
            eqStates[clip.layerId] = st
        } else if (kotlin.math.abs(start - st.nextTime) > 2.0 / MIX_RATE) {
            st.eq.reset() // a seek or a loop: the old samples must not ring into the new position
        }
        return st.eq.takeIf { it.isActive }
    }

    fun setProject(project: KnsProject) {
        clips = AudioPlan.clips(project)
    }

    /** Fills [out] (2 × [frames] floats) with the mix starting at composition time [start]. */
    fun mix(start: Double, frames: Int, out: FloatArray) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val end = start + frames.toDouble() / MIX_RATE
        for (clip in clips) {
            if (clip.compEnd <= start || clip.compStart >= end) continue
            val data = pcm.get(clip.assetId) ?: continue
            val total = data.limit() / 2
            val block = 256
            val eq = eqFor(clip, start)
            var f = 0
            while (f < frames) {
                val n = minOf(block, frames - f)
                val t0 = start + f.toDouble() / MIX_RATE
                val t1 = start + (f + n).toDouble() / MIX_RATE
                val g0 = clip.gainAt(t0)
                val g1 = clip.gainAt(minOf(t1, clip.compEnd - 1e-6))
                if (g0 > 0f || g1 > 0f) {
                    // With an equalizer the clip's block goes to a scratch buffer first, is filtered, then added.
                    val dst = if (eq != null) eqScratch else out
                    val base = if (eq != null) 0 else f
                    if (eq != null) java.util.Arrays.fill(eqScratch, 0, n * 2, 0f)
                    for (k in 0 until n) {
                        val t = t0 + k.toDouble() / MIX_RATE
                        if (t < clip.compStart || t >= clip.compEnd) continue
                        val src = clip.sourceTime(t) * MIX_RATE
                        val i0 = floor(src).toInt()
                        if (i0 < 0 || i0 + 1 >= total) continue
                        val frac = (src - i0).toFloat()
                        val g = g0 + (g1 - g0) * (k.toFloat() / n)
                        val l = (data.get(i0 * 2) + (data.get(i0 * 2 + 2) - data.get(i0 * 2)) * frac) / 32768f
                        val r = (data.get(i0 * 2 + 1) + (data.get(i0 * 2 + 3) - data.get(i0 * 2 + 1)) * frac) / 32768f
                        dst[(base + k) * 2] += l * g
                        dst[(base + k) * 2 + 1] += r * g
                    }
                    if (eq != null) {
                        eq.process(eqScratch, 0, n)
                        for (i in 0 until n * 2) out[f * 2 + i] += eqScratch[i]
                    }
                }
                f += n
            }
            eqStates[clip.layerId]?.nextTime = end
        }
        // Soft limiter: transparent below ~-3 dBFS, smooth above.
        for (i in 0 until frames * 2) {
            val v = out[i]
            if (v > 0.7f || v < -0.7f) out[i] = tanh(v.toDouble()).toFloat()
        }
    }
}

/** Plays the mix through an AudioTrack while the preview is playing. */
class PreviewAudio(private val mixer: AudioMixer) {
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start(fromSeconds: Double, endSeconds: Double) {
        stop()
        if (mixer.clips.isEmpty()) return
        running = true
        thread = Thread({ loop(fromSeconds, endSeconds) }, "kinesis-preview-audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop(from: Double, end: Double) {
        val minBuf = AudioTrack.getMinBufferSize(MIX_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(MIX_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(maxOf(minBuf, 4096 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack unavailable", e)
            return
        }
        val frames = 1024
        val mix = FloatArray(frames * 2)
        val pcm = ShortArray(frames * 2)
        var t = from
        try {
            track.play()
            while (running && t < end) {
                mixer.mix(t, frames, mix)
                for (i in pcm.indices) pcm[i] = (mix[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                track.write(pcm, 0, pcm.size)
                t += frames.toDouble() / MIX_RATE
            }
        } finally {
            runCatching { track.pause(); track.flush() }
            track.release()
        }
    }
}

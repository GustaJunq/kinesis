package dev.kinesis.editor.media

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val TAG = "KinesisExport"

/** Minimal EGL setup for rendering into a recordable window surface (an encoder's input surface). */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("No EGL display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("eglInitialize failed")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw IllegalStateException("No suitable EGL config for recording")
        }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext failed")
    }

    fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            .also { if (it == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface failed") }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw IllegalStateException("eglMakeCurrent failed")
    }

    fun swap(surface: EGLSurface) = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) = EGLExt.eglPresentationTimeANDROID(display, surface, nanos)

    fun releaseSurface(surface: EGLSurface) = EGL14.eglDestroySurface(display, surface)

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        // The display is shared with the preview's GLSurfaceView, so it is not terminated here.
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

enum class ExportResolution(val shortSide: Int?) { ORIGINAL(null), P720(720), P1080(1080), P2160(2160) }
enum class ExportQuality(val bitsPerPixel: Float) { STANDARD(0.08f), HIGH(0.14f), MAXIMUM(0.24f) }

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val quality: ExportQuality = ExportQuality.HIGH,
    val includeAudio: Boolean = true,
)

class ExportCancelled : Exception("Export cancelled")

/** An export that stopped while drawing frame [time] (seconds) of the composition, in layer [layerId]. */
class ExportFailure(cause: Throwable, val time: Double, val layerId: String?) :
    Exception(cause.message ?: cause.javaClass.simpleName, cause) {
    /** The device ran out of (GPU or Java) memory. */
    val outOfMemory: Boolean = cause is OutOfMemoryError ||
        (cause.message ?: "").contains("memory", ignoreCase = true) ||
        (cause is MediaCodec.CodecException && (cause.message ?: "").contains("NO_MEMORY", ignoreCase = true))
}

/** Seconds of video per block of a resumable export. */
const val SEGMENT_SECONDS = 5.0

/** Bumped when the block layout changes, so old blocks are never reused. */
private const val FORMAT = 1

/** Renders a project to an H.264/AAC MP4 file. Blocking: run on a dedicated thread. */
class VideoExporter(
    private val assets: AssetManager,
    private val files: AssetFiles,
    private val pcm: PcmCache,
) {
    /** Output size for [settings]: even dimensions the AVC encoder accepts. [packAlpha] doubles the width. */
    fun outputSize(project: KnsProject, settings: ExportSettings, packAlpha: Boolean = false): Pair<Int, Int> {
        val comp = project.composition
        var w = comp.width.toFloat()
        var h = comp.height.toFloat()
        settings.resolution.shortSide?.let { side ->
            val s = side / min(w, h)
            w *= s
            h *= s
        }
        if (packAlpha) w *= 2
        // Packed frames are split in two equal halves: keep the width a multiple of 4.
        val step = if (packAlpha) 4 else 2
        var width = (w.roundToInt() / step) * step
        var height = (h.roundToInt() / 2) * 2
        val caps = encoderCapabilities()
        if (caps != null) {
            var tries = 0
            while (!caps.isSizeSupported(width, height) && tries++ < 20) {
                width = ((width * 0.9f).roundToInt() / 16) * 16
                height = ((height * 0.9f).roundToInt() / 16) * 16
            }
        }
        return max(16, width) to max(16, height)
    }

    private fun encoderCapabilities(): MediaCodecInfo.VideoCapabilities? = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
            ?.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)?.videoCapabilities
    } catch (e: Exception) {
        null
    }

    /**
     * Renders [project] to [out]. The video is encoded in blocks of [SEGMENT_SECONDS] kept in [workDir]: an export
     * that failed, was cancelled or was interrupted resumes from the blocks already made, as long as the project
     * and the settings did not change. The blocks are joined (no re-encoding) at the end.
     *
     * [range] limits the export to part of the composition; [packAlpha] writes a transparent-background render as
     * colour + alpha side by side (no audio). A failure while rendering a frame is reported as [ExportFailure]
     * with the composition time and the layer being drawn.
     */
    fun export(
        project: KnsProject,
        settings: ExportSettings,
        out: File,
        progress: (Float) -> Unit,
        cancelled: () -> Boolean,
        workDir: File? = null,
        range: ClosedFloatingPointRange<Double>? = null,
        packAlpha: Boolean = false,
    ) {
        val comp = project.composition
        val (width, height) = outputSize(project, settings, packAlpha)
        val total = comp.frameCount
        val first = range?.let { ceil(it.start * comp.fps - 1e-6).toInt() }?.coerceIn(0, total) ?: 0
        val last = range?.let { ceil(it.endInclusive * comp.fps - 1e-6).toInt() }?.coerceIn(first, total) ?: total
        if (last <= first) throw IllegalArgumentException("Nothing to export")

        val mixer = AudioMixer(pcm)
        mixer.setProject(project)
        val withAudio = settings.includeAudio && !packAlpha && range == null && mixer.clips.isNotEmpty()

        val key = sha256("${KnsprojJson.encode(project)}|$settings|${width}x$height|$first-$last|$packAlpha|$FORMAT")
        val root = workDir ?: File(out.parentFile, ".kinesis-blocks")
        root.listFiles()?.filter { it.isDirectory && it.name != key }?.forEach { it.deleteRecursively() }
        val dir = File(root, key).apply { mkdirs() }

        val segmentFrames = max(1, (SEGMENT_SECONDS * comp.fps).roundToInt())
        val segments = (first until last step segmentFrames).map { a -> a until min(last, a + segmentFrames) }
        val frames = last - first
        val weightAudio = if (withAudio) 0.04f else 0f
        fun report(done: Int) = progress(done.toFloat() / frames * (0.97f - weightAudio))

        val renderer = SegmentRenderer(project, width, height, packAlpha)
        try {
            var done = 0
            for ((i, seg) in segments.withIndex()) {
                val file = File(dir, "video-$i.mp4")
                if (!file.isFile) {
                    if (cancelled()) throw ExportCancelled()
                    val tmp = File(dir, "video-$i.tmp")
                    renderer.encode(seg, tmp, settings, cancelled) { n -> report(done + n) }
                    if (!tmp.renameTo(file)) throw IllegalStateException("Cannot keep block $i")
                }
                done += seg.count()
                report(done)
            }
        } finally {
            renderer.release()
        }

        val audioFile = File(dir, "audio.m4a")
        if (withAudio && !audioFile.isFile) {
            if (cancelled()) throw ExportCancelled()
            pcm.prepare(project)
            val tmp = File(dir, "audio.tmp")
            encodeAudio(mixer, comp.duration, tmp)
            if (!tmp.renameTo(audioFile)) throw IllegalStateException("Cannot keep audio")
        }
        progress(0.97f)
        if (cancelled()) throw ExportCancelled()
        val starts = segments.map { ((comp.frameTime(it.first) - comp.frameTime(first)) * 1_000_000.0).roundToLong() }
        try {
            join(segments.indices.map { File(dir, "video-$it.mp4") }, starts, audioFile.takeIf { withAudio }, out)
        } catch (e: Exception) {
            out.delete()
            // Blocks that cannot be joined (e.g. the encoder changed its parameters) are made again next time.
            dir.deleteRecursively()
            throw e
        }
        dir.deleteRecursively()
        progress(1f)
    }

    /** Encodes blocks of frames, keeping one GL context and renderer for the whole export. */
    private inner class SegmentRenderer(private val project: KnsProject, private val width: Int, private val height: Int, private val packAlpha: Boolean) {
        private var egl: EglCore? = null
        private var resources: RenderResources? = null
        private var compositor: Compositor? = null
        private val comp = project.composition
        private val renderSettings = RenderSettings(motionBlurSamples = comp.motionBlur.samples, transparentBackground = packAlpha)

        fun encode(frames: IntRange, file: File, settings: ExportSettings, cancelled: () -> Boolean, progress: (Int) -> Unit) {
            val fps = comp.fps
            var bitrate = (width * height * fps * settings.quality.bitsPerPixel).roundToInt()
            encoderCapabilities()?.bitrateRange?.let { bitrate = bitrate.coerceIn(it.lower, it.upper) }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            var inputSurface: Surface? = null
            var eglSurface: EGLSurface? = null
            val muxer = Muxer(file, 1)
            var success = false
            var time = comp.frameTime(frames.first)
            try {
                video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = video.createInputSurface()
                video.start()
                val core = egl ?: EglCore().also { egl = it }
                eglSurface = core.createWindowSurface(inputSurface)
                core.makeCurrent(eglSurface)
                val res = resources ?: RenderResources(assets, files).also { resources = it }
                val drawer = compositor ?: Compositor(res).also { compositor = it }
                val origin = comp.frameTime(frames.first)
                for ((n, frame) in frames.withIndex()) {
                    if (cancelled()) throw ExportCancelled()
                    time = comp.frameTime(frame)
                    val target = try {
                        val t = drawer.render(project, SceneEvaluator.evaluate(project, time), if (packAlpha) width / 2 else width, height, renderSettings)
                        val err = GLES30.glGetError()
                        if (err == GLES30.GL_OUT_OF_MEMORY) throw OutOfMemoryError("GPU out of memory")
                        t
                    } catch (e: ExportCancelled) {
                        throw e
                    } catch (e: Throwable) {
                        throw ExportFailure(e, time, drawer.currentLayerId)
                    }
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                    if (packAlpha) drawer.presentPacked(target, width, height) else drawer.present(target, 0, 0, width, height)
                    core.setPresentationTime(eglSurface, ((time - origin) * 1_000_000_000L).toLong())
                    core.swap(eglSurface)
                    drain(video, muxer, isVideo = true, endOfStream = false)
                    progress(n + 1)
                }
                video.signalEndOfInputStream()
                drain(video, muxer, isVideo = true, endOfStream = true)
                muxer.stop()
                success = true
            } catch (e: ExportCancelled) {
                throw e
            } catch (e: ExportFailure) {
                throw e
            } catch (e: Throwable) {
                // Encoder or surface trouble: still say where it happened.
                throw ExportFailure(e, time, compositor?.currentLayerId)
            } finally {
                runCatching { eglSurface?.let { egl?.releaseSurface(it) } }
                runCatching { video.stop() }
                runCatching { video.release() }
                runCatching { inputSurface?.release() }
                muxer.release()
                if (!success) file.delete()
            }
        }

        fun release() {
            runCatching { compositor?.release() }
            runCatching { resources?.release() }
            runCatching { egl?.release() }
        }
    }

    private fun encodeAudio(mixer: AudioMixer, duration: Double, file: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = Muxer(file, 1)
        var success = false
        try {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MIX_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val pump = AudioPump(codec, mixer, muxer, duration)
            var t = 0.0
            while (t < duration) {
                t += 0.25
                pump.feedUntil(t)
            }
            pump.finish()
            muxer.stop()
            success = true
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            muxer.release()
            if (!success) file.delete()
        }
    }

    /** Joins the video blocks (shifted to [starts], µs) and the audio track into [out] without re-encoding. */
    private fun join(blocks: List<File>, starts: List<Long>, audio: File?, out: File) {
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            var videoFormat: MediaFormat? = null
            val extractors = blocks.map { f -> MediaExtractor().apply { setDataSource(f.path) } }
            try {
                for (ex in extractors) {
                    val fmt = ex.getTrackFormat(0)
                    val reference = videoFormat
                    if (reference == null) videoFormat = fmt
                    else if (!sameConfig(reference, fmt)) throw IllegalStateException("Video blocks have different encoder settings")
                }
                val videoTrack = muxer.addTrack(videoFormat!!)
                val audioEx = audio?.let { f -> MediaExtractor().apply { setDataSource(f.path) } }
                try {
                    val audioTrack = audioEx?.let { muxer.addTrack(it.getTrackFormat(0)) } ?: -1
                    muxer.start()
                    started = true
                    val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    fun copy(ex: MediaExtractor, track: Int, offsetUs: Long) {
                        ex.selectTrack(0)
                        while (true) {
                            buffer.clear()
                            val size = ex.readSampleData(buffer, 0)
                            if (size < 0) break
                            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, size, ex.sampleTime + offsetUs, flags)
                            muxer.writeSampleData(track, buffer, info)
                            ex.advance()
                        }
                    }
                    extractors.forEachIndexed { i, ex -> copy(ex, videoTrack, starts[i]) }
                    audioEx?.let { copy(it, audioTrack, 0L) }
                } finally {
                    audioEx?.release()
                }
            } finally {
                extractors.forEach { it.release() }
            }
            muxer.stop()
        } finally {
            if (!started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun sameConfig(a: MediaFormat, b: MediaFormat): Boolean {
        fun csd(f: MediaFormat, k: String) = if (f.containsKey(k)) f.getByteBuffer(k)?.duplicate() else null
        if (a.getInteger(MediaFormat.KEY_WIDTH) != b.getInteger(MediaFormat.KEY_WIDTH)) return false
        if (a.getInteger(MediaFormat.KEY_HEIGHT) != b.getInteger(MediaFormat.KEY_HEIGHT)) return false
        return csd(a, "csd-0") == csd(b, "csd-0") && csd(a, "csd-1") == csd(b, "csd-1")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Copies encoded output into the muxer. With [endOfStream], waits for the end-of-stream buffer. */
    private fun drain(codec: MediaCodec, muxer: Muxer, isVideo: Boolean, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 500) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> muxer.addTrack(codec.outputFormat, isVideo)
                idx >= 0 -> {
                    idle = 0
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.write(isVideo, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Feeds mixed PCM to the AAC encoder in step with the video frames. */
    private inner class AudioPump(private val codec: MediaCodec, private val mixer: AudioMixer, private val muxer: Muxer, duration: Double) {
        private val totalFrames = (duration * MIX_RATE).toLong()
        private var queued = 0L
        private val chunk = 1024
        private val mix = FloatArray(chunk * 2)

        fun feedUntil(seconds: Double) {
            val until = min(totalFrames, (seconds * MIX_RATE).toLong())
            var spins = 0
            while (queued < until && spins++ < 1000) {
                val idx = codec.dequeueInputBuffer(5_000)
                if (idx < 0) {
                    drain(codec, muxer, isVideo = false, endOfStream = false)
                    continue
                }
                val buf = codec.getInputBuffer(idx)!!
                buf.clear()
                val frames = min(chunk.toLong(), min(until - queued, (buf.capacity() / 4).toLong())).toInt()
                mixer.mix(queued.toDouble() / MIX_RATE, frames, mix)
                val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until frames * 2) shorts.put((mix[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                codec.queueInputBuffer(idx, 0, frames * 4, queued * 1_000_000L / MIX_RATE, 0)
                queued += frames
                drain(codec, muxer, isVideo = false, endOfStream = false)
            }
        }

        fun finish() {
            feedUntil(totalFrames.toDouble() / MIX_RATE)
            var idx = -1
            var tries = 0
            while (idx < 0 && tries++ < 200) {
                idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) drain(codec, muxer, isVideo = false, endOfStream = false)
            }
            if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, queued * 1_000_000L / MIX_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(codec, muxer, isVideo = false, endOfStream = true)
        }
    }

    /** MediaMuxer that buffers samples until every expected track has been added. */
    private class Muxer(file: File, private val expectedTracks: Int) {
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var videoTrack = -1
        private var audioTrack = -1
        private var started = false
        private var stopped = false
        private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()

        fun addTrack(format: MediaFormat, isVideo: Boolean) {
            if (isVideo && videoTrack < 0) videoTrack = muxer.addTrack(format)
            if (!isVideo && audioTrack < 0) audioTrack = muxer.addTrack(format)
            val added = (if (videoTrack >= 0) 1 else 0) + (if (audioTrack >= 0) 1 else 0)
            if (!started && added == expectedTracks) {
                muxer.start()
                started = true
                pending.forEach { (v, b, i) -> muxer.writeSampleData(if (v) videoTrack else audioTrack, b, i) }
                pending.clear()
            }
        }

        fun write(isVideo: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (started) {
                muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buf, info)
            } else {
                val copy = ByteBuffer.allocateDirect(info.size)
                copy.put(buf)
                copy.flip()
                val ci = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending += Triple(isVideo, copy, ci)
            }
        }

        fun stop() {
            if (started && !stopped) {
                muxer.stop()
                stopped = true
            }
        }

        fun release() {
            runCatching { stop() }
            runCatching { muxer.release() }
        }
    }
}

/** Publishes an exported MP4 to the gallery (Movies/Kinesis). */
object VideoGallery {
    fun save(context: Context, file: File, displayName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Kinesis")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                Log.w(TAG, "cannot publish video", e)
                resolver.delete(uri, null, null)
                null
            }
        } else {
            // Before Android 10 the video stays in the app's Movies folder; it is shared from there.
            null
        }
    }
}

package dev.kinesis.editor.media

import android.content.ContentValues
import android.content.Context
import android.content.res.AssetManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

/** Renders a project to an H.264/AAC MP4 file. Blocking: run on a dedicated thread. */
class VideoExporter(
    private val assets: AssetManager,
    private val files: AssetFiles,
    private val pcm: PcmCache,
) {
    /** Output size for [settings]: even dimensions the AVC encoder accepts. */
    fun outputSize(project: KnsProject, settings: ExportSettings): Pair<Int, Int> {
        val comp = project.composition
        var w = comp.width.toFloat()
        var h = comp.height.toFloat()
        settings.resolution.shortSide?.let { side ->
            val s = side / min(w, h)
            w *= s
            h *= s
        }
        var width = (w.roundToInt() / 2) * 2
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

    fun export(project: KnsProject, settings: ExportSettings, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val comp = project.composition
        val (width, height) = outputSize(project, settings)
        val fps = comp.fps
        val frames = comp.frameCount

        val mixer = AudioMixer(pcm)
        mixer.setProject(project)
        val withAudio = settings.includeAudio && mixer.clips.isNotEmpty()
        if (withAudio) {
            progress(0f)
            pcm.prepare(project)
            if (cancelled()) throw ExportCancelled()
        }

        var bitrate = (width * height * fps * settings.quality.bitsPerPixel).roundToInt()
        encoderCapabilities()?.bitrateRange?.let { bitrate = bitrate.coerceIn(it.lower, it.upper) }
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var audio: MediaCodec? = null
        var egl: EglCore? = null
        var eglSurface: EGLSurface? = null
        var resources: RenderResources? = null
        var compositor: Compositor? = null
        var inputSurface: Surface? = null
        val muxer = Muxer(out, if (withAudio) 2 else 1)
        var success = false
        try {
            video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = video.createInputSurface()
            video.start()
            if (withAudio) {
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MIX_RATE, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                    }
                    configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }
            egl = EglCore()
            eglSurface = egl.createWindowSurface(inputSurface)
            egl.makeCurrent(eglSurface)
            resources = RenderResources(assets, files)
            compositor = Compositor(resources)
            val settingsMb = RenderSettings(motionBlurSamples = comp.motionBlur.samples)
            val audioPump = audio?.let { AudioPump(it, mixer, muxer, comp.duration) }

            for (frame in 0 until frames) {
                if (cancelled()) throw ExportCancelled()
                val t = comp.frameTime(frame)
                val scene = SceneEvaluator.evaluate(project, t)
                val target = compositor.render(project, scene, width, height, settingsMb)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                compositor.present(target, 0, 0, width, height)
                egl.setPresentationTime(eglSurface, (t * 1_000_000_000L).toLong())
                egl.swap(eglSurface)
                drain(video, muxer, isVideo = true, endOfStream = false)
                audioPump?.feedUntil(t + comp.frameDuration)
                progress((frame + 1).toFloat() / frames)
            }
            video.signalEndOfInputStream()
            drain(video, muxer, isVideo = true, endOfStream = true)
            audioPump?.finish()
            muxer.stop()
            success = true
        } finally {
            runCatching { compositor?.release() }
            runCatching { resources?.release() }
            runCatching { eglSurface?.let { egl?.releaseSurface(it) } }
            runCatching { egl?.release() }
            runCatching { video.stop() }
            runCatching { video.release() }
            runCatching { inputSurface?.release() }
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            muxer.release()
            if (!success) out.delete()
        }
    }

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

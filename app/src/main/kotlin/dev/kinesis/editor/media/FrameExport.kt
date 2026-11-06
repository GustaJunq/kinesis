package dev.kinesis.editor.media

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Bundle
import dev.kinesis.core.export.GifEncoder
import dev.kinesis.core.export.MovWriter
import dev.kinesis.core.export.PngSequenceWriter
import dev.kinesis.core.export.WebmWriter
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.scene.SceneEvaluator
import dev.kinesis.editor.render.AssetFiles
import dev.kinesis.editor.render.Compositor
import dev.kinesis.editor.render.RenderResources
import dev.kinesis.editor.render.RenderSettings
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Output formats. All but MP4 can keep the transparency of the composition. */
enum class ExportFormat(val extension: String, val mime: String, val supportsAlpha: Boolean) {
    MP4("mp4", "video/mp4", false),
    GIF("gif", "image/gif", true),
    WEBM("webm", "video/webm", true),
    MOV("mov", "video/quicktime", true),
    PNG_SEQUENCE("zip", "application/zip", true),
}

/** Offscreen GL context (1×1 pbuffer): the compositor draws into its own framebuffers. */
private class OffscreenEgl : AutoCloseable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no ES3 pbuffer config" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}

/**
 * Exports frame by frame to GIF, WebM (VP8 + alpha), MOV (PNG frames) or a PNG sequence. Each frame is rendered by
 * the same compositor as the MP4 export, read back as RGBA and handed to the writer of the format. With
 * [ExportSettings.transparent] the composition background is left out, so the files keep the alpha channel.
 * Blocking: run on a dedicated thread.
 */
class FrameExporter(private val assets: AssetManager, private val files: AssetFiles) {

    fun outputSize(project: KnsProject, settings: ExportSettings): Pair<Int, Int> {
        val comp = project.composition
        var w = comp.width.toFloat(); var h = comp.height.toFloat()
        settings.resolution.shortSide?.let { side -> val s = side / min(w, h); if (s < 1f || settings.format != ExportFormat.GIF) { w *= s; h *= s } }
        // VP8 and most decoders want even sizes.
        return max(2, (w.roundToInt() / 2) * 2) to max(2, (h.roundToInt() / 2) * 2)
    }

    fun export(project: KnsProject, settings: ExportSettings, out: File, progress: (Float) -> Unit, cancelled: () -> Boolean) {
        val comp = project.composition
        val fps = (settings.fps ?: comp.fps).coerceIn(1.0, 120.0)
        val frames = max(1, ceil(comp.duration * fps - 1e-6).toInt())
        val (w, h) = outputSize(project, settings)
        val transparent = settings.transparent && settings.format.supportsAlpha
        val sink: FrameSink = when (settings.format) {
            ExportFormat.GIF -> GifSink(out, w, h, fps, settings.gifColors, settings.gifDither)
            ExportFormat.MOV -> MovSink(out, w, h, fps)
            ExportFormat.PNG_SEQUENCE -> PngSink(out, project.meta.title, fps)
            ExportFormat.WEBM -> WebmSink(out, w, h, fps, settings.quality, transparent)
            ExportFormat.MP4 -> error("MP4 is exported by VideoExporter")
        }
        var ok = false
        try {
            OffscreenEgl().use {
                val res = RenderResources(assets, files)
                val compositor = Compositor(res)
                val pixels = IntArray(w * h)
                try {
                    val render = RenderSettings(motionBlurSamples = comp.motionBlur.samples, transparentBackground = transparent)
                    for (i in 0 until frames) {
                        if (cancelled()) throw ExportCancelled()
                        val time = i / fps
                        val target = try {
                            compositor.render(project, SceneEvaluator.evaluate(project, time), w, h, render).also {
                                if (GLES30.glGetError() == GLES30.GL_OUT_OF_MEMORY) throw OutOfMemoryError("GPU out of memory")
                            }
                        } catch (e: ExportCancelled) {
                            throw e
                        } catch (e: Throwable) {
                            throw ExportFailure(e, time, compositor.currentLayerId)
                        }
                        val bmp = compositor.readPixels(target)
                        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                        sink.add(bmp, pixels, (i * 1_000_000.0 / fps).roundToLong())
                        bmp.recycle()
                        progress((i + 1).toFloat() / frames * 0.98f)
                    }
                } finally {
                    compositor.release()
                    res.release()
                }
            }
            sink.finish()
            ok = true
            progress(1f)
        } finally {
            if (!ok) { runCatching { sink.abort() }; out.delete() }
        }
    }

    private interface FrameSink {
        /** [bitmap] and [argb] are the same frame (unpremultiplied ARGB in [argb]). */
        fun add(bitmap: Bitmap, argb: IntArray, timeUs: Long)
        fun finish()
        fun abort()
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private class GifSink(out: File, w: Int, h: Int, private val fps: Double, colors: Int, dither: Boolean) : FrameSink {
        private val gif = GifEncoder(out.outputStream(), w, h, colors, dither)
        private var n = 0
        override fun add(bitmap: Bitmap, argb: IntArray, timeUs: Long) {
            // GIF delays are in hundredths of a second: spread the rounding so the total length stays right.
            val delay = (100.0 * (n + 1) / fps).roundToInt() - (100.0 * n / fps).roundToInt()
            gif.addFrame(argb, max(2, delay))
            n++
        }
        override fun finish() = gif.close()
        override fun abort() = gif.close()
    }

    private inner class MovSink(out: File, w: Int, h: Int, fps: Double) : FrameSink {
        private val mov = MovWriter(out, w, h, fps)
        override fun add(bitmap: Bitmap, argb: IntArray, timeUs: Long) = mov.addFrame(png(bitmap))
        override fun finish() = mov.close()
        override fun abort() = mov.close()
    }

    private inner class PngSink(out: File, title: String, fps: Double) : FrameSink {
        private val seq = PngSequenceWriter(out.outputStream(), title.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "kinesis" }.take(40), fps)
        override fun add(bitmap: Bitmap, argb: IntArray, timeUs: Long) = seq.addFrame(png(bitmap))
        override fun finish() = seq.close()
        override fun abort() = seq.close()
    }

    /**
     * WebM: two VP8 encoders (MediaCodec) fed with the same frames, one with the colour and one with the alpha as
     * luma. Key frames are requested on both at the same frames; frames are paired by presentation time.
     */
    private class WebmSink(out: File, private val w: Int, private val h: Int, private val fps: Double, quality: ExportQuality, private val alpha: Boolean) : FrameSink {
        private val writer = WebmWriter(out, w, h, fps, alpha)
        private val bitrate = (w * h * fps * quality.bitsPerPixel * 1.6).roundToInt().coerceIn(200_000, 40_000_000)
        private val color = Vp8(w, h, fps, bitrate)
        private val alphaEnc = if (alpha) Vp8(w, h, fps, (bitrate / 3).coerceAtLeast(100_000)) else null
        private val colorOut = java.util.TreeMap<Long, Pair<ByteArray, Boolean>>()
        private val alphaOut = java.util.TreeMap<Long, Pair<ByteArray, Boolean>>()
        private var index = 0
        private val keyEvery = max(1, (fps * 2).roundToInt())
        private val y = ByteArray(w * h)
        private val u = ByteArray((w / 2) * (h / 2))
        private val v = ByteArray((w / 2) * (h / 2))
        private val a = ByteArray(w * h)

        override fun add(bitmap: Bitmap, argb: IntArray, timeUs: Long) {
            // BT.601 limited range, the default of VP8 decoders.
            for (row in 0 until h) for (col in 0 until w) {
                val c = argb[row * w + col]
                val r = (c shr 16) and 0xff; val g = (c shr 8) and 0xff; val b = c and 0xff
                y[row * w + col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                a[row * w + col] = (c ushr 24).toByte()
            }
            for (row in 0 until h / 2) for (col in 0 until w / 2) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(row * 2 + dy) * w + col * 2 + dx]
                    r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                }
                r /= 4; g /= 4; b /= 4
                u[row * (w / 2) + col] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                v[row * (w / 2) + col] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
            val key = index % keyEvery == 0
            color.encode(y, u, v, timeUs, key) { pts, data, k -> colorOut[pts] = data to k }
            alphaEnc?.let { e -> java.util.Arrays.fill(u, 128.toByte()); java.util.Arrays.fill(v, 128.toByte()); e.encode(a, u, v, timeUs, key) { pts, data, k -> alphaOut[pts] = data to k } }
            index++
            flush(false)
        }

        /** Writes the frames both encoders produced, pairing them by presentation time (a frame one dropped is skipped). */
        private fun flush(end: Boolean) {
            if (alphaEnc == null) {
                while (colorOut.isNotEmpty()) { val (pts, f) = colorOut.pollFirstEntry()!!.let { it.key to it.value }; writer.addFrame(f.first, null, f.second, pts) }
                return
            }
            while (colorOut.isNotEmpty() && alphaOut.isNotEmpty()) {
                val pc = colorOut.firstKey(); val pa = alphaOut.firstKey()
                when {
                    pc == pa -> {
                        val c = colorOut.pollFirstEntry()!!.value; val al = alphaOut.pollFirstEntry()!!.value
                        writer.addFrame(c.first, al.first, c.second && al.second, pc)
                    }
                    pa < pc -> alphaOut.pollFirstEntry()
                    else -> colorOut.pollFirstEntry()
                }
            }
            if (end) { colorOut.clear(); alphaOut.clear() }
        }

        override fun finish() {
            color.finish { pts, data, k -> colorOut[pts] = data to k }
            alphaEnc?.finish { pts, data, k -> alphaOut[pts] = data to k }
            flush(true)
            writer.close()
        }

        override fun abort() {
            runCatching { color.release() }
            runCatching { alphaEnc?.release() }
            runCatching { writer.close() }
        }
    }

    /** A VP8 encoder (MediaCodec, byte-buffer input) fed with I420 planes. */
    private class Vp8(private val w: Int, private val h: Int, fps: Double, bitrate: Int) {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP8)
        private val info = MediaCodec.BufferInfo()

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
                // Key frames are requested explicitly so both streams have them at the same frames.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3600)
            }
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun encode(y: ByteArray, u: ByteArray, v: ByteArray, ptsUs: Long, key: Boolean, out: (Long, ByteArray, Boolean) -> Unit) {
            if (key) codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            var index: Int
            do {
                index = codec.dequeueInputBuffer(10_000)
                if (index < 0) drain(out, false)
            } while (index < 0)
            val image = codec.getInputImage(index) ?: error("VP8 encoder has no image input")
            val planes = image.planes
            fun put(plane: android.media.Image.Plane, data: ByteArray, pw: Int, ph: Int) {
                val buf = plane.buffer
                val rs = plane.rowStride; val ps = plane.pixelStride
                for (row in 0 until ph) {
                    if (ps == 1) {
                        buf.position(row * rs); buf.put(data, row * pw, pw)
                    } else {
                        for (col in 0 until pw) buf.put(row * rs + col * ps, data[row * pw + col])
                    }
                }
            }
            put(planes[0], y, w, h); put(planes[1], u, w / 2, h / 2); put(planes[2], v, w / 2, h / 2)
            codec.queueInputBuffer(index, 0, w * h * 3 / 2, ptsUs, 0)
            drain(out, false)
        }

        private fun drain(out: (Long, ByteArray, Boolean) -> Unit, end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i < 0 -> Unit
                    else -> {
                        val buf = codec.getOutputBuffer(i)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset); buf.get(data)
                            out(info.presentationTimeUs, data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(i, false)
                        if (eos) return
                    }
                }
            }
        }

        fun finish(out: (Long, ByteArray, Boolean) -> Unit) {
            var index: Int
            do { index = codec.dequeueInputBuffer(10_000); if (index < 0) drain(out, false) } while (index < 0)
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(out, true)
            release()
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}

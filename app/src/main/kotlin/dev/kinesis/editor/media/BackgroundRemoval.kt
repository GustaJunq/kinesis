package dev.kinesis.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import dev.kinesis.core.video.I420Frame
import dev.kinesis.core.video.MaskTools
import dev.kinesis.core.video.Yuv
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

/**
 * Person segmentation on the device with MediaPipe's Selfie Segmenter (Apache-2.0 library and model, see
 * THIRD_PARTY_LICENSES.md). The model finds people, so the result keeps people and removes everything else.
 */
class PersonSegmenter(context: Context) : AutoCloseable {
    private val segmenter: ImageSegmenter = ImageSegmenter.createFromOptions(
        context.applicationContext,
        ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build(),
    )

    /** Confidence (0..1) that each pixel of [bitmap] belongs to a person; same size as the bitmap. */
    fun segment(bitmap: Bitmap): FloatArray {
        val input = BitmapImageBuilder(bitmap).build()
        try {
            val result = segmenter.segment(input)
            val masks = result.confidenceMasks().orElse(null) ?: throw IOException("the segmenter returned no mask")
            try {
                // Two classes (background, person): take the person's. A single mask is the person's too, unless the
                // frame border (almost always background) is mostly "on", in which case it is the background's.
                val m = if (masks.size >= 2) masks[1] else masks[0]
                val floats = ByteBufferExtractor.extract(m).asFloatBuffer()
                var out = FloatArray(m.width * m.height)
                floats.get(out)
                if (masks.size < 2 && borderMean(out, m.width, m.height) > 0.5f) out = FloatArray(out.size) { 1f - out[it] }
                return if (m.width == bitmap.width && m.height == bitmap.height) out
                else MaskTools.resize(out, m.width, m.height, bitmap.width, bitmap.height)
            } finally {
                masks.forEach { it.close() }
            }
        } finally {
            input.close()
        }
    }

    private fun borderMean(mask: FloatArray, w: Int, h: Int): Float {
        val b = max(2, minOf(w, h) / 16)
        var sum = 0.0; var n = 0
        for (y in 0 until h) for (x in 0 until w) if (x < b || y < b || x >= w - b || y >= h - b) { sum += mask[y * w + x]; n++ }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    override fun close() {
        runCatching { segmenter.close() }
    }

    companion object {
        const val MODEL = "models/selfie_segmenter.tflite"
    }
}

/** Removes the background of a picture or a video clip, producing files the project can use as layers. */
object BackgroundRemoval {
    /** Pictures are processed at most this big on the long side; videos at most [MAX_VIDEO_SIDE]. */
    private const val MAX_IMAGE_SIDE = 2048
    private const val MAX_VIDEO_SIDE = 1280

    /** What the new layer needs: how much to scale it to look as big as the original, and whether it has sound. */
    class Result(val scale: Float, val hasAudio: Boolean)

    /** A PNG with transparency. Returns the scale to give the new layer so it looks as big as the original. */
    fun removeFromImage(context: Context, source: File, target: File): Result {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("unsupported image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("cannot decode the image")
        val rotation = exifRotation(source)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val logicalWidth = if (rotation == 90 || rotation == 270) bounds.outHeight else bounds.outWidth
        PersonSegmenter(context).use { seg ->
            val mask = MaskTools.refine(seg.segment(bmp))
            val argb = IntArray(bmp.width * bmp.height)
            bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val cut = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            cut.setPixels(MaskTools.withAlpha(argb, mask), 0, bmp.width, 0, 0, bmp.width, bmp.height)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { cut.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val scale = logicalWidth.toFloat() / bmp.width
            cut.recycle(); bmp.recycle()
            return Result(scale, hasAudio = false)
        }
    }

    /**
     * A packed-alpha MP4 (colour on the left, alpha as grey on the right) of [source] between [startUs] and [endUs],
     * with the edge flicker smoothed over time and the clip's sound copied. Returns null when [cancelled].
     * Blocking; call from a background thread.
     */
    fun removeFromVideo(
        context: Context, source: File, target: File, startUs: Long, endUs: Long,
        progress: (Float) -> Unit, cancelled: () -> Boolean,
    ): Result? {
        val reader = YuvVideoReader(source)
        val segmenter = PersonSegmenter(context)
        try {
            val info = reader.info
            val step = ceil(max(info.width, info.height) / MAX_VIDEO_SIDE.toDouble()).toInt().coerceAtLeast(1)
            val pw = info.width / step
            val ph = (info.height / step) and 1.inv()
            val (rw, rh0) = MaskTools.rotatedSize(pw, ph, info.rotation)
            val rh = rh0 and 1.inv()
            if (rw < 16 || rh < 16) throw IOException("video too small")

            val src = I420Frame(info.width, info.height)
            val raw = IntArray(pw * ph)
            val upright = IntArray(pw * ph)
            val packed = IntArray(2 * rw * rh)
            val outFrame = I420Frame(2 * rw, rh)
            val outDirect = DirectI420(2 * rw, rh)
            val bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
            var previous: FloatArray? = null
            var smoothed = FloatArray(rw * rh)
            val writer = YuvVideoWriter(
                target, 2 * rw, rh, info.frameRate, rotation = 0, audioSource = source,
                audioStartUs = startUs, audioEndUs = endUs, bitsPerPixel = 0.14,
            )
            var ok = false
            try {
                val span = (endUs - startUs).coerceAtLeast(1L)
                reader.read(startUs, endUs) { frame, pts ->
                    if (cancelled()) return@read false
                    frame.toArrays(src)
                    Yuv.i420ToArgb(src.y, info.width, src.u, src.chromaWidth, src.v, src.chromaWidth, pw * step, ph * step, step, raw)
                    MaskTools.rotateArgb(raw, pw, ph, info.rotation, upright)
                    // A crop to the even height (rh) is the same array: the extra last row of an odd rotation is dropped.
                    bitmap.setPixels(upright, 0, rw, 0, 0, rw, rh)
                    val mask = MaskTools.refine(segmenter.segment(bitmap))
                    MaskTools.smoothInTime(previous, mask, 0.65f, smoothed)
                    previous = smoothed.copyOf()
                    MaskTools.packAlpha(upright, smoothed, rw, rh, packed)
                    Yuv.argbToI420(packed, 2 * rw, rh, outFrame)
                    outDirect.fromArrays(outFrame)
                    writer.writeFrame(outDirect, pts - startUs)
                    progress(((pts - startUs).toFloat() / span).coerceIn(0f, 1f))
                    true
                }
                if (cancelled()) return null
                writer.finish()
                ok = true
            } finally {
                writer.close()
                bitmap.recycle()
                if (!ok) target.delete()
            }
            val shown = if (info.rotation % 180 == 90) info.height else info.width
            return Result(scale = shown.toFloat() / rw, hasAudio = writer.hasAudio)
        } finally {
            segmenter.close()
            reader.close()
        }
    }

    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}

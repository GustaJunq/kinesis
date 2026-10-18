package dev.kinesis.editor.media

import dev.kinesis.core.video.I420Frame
import dev.kinesis.core.video.MaskTools
import dev.kinesis.core.video.Yuv
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max

/**
 * Writes a video with transparency in the project's packed-alpha layout (colour on the left half of each frame, the mask
 * as grey on the right half), taking the mask of each frame from a [MaskSource]. Shared by background removal and the
 * Roto Brush. The sound of the source is copied without re-encoding.
 */
object PackedAlpha {
    /** What a new layer needs: how much to scale it to look as big as the original, and whether it has sound. */
    class Result(val scale: Float, val hasAudio: Boolean)

    /** The mask (0..1, [width]×[height], upright) of frame number [index] of the range; [frame] is the picture, opaque ARGB. */
    fun interface MaskSource {
        fun mask(index: Int, frame: IntArray, width: Int, height: Int): FloatArray
    }

    /** Frames are processed at most this big on the long side. */
    const val MAX_SIDE = 1280

    /**
     * Writes frames from [startUs] to [endUs] of [source] into [target]. Returns null when [cancelled] (the partial file is
     * deleted). Blocking: call from a background thread.
     */
    fun render(
        source: File, target: File, startUs: Long, endUs: Long, masks: MaskSource,
        progress: (Float) -> Unit, cancelled: () -> Boolean, maxSide: Int = MAX_SIDE,
    ): Result? {
        val reader = YuvVideoReader(source)
        try {
            val info = reader.info
            val step = ceil(max(info.width, info.height) / maxSide.toDouble()).toInt().coerceAtLeast(1)
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
            val writer = YuvVideoWriter(
                target, 2 * rw, rh, info.frameRate, rotation = 0, audioSource = source,
                audioStartUs = startUs, audioEndUs = endUs, bitsPerPixel = 0.14,
            )
            var ok = false
            try {
                val span = (endUs - startUs).coerceAtLeast(1L)
                var index = 0
                reader.read(startUs, endUs) { frame, pts ->
                    if (cancelled()) return@read false
                    frame.toArrays(src)
                    Yuv.i420ToArgb(src.y, info.width, src.u, src.chromaWidth, src.v, src.chromaWidth, pw * step, ph * step, step, raw)
                    MaskTools.rotateArgb(raw, pw, ph, info.rotation, upright)
                    // A crop to the even height (rh) is the same array: the extra last row of an odd rotation is dropped.
                    val mask = masks.mask(index++, upright, rw, rh)
                    MaskTools.packAlpha(upright, mask, rw, rh, packed)
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
                if (!ok) target.delete()
            }
            val shown = if (info.rotation % 180 == 90) info.height else info.width
            return Result(scale = shown.toFloat() / rw, hasAudio = writer.hasAudio)
        } finally {
            reader.close()
        }
    }
}

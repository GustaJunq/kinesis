package dev.kinesis.core.video

import kotlin.math.abs

/**
 * Pure helpers of the background removal pipeline (the neural network itself lives in the app): cleaning the
 * confidence mask, keeping it steady over time and writing the packed-alpha frames the project format uses.
 */
object MaskTools {

    /** Contrast curve for a soft mask: below [low] is background, above [high] is subject, smooth in between. */
    fun refine(mask: FloatArray, low: Float = 0.3f, high: Float = 0.7f, out: FloatArray = FloatArray(mask.size)): FloatArray {
        val span = (high - low).coerceAtLeast(1e-6f)
        for (i in mask.indices) {
            val t = ((mask[i] - low) / span).coerceIn(0f, 1f)
            out[i] = t * t * (3f - 2f * t)
        }
        return out
    }

    /**
     * Temporal smoothing against flicker: each pixel moves from its previous value toward the new one by a fraction
     * that grows with how much it changed. Small changes (network noise on a steady edge) are mostly absorbed;
     * a big change (the subject really moved) is followed almost at once, so there is no ghost trailing it.
     *
     * @param steadiness 0..1, how much of a small change is absorbed (0 = no smoothing).
     */
    fun smoothInTime(previous: FloatArray?, current: FloatArray, steadiness: Float = 0.65f, out: FloatArray = FloatArray(current.size)): FloatArray {
        if (previous == null || previous.size != current.size) {
            System.arraycopy(current, 0, out, 0, current.size)
            return out
        }
        val keep = steadiness.coerceIn(0f, 0.95f)
        for (i in current.indices) {
            val d = current[i] - previous[i]
            // follow = (1 - keep) for tiny changes, rising to 1 for changes of 0.5 or more
            val follow = ((1f - keep) + abs(d) * 2f * keep).coerceAtMost(1f)
            out[i] = previous[i] + d * follow
        }
        return out
    }

    /**
     * One frame of a packed-alpha video: colour in the left half, the alpha as grey in the right half
     * (`video.alphaPacked`, see the format docs). [color] is opaque ARGB, [alpha] 0..1, both [width] × [height].
     * The result is (2·[width]) × [height] ARGB.
     */
    fun packAlpha(color: IntArray, alpha: FloatArray, width: Int, height: Int, out: IntArray = IntArray(2 * width * height)): IntArray {
        require(color.size == width * height && alpha.size == width * height) { "size mismatch" }
        for (y in 0 until height) {
            val src = y * width
            val dst = y * 2 * width
            for (x in 0 until width) {
                val c = color[src + x]
                out[dst + x] = (0xFF shl 24) or (c and 0x00FFFFFF)
                val g = (alpha[src + x].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                out[dst + width + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return out
    }

    /** ARGB (straight alpha) of the cut-out: the colours with the mask as alpha (for a PNG of a still image). */
    fun withAlpha(color: IntArray, alpha: FloatArray, out: IntArray = IntArray(color.size)): IntArray {
        for (i in color.indices) {
            val a = (alpha[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            out[i] = (a shl 24) or (color[i] and 0x00FFFFFF)
        }
        return out
    }

    /**
     * Rotates an ARGB image clockwise by [degrees] (0, 90, 180 or 270). The result is [width]×[height], or
     * [height]×[width] for 90 and 270 (what [rotatedSize] says).
     */
    fun rotateArgb(src: IntArray, width: Int, height: Int, degrees: Int, out: IntArray = IntArray(src.size)): IntArray {
        when (((degrees % 360) + 360) % 360) {
            0 -> System.arraycopy(src, 0, out, 0, src.size)
            90 -> for (y in 0 until height) for (x in 0 until width) out[x * height + (height - 1 - y)] = src[y * width + x]
            180 -> for (i in src.indices) out[src.size - 1 - i] = src[i]
            270 -> for (y in 0 until height) for (x in 0 until width) out[(width - 1 - x) * height + y] = src[y * width + x]
            else -> throw IllegalArgumentException("rotation must be a multiple of 90")
        }
        return out
    }

    /** Width and height after [rotateArgb]. */
    fun rotatedSize(width: Int, height: Int, degrees: Int): Pair<Int, Int> =
        if (((degrees % 360) + 360) % 360 % 180 == 90) height to width else width to height

    /** Scales a mask (nearest/bilinear mix: bilinear) from [sw]×[sh] to [dw]×[dh]. */
    fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int, out: FloatArray = FloatArray(dw * dh)): FloatArray {
        val sx = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val sy = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f
        for (y in 0 until dh) {
            val fy = y * sy
            val y0 = fy.toInt().coerceIn(0, sh - 1)
            val y1 = (y0 + 1).coerceAtMost(sh - 1)
            val ty = fy - y0
            for (x in 0 until dw) {
                val fx = x * sx
                val x0 = fx.toInt().coerceIn(0, sw - 1)
                val x1 = (x0 + 1).coerceAtMost(sw - 1)
                val tx = fx - x0
                val top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                val bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * dw + x] = top * (1 - ty) + bottom * ty
            }
        }
        return out
    }
}

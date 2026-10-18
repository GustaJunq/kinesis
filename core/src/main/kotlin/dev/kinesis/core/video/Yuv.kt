package dev.kinesis.core.video

/**
 * Planar YUV 4:2:0 (I420) frames and their conversion to and from ARGB, BT.709 limited range (what the app's video
 * encoder writes and the decoder expects for HD video). Planes are plain arrays with a stride per row, so the same
 * code serves frames copied out of MediaCodec images and test data.
 */
class I420Frame(val width: Int, val height: Int) {
    val chromaWidth: Int get() = (width + 1) / 2
    val chromaHeight: Int get() = (height + 1) / 2
    val y = ByteArray(width * height)
    val u = ByteArray(chromaWidth * chromaHeight)
    val v = ByteArray(chromaWidth * chromaHeight)
}

object Yuv {
    private fun clamp8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * ARGB [argb] (width × height, alpha ignored) into [out]. Chroma is the mean of each 2×2 block.
     */
    fun argbToI420(argb: IntArray, width: Int, height: Int, out: I420Frame) {
        require(out.width == width && out.height == height) { "frame size mismatch" }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val c = argb[row + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                // BT.709 limited: Y = 16 + 0.1826 R + 0.6142 G + 0.0620 B
                out.y[row + x] = clamp8(16 + ((47 * r + 157 * g + 16 * b + 128) shr 8)).toByte()
            }
        }
        val cw = out.chromaWidth
        for (cy in 0 until out.chromaHeight) {
            for (cx in 0 until cw) {
                var rs = 0; var gs = 0; var bs = 0; var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val x = cx * 2 + dx; val y = cy * 2 + dy
                    if (x < width && y < height) {
                        val c = argb[y * width + x]
                        rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF; n++
                    }
                }
                val r = rs / n; val g = gs / n; val b = bs / n
                // Cb = 128 - 0.1006 R - 0.3386 G + 0.4392 B ; Cr = 128 + 0.4392 R - 0.3989 G - 0.0403 B
                out.u[cy * cw + cx] = clamp8(128 + ((-26 * r - 87 * g + 112 * b + 128) shr 8)).toByte()
                out.v[cy * cw + cx] = clamp8(128 + ((112 * r - 102 * g - 10 * b + 128) shr 8)).toByte()
            }
        }
    }

    /**
     * Planes with arbitrary strides into opaque ARGB, sampling every [step]-th pixel (a cheap down-scale for big
     * videos). The output is (width / step) × (height / step).
     */
    fun i420ToArgb(
        y: ByteArray, yStride: Int, u: ByteArray, uStride: Int, v: ByteArray, vStride: Int,
        width: Int, height: Int, step: Int = 1, out: IntArray = IntArray((width / step) * (height / step)),
    ): IntArray {
        val ow = width / step; val oh = height / step
        for (oy in 0 until oh) {
            val sy = oy * step
            for (ox in 0 until ow) {
                val sx = ox * step
                val yy = ((y[sy * yStride + sx].toInt() and 0xFF) - 16).coerceAtLeast(0)
                val cb = (u[(sy shr 1) * uStride + (sx shr 1)].toInt() and 0xFF) - 128
                val cr = (v[(sy shr 1) * vStride + (sx shr 1)].toInt() and 0xFF) - 128
                val yf = 298 * yy                               // 1.164 * 256
                val r = clamp8((yf + 459 * cr + 128) shr 8)     // 1.793
                val g = clamp8((yf - 55 * cb - 136 * cr + 128) shr 8) // 0.213, 0.533
                val b = clamp8((yf + 541 * cb + 128) shr 8)     // 2.112
                out[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }
}

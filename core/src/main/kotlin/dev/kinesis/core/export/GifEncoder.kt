package dev.kinesis.core.export

import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Animated GIF writer (GIF89a). Each frame gets its own palette (median cut over the frame, up to [colors] entries);
 * pixels with alpha below 50 % become the transparent index, so a transparent background stays transparent. Optional
 * Floyd–Steinberg dithering. Loops forever.
 *
 * Frames are ARGB ints, not premultiplied, row by row.
 */
class GifEncoder(
    out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val colors: Int = 256,
    private val dither: Boolean = true,
) : AutoCloseable {
    private val out = BufferedOutputStream(out, 64 * 1024)
    private var started = false
    private var closed = false

    init {
        require(width in 1..65535 && height in 1..65535) { "bad GIF size" }
        require(colors in 2..256) { "colors must be 2..256" }
    }

    private fun u16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }

    private fun header() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width); u16(height)
        out.write(0x00) // no global colour table
        out.write(0); out.write(0)
        // NETSCAPE2.0: loop forever.
        out.write(byteArrayOf(0x21, 0xff.toByte(), 0x0b))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        started = true
    }

    /** Adds a frame shown for [delayCs] hundredths of a second. */
    fun addFrame(argb: IntArray, delayCs: Int) {
        require(argb.size == width * height)
        if (!started) header()
        val transparent = argb.any { (it ushr 24) < 128 }
        val maxColors = if (transparent) colors - 1 else colors
        val palette = Quantizer.palette(argb, maxColors)
        val indices = Quantizer.map(argb, width, height, palette, if (transparent) palette.size else -1, dither)
        val entries = palette.size + (if (transparent) 1 else 0)
        var bits = 1
        while ((1 shl bits) < entries) bits++
        val tableSize = 1 shl bits

        // Graphic control extension: delay, transparency; frames replace each other (restore to background).
        out.write(byteArrayOf(0x21, 0xf9.toByte(), 0x04))
        out.write((2 shl 2) or (if (transparent) 1 else 0))
        u16(delayCs.coerceIn(1, 65535))
        out.write(if (transparent) palette.size else 0)
        out.write(0)
        // Image descriptor with a local colour table.
        out.write(0x2c)
        u16(0); u16(0); u16(width); u16(height)
        out.write(0x80 or (bits - 1))
        for (i in 0 until tableSize) {
            val c = if (i < palette.size) palette[i] else 0
            out.write((c shr 16) and 0xff); out.write((c shr 8) and 0xff); out.write(c and 0xff)
        }
        Lzw.write(out, indices, maxOf(2, bits))
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!started) header()
        out.write(0x3b)
        out.flush()
        out.close()
    }

    /** Median-cut palette and nearest-colour mapping over a 15-bit colour cube. */
    internal object Quantizer {
        private fun key(c: Int) = (((c shr 19) and 0x1f) shl 10) or (((c shr 11) and 0x1f) shl 5) or ((c shr 3) and 0x1f)

        fun palette(argb: IntArray, max: Int): IntArray {
            val hist = IntArray(32768)
            var any = false
            for (c in argb) if ((c ushr 24) >= 128) { hist[key(c)]++; any = true }
            if (!any) return intArrayOf(0)
            val used = (0 until 32768).filter { hist[it] > 0 }.toIntArray()
            if (used.size <= max) return used.map { expand(it) }.toIntArray()
            class Box(val keys: IntArray)
            val boxes = mutableListOf(Box(used))
            fun ch(k: Int, axis: Int) = when (axis) { 0 -> (k shr 10) and 31; 1 -> (k shr 5) and 31; else -> k and 31 }
            fun range(b: Box, axis: Int): Int { var lo = 31; var hi = 0; for (k in b.keys) { val v = ch(k, axis); if (v < lo) lo = v; if (v > hi) hi = v }; return hi - lo }
            while (boxes.size < max) {
                // Split the box with the most pixels times its widest range.
                val target = boxes.filter { it.keys.size > 1 }.maxByOrNull { b -> (0..2).maxOf { range(b, it) }.toLong() * b.keys.sumOf { hist[it] } } ?: break
                val axis = (0..2).maxBy { range(target, it) }
                val sorted = target.keys.sortedBy { ch(it, axis) }
                val total = sorted.sumOf { hist[it] }
                var acc = 0; var cut = 1
                for ((i, k) in sorted.withIndex()) { acc += hist[k]; if (acc * 2 >= total) { cut = (i + 1).coerceIn(1, sorted.size - 1); break } }
                boxes.remove(target)
                boxes += Box(sorted.subList(0, cut).toIntArray())
                boxes += Box(sorted.subList(cut, sorted.size).toIntArray())
            }
            return boxes.map { b ->
                var r = 0L; var g = 0L; var bl = 0L; var n = 0L
                for (k in b.keys) { val w = hist[k].toLong(); val c = expand(k); r += ((c shr 16) and 0xff) * w; g += ((c shr 8) and 0xff) * w; bl += (c and 0xff) * w; n += w }
                (((r / n).toInt()) shl 16) or (((g / n).toInt()) shl 8) or (bl / n).toInt()
            }.toIntArray()
        }

        private fun expand(k: Int): Int {
            val r = ((k shr 10) and 31) * 255 / 31; val g = ((k shr 5) and 31) * 255 / 31; val b = (k and 31) * 255 / 31
            return (r shl 16) or (g shl 8) or b
        }

        fun map(argb: IntArray, w: Int, h: Int, palette: IntArray, transparentIndex: Int, dither: Boolean): ByteArray {
            val cache = IntArray(32768) { -1 }
            fun nearest(r: Int, g: Int, b: Int): Int {
                val k = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                val c = cache[k]
                if (c >= 0) return c
                var best = 0; var bestD = Int.MAX_VALUE
                for (i in palette.indices) {
                    val p = palette[i]
                    val dr = ((p shr 16) and 0xff) - r; val dg = ((p shr 8) and 0xff) - g; val db = (p and 0xff) - b
                    val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                    if (d < bestD) { bestD = d; best = i }
                }
                cache[k] = best
                return best
            }
            val out = ByteArray(w * h)
            // Error buffers for the current and next row (r, g, b).
            var cur = IntArray((w + 2) * 3); var next = IntArray((w + 2) * 3)
            for (y in 0 until h) {
                java.util.Arrays.fill(next, 0)
                for (x in 0 until w) {
                    val c = argb[y * w + x]
                    if ((c ushr 24) < 128 && transparentIndex >= 0) { out[y * w + x] = transparentIndex.toByte(); continue }
                    val e = (x + 1) * 3
                    val r = (((c shr 16) and 0xff) + (if (dither) cur[e] / 16 else 0)).coerceIn(0, 255)
                    val g = (((c shr 8) and 0xff) + (if (dither) cur[e + 1] / 16 else 0)).coerceIn(0, 255)
                    val b = ((c and 0xff) + (if (dither) cur[e + 2] / 16 else 0)).coerceIn(0, 255)
                    val i = nearest(r, g, b)
                    out[y * w + x] = i.toByte()
                    if (dither) {
                        val p = palette[i]
                        val er = r - ((p shr 16) and 0xff); val eg = g - ((p shr 8) and 0xff); val eb = b - (p and 0xff)
                        for ((ch, err) in intArrayOf(er, eg, eb).withIndex()) {
                            cur[e + 3 + ch] += err * 7
                            next[e - 3 + ch] += err * 3
                            next[e + ch] += err * 5
                            next[e + 3 + ch] += err
                        }
                    }
                }
                val t = cur; cur = next; next = t
            }
            return out
        }
    }

    /** GIF LZW with variable code size and sub-blocks of 255 bytes. */
    internal object Lzw {
        fun write(out: OutputStream, data: ByteArray, minCodeSize: Int) {
            out.write(minCodeSize)
            val clear = 1 shl minCodeSize
            val eoi = clear + 1
            val block = ByteArray(255)
            var blockLen = 0
            var acc = 0L
            var accBits = 0
            fun flushBlock() { if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 } }
            fun emit(code: Int, size: Int) {
                acc = acc or (code.toLong() shl accBits)
                accBits += size
                while (accBits >= 8) {
                    block[blockLen++] = (acc and 0xff).toByte()
                    acc = acc ushr 8
                    accBits -= 8
                    if (blockLen == 255) flushBlock()
                }
            }
            val dict = HashMap<Int, Int>(8192)
            var codeSize = minCodeSize + 1
            var nextCode = eoi + 1
            emit(clear, codeSize)
            if (data.isEmpty()) { emit(eoi, codeSize) } else {
                var prefix = data[0].toInt() and 0xff
                for (i in 1 until data.size) {
                    val k = data[i].toInt() and 0xff
                    val key = (prefix shl 8) or k
                    val found = dict[key]
                    if (found != null) { prefix = found; continue }
                    emit(prefix, codeSize)
                    if (nextCode < 4096) {
                        dict[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                    } else {
                        emit(clear, codeSize)
                        dict.clear()
                        codeSize = minCodeSize + 1
                        nextCode = eoi + 1
                    }
                    prefix = k
                }
                emit(prefix, codeSize)
                emit(eoi, codeSize)
            }
            if (accBits > 0) { block[blockLen++] = (acc and 0xff).toByte(); if (blockLen == 255) flushBlock() }
            flushBlock()
            out.write(0)
        }
    }
}

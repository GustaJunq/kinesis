package dev.kinesis.core.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToLong

/**
 * WebM (Matroska) writer for VP8 video with an alpha channel, the layout browsers, ffmpeg (libvpx) and
 * video editors (some through plug-ins) read: the colour frame goes in the Block and the alpha frame (a second VP8 stream whose
 * luma is the alpha) in `BlockAdditions` with `BlockAddID` 1; the track says `AlphaMode` 1.
 *
 * Frames are written in display order with a constant frame rate. A cluster starts at every key frame (and at least
 * every 30 s); `Cues` point to each cluster so players can seek.
 */
class WebmWriter(file: File, private val width: Int, private val height: Int, private val fps: Double, private val alpha: Boolean = true) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var segmentDataStart = 0L
    private var durationPos = 0L
    private var frames = 0L
    private var clusterStartMs = -1L
    private var clusterPos = -1L
    private var clusterSizePos = -1L
    private val cues = ArrayList<Pair<Long, Long>>() // time ms -> cluster position relative to the segment
    private var segmentSizePos = 0L
    private var lastTimeMs = -1L
    private var closed = false

    init {
        require(width > 0 && height > 0 && fps > 0)
        raf.write(element(0x1A45DFA3, concat(
            uint(0x4286, 1), uint(0x42F7, 1), uint(0x42F2, 4), uint(0x42F3, 8),
            string(0x4282, "webm"), uint(0x4287, 4), uint(0x4285, 2),
        )))
        // Segment with an unknown size, patched on close.
        raf.write(id(0x18538067)); val segSize = raf.filePointer; raf.write(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0))
        segmentSizePos = segSize
        segmentDataStart = raf.filePointer
        val info = concat(
            uint(0x2AD7B1, 1_000_000),
            string(0x4D80, "Kinesis Editor"), string(0x5741, "Kinesis Editor"),
        )
        // Duration as a float64, patched on close: write the Info element by hand to know its position.
        val durationEl = concat(id(0x4489), size(8), ByteArray(8))
        val infoBody = concat(info, durationEl)
        raf.write(concat(id(0x1549A966), size(infoBody.size.toLong())))
        val infoStart = raf.filePointer
        raf.write(infoBody)
        durationPos = infoStart + info.size + 3
        val video = concat(uint(0xB0, width.toLong()), uint(0xBA, height.toLong()), if (alpha) uint(0x53C0, 1) else ByteArray(0))
        val track = concat(
            uint(0xD7, 1), uint(0x73C5, 1), uint(0x83, 1), uint(0x9C, 0),
            string(0x86, "V_VP8"),
            uint(0x23E383, (1_000_000_000.0 / fps).roundToLong()),
            if (alpha) uint(0x55EE, 1) else ByteArray(0),
            element(0xE0, video),
        )
        raf.write(element(0x1654AE6B, element(0xAE, track)))
    }

    /**
     * Adds the next frame: [color] is a VP8 frame, [alphaFrame] the VP8 frame of the alpha channel (required when the
     * writer has alpha). Key frames of both streams must coincide. [timeUs] is the presentation time (by default the
     * frame count at the frame rate).
     */
    fun addFrame(color: ByteArray, alphaFrame: ByteArray?, keyFrame: Boolean, timeUs: Long? = null) {
        check(!closed)
        require(!alpha || alphaFrame != null) { "alpha frame missing" }
        val timeMs = timeUs?.let { (it + 500) / 1000 } ?: (frames * 1000.0 / fps).roundToLong()
        lastTimeMs = timeMs
        if (clusterPos < 0 || keyFrame || timeMs - clusterStartMs > 30_000) startCluster(timeMs)
        val rel = (timeMs - clusterStartMs).toInt()
        val block = concat(byteArrayOf(0x81.toByte(), (rel shr 8).toByte(), rel.toByte(), 0), color)
        val body = ByteArrayOutputStream()
        body.write(concat(id(0xA1), size(block.size.toLong()), block))
        if (alphaFrame != null) {
            val more = element(0xA6, concat(uint(0xEE, 1), concat(id(0xA5), size(alphaFrame.size.toLong()), alphaFrame)))
            body.write(element(0x75A1, more))
        }
        // A frame that is not a key frame refers to the previous one.
        if (!keyFrame) body.write(sint(0xFB, -(1000.0 / fps).roundToLong().coerceAtLeast(1)))
        raf.write(element(0xA0, body.toByteArray()))
        frames++
    }

    private fun startCluster(timeMs: Long) {
        endCluster()
        clusterPos = raf.filePointer
        clusterStartMs = timeMs
        cues += timeMs to (clusterPos - segmentDataStart)
        raf.write(id(0x1F43B675))
        clusterSizePos = raf.filePointer
        raf.write(byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0))
        raf.write(uint(0xE7, timeMs))
    }

    private fun endCluster() {
        if (clusterPos < 0) return
        val end = raf.filePointer
        patchSize(clusterSizePos, end - clusterSizePos - 8)
        raf.seek(end)
    }

    private fun patchSize(pos: Long, value: Long) {
        raf.seek(pos)
        val b = ByteArray(8)
        b[0] = 0x01
        for (i in 1..7) b[i] = (value shr (8 * (7 - i))).toByte()
        raf.write(b)
    }

    override fun close() {
        if (closed) return
        closed = true
        endCluster()
        val cuePoints = cues.map { (t, pos) ->
            element(0xBB, concat(uint(0xB3, t), element(0xB7, concat(uint(0xF7, 1), uint(0xF1, pos)))))
        }
        raf.write(element(0x1C53BB6B, concat(*cuePoints.toTypedArray())))
        val end = raf.filePointer
        patchSize(segmentSizePos, end - segmentDataStart)
        raf.seek(durationPos)
        raf.writeLong(java.lang.Double.doubleToLongBits(if (frames == 0L) 0.0 else lastTimeMs + 1000.0 / fps))
        raf.close()
    }

    // ------------------------------------------------------------------ EBML

    private fun id(v: Int): ByteArray = when {
        v > 0xFFFFFF -> byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        v > 0xFFFF -> byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        v > 0xFF -> byteArrayOf((v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(v.toByte())
    }

    private fun size(n: Long): ByteArray {
        var len = 1
        while (len < 8 && n >= (1L shl (7 * len)) - 1) len++
        val b = ByteArray(len)
        for (i in 0 until len) b[i] = (n shr (8 * (len - 1 - i))).toByte()
        b[0] = (b[0].toInt() or (0x80 shr (len - 1))).toByte()
        return b
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        parts.forEach { o.write(it) }
        return o.toByteArray()
    }

    private fun element(id: Int, body: ByteArray) = concat(id(id), size(body.size.toLong()), body)

    private fun uint(id: Int, v: Long): ByteArray {
        var len = 1
        while (len < 8 && (v ushr (8 * len)) != 0L) len++
        return element(id, ByteArray(len) { i -> (v shr (8 * (len - 1 - i))).toByte() })
    }

    private fun sint(id: Int, v: Long): ByteArray {
        var len = 1
        while (len < 8 && (v < -(1L shl (8 * len - 1)) || v >= (1L shl (8 * len - 1)))) len++
        return element(id, ByteArray(len) { i -> (v shr (8 * (len - 1 - i))).toByte() })
    }

    private fun string(id: Int, s: String) = element(id, s.toByteArray(Charsets.UTF_8))
}

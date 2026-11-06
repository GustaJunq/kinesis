package dev.kinesis.core.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToLong

/**
 * QuickTime `.mov` with one video track of PNG frames (`png ` codec, 32-bit, with alpha): lossless and transparent,
 * opened by After Effects, Premiere, DaVinci Resolve, Final Cut and ffmpeg. Frames are written as they come (`mdat`
 * first, 64-bit size) and the index (`moov`) at the end.
 */
class MovWriter(file: File, private val width: Int, private val height: Int, fps: Double) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val sizes = ArrayList<Int>()
    private val offsets = ArrayList<Long>()
    private val timescale: Int
    private val frameDuration: Int
    private val mdatStart: Long
    private var closed = false

    init {
        require(width > 0 && height > 0 && fps > 0)
        // Exact for whole and NTSC-style rates (29.97 = 30000/1001).
        val ntsc = kotlin.math.abs(fps * 1001 / 1000 - (fps * 1001 / 1000).roundToLong()) < 1e-3 && kotlin.math.abs(fps - fps.roundToLong()) > 1e-3
        if (ntsc) { timescale = ((fps * 1001).roundToLong()).toInt(); frameDuration = 1001 } else { timescale = (fps * 100).roundToLong().toInt(); frameDuration = 100 }
        // ftyp
        raf.write(box("ftyp") { it.write("qt  ".toByteArray()); it.writeInt(0x200); it.write("qt  ".toByteArray()) })
        // mdat with a 64-bit size, patched on close.
        mdatStart = raf.filePointer
        raf.writeInt(1); raf.write("mdat".toByteArray()); raf.writeLong(0)
    }

    /** Adds one frame: the bytes of a PNG image of [width]×[height]. */
    fun addFrame(png: ByteArray) {
        check(!closed)
        offsets += raf.filePointer
        sizes += png.size
        raf.write(png)
    }

    override fun close() {
        if (closed) return
        closed = true
        val end = raf.filePointer
        raf.seek(mdatStart + 8)
        raf.writeLong(end - mdatStart)
        raf.seek(end)
        raf.write(moov())
        raf.close()
    }

    private fun box(type: String, body: (DataOutputStream) -> Unit): ByteArray {
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        body(d)
        val payload = b.toByteArray()
        val out = ByteArrayOutputStream()
        val o = DataOutputStream(out)
        o.writeInt(payload.size + 8); o.write(type.toByteArray(Charsets.US_ASCII)); o.write(payload)
        return out.toByteArray()
    }

    private fun matrix(d: DataOutputStream) {
        intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000).forEach { d.writeInt(it) }
    }

    private fun moov(): ByteArray {
        val n = sizes.size
        val duration = n.toLong() * frameDuration
        val mvhd = box("mvhd") { d ->
            d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(timescale); d.writeInt(duration.toInt())
            d.writeInt(0x10000); d.writeShort(0x100); d.write(ByteArray(10)); matrix(d); d.write(ByteArray(24)); d.writeInt(2)
        }
        val tkhd = box("tkhd") { d ->
            d.writeInt(0x0000000f); d.writeInt(0); d.writeInt(0); d.writeInt(1); d.writeInt(0); d.writeInt(duration.toInt())
            d.write(ByteArray(8)); d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(0); matrix(d)
            d.writeInt(width shl 16); d.writeInt(height shl 16)
        }
        val mdhd = box("mdhd") { d ->
            d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(timescale); d.writeInt(duration.toInt()); d.writeShort(0x7fff); d.writeShort(0)
        }
        val hdlr = box("hdlr") { d ->
            d.writeInt(0); d.write("mhlr".toByteArray()); d.write("vide".toByteArray()); d.write(ByteArray(12))
            val name = "Kinesis video".toByteArray(); d.writeByte(name.size); d.write(name)
        }
        val vmhd = box("vmhd") { d -> d.writeInt(1); d.writeShort(0x40); d.write(ByteArray(6)) }
        val dhdlr = box("hdlr") { d ->
            d.writeInt(0); d.write("dhlr".toByteArray()); d.write("alis".toByteArray()); d.write(ByteArray(12))
            val name = "Kinesis data".toByteArray(); d.writeByte(name.size); d.write(name)
        }
        val dinf = box("dinf") { d -> d.write(box("dref") { r -> r.writeInt(0); r.writeInt(1); r.write(box("alis") { a -> a.writeInt(1) }) }) }
        val entry = box("png ") { d ->
            d.write(ByteArray(6)); d.writeShort(1) // data reference index
            d.writeShort(0); d.writeShort(0); d.write("appl".toByteArray()); d.writeInt(0); d.writeInt(512)
            d.writeShort(width); d.writeShort(height); d.writeInt(72 shl 16); d.writeInt(72 shl 16); d.writeInt(0); d.writeShort(1)
            val name = "PNG".toByteArray(); d.writeByte(name.size); d.write(name); d.write(ByteArray(31 - name.size))
            d.writeShort(32) // depth: colour + alpha
            d.writeShort(-1) // no colour table
        }
        val stsd = box("stsd") { d -> d.writeInt(0); d.writeInt(1); d.write(entry) }
        val stts = box("stts") { d -> d.writeInt(0); d.writeInt(1); d.writeInt(n); d.writeInt(frameDuration) }
        val stsc = box("stsc") { d -> d.writeInt(0); d.writeInt(1); d.writeInt(1); d.writeInt(1); d.writeInt(1) }
        val stsz = box("stsz") { d -> d.writeInt(0); d.writeInt(0); d.writeInt(n); sizes.forEach { d.writeInt(it) } }
        val co64 = box("co64") { d -> d.writeInt(0); d.writeInt(n); offsets.forEach { d.writeLong(it) } }
        val stbl = box("stbl") { d -> d.write(stsd); d.write(stts); d.write(stsc); d.write(stsz); d.write(co64) }
        val minf = box("minf") { d -> d.write(vmhd); d.write(dhdlr); d.write(dinf); d.write(stbl) }
        val mdia = box("mdia") { d -> d.write(mdhd); d.write(hdlr); d.write(minf) }
        val trak = box("trak") { d -> d.write(tkhd); d.write(mdia) }
        return box("moov") { d -> d.write(mvhd); d.write(trak) }
    }
}

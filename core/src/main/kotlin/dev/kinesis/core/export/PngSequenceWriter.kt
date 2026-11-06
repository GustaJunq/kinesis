package dev.kinesis.core.export

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A PNG sequence packed in one `.zip` (`<name>_00000.png`, `<name>_00001.png`, …), stored without recompression. Editors
 * import the unzipped folder as an image sequence; the alpha channel of each PNG is kept.
 */
class PngSequenceWriter(out: OutputStream, private val name: String, private val fps: Double) : AutoCloseable {
    private val zip = ZipOutputStream(out)
    private var index = 0

    fun addFrame(png: ByteArray) {
        val e = ZipEntry("${name}_%05d.png".format(index++))
        e.method = ZipEntry.STORED
        e.size = png.size.toLong()
        e.compressedSize = png.size.toLong()
        e.crc = CRC32().apply { update(png) }.value
        zip.putNextEntry(e)
        zip.write(png)
        zip.closeEntry()
    }

    override fun close() {
        // A note with the frame rate, which a sequence of pictures does not carry.
        zip.putNextEntry(ZipEntry("LEIA-ME.txt"))
        zip.write("Sequência PNG exportada pelo Kinesis Editor: $index quadros a ${"%.3f".format(java.util.Locale.ROOT, fps).trimEnd('0').trimEnd('.')} quadros por segundo, com canal alfa.\n".toByteArray())
        zip.closeEntry()
        zip.close()
    }
}

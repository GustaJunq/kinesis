package dev.kinesis.core.format

import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.KnsProject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * `.knsproj` container: a ZIP file with
 * ```
 * project.json        the document (required)
 * assets/<file>       media referenced by project.json "assets[].path"
 * thumbnail.jpg       optional preview image
 * ```
 */
object KnsprojArchive {
    const val PROJECT_ENTRY = "project.json"
    const val ASSETS_DIR = "assets/"
    const val THUMBNAIL_ENTRY = "thumbnail.jpg"
    const val MIME_TYPE = "application/vnd.kinesis.project+zip"
    const val EXTENSION = "knsproj"

    /** Limits that protect against zip bombs and absurd inputs. */
    const val MAX_PROJECT_JSON_BYTES = 64L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 10_000

    private val storedExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "mp4", "m4v", "mov", "webm", "mkv", "3gp", "m4a", "mp3", "aac", "ogg", "opus", "flac")

    /** Provides the bytes of an asset; called twice per stored asset (checksum pass and copy pass). */
    fun interface AssetSource {
        fun open(asset: Asset): InputStream?
    }

    /**
     * Writes [project] and its assets to [out]. Missing asset files raise an [IOException] so that
     * exported projects are always complete.
     */
    fun write(project: KnsProject, assets: AssetSource, out: OutputStream, thumbnailJpeg: ByteArray? = null) {
        val zip = ZipOutputStream(out)
        zip.setLevel(6)
        val jsonBytes = KnsprojJson.encode(project).toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(PROJECT_ENTRY).apply { time = FIXED_TIME })
        zip.write(jsonBytes)
        zip.closeEntry()

        val written = HashSet<String>()
        for (asset in project.assets) {
            val path = normalizeEntryName(asset.path)
                ?: throw IOException("Asset '${asset.id}' has an invalid path '${asset.path}'")
            if (!written.add(path)) continue
            val ext = path.substringAfterLast('.', "").lowercase()
            val entry = ZipEntry(path).apply { time = FIXED_TIME }
            if (ext in storedExtensions) {
                // Already-compressed media: store as-is (needs size and CRC before writing).
                val crc = CRC32()
                var size = 0L
                (assets.open(asset) ?: throw IOException("Missing file for asset '${asset.id}' (${asset.path})")).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                        size += n
                    }
                }
                entry.method = ZipEntry.STORED
                entry.size = size
                entry.compressedSize = size
                entry.crc = crc.value
            }
            zip.putNextEntry(entry)
            (assets.open(asset) ?: throw IOException("Missing file for asset '${asset.id}' (${asset.path})")).use { it.copyTo(zip, 64 * 1024) }
            zip.closeEntry()
        }

        if (thumbnailJpeg != null) {
            zip.putNextEntry(ZipEntry(THUMBNAIL_ENTRY).apply { time = FIXED_TIME })
            zip.write(thumbnailJpeg)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** Receives every non-project entry (assets, thumbnail) while reading. */
    fun interface EntrySink {
        fun accept(path: String, data: InputStream)
    }

    class ReadResult(val project: KnsProject, val entries: Set<String>)

    /**
     * Reads a `.knsproj` stream. Entries other than `project.json` are handed to [sink] with sanitized names.
     * Throws [KnsprojFormatException] for invalid containers.
     */
    fun read(input: InputStream, sink: EntrySink): ReadResult {
        var project: KnsProject? = null
        val entries = LinkedHashSet<String>()
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = try {
                    zip.nextEntry
                } catch (e: Exception) {
                    throw KnsprojFormatException("The file is not a valid .knsproj (ZIP) archive: ${e.message}", e)
                } ?: break
                if (++count > MAX_ENTRIES) throw KnsprojFormatException("Too many entries in archive")
                if (entry.isDirectory) continue
                val name = normalizeEntryName(entry.name)
                    ?: throw KnsprojFormatException("Unsafe entry name in archive: '${entry.name}'")
                val limited = LimitedInputStream(zip, MAX_TOTAL_BYTES - total)
                if (name == PROJECT_ENTRY) {
                    val bytes = readLimited(limited, MAX_PROJECT_JSON_BYTES)
                    project = KnsprojJson.decode(bytes.toString(Charsets.UTF_8))
                } else {
                    sink.accept(name, limited)
                    // Drain whatever the sink did not read so the size accounting stays correct.
                    limited.skipRemaining()
                }
                total += limited.consumed
                entries += name
                zip.closeEntry()
            }
        }
        val p = project ?: throw KnsprojFormatException("project.json not found in archive")
        return ReadResult(p, entries)
    }

    /** Returns a safe relative entry name, or null if it is absolute or escapes the root. */
    fun normalizeEntryName(raw: String): String? {
        val name = raw.replace('\\', '/')
        if (name.isEmpty() || name.startsWith("/") || name.contains(':')) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw KnsprojFormatException("project.json is too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** 1980-01-01 so archives are byte-for-byte reproducible. */
    private const val FIXED_TIME = 315532800000L

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        var consumed = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count(n.toLong())
            return n
        }

        override fun close() {
            // The underlying ZipInputStream must stay open for the next entries.
        }

        fun skipRemaining() {
            val buf = ByteArray(64 * 1024)
            while (read(buf, 0, buf.size) >= 0) Unit
        }

        private fun count(n: Long) {
            consumed += n
            if (consumed > limit) throw KnsprojFormatException("Archive is too large (more than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB uncompressed)")
        }
    }
}

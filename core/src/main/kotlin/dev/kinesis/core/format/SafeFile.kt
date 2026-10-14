package dev.kinesis.core.format

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file replacement: data is written to a temporary sibling, flushed to disk, the previous
 * version is kept as `<name>.bak`, and the temporary file is atomically renamed over the target.
 * A crash at any point leaves either the old or the new file intact.
 */
object SafeFile {

    fun write(target: File, keepBackup: Boolean = true, writer: (OutputStream) -> Unit) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No parent directory for $target")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create directory $dir")
        val tmp = File(dir, ".${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                writer(out)
                out.flush()
                out.fd.sync()
            }
            if (keepBackup && target.exists()) {
                Files.copy(target.toPath(), backupOf(target).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun writeText(target: File, text: String, keepBackup: Boolean = true) =
        write(target, keepBackup) { it.write(text.toByteArray(Charsets.UTF_8)) }

    fun backupOf(target: File) = File(target.absoluteFile.parentFile, "${target.name}.bak")
}

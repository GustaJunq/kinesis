package dev.kinesis.editor.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.kinesis.core.alight.AlightMedia
import dev.kinesis.core.alight.AlightMediaKind
import dev.kinesis.core.alight.AlightMediaMatcher
import dev.kinesis.core.alight.MediaCandidate
import dev.kinesis.core.alight.MediaMatch

/**
 * Finds, on this device, the files an Alight Motion project points at. The XML only has `content://` addresses of the
 * phone that made it, so for each media entry this tries, in order: the address itself (it still works when the project
 * was made on this phone and the access is still granted), then the MediaStore by name, size and pixel size. Reading the
 * MediaStore needs the media permission, which the app asks for only when a project is imported; without it this simply
 * finds less and the person picks the files by hand.
 *
 * A [MediaMatch.candidate]'s `key` is the `content://` address of the file.
 */
object AlightMediaResolver {
    fun resolve(context: Context, media: List<AlightMedia>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Map<String, MediaMatch> {
        val out = LinkedHashMap<String, MediaMatch>()
        for ((i, m) in media.withIndex()) {
            onProgress(i, media.size)
            val direct = direct(context, m)
            val match = direct?.takeIf { it.automatic } ?: AlightMediaMatcher.match(m, searchStore(context, m)) ?: direct
            if (match != null) out[m.uri] = match
        }
        return out
    }

    private fun direct(context: Context, m: AlightMedia): MediaMatch? = try {
        val uri = Uri.parse(m.uri)
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getString(0) ?: m.filename
            val size = if (c.isNull(1)) null else c.getLong(1)
            AlightMediaMatcher.match(m, listOf(MediaCandidate(m.uri, name, size, m.width, m.height)))
        }
    } catch (e: Exception) {
        null // no access to that address on this phone: normal, not an error
    }

    private fun searchStore(context: Context, m: AlightMedia): List<MediaCandidate> {
        val collections = when (m.kind) {
            AlightMediaKind.IMAGE -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to true)
            AlightMediaKind.VIDEO -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to false)
            AlightMediaKind.AUDIO -> listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to false)
            AlightMediaKind.OTHER -> emptyList()
        }
        val found = ArrayList<MediaCandidate>()
        for ((collection, visual) in collections) {
            val projection = if (visual) {
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT)
            } else {
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            }
            val clauses = ArrayList<String>(); val args = ArrayList<String>()
            if (m.sizeBytes != null) { clauses += "${MediaStore.MediaColumns.SIZE} = ?"; args += m.sizeBytes.toString() }
            if (m.filename.isNotBlank()) { clauses += "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"; args += m.filename }
            if (clauses.isEmpty()) continue
            try {
                context.contentResolver.query(collection, projection, clauses.joinToString(" OR "), args.toTypedArray(), null)?.use { c ->
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                        found += MediaCandidate(
                            key = uri, name = c.getString(1) ?: "", sizeBytes = if (c.isNull(2)) null else c.getLong(2),
                            width = if (visual && !c.isNull(3)) c.getInt(3).takeIf { it > 0 } else null,
                            height = if (visual && !c.isNull(4)) c.getInt(4).takeIf { it > 0 } else null,
                        )
                    }
                }
            } catch (e: SecurityException) {
                // No media permission: leave this collection out.
            } catch (e: Exception) {
                // A device that rejects the query: the file just stays unresolved.
            }
        }
        return found
    }
}

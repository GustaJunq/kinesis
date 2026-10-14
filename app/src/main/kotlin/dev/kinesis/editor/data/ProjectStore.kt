package dev.kinesis.editor.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.format.KnsprojArchive
import dev.kinesis.core.format.KnsprojFormatException
import dev.kinesis.core.format.KnsprojJson
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.format.SafeFile
import dev.kinesis.core.format.ValidationIssue
import dev.kinesis.core.geom.GltfLoader
import dev.kinesis.core.model.Asset
import dev.kinesis.core.model.AssetType
import dev.kinesis.core.model.KnsProject
import dev.kinesis.editor.render.AssetFiles
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/** Summary shown on the home screen. */
data class ProjectSummary(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val duration: Double,
    val modified: Long,
    val thumbnail: File?,
    val error: String? = null,
)

/** Thrown when an imported project is structurally invalid. */
class ProjectImportException(message: String, val issues: List<ValidationIssue> = emptyList()) : Exception(message)

/**
 * Projects live in `files/projects/<id>/` as `project.json` + `assets/` (the unpacked form of a `.knsproj`).
 * Writes go through [SafeFile] so a crash never corrupts a project.
 */
class ProjectStore(private val context: Context) {
    val root: File = File(context.filesDir, "projects").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun projectFile(id: String) = File(dir(id), KnsprojArchive.PROJECT_ENTRY)
    fun thumbnailFile(id: String) = File(dir(id), KnsprojArchive.THUMBNAIL_ENTRY)
    fun assetFile(id: String, asset: Asset) = File(dir(id), asset.path)

    fun assetFiles(id: String, project: () -> KnsProject?): AssetFiles = AssetFiles { assetId ->
        project()?.asset(assetId)?.let { assetFile(id, it) }
    }

    fun list(): List<ProjectSummary> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        .mapNotNull { d ->
            val pf = File(d, KnsprojArchive.PROJECT_ENTRY)
            if (!pf.isFile) return@mapNotNull null
            try {
                val p = KnsprojJson.decode(pf.readText())
                ProjectSummary(
                    d.name, p.meta.title, p.composition.width, p.composition.height, p.composition.duration,
                    pf.lastModified(), File(d, KnsprojArchive.THUMBNAIL_ENTRY).takeIf { it.isFile },
                )
            } catch (e: Exception) {
                ProjectSummary(d.name, d.name, 0, 0, 0.0, pf.lastModified(), null, e.message)
            }
        }
        .sortedByDescending { it.modified }

    fun create(project: KnsProject): String {
        val id = UUID.randomUUID().toString()
        File(dir(id), "assets").mkdirs()
        save(id, project)
        return id
    }

    /** Loads a project; falls back to the backup written by the previous save if the main file is unreadable. */
    fun load(id: String): KnsProject {
        val f = projectFile(id)
        return try {
            KnsprojJson.decode(f.readText())
        } catch (e: Exception) {
            val bak = SafeFile.backupOf(f)
            if (bak.isFile) KnsprojJson.decode(bak.readText()) else throw e
        }
    }

    fun save(id: String, project: KnsProject) {
        val stamped = project.copy(meta = project.meta.copy(modifiedAt = Instant.now().toString()))
        SafeFile.writeText(projectFile(id), KnsprojJson.encode(stamped))
    }

    fun saveThumbnail(id: String, jpeg: ByteArray) = SafeFile.write(thumbnailFile(id), keepBackup = false) { it.write(jpeg) }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun duplicate(id: String): String {
        val newId = UUID.randomUUID().toString()
        dir(id).copyRecursively(dir(newId))
        val p = load(newId)
        save(newId, p.copy(meta = p.meta.copy(title = p.meta.title + " (2)", createdAt = Instant.now().toString())))
        return newId
    }

    /** Imports a `.knsproj` stream into a new project. Rejects invalid containers and documents. */
    fun import(input: InputStream): String {
        val tmp = File(root, ".import-${UUID.randomUUID()}")
        tmp.mkdirs()
        try {
            val result = KnsprojArchive.read(input) { path, data ->
                if (path.startsWith(KnsprojArchive.ASSETS_DIR) || path == KnsprojArchive.THUMBNAIL_ENTRY) {
                    val target = File(tmp, path)
                    target.parentFile?.mkdirs()
                    target.outputStream().use { data.copyTo(it) }
                }
            }
            val report = ProjectValidator.validate(result.project, result.entries)
            if (!report.isValid) {
                throw ProjectImportException(report.errors.take(5).joinToString("\n") { "${it.path}: ${it.message}" }, report.errors)
            }
            File(tmp, "assets").mkdirs()
            SafeFile.writeText(File(tmp, KnsprojArchive.PROJECT_ENTRY), KnsprojJson.encode(result.project), keepBackup = false)
            val id = UUID.randomUUID().toString()
            if (!tmp.renameTo(dir(id))) throw IOException("Cannot move imported project into place")
            return id
        } catch (e: KnsprojFormatException) {
            throw ProjectImportException(e.message ?: "Invalid project")
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    /** Writes the project and all its media as a `.knsproj` archive. */
    fun export(id: String, out: OutputStream) {
        val project = load(id)
        val thumb = thumbnailFile(id).takeIf { it.isFile }?.readBytes()
        KnsprojArchive.write(project, { a -> assetFile(id, a).takeIf { it.isFile }?.inputStream() }, out, thumb)
        out.flush()
    }

    /**
     * Copies a picked file into the project's assets and returns the new [Asset] with probed metadata,
     * or throws if the media cannot be read.
     */
    fun importMedia(id: String, project: KnsProject, uri: Uri, type: AssetType): Asset {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri)
        val mime = resolver.getType(uri)
        val ext = name?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: defaultExtension(type)
        var asset = Factory.newAsset(project, type, ext, name, mime)
        val file = assetFile(id, asset)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        try {
            (resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")).use { input ->
                tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            if (!tmp.renameTo(file)) throw IOException("Cannot store media")
            asset = probe(asset, file)
            return asset
        } catch (e: Exception) {
            tmp.delete()
            file.delete()
            throw e
        }
    }

    private fun probe(asset: Asset, file: File): Asset = when (asset.type) {
        AssetType.IMAGE -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            if (o.outWidth <= 0) throw IOException("Unsupported image")
            val rotated = runCatching {
                val r = android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
                r == android.media.ExifInterface.ORIENTATION_ROTATE_90 || r == android.media.ExifInterface.ORIENTATION_ROTATE_270
            }.getOrDefault(false)
            asset.copy(width = if (rotated) o.outHeight else o.outWidth, height = if (rotated) o.outWidth else o.outHeight)
        }
        AssetType.VIDEO, AssetType.AUDIO -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.path)
                val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000.0)
                if (asset.type == AssetType.VIDEO) {
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w == null || h == null) throw IOException("Unsupported video")
                    val swap = rot == 90 || rot == 270
                    val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                    asset.copy(width = if (swap) h else w, height = if (swap) w else h, duration = duration, hasAudio = hasAudio)
                } else {
                    asset.copy(duration = duration ?: throw IOException("Unsupported audio"))
                }
            } finally {
                runCatching { r.release() }
            }
        }
        AssetType.MODEL -> {
            // Validates the file now so problems are reported at import time.
            GltfLoader.load(file.readBytes())
            asset
        }
        AssetType.FONT -> {
            runCatching { android.graphics.Typeface.createFromFile(file) }.getOrElse { throw IOException("Unsupported font") }
            asset
        }
    }

    private fun defaultExtension(type: AssetType) = when (type) {
        AssetType.IMAGE -> "png"
        AssetType.VIDEO -> "mp4"
        AssetType.AUDIO -> "m4a"
        AssetType.MODEL -> "glb"
        AssetType.FONT -> "ttf"
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Removes leftovers of interrupted imports. */
    fun cleanup() {
        root.listFiles { f -> f.name.startsWith(".import-") }?.forEach { it.deleteRecursively() }
    }
}

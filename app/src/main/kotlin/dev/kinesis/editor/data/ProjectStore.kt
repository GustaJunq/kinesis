package dev.kinesis.editor.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dev.kinesis.core.alight.AlightConversion
import dev.kinesis.core.alight.ImportReport
import dev.kinesis.core.alight.ReportKind
import dev.kinesis.core.edit.Factory
import dev.kinesis.core.format.AureaFormatException
import dev.kinesis.core.format.AureaImport
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
import dev.kinesis.editor.BuildConfig
import dev.kinesis.editor.render.AssetFiles
import java.io.BufferedInputStream
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
/** A new project id and what an imported Aurea project lost in the conversion. */
data class ImportResult(val id: String, val warnings: List<String>)

/** A new project made from an Alight Motion import, and what the conversion did. */
class AlightImported(val id: String, val report: ImportReport)

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
        // The copy gets its own versions and its own file in Documents.
        File(dir(newId), MIRROR).delete()
        File(dir(newId), SNAPSHOTS).deleteRecursively()
        val p = load(newId)
        save(newId, p.copy(meta = p.meta.copy(title = p.meta.title + " (2)", createdAt = Instant.now().toString())))
        return newId
    }

    /**
     * Imports a `.knsproj` or an Aurea `.aureaproj` into a new project. [ImportResult.warnings] lists what an Aurea
     * project lost in the conversion (always empty for `.knsproj`).
     */
    fun import(input: InputStream): ImportResult {
        // The format is decided by the entries of the whole zip, not by the first one: the `.aureaproj` of the Aurea app
        // lists its media before `project.aurea`. The stream is kept in a file so it can be read twice.
        val tmp = File(root, ".import-src-${UUID.randomUUID()}")
        try {
            tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
            val aurea = try {
                java.util.zip.ZipFile(tmp).use { z -> z.getEntry(AureaImport.PROJECT_ENTRY) != null && z.getEntry(KnsprojArchive.PROJECT_ENTRY) == null }
            } catch (e: java.util.zip.ZipException) {
                throw ProjectImportException("not a zip archive")
            }
            return tmp.inputStream().buffered(64 * 1024).use { s ->
                if (aurea) importAurea(s) else ImportResult(importKnsproj(s), emptyList())
            }
        } finally {
            tmp.delete()
        }
    }

    private fun importAurea(input: InputStream): ImportResult {
        val tmp = File(root, ".import-${UUID.randomUUID()}")
        val media = File(tmp, ".media").apply { mkdirs() }
        try {
            val stored = HashMap<String, File>()
            val result = try {
                AureaImport.read(input, "Kinesis Editor ${BuildConfig.VERSION_NAME}") { entry, data ->
                    val f = File(media, "${stored.size}")
                    f.outputStream().use { data.copyTo(it, 64 * 1024) }
                    stored[entry] = f
                }
            } catch (e: AureaFormatException) {
                throw ProjectImportException(e.message ?: "Invalid Aurea project")
            }
            val warnings = result.warnings.toMutableList()
            // Move each media file into place and read what the pool did not say (duration, audio track).
            val assets = result.project.assets.mapNotNull { asset ->
                val src = stored[result.media[asset.path]] ?: return@mapNotNull asset
                val target = File(tmp, asset.path)
                target.parentFile?.mkdirs()
                if (!src.renameTo(target)) src.copyTo(target, overwrite = true)
                runCatching { probe(asset, target) }.getOrElse {
                    warnings += "\"${asset.name ?: asset.path}\": o arquivo de mídia não pôde ser lido"
                    asset
                }
            }
            val project = result.project.copy(assets = assets)
            val report = ProjectValidator.validate(project)
            if (!report.isValid) {
                throw ProjectImportException(report.errors.take(5).joinToString("\n") { "${it.path}: ${it.message}" }, report.errors)
            }
            media.deleteRecursively()
            File(tmp, "assets").mkdirs()
            SafeFile.writeText(File(tmp, KnsprojArchive.PROJECT_ENTRY), KnsprojJson.encode(project), keepBackup = false)
            val id = UUID.randomUUID().toString()
            if (!tmp.renameTo(dir(id))) throw IOException("Cannot move imported project into place")
            return ImportResult(id, warnings)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    /**
     * Creates a project from an Alight Motion [conversion]. [source] opens the file behind an Alight media address; the
     * files are copied into the project, so nothing outside it is needed afterwards.
     */
    fun importAlight(conversion: AlightConversion, source: (String) -> InputStream?): AlightImported {
        val tmp = File(root, ".import-${UUID.randomUUID()}")
        try {
            File(tmp, "assets").mkdirs()
            val assets = conversion.project.assets.map { asset ->
                val label = asset.name ?: asset.path
                val uri = conversion.media[asset.path] ?: throw ProjectImportException("mídia sem origem: $label")
                val target = File(tmp, asset.path)
                target.parentFile?.mkdirs()
                try {
                    (source(uri) ?: throw IOException("sem acesso")).use { i -> target.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                } catch (e: IOException) {
                    throw ProjectImportException("não foi possível copiar \"$label\": ${e.message}")
                } catch (e: SecurityException) {
                    throw ProjectImportException("sem permissão para ler \"$label\"")
                }
                val probed = try { probe(asset, target) } catch (e: Exception) { throw ProjectImportException("\"$label\" não é uma mídia que o Kinesis consegue ler") }
                if (asset.width != null && asset.height != null && (probed.width != asset.width || probed.height != asset.height)) {
                    conversion.report.add(
                        ReportKind.WARNING, "media:$label",
                        "o arquivo tem ${probed.width}×${probed.height}, mas o projeto do Alight usava ${asset.width}×${asset.height}: a posição e o corte podem ficar diferentes",
                    )
                }
                probed
            }
            val project = conversion.project.copy(assets = assets)
            val report = ProjectValidator.validate(project)
            if (!report.isValid) {
                throw ProjectImportException(report.errors.take(5).joinToString("\n") { "${it.path}: ${it.message}" }, report.errors)
            }
            SafeFile.writeText(File(tmp, KnsprojArchive.PROJECT_ENTRY), KnsprojJson.encode(project), keepBackup = false)
            val id = UUID.randomUUID().toString()
            if (!tmp.renameTo(dir(id))) throw IOException("Cannot move imported project into place")
            return AlightImported(id, conversion.report)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    private fun importKnsproj(input: InputStream): String {
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
        val name = displayName(resolver, uri) ?: uri.lastPathSegment.takeIf { uri.scheme == "file" }
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

    // ------------------------------------------------------------------ snapshots

    /** A saved version of a project's document (assets are shared with the project). */
    class Snapshot(val file: File, val time: Long)

    private fun snapshotDir(id: String) = File(dir(id), SNAPSHOTS)

    /** Newest first. */
    fun snapshots(id: String): List<Snapshot> = snapshotDir(id).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Snapshot(f, it) } }
        .sortedByDescending { it.time }

    /** Saves [project] as a new snapshot, keeping the newest [MAX_SNAPSHOTS]. Skipped when identical to the newest one. */
    fun snapshot(id: String, project: KnsProject) {
        val text = KnsprojJson.encode(project)
        val existing = snapshots(id)
        if (existing.firstOrNull()?.file?.let { runCatching { it.readText() }.getOrNull() } == text) return
        val dir = snapshotDir(id).apply { mkdirs() }
        var time = System.currentTimeMillis()
        while (File(dir, "$time.json").exists()) time++
        SafeFile.writeText(File(dir, "$time.json"), text, keepBackup = false)
        (snapshots(id).drop(MAX_SNAPSHOTS)).forEach { it.file.delete() }
    }

    fun loadSnapshot(snapshot: Snapshot): KnsProject = KnsprojJson.decode(snapshot.file.readText())

    // ------------------------------------------------------------------ crash recovery

    private val sessionFile = File(root, ".session")

    /** Marks [id] as open in the editor; the mark is left behind if the app dies before [endSession]. */
    fun beginSession(id: String) = runCatching { sessionFile.writeText(id) }

    fun endSession(id: String) {
        runCatching { if (sessionFile.isFile && sessionFile.readText() == id) sessionFile.delete() }
    }

    /** The project that was open when the app last stopped without closing the editor, if it still exists. */
    fun interruptedSession(): String? = runCatching { sessionFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        ?.takeIf { it.isNotEmpty() && projectFile(it).isFile }

    fun clearSession() {
        sessionFile.delete()
    }

    // ------------------------------------------------------------------ copy in a user-visible folder

    /**
     * Writes the project as a `.knsproj` into `Documents/Kinesis` (Android 11+) or `Download/Kinesis` (Android 10),
     * where it survives uninstalling the app. The same file is overwritten on later calls. Returns false when not
     * possible (older Android, storage refused).
     */
    fun mirrorToDocuments(id: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = context.contentResolver
        val marker = File(dir(id), MIRROR)
        fun write(uri: Uri): Boolean = resolver.openOutputStream(uri, "wt")?.use { out -> export(id, out); true } ?: false
        marker.takeIf { it.isFile }?.readText()?.let(Uri::parse)?.let { uri ->
            try {
                if (write(uri)) return true
            } catch (e: Exception) {
                // Deleted by the user or no longer ours (e.g. after reinstalling): create a new file below.
            }
        }
        val title = load(id).meta.title.ifBlank { "Kinesis" }.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title-${id.take(8)}.knsproj")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Kinesis")
            MediaStore.Files.getContentUri("external")
        } else {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kinesis")
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return false
        return try {
            if (!write(uri)) return false
            marker.writeText(uri.toString())
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    companion object {
        const val MAX_SNAPSHOTS = 10
        private const val SNAPSHOTS = ".snapshots"
        private const val MIRROR = ".mirror"
    }
}

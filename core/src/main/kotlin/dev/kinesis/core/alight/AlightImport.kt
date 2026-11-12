package dev.kinesis.core.alight

import dev.kinesis.core.format.KnsprojArchive
import dev.kinesis.core.format.ProjectValidator
import dev.kinesis.core.model.KnsProject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** A file that might be one of the project's media: where it is ([key], opaque to the core), its name and what is known of it. */
data class MediaCandidate(val key: String, val name: String, val sizeBytes: Long?, val width: Int? = null, val height: Int? = null)

/** How sure a [MediaMatch] is. [EXACT]: same size and the same name or pixel size. [SIZE]: only the byte size agrees. [NAME]: the name agrees but the size does not (a different file: never used on its own). */
enum class MatchKind { EXACT, SIZE, NAME }

data class MediaMatch(val media: AlightMedia, val candidate: MediaCandidate, val kind: MatchKind) {
    /** True when the file can be used without asking. */
    val automatic get() = kind != MatchKind.NAME
}

/**
 * Finds the file behind each media entry of a project. The `content://` address of the XML only works on the device that
 * made it, so the match is by name, size in bytes and pixel size (the files may have been renamed, which the size catches).
 */
object AlightMediaMatcher {
    /** The best candidate for [media], or null when none agrees on size or name. */
    fun match(media: AlightMedia, candidates: List<MediaCandidate>): MediaMatch? {
        var best: MediaMatch? = null
        var bestScore = 0
        for (c in candidates) {
            val sameSize = media.sizeBytes != null && c.sizeBytes == media.sizeBytes
            val sameName = c.name.equals(media.filename, ignoreCase = true)
            val sameRes = media.width != null && media.height != null && c.width == media.width && c.height == media.height
            val kind = when {
                sameSize && (sameName || sameRes) -> MatchKind.EXACT
                sameSize && (c.width == null || media.width == null) -> MatchKind.SIZE
                sameSize -> MatchKind.SIZE
                sameName -> MatchKind.NAME
                else -> continue
            }
            val score = when (kind) { MatchKind.EXACT -> 30 + (if (sameName) 2 else 0) + (if (sameRes) 1 else 0); MatchKind.SIZE -> 20; MatchKind.NAME -> 10 + (if (sameRes) 1 else 0) }
            if (score > bestScore) { bestScore = score; best = MediaMatch(media, c, kind) }
        }
        return best
    }

    /** [match] for every media entry; entries with no candidate are absent from the result. */
    fun matchAll(media: List<AlightMedia>, candidates: List<MediaCandidate>): Map<String, MediaMatch> =
        media.mapNotNull { m -> match(m, candidates)?.let { m.uri to it } }.toMap()
}

/**
 * The whole import, with no UI and no Android: [scan] reads the XML, the caller finds the files, [convert] builds the
 * project and [write] packs it as a `.knsproj`. The app and the command line use these same three steps.
 */
object AlightImport {
    /** Reads the XML. Throws [AlightFormatException] when it is not an Alight project. */
    fun scan(xml: ByteArray): AlightScan = AlightParser.scan(xml)

    /** Converts [scan] using the media listed in [available] (the `uri`s whose files the caller will provide). */
    fun convert(scan: AlightScan, available: Set<String>, generator: String = "Kinesis Editor"): AlightConversion {
        val c = AlightConverter.convert(scan.scene, available, generator)
        // What the scan noticed (odd values, nested media) comes first in the final report.
        val report = ImportReport()
        for (e in scan.report.entries) report.add(e.kind, e.where, e.message)
        for (e in c.report.entries) report.add(e.kind, e.where, e.message)
        return AlightConversion(c.project, report, c.media)
    }

    /**
     * Packs [conversion] as a `.knsproj`. [open] gives the bytes of the Alight media `uri` an asset was filled from.
     * Throws [IOException] when a file cannot be read, and [AlightFormatException] when the project does not validate.
     */
    fun write(conversion: AlightConversion, open: (uri: String) -> InputStream?, out: OutputStream) {
        validate(conversion.project)
        KnsprojArchive.write(conversion.project, { asset -> conversion.media[asset.path]?.let(open) }, out)
    }

    fun validate(project: KnsProject) {
        val r = ProjectValidator.validate(project)
        if (!r.isValid) throw AlightFormatException("o projeto convertido não passou na validação: " + r.errors.take(3).joinToString("; ") { "${it.path}: ${it.message}" })
    }
}

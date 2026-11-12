package dev.kinesis.core.alight

import kotlinx.serialization.Serializable

/**
 * Neutral intermediate representation of an Alight Motion project (`.xml` export). It knows nothing about Kinesis:
 * [AlightParser] fills it from the XML and a later stage maps it to a `.knsproj`. It is JSON-serializable so a tool or an
 * agent can inspect what was read.
 */
@Serializable
data class AlightScene(
    val title: String,
    val width: Int,
    val height: Int,
    val fps: Double,
    /** Length of the composition in milliseconds. */
    val totalTimeMs: Long,
    /** `#aarrggbb` as written by Alight. */
    val background: String,
    /** Alight version that exported the file (the `am` attribute), when present. */
    val exportedBy: String? = null,
    val media: List<AlightMedia> = emptyList(),
    /** Bookmark times in milliseconds. */
    val bookmarksMs: List<Long> = emptyList(),
)

enum class AlightMediaKind { IMAGE, VIDEO, AUDIO, OTHER }

/**
 * A media pointer. The XML carries no media bytes: [uri] is a `content://` address that only works on the device that
 * made the project, so [filename], [size] and the pixel size are kept to find the file again.
 */
@Serializable
data class AlightMedia(
    val uri: String,
    val filename: String,
    val mimeType: String,
    val kind: AlightMediaKind,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    /** Microseconds (the unit Alight writes: a video as long as the 19 865 ms scene says 19 867 000). */
    val durationUs: Long?,
)

/** What a conversion did with each part of the project. */
enum class ReportKind { CONVERTED, APPROXIMATED, UNSUPPORTED, MISSING_MEDIA, WARNING }

@Serializable
data class ReportEntry(val kind: ReportKind, val where: String, val message: String)

/** Everything a conversion has to tell the person; nothing is dropped silently. */
class ImportReport {
    private val items = ArrayList<ReportEntry>()
    val entries: List<ReportEntry> get() = items
    fun add(kind: ReportKind, where: String, message: String) { items += ReportEntry(kind, where, message) }
    fun count(kind: ReportKind) = items.count { it.kind == kind }
}

/** The XML could not be read. [message] is ready to show. */
class AlightFormatException(message: String) : Exception(message)

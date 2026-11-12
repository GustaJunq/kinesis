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
    /** Layers of this scene in document order: the first is at the back, the last on top. */
    val layers: List<AlightLayer> = emptyList(),
)

/** A `<shadow>` of a layer: [direction] is `outside` (drop shadow) or `inside`; [size] is the blur extent in pixels. */
@Serializable
data class AlightShadow(
    val direction: String,
    val color: String,
    val opacity: AlightProp? = null,
    val size: AlightProp? = null,
    val offset: AlightProp? = null,
)

enum class AlightLayerKind { SHAPE, EMBED, AUDIO }

/** One keyframe: [t] is 0..1 of the layer's duration (the file lists them out of order), [easing] the raw `e` text. */
@Serializable
data class AlightKeyframe(val t: Double, val value: List<Double>, val easing: String? = null)

/** A property that is either a constant ([static]) or animated ([keyframes], sorted by time). */
@Serializable
data class AlightProp(val static: List<Double>? = null, val keyframes: List<AlightKeyframe> = emptyList()) {
    val animated get() = keyframes.isNotEmpty()
}

@Serializable
data class AlightTransform(
    val location: AlightProp? = null,
    val scale: AlightProp? = null,
    val rotation: AlightProp? = null,
    val pivot: AlightProp? = null,
    val opacity: AlightProp? = null,
)

/**
 * A layer as written in the XML. Geometry facts that the mapper relies on (checked against the fixture): `size` is the
 * half-extent of the rectangle, `location` is where the layer's centre goes (in the parent's space, origin top-left
 * for the scene and the centre for a parent layer) and `pivot` only moves the axis of rotation and scale.
 */
@Serializable
data class AlightLayer(
    val id: String,
    val label: String,
    val kind: AlightLayerKind,
    val startMs: Long,
    val endMs: Long,
    val parentId: String? = null,
    /** `media` or `color` for shapes, `intrinsic` for embedded scenes. */
    val fillType: String? = null,
    /** `content://` address of the picture, video or audio the layer shows or plays. */
    val mediaUri: String? = null,
    val mediaFillMode: String? = null,
    /** Half-extent of the rectangle, from the `size` property. */
    val size: List<Double>? = null,
    val inTimeMs: Long? = null,
    val outTimeMs: Long? = null,
    val speed: Double? = null,
    /** Embedded scene (pre-composition) of an `embedScene`: its own size, length and layers. */
    val scene: AlightScene? = null,
    /** `link` of a pre-composition that Alight keeps in sync across copies. */
    val linkId: String? = null,
    /** `gain` of a video or audio layer: linear volume (0 = silent), constant or animated over the layer. */
    val gain: AlightProp? = null,
    /** Shape type written in `s` (`.rect` for rectangles), null when absent. */
    val shapeType: String? = null,
    val shadows: List<AlightShadow> = emptyList(),
    val transform: AlightTransform = AlightTransform(),
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

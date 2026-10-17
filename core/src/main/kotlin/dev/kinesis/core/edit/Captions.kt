package dev.kinesis.core.edit

import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Color
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.FontSpec
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.model.TextAlign
import dev.kinesis.core.model.TextContent
import dev.kinesis.core.model.Transform2D
import dev.kinesis.core.scene.AudioClip
import kotlin.math.max
import kotlin.math.min

/** One caption: text shown from [start] to [end] (composition seconds). */
data class CaptionSegment(val start: Double, val end: Double, val text: String)

/** Look shared by every caption of a group. Sizes are fractions of the composition so they follow its format. */
data class CaptionStyle(
    val fontFamily: String = "sans-serif",
    val weight: Int = 800,
    /** Font size as a fraction of the shorter side of the composition. */
    val size: Float = 0.06f,
    val color: Color = Color.WHITE,
    /** Outline colour; null for no outline. */
    val outline: Color? = Color(0f, 0f, 0f, 1f),
    /** Outline width as a fraction of the font size. */
    val outlineWidth: Float = 0.12f,
    /** Distance of the caption centre from the bottom edge, as a fraction of the composition height. */
    val bottom: Float = 0.14f,
    /** Wrap width as a fraction of the composition width. */
    val width: Float = 0.86f,
)

/**
 * Subtitles as part of the normal timeline: a group layer (id `captions_…`) holding one text layer per caption, so
 * they are edited, animated, rendered and exported like any other layer. No file format change is needed.
 */
object Captions {
    const val GROUP_PREFIX = "captions"

    /** Captions shorter than this are stretched, so they can be read. */
    const val MIN_DURATION = 0.4

    fun isGroup(layer: Layer): Boolean = layer.type == LayerType.GROUP && layer.id.startsWith(GROUP_PREFIX)

    /** Ids of the caption groups at the top level of [project]. */
    fun groupIds(project: KnsProject): List<String> = project.layers.filter(::isGroup).map { it.id }

    /** The captions of [groupId] in time order. */
    fun segments(project: KnsProject, groupId: String): List<CaptionSegment> {
        val g = LayerTree.find(project, groupId) ?: return emptyList()
        return g.layers.filter { it.type == LayerType.TEXT }
            .map { CaptionSegment(g.start + it.start, g.start + it.end, it.text?.content.orEmpty()) }
            .sortedBy { it.start }
    }

    /** The style of the first caption of [groupId] (what the style editor shows), or the default. */
    fun style(project: KnsProject, groupId: String): CaptionStyle {
        val g = LayerTree.find(project, groupId) ?: return CaptionStyle()
        val t = g.layers.firstOrNull { it.type == LayerType.TEXT }?.text ?: return CaptionStyle()
        val short = min(project.composition.width, project.composition.height).toFloat()
        val size = t.fontSize.value / short
        return CaptionStyle(
            fontFamily = t.font.family,
            weight = t.font.weight,
            size = size,
            color = t.color.value,
            outline = t.stroke?.takeIf { it.enabled }?.color?.value,
            outlineWidth = t.stroke?.width?.value?.let { it / t.fontSize.value } ?: 0.12f,
            bottom = 0.5f - (g.layers.first { it.type == LayerType.TEXT }.transform.position.value.y / project.composition.height),
            width = if (t.maxWidth > 0f) t.maxWidth / project.composition.width else 0.86f,
        )
    }

    /** Adds a caption group built from [segments] (composition seconds). Returns the new project and the group id. */
    fun add(project: KnsProject, segments: List<CaptionSegment>, style: CaptionStyle = CaptionStyle(), name: String = "Captions"): Pair<KnsProject, String> {
        val id = Ids.newId(GROUP_PREFIX, LayerTree.allIds(project))
        val group = Layer(id = id, type = LayerType.GROUP, name = name, start = 0.0, duration = project.composition.duration)
        val withGroup = LayerTree.insert(project, null, -1, group)
        return replaceSegments(withGroup, id, segments, style) to id
    }

    /** Replaces the captions of [groupId] by [segments], all with [style] (the group's current style when null). */
    fun replaceSegments(project: KnsProject, groupId: String, segments: List<CaptionSegment>, style: CaptionStyle? = null): KnsProject {
        val group = LayerTree.find(project, groupId) ?: return project
        val st = style ?: style(project, groupId)
        val clean = normalize(segments, project.composition.duration)
        val taken = LayerTree.allIds(project).toMutableSet()
        val children = clean.mapIndexed { i, s ->
            val cid = Ids.newId("cap", taken); taken += cid
            captionLayer(project, cid, "Caption ${i + 1}", s.copy(start = s.start - group.start, end = s.end - group.start), st)
        }
        return LayerTree.update(project, groupId) { it.copy(layers = children) }
    }

    /** Applies [style] to every caption of [groupId]. */
    fun applyStyle(project: KnsProject, groupId: String, style: CaptionStyle): KnsProject =
        replaceSegments(project, groupId, segments(project, groupId), style)

    /** Time order, no overlaps, readable minimum length, inside [0, duration]; empty texts are dropped. */
    fun normalize(segments: List<CaptionSegment>, duration: Double): List<CaptionSegment> {
        val sorted = segments.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() && it.end > it.start }.sortedBy { it.start }
        val out = ArrayList<CaptionSegment>(sorted.size)
        for ((i, s) in sorted.withIndex()) {
            val start = s.start.coerceIn(0.0, duration)
            val limit = if (i + 1 < sorted.size) min(sorted[i + 1].start, duration) else duration
            val end = min(max(s.end, start + MIN_DURATION), limit).coerceAtMost(duration)
            if (end - start > 1e-3) out += CaptionSegment(start, end, s.text)
        }
        return out
    }

    /** Splits the caption at [index] in two at time [at]; the text is cut at the nearest space to the proportion. */
    fun split(segments: List<CaptionSegment>, index: Int, at: Double): List<CaptionSegment> {
        val s = segments.getOrNull(index) ?: return segments
        if (at <= s.start + 0.05 || at >= s.end - 0.05) return segments
        val target = ((at - s.start) / (s.end - s.start) * s.text.length).toInt().coerceIn(1, s.text.length - 1)
        val cut = (0 until s.text.length).filter { s.text[it] == ' ' }.minByOrNull { kotlin.math.abs(it - target) } ?: target
        val a = s.text.substring(0, cut).trim(); val b = s.text.substring(cut).trim()
        if (a.isEmpty() || b.isEmpty()) return segments
        return segments.toMutableList().also { it[index] = CaptionSegment(s.start, at, a); it.add(index + 1, CaptionSegment(at, s.end, b)) }
    }

    /** Joins the caption at [index] with the next one. */
    fun merge(segments: List<CaptionSegment>, index: Int): List<CaptionSegment> {
        val a = segments.getOrNull(index) ?: return segments
        val b = segments.getOrNull(index + 1) ?: return segments
        return segments.toMutableList().also { it[index] = CaptionSegment(a.start, b.end, "${a.text} ${b.text}"); it.removeAt(index + 1) }
    }

    /**
     * Maps recognised speech to the timeline. [recognised] times are seconds of the source media (what Whisper
     * heard); the layer plays that media from `sourceStart` at `speed`, starting at the clip's origin.
     */
    fun fromRecognised(clip: AudioClip, recognised: List<CaptionSegment>): List<CaptionSegment> =
        recognised.mapNotNull { r ->
            val start = clip.layerOrigin + (r.start - clip.sourceStart) / clip.speed
            val end = clip.layerOrigin + (r.end - clip.sourceStart) / clip.speed
            val s = max(start, clip.compStart); val e = min(end, clip.compEnd)
            if (e - s > 1e-3) CaptionSegment(s, e, r.text) else null
        }

    private fun captionLayer(project: KnsProject, id: String, name: String, s: CaptionSegment, st: CaptionStyle): Layer {
        val w = project.composition.width; val h = project.composition.height
        val size = min(w, h) * st.size
        return Layer(
            id = id, type = LayerType.TEXT, name = name, start = s.start, duration = s.end - s.start,
            transform = Transform2D(position = anim(Vec2(0f, h * (0.5f - st.bottom)))),
            text = TextContent(
                content = s.text,
                font = FontSpec(family = st.fontFamily, weight = st.weight),
                fontSize = anim(size),
                color = anim(st.color),
                align = TextAlign.CENTER,
                maxWidth = w * st.width,
                stroke = st.outline?.let { StrokeSpec(color = anim(it), width = anim(size * st.outlineWidth)) },
            ),
        )
    }

    // ------------------------------------------------------------------ SRT

    fun toSrt(segments: List<CaptionSegment>): String = buildString {
        for ((i, s) in segments.withIndex()) {
            append(i + 1).append('\n').append(srtTime(s.start)).append(" --> ").append(srtTime(s.end)).append('\n').append(s.text).append("\n\n")
        }
    }

    fun parseSrt(text: String): List<CaptionSegment> {
        val time = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")
        return text.replace("\r\n", "\n").split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.trim().lines()
            val ti = lines.indexOfFirst { time.containsMatchIn(it) }
            if (ti < 0) return@mapNotNull null
            val m = time.find(lines[ti])!!.groupValues
            fun sec(o: Int) = m[o].toInt() * 3600.0 + m[o + 1].toInt() * 60 + m[o + 2].toInt() + m[o + 3].padEnd(3, '0').toInt() / 1000.0
            val body = lines.drop(ti + 1).joinToString("\n").trim()
            if (body.isEmpty()) null else CaptionSegment(sec(1), sec(5), body)
        }
    }

    private fun srtTime(t: Double): String {
        val ms = Math.round(t * 1000)
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }
}

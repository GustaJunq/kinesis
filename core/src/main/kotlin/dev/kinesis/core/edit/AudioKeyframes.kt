package dev.kinesis.core.edit

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Keyframe
import dev.kinesis.core.audio.AudioAnalysis
import dev.kinesis.core.model.Effect
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerTree
import dev.kinesis.core.model.LayerType
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * "Convert audio to keyframes": turns the loudness of an audio or video clip into keyframes of Slider Control effects on
 * a null layer, so expressions read them like in After Effects:
 * `thisComp.layer("Audio Amplitude").effect("Both Channels")("Slider")`.
 *
 * - "Audio Amplitude" has the sliders "Left Channel", "Right Channel" and "Both Channels".
 * - "Audio Bands" has "Low", "Mid" and "High" (low 20–250 Hz, mid 250–4000 Hz, high 4–16 kHz; a Kinesis extra).
 *
 * Values are normalised to the loudest point of the clip: 0 is silence and 100 is the peak (After Effects does not
 * normalise; its own scale is not documented, so use `linear()` to map the range). The null layers start where the
 * clip starts and last as long as it does, inside the same container, so layer-local times match.
 */
object AudioKeyframes {
    const val AMPLITUDE_LAYER = "Audio Amplitude"
    const val BANDS_LAYER = "Audio Bands"
    val CHANNELS = listOf("Left Channel" to "left", "Right Channel" to "right", "Both Channels" to "both")
    val BANDS = listOf("Low" to "low", "Mid" to "mid", "High" to "high")

    /**
     * Adds (or replaces, when a null layer of that name already exists in the container) the layers for [sourceId].
     * [analysis] must start at the clip's `sourceStart` and cover `duration * speed` seconds. One keyframe is written
     * every [everyFrames] composition frames. Returns the project unchanged if [sourceId] has no audio clip.
     */
    fun apply(project: KnsProject, sourceId: String, analysis: AudioAnalysis, everyFrames: Int = 1, bands: Boolean = true): KnsProject {
        val source = LayerTree.find(project, sourceId) ?: return project
        val media = source.media ?: return project
        if (source.type != LayerType.AUDIO && source.type != LayerType.VIDEO) return project
        val containerId = LayerTree.locate(project, sourceId)?.containerId
        val step = (everyFrames.coerceAtLeast(1)) / project.composition.fps
        var out = project
        out = put(out, containerId, source, AMPLITUDE_LAYER, CHANNELS, analysis, media.speed, step)
        if (bands) out = put(out, containerId, source, BANDS_LAYER, BANDS, analysis, media.speed, step)
        return out
    }

    private fun put(
        project: KnsProject, containerId: String?, source: Layer, name: String, sliders: List<Pair<String, String>>,
        analysis: AudioAnalysis, speed: Double, step: Double,
    ): KnsProject {
        val series = sliders.map { (_, key) -> analysis.series(key)!! }
        val peak = series.maxOf { s -> s.maxOrNull() ?: 0f }
        val count = floor(source.duration / step + 1e-9).toInt() + 1
        val times = DoubleArray(count) { if (it == count - 1) source.duration else it * step }
        val effects = sliders.mapIndexed { i, (label, _) ->
            val keys = times.map { t ->
                val level = analysis.at(series[i], t * speed)
                Keyframe(t, if (peak <= 0f) 0f else ((level / peak) * 10000f).roundToInt() / 100f)
            }.dedupe()
            Effect.create("sliderControl").withName(label).withNumber("slider", Animatable(keys.first().value, keys))
        }
        val siblings = LayerTree.container(project, containerId)
        val existing = siblings.firstOrNull { it.type == LayerType.NULL && it.name == name }
        val layer = (existing ?: Factory.nullObject(project, source.start, false, name)).copy(
            start = source.start, duration = source.duration, effects = effects,
        )
        return if (existing != null) LayerTree.update(project, existing.id) { layer } else LayerTree.insert(project, containerId, -1, layer)
    }

    /** Drops the middle keyframes of runs of equal values (silence stays two keyframes). */
    private fun List<Keyframe<Float>>.dedupe(): List<Keyframe<Float>> {
        if (size < 3) return this
        val out = ArrayList<Keyframe<Float>>(size)
        out += first()
        for (i in 1 until lastIndex) {
            if (this[i].value == out.last().value && this[i].value == this[i + 1].value) continue
            out += this[i]
        }
        out += last()
        return out
    }
}

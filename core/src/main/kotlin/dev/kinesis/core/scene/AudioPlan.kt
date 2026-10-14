package dev.kinesis.core.scene

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.at
import dev.kinesis.core.model.KnsProject
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.LayerType
import kotlin.math.max
import kotlin.math.min

/** An audible clip placed on the composition timeline. */
class AudioClip(
    val layerId: String,
    val assetId: String,
    /** Composition seconds where the clip starts and ends (already clipped by enclosing groups). */
    val compStart: Double,
    val compEnd: Double,
    /** Composition time that corresponds to layer-local time 0. */
    val layerOrigin: Double,
    val sourceStart: Double,
    val speed: Double,
    val volume: Animatable<Float>,
    val fadeIn: Double,
    val fadeOut: Double,
    /** Layer end in composition time (fade-out reference). */
    val layerEnd: Double,
) {
    /** Source media time heard at composition time [t]. */
    fun sourceTime(t: Double): Double = sourceStart + (t - layerOrigin) * speed

    /** Linear gain at composition time [t] (volume keyframes × fades), 0 outside the clip. */
    fun gainAt(t: Double): Float {
        if (t < compStart || t >= compEnd) return 0f
        val local = t - layerOrigin
        var g = volume.at(local).coerceAtLeast(0f)
        if (fadeIn > 0.0 && local < fadeIn) g *= (local / fadeIn).toFloat().coerceIn(0f, 1f)
        if (fadeOut > 0.0 && t > layerEnd - fadeOut) g *= ((layerEnd - t) / fadeOut).toFloat().coerceIn(0f, 1f)
        return g
    }
}

/** Collects every clip that contributes to the soundtrack. Hidden layers are silent. */
object AudioPlan {
    fun clips(project: KnsProject): List<AudioClip> {
        val out = ArrayList<AudioClip>()
        fun visit(layers: List<Layer>, offset: Double, windowStart: Double, windowEnd: Double) {
            for (l in layers) {
                if (!l.visible) continue
                val absStart = offset + l.start
                val absEnd = offset + l.end
                val start = max(absStart, windowStart)
                val end = min(absEnd, windowEnd)
                if (end <= start) continue
                when (l.type) {
                    LayerType.GROUP -> visit(l.layers, absStart, start, end)
                    LayerType.AUDIO, LayerType.VIDEO -> {
                        val media = l.media ?: continue
                        if (media.muted) continue
                        if (l.type == LayerType.VIDEO && project.asset(media.asset)?.hasAudio == false) continue
                        out += AudioClip(
                            layerId = l.id,
                            assetId = media.asset,
                            compStart = start,
                            compEnd = end,
                            layerOrigin = absStart,
                            sourceStart = media.sourceStart,
                            speed = media.speed,
                            volume = media.volume,
                            fadeIn = media.fadeIn,
                            fadeOut = media.fadeOut,
                            layerEnd = absEnd,
                        )
                    }
                    else -> Unit
                }
            }
        }
        visit(project.layers, 0.0, 0.0, project.composition.duration)
        return out
    }
}

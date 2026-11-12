package dev.kinesis.core.alight

import dev.kinesis.core.anim.Animatable
import dev.kinesis.core.anim.Easing
import dev.kinesis.core.anim.EasingPreset
import dev.kinesis.core.anim.Keyframe

/**
 * Keyframes and easing of Alight Motion.
 *
 * - `kf t` is a fraction of the layer's duration (and may fall outside 0..1); the file lists them out of order.
 * - The `e` of a keyframe shapes the segment that arrives at it (checked on the fixture: an intro that eases out and an
 *   exit that eases in). Kinesis keeps the easing on the keyframe a segment starts from, so it moves one keyframe back.
 * - No `e` means linear.
 */
internal class EasingStats {
    var elastic = 0
    var cyclic = 0
    var unknown = LinkedHashSet<String>()
}

internal object AlightAnimation {
    /** Kinesis easing for the Alight easing text [e]. */
    fun easing(e: String?, stats: EasingStats): Easing {
        if (e.isNullOrBlank()) return Easing.LINEAR
        val parts = e.trim().split(Regex("\\s+"))
        val nums = parts.drop(1).map { it.toFloatOrNull() }
        return when (parts[0]) {
            "cubicBezier" -> if (nums.size == 4 && nums.none { it == null }) {
                val (a, b, c, d) = nums.map { it!! }
                if (a == 0f && b == 0f && c == 1f && d == 1f) Easing.LINEAR else Easing.CubicBezier(a.coerceIn(0f, 1f), b, c.coerceIn(0f, 1f), d)
            } else bad(e, stats)
            // Every elastic keyframe of the fixture has the same four numbers; the shape of the spring is not documented.
            "elastic" -> { stats.elastic++; Easing.Preset(EasingPreset.EASE_OUT_ELASTIC) }
            // The first number is where the first peak falls inside the segment: that makes 0.5 / peak cycles.
            "cyclic" -> {
                stats.cyclic++
                val peak = nums.firstOrNull()?.takeIf { it > 0f } ?: return bad(e, stats)
                Easing.Cyclic(offset = 0f, cycles = (0.5f / peak).coerceIn(0.5f, 64f))
            }
            else -> bad(e, stats)
        }
    }

    private fun bad(e: String, stats: EasingStats): Easing { stats.unknown += e.substringBefore(' '); return Easing.LINEAR }

    /**
     * Builds the animatable of [p] for a layer lasting [durationSec]. [make] turns the numbers of one value into the
     * Kinesis value. A property without data is [default]. Keyframes closer than a frame fraction are merged.
     */
    fun <T> build(p: AlightProp?, durationSec: Double, default: T, stats: EasingStats, make: (List<Double>) -> T): Animatable<T> {
        if (p == null) return Animatable(default)
        if (p.keyframes.isEmpty()) return Animatable(p.static?.let(make) ?: default)
        val src = p.keyframes.sortedBy { it.t }
        // Merge keyframes that land on the same time (the later one wins).
        val kept = ArrayList<AlightKeyframe>()
        for (k in src) {
            if (kept.isNotEmpty() && (k.t - kept.last().t) * durationSec < Animatable.KEYFRAME_EPSILON * 2) kept[kept.size - 1] = k else kept += k
        }
        val keys = kept.mapIndexed { i, k ->
            // The segment starting here is the one that arrives at the next keyframe.
            val easing = kept.getOrNull(i + 1)?.let { easing(it.easing, stats) } ?: Easing.LINEAR
            Keyframe(k.t * durationSec, make(k.value), easing)
        }
        return Animatable(keys.first().value, keys)
    }
}

/** Applies [f] to the value and to every keyframe value, keeping times and easing. */
internal fun <A, B> Animatable<A>.mapValues(f: (A) -> B): Animatable<B> =
    Animatable(f(value), keyframes.map { Keyframe(it.time, f(it.value), it.easing) })

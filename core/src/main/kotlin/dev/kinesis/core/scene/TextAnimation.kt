package dev.kinesis.core.scene

import dev.kinesis.core.math.clamp
import dev.kinesis.core.math.lerp
import dev.kinesis.core.model.TextAnimator
import dev.kinesis.core.model.TextAnimatorMode
import dev.kinesis.core.model.TextAnimatorType
import dev.kinesis.core.model.TextOrder
import dev.kinesis.core.model.TextUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Per-character offset (pixels), scale, rotation (degrees) and opacity produced by text animators. */
data class GlyphState(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
) {
    val isIdentity get() = dx == 0f && dy == 0f && scale == 1f && rotation == 0f && opacity == 1f

    companion object {
        val IDENTITY = GlyphState()
    }
}

object TextAnimation {

    /**
     * Unit index of every character and the number of units. Whitespace does not form units of its own:
     * it takes the index of the following visible character (it is invisible anyway).
     * @param lineOfChar line number of each character from the text layout; when null lines split on '\n'.
     */
    fun unitIndices(text: String, unit: TextUnit, lineOfChar: IntArray? = null): Pair<IntArray, Int> {
        val idx = IntArray(text.length)
        when (unit) {
            TextUnit.CHARACTER -> {
                var n = 0
                for (i in text.indices) {
                    idx[i] = n
                    if (!text[i].isWhitespace()) n++
                }
                for (i in text.indices) if (text[i].isWhitespace()) idx[i] = idx[i].coerceAtMost((n - 1).coerceAtLeast(0))
                return idx to n
            }
            TextUnit.WORD -> {
                var n = -1
                var inWord = false
                for (i in text.indices) {
                    val ws = text[i].isWhitespace()
                    if (!ws && !inWord) n++
                    inWord = !ws
                    idx[i] = n.coerceAtLeast(0)
                }
                return idx to (n + 1)
            }
            TextUnit.LINE -> {
                if (lineOfChar != null && lineOfChar.size == text.length) {
                    for (i in text.indices) idx[i] = lineOfChar[i]
                    return idx to ((lineOfChar.maxOrNull() ?: -1) + 1)
                }
                var line = 0
                for (i in text.indices) {
                    idx[i] = line
                    if (text[i] == '\n') line++
                }
                return idx to (line + 1)
            }
        }
    }

    /** Position of unit [i] (of [n]) in the animation sequence. */
    fun orderPosition(i: Int, n: Int, order: TextOrder): Int = when (order) {
        TextOrder.FORWARD -> i
        TextOrder.BACKWARD -> n - 1 - i
        TextOrder.CENTER -> {
            // Units closest to the centre go first; symmetric pairs share a position.
            val mid = (n - 1) / 2f
            (abs(i - mid) + 0.001f).toInt()
        }
        TextOrder.RANDOM -> randomPermutation(n)[i]
    }

    /** Deterministic shuffle so random order is stable across frames and devices. */
    private fun randomPermutation(n: Int): IntArray {
        val p = IntArray(n) { it }
        var seed = 0x9E3779B9L xor n.toLong()
        for (i in n - 1 downTo 1) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val j = ((seed ushr 33) % (i + 1)).toInt()
            val tmp = p[i]; p[i] = p[j]; p[j] = tmp
        }
        return p
    }

    /** Glyph states for every character of [text] at layer-local [time]. */
    fun evaluate(text: String, animators: List<TextAnimator>, time: Double, lineOfChar: IntArray? = null): Array<GlyphState> {
        val states = Array(text.length) { GlyphState.IDENTITY }
        for (a in animators) {
            val (unitOf, count) = unitIndices(text, a.unit, lineOfChar)
            if (count == 0) continue
            for (c in text.indices) {
                val k = orderPosition(unitOf[c].coerceIn(0, count - 1), count, a.order)
                states[c] = combine(states[c], stateFor(a, k, time))
            }
        }
        return states
    }

    private fun combine(s: GlyphState, o: GlyphState) = GlyphState(
        dx = s.dx + o.dx, dy = s.dy + o.dy, scale = s.scale * o.scale,
        rotation = s.rotation + o.rotation, opacity = s.opacity * o.opacity,
    )

    /** State of the unit at sequence position [k]. */
    fun stateFor(a: TextAnimator, k: Int, time: Double): GlyphState {
        val unitStart = a.start + k * a.stagger
        if (a.type == TextAnimatorType.WAVE) {
            if (time < a.start) return GlyphState.IDENTITY
            val period = a.unitDuration.coerceAtLeast(0.01)
            val phase = (time - unitStart) / period
            return GlyphState(dy = (-a.amount * sin(2.0 * PI * phase)).toFloat())
        }
        val u = ((time - unitStart) / a.unitDuration.coerceAtLeast(1e-6)).toFloat().clamp(0f, 1f)
        val entering = a.mode == TextAnimatorMode.IN
        if (a.type == TextAnimatorType.TYPEWRITER) {
            val shown = if (entering) time >= unitStart else time < unitStart
            return GlyphState(opacity = if (shown) 1f else 0f)
        }
        val e = a.easing.transform(u)
        val v = if (entering) e else 1f - e
        val away = 1f - v
        val sign = if (entering) 1f else -1f
        return when (a.type) {
            TextAnimatorType.FADE -> GlyphState(opacity = v.clamp(0f, 1f))
            TextAnimatorType.SLIDE_UP -> GlyphState(dy = sign * a.amount * away, opacity = v.clamp(0f, 1f))
            TextAnimatorType.SLIDE_DOWN -> GlyphState(dy = -sign * a.amount * away, opacity = v.clamp(0f, 1f))
            TextAnimatorType.SLIDE_LEFT -> GlyphState(dx = sign * a.amount * away, opacity = v.clamp(0f, 1f))
            TextAnimatorType.SLIDE_RIGHT -> GlyphState(dx = -sign * a.amount * away, opacity = v.clamp(0f, 1f))
            TextAnimatorType.SCALE -> GlyphState(scale = lerp((a.amount / 100f).coerceAtLeast(0f), 1f, v), opacity = v.clamp(0f, 1f))
            TextAnimatorType.ROTATE -> GlyphState(rotation = -sign * a.amount * away, opacity = v.clamp(0f, 1f))
            TextAnimatorType.TYPEWRITER, TextAnimatorType.WAVE -> GlyphState.IDENTITY
        }
    }
}

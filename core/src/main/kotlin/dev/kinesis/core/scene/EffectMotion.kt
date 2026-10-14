package dev.kinesis.core.scene

import dev.kinesis.core.math.Affine2D
import dev.kinesis.core.math.Vec2
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Image transforms of the "Movimentar e transformar" effects. Pure functions of the parameters and the layer-local
 * time, so a frame always renders the same way. Every transform pivots on the layer origin unless the effect has
 * its own `center`.
 */
internal object EffectMotion {

    fun transform(e: ResolvedEffect): Affine2D? {
        val t = e.time
        return when (e.type) {
            "autoShake" -> {
                val x = e.f("frequency") * t
                val seed = e.i("seed")
                val amp = e.f("amplitude")
                val axes = e.choice("axes")
                val dx = if (axes == "y") 0f else amp * noise(seed * 3, x)
                val dy = if (axes == "x") 0f else amp * noise(seed * 3 + 1, x)
                around(e.origin, e.f("rotation") * noise(seed * 3 + 2, x), 1f, Vec2(dx, dy))
            }
            "spin" -> around(e.point("center"), (e.f("speed") * t).toFloat(), 1f, Vec2.ZERO)
            "oscillate" -> {
                val w = 2.0 * PI * e.f("frequency") * t + Math.toRadians(e.f("phase").toDouble())
                val a = e.f("amount")
                around(
                    e.origin,
                    e.f("rotationAmount") * sin(w * 1.27 + 0.6).toFloat(),
                    1f + e.f("scaleAmount") * sin(w * 0.91 + 2.1).toFloat(),
                    Vec2(a * sin(w).toFloat(), a * sin(w * 1.618 + 1.3).toFloat()),
                )
            }
            "float" -> {
                val w = 2.0 * PI * e.f("frequency") * t + Math.toRadians(e.f("phase").toDouble())
                val a = e.f("amplitude")
                around(e.origin, a * 0.05f * sin(w * 0.5 + 0.8).toFloat(), 1f, Vec2(a * 0.3f * sin(w * 0.5).toFloat(), -a * sin(w).toFloat()))
            }
            "pulse" -> {
                val w = 2.0 * PI * e.f("frequency") * t + Math.toRadians(e.f("phase").toDouble())
                around(e.origin, 0f, 1f + e.f("amount") * (0.5f + 0.5f * sin(w).toFloat()), Vec2.ZERO)
            }
            else -> null
        }
    }

    private fun around(pivot: Vec2, rotation: Float, scale: Float, offset: Vec2): Affine2D =
        Affine2D.translate(offset) * Affine2D.translate(pivot) * Affine2D.rotate(rotation) * Affine2D.scale(scale, scale) * Affine2D.translate(-pivot.x, -pivot.y)

    /** Smooth 1D value noise in -1..1, deterministic for a given [seed]. */
    fun noise(seed: Int, x: Double): Float {
        val i = floor(x)
        val f = (x - i).toFloat()
        val a = hash(seed, i.toLong())
        val b = hash(seed, i.toLong() + 1)
        val s = f * f * (3f - 2f * f)
        return a + (b - a) * s
    }

    /** Integer hash mapped to -1..1. */
    fun hash(seed: Int, n: Long): Float {
        var h = (n * 0x27d4eb2dL + seed * 0x165667b1L).toInt()
        h = h xor (h ushr 15)
        h *= 0x2c1b3c6d
        h = h xor (h ushr 12)
        h *= 0x297a2d39
        h = h xor (h ushr 15)
        return (h ushr 8) / 8388607.5f - 1f
    }
}

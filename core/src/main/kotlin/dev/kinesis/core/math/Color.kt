package dev.kinesis.core.math

import dev.kinesis.core.format.ColorSerializer
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Straight (non-premultiplied) sRGB colour with components in 0..1. Serialized as `#RRGGBB` or `#RRGGBBAA`. */
@Serializable(with = ColorSerializer::class)
data class Color(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {

    fun withAlpha(alpha: Float) = copy(a = alpha)

    /** ARGB packed int, the layout used by android.graphics.Color. */
    fun toArgb(): Int {
        fun c(v: Float) = (v.clamp(0f, 1f) * 255f).roundToInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    fun toHex(): String {
        fun c(v: Float) = (v.clamp(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0').uppercase()
        val rgb = "#${c(r)}${c(g)}${c(b)}"
        return if (a >= 0.9999f) rgb else rgb + c(a)
    }

    /** Rec. 709 luma of the straight colour. */
    fun luma(): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    companion object {
        val BLACK = Color(0f, 0f, 0f)
        val WHITE = Color(1f, 1f, 1f)
        val TRANSPARENT = Color(0f, 0f, 0f, 0f)

        fun fromArgb(argb: Int) = Color(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            ((argb ushr 24) and 0xFF) / 255f,
        )

        /** Parses `#RGB`, `#RRGGBB` or `#RRGGBBAA`. Returns null for malformed input. */
        fun parseOrNull(text: String): Color? {
            val s = text.trim()
            if (!s.startsWith("#")) return null
            val hex = s.substring(1)
            if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            fun byte(i: Int) = hex.substring(i, i + 2).toInt(16) / 255f
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToInt(16); val g = hex[1].digitToInt(16); val b = hex[2].digitToInt(16)
                    Color(r * 17 / 255f, g * 17 / 255f, b * 17 / 255f)
                }
                6 -> Color(byte(0), byte(2), byte(4))
                8 -> Color(byte(0), byte(2), byte(4), byte(6))
                else -> null
            }
        }

        fun parse(text: String): Color =
            parseOrNull(text) ?: throw IllegalArgumentException("Invalid colour '$text' (expected #RRGGBB or #RRGGBBAA)")
    }
}

fun lerp(a: Color, b: Color, t: Float) = Color(lerp(a.r, b.r, t), lerp(a.g, b.g, t), lerp(a.b, b.b, t), lerp(a.a, b.a, t))

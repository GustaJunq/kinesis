package dev.kinesis.editor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.kinesis.core.model.PixelEncoderCharsets

/**
 * The glyphs of the Pixel Encoder effect: one horizontal strip texture per character set (one square per luminance level,
 * white on black, light to dark). Built on first use and kept until [release]; nothing is created per frame.
 */
class GlyphAtlas {
    private val textures = HashMap<String, Int>()

    fun levels(charset: String): Int = PixelEncoderCharsets.levels(charset)

    fun texture(charset: String): Int = textures.getOrPut(charset) { build(charset) }

    fun release() {
        textures.values.forEach { Gl.deleteTexture(it) }
        textures.clear()
    }

    private fun build(charset: String): Int {
        val n = levels(charset)
        val g = GLYPH_PX
        val bitmap = Bitmap.createBitmap(n * g, g, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = g * 0.9f
            textAlign = Paint.Align.CENTER
        }
        val ramp = PixelEncoderCharsets.ramp(charset)
        val baseline = g / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        for (i in 0 until n) {
            if (ramp != null) {
                canvas.drawText(ramp[i].toString(), i * g + g / 2f, baseline, paint)
            } else {
                val height = g * i / (n - 1).toFloat()
                canvas.drawRect(i * g.toFloat(), g - height, (i + 1) * g.toFloat(), g.toFloat(), paint)
            }
        }
        val id = uploadBitmap(0, bitmap)
        bitmap.recycle()
        return id
    }

    private companion object {
        const val GLYPH_PX = 32
    }
}

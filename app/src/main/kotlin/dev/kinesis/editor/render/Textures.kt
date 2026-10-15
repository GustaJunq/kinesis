package dev.kinesis.editor.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.media.ExifInterface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import dev.kinesis.core.scene.Content2D
import java.io.File
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Locates asset files on disk. */
fun interface AssetFiles {
    fun file(assetId: String): File?
}

class TexturedRect(val texture: Int, val rect: RectF)

/** Uploads a bitmap into [texture] (allocating it if 0) and returns the texture id. */
internal fun uploadBitmap(texture: Int, bitmap: Bitmap): Int {
    val id = if (texture != 0) texture else Gl.genTexture()
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    Gl.setDefaultParams(GLES30.GL_TEXTURE_2D)
    GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    return id
}

/**
 * Textures of rasterized text and shapes, one slot per layer. A slot is re-rasterized only when what it
 * shows changes (content, style, or a significant change of on-screen scale).
 */
class ContentTextures(private val typefaces: TypefaceProvider) {
    private class Slot(var texture: Int, var key: Any?, var rect: RectF, var lastUsed: Long)

    private val slots = HashMap<String, Slot>()
    private var frame = 0L

    fun beginFrame() {
        frame++
    }

    /** Rounds the raster scale to quarter-octave steps so smooth zooms do not re-rasterize every frame. */
    fun bucket(scale: Float): Float {
        val s = scale.coerceIn(0.05f, 8f)
        val steps = (ln(s) / ln(2f) * 4f).roundToInt()
        return 2f.pow(steps / 4f).coerceAtLeast(0.05f)
    }

    fun text(slotId: String, t: Content2D.Text, scale: Float): TexturedRect? {
        val sc = bucket(scale)
        val key = TextKey(t.content, t.font, t.fontSize, t.color, t.align, t.lineHeight, t.letterSpacing, t.maxWidth, t.strokeColor, t.strokeWidth, t.animators, if (t.animators.isEmpty()) 0.0 else t.localTime, t.glyphStates?.toList(), sc)
        return slot(slotId, key) { TextRasterizer.rasterize(t, sc, typefaces) }
    }

    fun shape(slotId: String, s: Content2D.Shape, scale: Float): TexturedRect? {
        val sc = bucket(scale)
        val key = ShapeKey(
            s.kind, s.size, s.roundness, s.sides, s.innerRatio, s.path, s.fillEnabled, s.fillColor, s.gradient,
            s.strokeEnabled, s.strokeColor, s.strokeWidth, s.cap, s.join, s.trimStart, s.trimEnd, s.trimOffset, sc,
        )
        return slot(slotId, key) { ShapeRasterizer.rasterize(s, sc) }
    }

    private inline fun slot(id: String, key: Any, raster: () -> Raster?): TexturedRect? {
        val existing = slots[id]
        if (existing != null && existing.key == key) {
            existing.lastUsed = frame
            return TexturedRect(existing.texture, existing.rect)
        }
        val r = try {
            raster()
        } catch (e: Exception) {
            Log.w("Kinesis", "rasterization failed for $id", e)
            null
        } ?: return null
        val tex = uploadBitmap(existing?.texture ?: 0, r.bitmap)
        r.bitmap.recycle()
        if (existing != null) {
            existing.key = key
            existing.rect = r.rect
            existing.lastUsed = frame
        } else {
            slots[id] = Slot(tex, key, r.rect, frame)
        }
        return TexturedRect(tex, r.rect)
    }

    /** Frees slots not used for a while (deleted or long-hidden layers). */
    fun trim(maxIdleFrames: Long = 300) {
        val it = slots.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (frame - e.value.lastUsed > maxIdleFrames) {
                Gl.deleteTexture(e.value.texture)
                it.remove()
            }
        }
    }

    fun release() {
        slots.values.forEach { Gl.deleteTexture(it.texture) }
        slots.clear()
    }

    private data class TextKey(
        val content: String, val font: Any, val size: Float, val color: Any, val align: Any, val lineHeight: Float,
        val letterSpacing: Float, val maxWidth: Float, val stroke: Any?, val strokeWidth: Float, val animators: Any,
        val time: Double, val glyphs: Any?, val scale: Float,
    )

    private data class ShapeKey(
        val kind: Any, val size: Any, val roundness: Float, val sides: Int, val inner: Float, val path: Any?, val fill: Boolean,
        val fillColor: Any, val gradient: Any?, val stroke: Boolean, val strokeColor: Any, val strokeWidth: Float,
        val cap: Any, val join: Any, val t0: Float, val t1: Float, val t2: Float, val scale: Float,
    )
}

/** Decoded image assets (EXIF orientation applied, downsampled to the GPU limit). */
class ImageTextures(private val files: AssetFiles, private val maxSize: Int) {
    class Image(val texture: Int, val width: Int, val height: Int)

    private val images = HashMap<String, Image?>()

    fun get(assetId: String): Image? = images.getOrPut(assetId) { load(assetId) }

    private fun load(assetId: String): Image? {
        val file = files.file(assetId)?.takeIf { it.isFile } ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
            val rotation = exifRotation(file)
            if (rotation != 0) {
                val m = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated !== bmp) bmp.recycle()
                bmp = rotated
            }
            val tex = uploadBitmap(0, bmp)
            // Logical size is the full-resolution size, so downsampling never changes layout.
            val (w, h) = if (rotation == 90 || rotation == 270) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
            bmp.recycle()
            Image(tex, w, h)
        } catch (e: Exception) {
            Log.w("Kinesis", "cannot load image $assetId", e)
            null
        }
    }

    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    /** Forgets an asset (e.g. after it was replaced). */
    fun evict(assetId: String) {
        images.remove(assetId)?.let { Gl.deleteTexture(it.texture) }
    }

    fun release() {
        images.values.forEach { it?.let { img -> Gl.deleteTexture(img.texture) } }
        images.clear()
    }
}

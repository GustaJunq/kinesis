package dev.kinesis.editor.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.kinesis.core.model.FontSpec
import dev.kinesis.core.model.GradientType
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeCap
import dev.kinesis.core.model.StrokeJoin
import dev.kinesis.core.model.TextAlign
import dev.kinesis.core.model.TextAnimatorType
import dev.kinesis.core.scene.Content2D
import dev.kinesis.core.scene.GlyphState
import dev.kinesis.core.scene.TextAnimation
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rasterized piece of content: the bitmap covers [rect] (layer-local pixels, unscaled). */
class Raster(val bitmap: Bitmap, val rect: RectF)

/** Resolves typefaces for system families and embedded font assets. */
class TypefaceProvider(private val fontFile: (String) -> File?) {
    private val cache = HashMap<FontSpec, Typeface>()

    fun get(font: FontSpec): Typeface = cache.getOrPut(font) {
        val base = font.asset?.let { id ->
            fontFile(id)?.takeIf { it.isFile }?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        } ?: Typeface.create(font.family, Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, font.weight.coerceIn(1, 1000), font.italic)
        } else {
            val style = when {
                font.weight >= 600 && font.italic -> Typeface.BOLD_ITALIC
                font.weight >= 600 -> Typeface.BOLD
                font.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(base, style)
        }
    }
}

object TextRasterizer {
    private const val MAX_DIMENSION = 4096

    /**
     * Lays out and draws [t] at [scale] output pixels per layer pixel. Text animators are applied per glyph,
     * so animated text is re-rasterized as it changes.
     */
    fun rasterize(t: Content2D.Text, scale: Float, typefaces: TypefaceProvider): Raster {
        val text = t.content.ifEmpty { " " }
        var s = scale
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.typeface = typefaces.get(t.font)
        paint.letterSpacing = t.letterSpacing

        fun layoutFor(sc: Float): StaticLayout {
            paint.textSize = t.fontSize * sc
            val width = if (t.maxWidth > 0f) ceil(t.maxWidth * sc).toInt() else ceil(Layout.getDesiredWidth(text, paint)).toInt()
            val align = when (t.align) {
                TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
                TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
                TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
                .setAlignment(align)
                .setLineSpacing(0f, t.lineHeight / 1.17f)
                .setIncludePad(false)
                .build()
        }

        var layout = layoutFor(s)
        val animPad = animatorPadding(t)
        // Keep the bitmap within texture limits.
        val needed = max(layout.width, layout.height) + 2 * (animPad + t.strokeWidth) * s
        if (needed > MAX_DIMENSION) {
            s *= MAX_DIMENSION / needed
            layout = layoutFor(s)
        }
        val pad = ceil((t.strokeWidth + animPad) * s + 2f).toInt()
        val w = (layout.width + 2 * pad).coerceIn(1, MAX_DIMENSION)
        val h = (layout.height + 2 * pad).coerceIn(1, MAX_DIMENSION)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(pad.toFloat(), pad.toFloat())

        val fill = t.color.toArgb()
        val stroke = t.strokeColor?.takeIf { t.strokeWidth > 0f }
        if (t.animators.isEmpty() && t.glyphStates == null) {
            if (stroke != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = t.strokeWidth * s
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = stroke.toArgb()
                layout.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = fill
            layout.draw(canvas)
        } else {
            val lines = IntArray(text.length) { layout.getLineForOffset(it) }
            val presets = if (t.animators.isEmpty()) null else TextAnimation.evaluate(text, t.animators, t.localTime, lines)
            val expr = t.glyphStates
            var i = 0
            while (i < text.length) {
                val cp = Character.codePointAt(text, i)
                val n = Character.charCount(cp)
                if (!Character.isWhitespace(cp)) {
                    val st = combine(presets?.getOrNull(i), expr?.getOrNull(i))
                    if (st.opacity > 0.002f) {
                        val line = lines[i]
                        val x = layout.getPrimaryHorizontal(i)
                        val baseline = layout.getLineBaseline(line).toFloat()
                        val cw = paint.measureText(text, i, i + n)
                        val cx = x + cw / 2f
                        val cy = baseline - paint.textSize * 0.35f
                        canvas.save()
                        canvas.translate(st.dx * s, st.dy * s)
                        if (st.rotation != 0f) canvas.rotate(st.rotation, cx, cy)
                        if (st.scale != 1f) canvas.scale(st.scale, st.scale, cx, cy)
                        val alpha = (st.opacity.coerceIn(0f, 1f) * 255f).toInt()
                        if (stroke != null) {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = t.strokeWidth * s
                            paint.strokeJoin = Paint.Join.ROUND
                            paint.color = stroke.toArgb()
                            paint.alpha = paint.alpha * alpha / 255
                            canvas.drawText(text, i, i + n, x, baseline, paint)
                        }
                        paint.style = Paint.Style.FILL
                        paint.color = fill
                        paint.alpha = paint.alpha * alpha / 255
                        canvas.drawText(text, i, i + n, x, baseline, paint)
                        canvas.restore()
                    }
                }
                i += n
            }
        }
        val lw = w / s
        val lh = h / s
        return Raster(bitmap, RectF(-lw / 2f, -lh / 2f, lw / 2f, lh / 2f))
    }

    /** Size of the laid-out text block in layer pixels, centred on the origin (for selection outlines). */
    fun measure(t: Content2D.Text, typefaces: TypefaceProvider): RectF {
        val text = t.content.ifEmpty { " " }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typefaces.get(t.font)
        paint.letterSpacing = t.letterSpacing
        paint.textSize = t.fontSize
        val width = if (t.maxWidth > 0f) ceil(t.maxWidth).toInt() else ceil(Layout.getDesiredWidth(text, paint)).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setLineSpacing(0f, t.lineHeight / 1.17f)
            .setIncludePad(false)
            .build()
        val w = layout.width + t.strokeWidth
        val h = layout.height + t.strokeWidth
        return RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
    }

    private fun combine(a: GlyphState?, b: GlyphState?): GlyphState = when {
        a == null -> b ?: GlyphState.IDENTITY
        b == null -> a
        else -> GlyphState(a.dx + b.dx, a.dy + b.dy, a.scale * b.scale, a.rotation + b.rotation, a.opacity * b.opacity)
    }

    /** Extra room (layer pixels) so animated glyphs are not clipped. */
    private fun animatorPadding(t: Content2D.Text): Float {
        var pad = 0f
        t.glyphStates?.let { g ->
            var shift = 0f; var grow = 1f; var spin = false
            for (s in g) {
                shift = max(shift, max(abs(s.dx), abs(s.dy)))
                grow = max(grow, abs(s.scale))
                if (s.rotation != 0f) spin = true
            }
            pad += shift + t.fontSize * (grow - 1f) * 0.6f + (if (spin) t.fontSize * 0.5f else 0f)
        }
        for (a in t.animators) {
            pad += when (a.type) {
                TextAnimatorType.SLIDE_UP, TextAnimatorType.SLIDE_DOWN, TextAnimatorType.SLIDE_LEFT,
                TextAnimatorType.SLIDE_RIGHT, TextAnimatorType.WAVE -> abs(a.amount)
                TextAnimatorType.SCALE -> t.fontSize * max(0f, a.amount / 100f - 1f) * 0.6f
                TextAnimatorType.ROTATE -> t.fontSize * 0.5f
                else -> 0f
            }
        }
        return min(pad, 2000f)
    }
}

object ShapeRasterizer {
    private const val MAX_DIMENSION = 4096

    /** Outline of the shape in layer-local pixels, centred on the origin. */
    fun path(s: Content2D.Shape): Path {
        val p = Path()
        val hw = s.size.x / 2f
        val hh = s.size.y / 2f
        when (s.kind) {
            ShapeKind.RECTANGLE -> {
                val r = min(s.roundness, min(hw, hh))
                p.addRoundRect(RectF(-hw, -hh, hw, hh), r, r, Path.Direction.CW)
            }
            ShapeKind.ELLIPSE -> p.addOval(RectF(-hw, -hh, hw, hh), Path.Direction.CW)
            ShapeKind.POLYGON, ShapeKind.STAR -> {
                val star = s.kind == ShapeKind.STAR
                val count = if (star) s.sides * 2 else s.sides
                for (i in 0 until count) {
                    val a = -PI / 2 + 2 * PI * i / count
                    val k = if (star && i % 2 == 1) s.innerRatio else 1f
                    val x = (cos(a) * hw * k).toFloat()
                    val y = (sin(a) * hh * k).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            ShapeKind.PATH -> {
                val pts = s.path?.points.orEmpty()
                if (pts.isNotEmpty()) {
                    p.moveTo(pts[0].point.x, pts[0].point.y)
                    val segments = if (s.path?.closed == true) pts.size else pts.size - 1
                    for (i in 0 until segments) {
                        val a = pts[i]
                        val b = pts[(i + 1) % pts.size]
                        p.cubicTo(
                            a.point.x + a.outTangent.x, a.point.y + a.outTangent.y,
                            b.point.x + b.inTangent.x, b.point.y + b.inTangent.y,
                            b.point.x, b.point.y,
                        )
                    }
                    if (s.path?.closed == true) p.close()
                }
            }
        }
        return p
    }

    /** Applies trim paths over the total length of all contours. */
    fun trimmed(path: Path, start: Float, end: Float, offset: Float): Path {
        val lengths = ArrayList<Float>()
        val pm = PathMeasure(path, false)
        do { lengths += pm.length } while (pm.nextContour())
        val total = lengths.sum()
        if (total <= 0f) return path
        var a = min(start, end)
        var b = max(start, end)
        if (b - a >= 0.9999f) return path
        val o = offset - floor(offset)
        a += o
        b += o
        val out = Path()
        val ranges = if (a >= 1f) listOf((a - 1f) to (b - 1f))
        else if (b > 1f) listOf(a to 1f, 0f to (b - 1f))
        else listOf(a to b)
        for ((ra, rb) in ranges) {
            var from = ra * total
            val to = rb * total
            val m = PathMeasure(path, false)
            var acc = 0f
            var idx = 0
            do {
                val len = lengths.getOrElse(idx) { 0f }
                val cs = max(from, acc)
                val ce = min(to, acc + len)
                if (ce > cs) m.getSegment(cs - acc, ce - acc, out, true)
                acc += len
                idx++
            } while (m.nextContour() && acc < to)
            from = to
        }
        return out
    }

    fun rasterize(s: Content2D.Shape, scale: Float): Raster? {
        if (!s.fillEnabled && !(s.strokeEnabled && s.strokeWidth > 0f)) return null
        var path = path(s)
        if (s.isTrimmed) path = trimmed(path, s.trimStart, s.trimEnd, s.trimOffset)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.isEmpty && bounds.width() <= 0f && bounds.height() <= 0f) {
            if (!(s.strokeEnabled && s.strokeWidth > 0f)) return null
        }
        val strokePad = if (s.strokeEnabled) s.strokeWidth else 0f
        bounds.inset(-strokePad - 1f, -strokePad - 1f)
        var sc = scale
        val needed = max(bounds.width(), bounds.height()) * sc
        if (needed > MAX_DIMENSION) sc *= MAX_DIMENSION / needed
        val w = ceil(bounds.width() * sc).toInt().coerceIn(1, MAX_DIMENSION)
        val h = ceil(bounds.height() * sc).toInt().coerceIn(1, MAX_DIMENSION)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(sc, sc)
        canvas.translate(-bounds.left, -bounds.top)

        val corner = if (s.roundness > 0f && (s.kind == ShapeKind.POLYGON || s.kind == ShapeKind.STAR)) CornerPathEffect(s.roundness) else null
        if (s.fillEnabled) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.pathEffect = corner
            val g = s.gradient
            if (g != null && g.stops.size >= 2) {
                val colors = g.stops.map { it.color.toArgb() }.toIntArray()
                val positions = g.stops.map { it.offset.coerceIn(0f, 1f) }.toFloatArray()
                paint.shader = when (g.type) {
                    GradientType.LINEAR -> LinearGradient(g.start.x, g.start.y, g.end.x, g.end.y, colors, positions, Shader.TileMode.CLAMP)
                    GradientType.RADIAL -> {
                        val r = max(1f, (g.end - g.start).length())
                        RadialGradient(g.start.x, g.start.y, r, colors, positions, Shader.TileMode.CLAMP)
                    }
                }
                // Gradient stops carry their own alpha; the fill colour's alpha still applies.
                paint.alpha = (s.fillColor.a.coerceIn(0f, 1f) * 255f).toInt()
            } else {
                paint.color = s.fillColor.toArgb()
            }
            canvas.drawPath(path, paint)
        }
        if (s.strokeEnabled && s.strokeWidth > 0f) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = s.strokeWidth
            paint.color = s.strokeColor.toArgb()
            paint.pathEffect = corner
            paint.strokeCap = when (s.cap) {
                StrokeCap.BUTT -> Paint.Cap.BUTT
                StrokeCap.ROUND -> Paint.Cap.ROUND
                StrokeCap.SQUARE -> Paint.Cap.SQUARE
            }
            paint.strokeJoin = when (s.join) {
                StrokeJoin.MITER -> Paint.Join.MITER
                StrokeJoin.ROUND -> Paint.Join.ROUND
                StrokeJoin.BEVEL -> Paint.Join.BEVEL
            }
            canvas.drawPath(path, paint)
        }
        val lw = w / sc
        val lh = h / sc
        return Raster(bitmap, RectF(bounds.left, bounds.top, bounds.left + lw, bounds.top + lh))
    }
}

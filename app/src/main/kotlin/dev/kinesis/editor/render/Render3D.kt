package dev.kinesis.editor.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLES30
import android.util.Log
import dev.kinesis.core.geom.Mesh
import dev.kinesis.core.geom.TextExtrusion
import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Text3D
import dev.kinesis.core.model.TextAlign
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** Depth-only framebuffer sampled with `sampler2DShadow` (hardware depth comparison). */
class ShadowMap(val size: Int) {
    val texture: Int = Gl.genTexture()
    val framebuffer: Int

    init {
        // Created lazily in the middle of a frame: keep the caller's framebuffer and texture bindings.
        val previous = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, previous, 0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, previous, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_DEPTH_COMPONENT24, size, size, 0, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, texture, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previous[0])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, previous[1])
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            release()
            throw GlException("Shadow framebuffer incomplete: 0x${Integer.toHexString(status)}")
        }
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, size, size)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        Gl.deleteTexture(texture)
    }
}

/** Equirectangular panoramas used as 3D environments, uploaded with mipmaps (rough reflections read lower levels). */
class EnvironmentMaps(private val files: AssetFiles, private val maxSize: Int) {
    class Map(val texture: Int, val maxLod: Float)

    private val maps = HashMap<String, Map?>()

    fun get(assetId: String): Map? = maps.getOrPut(assetId) { load(assetId) }

    private fun load(assetId: String): Map? {
        val file = files.file(assetId)?.takeIf { it.isFile } ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return null
            val tex = uploadBitmap(0, bmp)
            val levels = floor(ln(max(bmp.width, bmp.height).toDouble()) / ln(2.0)).toFloat()
            bmp.recycle()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            Map(tex, levels)
        } catch (e: Exception) {
            Log.w("Kinesis", "cannot load environment $assetId", e)
            null
        }
    }

    fun release() {
        maps.values.forEach { it?.let { m -> Gl.deleteTexture(m.texture) } }
        maps.clear()
    }
}

/** Turns [Text3D] into glyph contours (Android text layout) and extrudes them with [TextExtrusion]. */
object TextMeshBuilder {
    /** Lines laid out like 2D text: centred on the origin, +y down. */
    fun build(t: Text3D, typefaces: TypefaceProvider): Mesh {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = typefaces.get(t.font)
        paint.textSize = t.fontSize
        val lines = t.content.ifEmpty { " " }.split('\n')
        val lineStep = t.fontSize * t.lineHeight
        val widths = lines.map { paint.measureText(it) }
        val maxWidth = widths.maxOrNull() ?: 0f
        val path = Path()
        val fm = paint.fontMetrics
        lines.forEachIndexed { i, line ->
            val x = when (t.align) {
                TextAlign.LEFT -> 0f
                TextAlign.CENTER -> (maxWidth - widths[i]) / 2f
                TextAlign.RIGHT -> maxWidth - widths[i]
            }
            val linePath = Path()
            paint.getTextPath(line, 0, line.length, x, i * lineStep - fm.ascent, linePath)
            path.addPath(linePath)
        }
        // Variable fonts draw many glyphs as overlapping strokes (non-zero fill): merge them first, or the
        // overlaps would read as holes in the extrusion.
        val merged = Path()
        val outline = if (merged.op(path, Path(), Path.Op.UNION)) merged else path
        val contours = contoursOf(outline, tolerance = max(0.2f, t.fontSize / 500f))
        if (contours.isEmpty()) return TextExtrusion.extrude(emptyList(), t.depth, t.bevel)
        val all = contours.flatten()
        val cx = (all.minOf { it.x } + all.maxOf { it.x }) / 2f
        val cy = (all.minOf { it.y } + all.maxOf { it.y }) / 2f
        val centred = contours.map { c -> c.map { Vec2(it.x - cx, it.y - cy) } }
        return TextExtrusion.extrude(centred, t.depth, t.bevel)
    }

    /** Splits [Path.approximate] output into closed contours (equal fractions mark a jump to a new contour). */
    private fun contoursOf(path: Path, tolerance: Float): List<List<Vec2>> {
        val pts = path.approximate(tolerance)
        val out = ArrayList<List<Vec2>>()
        var cur = ArrayList<Vec2>()
        var i = 0
        var lastFraction = -1f
        while (i + 2 < pts.size) {
            val f = pts[i]; val x = pts[i + 1]; val y = pts[i + 2]
            if (cur.isNotEmpty() && abs(f - lastFraction) < 1e-7f) {
                if (cur.size >= 3) out += cur
                cur = ArrayList()
            }
            cur += Vec2(x, y)
            lastFraction = f
            i += 3
        }
        if (cur.size >= 3) out += cur
        return out
    }
}

/** De-indexed copy of a mesh with barycentric coordinates for anti-aliased wireframes; flat diagonals are hidden. */
object WireMeshBuilder {
    fun build(mesh: Mesh): Pair<Mesh, FloatArray> {
        val tris = mesh.indices.size / 3
        val p = FloatArray(tris * 9); val n = FloatArray(tris * 9); val uv = FloatArray(tris * 6)
        val bary = FloatArray(tris * 9)
        val idx = IntArray(tris * 3) { it }
        fun pos(i: Int) = Vec3(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2])
        fun faceNormal(t: Int): Vec3 {
            val a = pos(mesh.indices[t * 3]); val b = pos(mesh.indices[t * 3 + 1]); val c = pos(mesh.indices[t * 3 + 2])
            return (b - a).cross(c - a).normalized()
        }
        // Edge -> triangles, keyed by quantized positions so duplicated seam vertices still match.
        fun key(i: Int): Triple<Int, Int, Int> = pos(i).let { Triple(Math.round(it.x * 1e4f), Math.round(it.y * 1e4f), Math.round(it.z * 1e4f)) }
        val edges = HashMap<Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>, MutableList<Int>>()
        fun edgeKey(a: Int, b: Int): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>> {
            val ka = key(a); val kb = key(b)
            return if (ka.toString() < kb.toString()) ka to kb else kb to ka
        }
        for (t in 0 until tris) for (e in 0 until 3) {
            edges.getOrPut(edgeKey(mesh.indices[t * 3 + e], mesh.indices[t * 3 + (e + 1) % 3])) { ArrayList() } += t
        }
        val normals = Array(tris) { faceNormal(it) }
        for (t in 0 until tris) {
            // Component k is the distance to the edge opposite vertex k; a hidden edge adds 1 to that component.
            val hidden = BooleanArray(3)
            for (k in 0 until 3) {
                val a = mesh.indices[t * 3 + (k + 1) % 3]; val b = mesh.indices[t * 3 + (k + 2) % 3]
                val shared = edges[edgeKey(a, b)].orEmpty()
                hidden[k] = shared.size == 2 && shared.all { normals[it].dot(normals[t]) > 0.9999f }
            }
            for (k in 0 until 3) {
                val src = mesh.indices[t * 3 + k]
                val dst = t * 3 + k
                for (c in 0 until 3) {
                    p[dst * 3 + c] = mesh.positions[src * 3 + c]
                    n[dst * 3 + c] = mesh.normals[src * 3 + c]
                    bary[dst * 3 + c] = (if (c == k) 1f else 0f) + (if (hidden[c]) 1f else 0f)
                }
                if (mesh.uvs.size >= (src + 1) * 2) { uv[dst * 2] = mesh.uvs[src * 2]; uv[dst * 2 + 1] = mesh.uvs[src * 2 + 1] }
            }
        }
        return Mesh(p, n, uv, idx) to bary
    }
}

/** Light-space camera that covers a bounding sphere, for one shadow map. */
object ShadowCamera {
    /** [center]/[radius]: what must be covered. Directional lights use an orthographic view along [direction]. */
    fun directional(center: Vec3, radius: Float, direction: Vec3): Mat4 {
        val dir = direction.normalized()
        val eye = center - dir * (radius * 2f)
        val up = if (abs(dir.y) > 0.95f) Vec3(0f, 0f, 1f) else Vec3(0f, -1f, 0f)
        val view = Mat4.lookAt(eye, center, up)
        return ortho(-radius, radius, -radius, radius, radius * 0.5f, radius * 3.5f) * view
    }

    fun point(center: Vec3, radius: Float, position: Vec3): Mat4 {
        val dist = (center - position).length().coerceAtLeast(1f)
        val dir = (center - position).normalized()
        val up = if (abs(dir.y) > 0.95f) Vec3(0f, 0f, 1f) else Vec3(0f, -1f, 0f)
        val view = Mat4.lookAt(position, center, up)
        val half = Math.toDegrees(kotlin.math.asin(min(0.999f, radius / dist).toDouble())).toFloat()
        val near = max(1f, dist - radius * 1.5f)
        return Mat4.perspective((half * 2f).coerceIn(10f, 170f), 1f, near, dist + radius * 1.5f) * view
    }

    fun ortho(l: Float, r: Float, b: Float, t: Float, n: Float, f: Float) = Mat4(
        floatArrayOf(
            2f / (r - l), 0f, 0f, 0f,
            0f, 2f / (t - b), 0f, 0f,
            0f, 0f, -2f / (f - n), 0f,
            -(r + l) / (r - l), -(t + b) / (t - b), -(f + n) / (f - n), 1f,
        ),
    )
}

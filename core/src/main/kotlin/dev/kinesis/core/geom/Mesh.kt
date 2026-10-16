package dev.kinesis.core.geom

import dev.kinesis.core.math.Mat4
import dev.kinesis.core.math.Vec3
import dev.kinesis.core.model.Primitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Indexed triangle mesh with interleavable attribute arrays. */
class Mesh(
    /** x, y, z per vertex. */
    val positions: FloatArray,
    /** Unit normals, x, y, z per vertex. */
    val normals: FloatArray,
    /** u, v per vertex. */
    val uvs: FloatArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    fun bounds(): Pair<Vec3, Vec3> {
        if (positions.isEmpty()) return Vec3.ZERO to Vec3.ZERO
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
        for (i in positions.indices step 3) {
            val x = positions[i]; val y = positions[i + 1]; val z = positions[i + 2]
            if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
            if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
        }
        return Vec3(minX, minY, minZ) to Vec3(maxX, maxY, maxZ)
    }

    /** Returns a copy with positions transformed by [m] and normals by its normal matrix. */
    fun transformed(m: Mat4): Mesh {
        val p = FloatArray(positions.size)
        val n = FloatArray(normals.size)
        val nm = m.normalMatrix()
        for (i in positions.indices step 3) {
            val v = m.transformPoint(Vec3(positions[i], positions[i + 1], positions[i + 2]))
            p[i] = v.x; p[i + 1] = v.y; p[i + 2] = v.z
            val nx = normals[i]; val ny = normals[i + 1]; val nz = normals[i + 2]
            var tx = nm[0] * nx + nm[3] * ny + nm[6] * nz
            var ty = nm[1] * nx + nm[4] * ny + nm[7] * nz
            var tz = nm[2] * nx + nm[5] * ny + nm[8] * nz
            val l = sqrt(tx * tx + ty * ty + tz * tz)
            if (l > 1e-9f) { tx /= l; ty /= l; tz /= l }
            n[i] = tx; n[i + 1] = ty; n[i + 2] = tz
        }
        // A mirroring transform flips the winding order.
        val flip = determinant3(m) < 0f
        val idx = if (!flip) indices else IntArray(indices.size).also {
            for (t in indices.indices step 3) { it[t] = indices[t]; it[t + 1] = indices[t + 2]; it[t + 2] = indices[t + 1] }
        }
        return Mesh(p, n, uvs, idx)
    }

    companion object {
        private fun determinant3(m: Mat4): Float =
            m[0, 0] * (m[1, 1] * m[2, 2] - m[2, 1] * m[1, 2]) -
                m[0, 1] * (m[1, 0] * m[2, 2] - m[1, 2] * m[2, 0]) +
                m[0, 2] * (m[1, 0] * m[2, 1] - m[1, 1] * m[2, 0])

        /** Smooth per-vertex normals from face normals (area weighted). */
        fun computeNormals(positions: FloatArray, indices: IntArray): FloatArray {
            val n = FloatArray(positions.size)
            for (t in indices.indices step 3) {
                val a = indices[t] * 3; val b = indices[t + 1] * 3; val c = indices[t + 2] * 3
                val ux = positions[b] - positions[a]; val uy = positions[b + 1] - positions[a + 1]; val uz = positions[b + 2] - positions[a + 2]
                val vx = positions[c] - positions[a]; val vy = positions[c + 1] - positions[a + 1]; val vz = positions[c + 2] - positions[a + 2]
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                for (v in intArrayOf(a, b, c)) { n[v] += nx; n[v + 1] += ny; n[v + 2] += nz }
            }
            for (i in n.indices step 3) {
                val l = sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2])
                if (l > 1e-12f) { n[i] /= l; n[i + 1] /= l; n[i + 2] /= l } else { n[i + 1] = -1f }
            }
            return n
        }
    }
}

/**
 * Procedural primitives, normalized to fit a unit cube centred on the origin (Kinesis axes: +y down,
 * +z away from the viewer, so "up" is -y and the side facing the default camera is -z).
 */
object Primitives {

    fun mesh(primitive: Primitive): Mesh = when (primitive) {
        Primitive.CUBE -> cube()
        Primitive.SPHERE -> sphere()
        Primitive.CYLINDER -> cylinder()
        Primitive.CONE -> cone()
        Primitive.TORUS -> torus()
        Primitive.PLANE -> plane()
        // Text needs glyph outlines: the app builds it with TextExtrusion.
        Primitive.EMPTY, Primitive.TEXT -> Mesh(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0))
    }

    private class Builder {
        val p = ArrayList<Float>(); val n = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        val count get() = p.size / 3
        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Int {
            p += x; p += y; p += z; n += nx; n += ny; n += nz; uv += u; uv += v
            return count - 1
        }

        /** Adds a triangle whose front face is the side its normals point to. */
        fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
        fun build() = Mesh(p.toFloatArray(), n.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }

    fun cube(): Mesh {
        val b = Builder()
        // normal, u axis, v axis (v grows downwards on screen)
        val faces = listOf(
            Triple(Vec3(0f, 0f, -1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)), // front (towards viewer)
            Triple(Vec3(0f, 0f, 1f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f)), // back
            Triple(Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)), // right
            Triple(Vec3(-1f, 0f, 0f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)), // left
            Triple(Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)), // top
            Triple(Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)), // bottom
        )
        for ((normal, u, v) in faces) {
            val c = normal * 0.5f
            val i0 = corner(b, c, u, v, normal, -1f, -1f)
            val i1 = corner(b, c, u, v, normal, 1f, -1f)
            val i2 = corner(b, c, u, v, normal, 1f, 1f)
            val i3 = corner(b, c, u, v, normal, -1f, 1f)
            addQuad(b, i0, i1, i2, i3, normal)
        }
        return b.build()
    }

    private fun corner(b: Builder, c: Vec3, u: Vec3, v: Vec3, n: Vec3, su: Float, sv: Float): Int {
        val p = c + u * (0.5f * su) + v * (0.5f * sv)
        return b.vertex(p.x, p.y, p.z, n.x, n.y, n.z, (su + 1f) / 2f, (sv + 1f) / 2f)
    }

    /** Adds two triangles, choosing the winding that faces [normal]. */
    private fun addQuad(b: Builder, i0: Int, i1: Int, i2: Int, i3: Int, normal: Vec3) {
        val p = b.p
        fun v(i: Int) = Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
        val faceN = (v(i1) - v(i0)).cross(v(i2) - v(i0))
        if (faceN.dot(normal) >= 0f) {
            b.tri(i0, i1, i2); b.tri(i0, i2, i3)
        } else {
            b.tri(i0, i2, i1); b.tri(i0, i3, i2)
        }
    }

    fun plane(): Mesh {
        val b = Builder()
        val n = Vec3(0f, 0f, -1f)
        val i0 = b.vertex(-0.5f, -0.5f, 0f, 0f, 0f, -1f, 0f, 0f)
        val i1 = b.vertex(0.5f, -0.5f, 0f, 0f, 0f, -1f, 1f, 0f)
        val i2 = b.vertex(0.5f, 0.5f, 0f, 0f, 0f, -1f, 1f, 1f)
        val i3 = b.vertex(-0.5f, 0.5f, 0f, 0f, 0f, -1f, 0f, 1f)
        addQuad(b, i0, i1, i2, i3, n)
        return b.build()
    }

    fun sphere(rings: Int = 32, segments: Int = 48): Mesh {
        val b = Builder()
        for (r in 0..rings) {
            val phi = PI * r / rings // 0 at top (-y)
            for (s in 0..segments) {
                val theta = 2.0 * PI * s / segments
                val nx = (sin(phi) * cos(theta)).toFloat()
                val ny = (-cos(phi)).toFloat()
                val nz = (sin(phi) * sin(theta)).toFloat()
                b.vertex(nx * 0.5f, ny * 0.5f, nz * 0.5f, nx, ny, nz, s.toFloat() / segments, r.toFloat() / rings)
            }
        }
        val row = segments + 1
        for (r in 0 until rings) for (s in 0 until segments) {
            val a = r * row + s; val c = a + row
            addTriFacingOut(b, a, c, a + 1)
            addTriFacingOut(b, a + 1, c, c + 1)
        }
        return b.build()
    }

    /** Adds a triangle oriented so that its face normal agrees with its vertex normals. */
    private fun addTriFacingOut(b: Builder, i0: Int, i1: Int, i2: Int) {
        val p = b.p; val n = b.n
        fun v(i: Int) = Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
        fun nn(i: Int) = Vec3(n[i * 3], n[i * 3 + 1], n[i * 3 + 2])
        val face = (v(i1) - v(i0)).cross(v(i2) - v(i0))
        if (face.length() < 1e-12f) return // degenerate (poles)
        if (face.dot(nn(i0) + nn(i1) + nn(i2)) >= 0f) b.tri(i0, i1, i2) else b.tri(i0, i2, i1)
    }

    fun cylinder(segments: Int = 48): Mesh = lathe(segments, topRadius = 0.5f, bottomRadius = 0.5f)

    fun cone(segments: Int = 48): Mesh = lathe(segments, topRadius = 0f, bottomRadius = 0.5f)

    /** Side surface between y = -0.5 (top) and y = 0.5 (bottom) plus caps. */
    private fun lathe(segments: Int, topRadius: Float, bottomRadius: Float): Mesh {
        val b = Builder()
        val slope = bottomRadius - topRadius // radius change over height 1
        val ny = -slope / sqrt(1f + slope * slope)
        val nr = 1f / sqrt(1f + slope * slope)
        for (s in 0..segments) {
            val theta = 2.0 * PI * s / segments
            val cx = cos(theta).toFloat(); val cz = sin(theta).toFloat()
            b.vertex(cx * topRadius, -0.5f, cz * topRadius, cx * nr, ny, cz * nr, s.toFloat() / segments, 0f)
            b.vertex(cx * bottomRadius, 0.5f, cz * bottomRadius, cx * nr, ny, cz * nr, s.toFloat() / segments, 1f)
        }
        for (s in 0 until segments) {
            val t0 = s * 2; val b0 = t0 + 1; val t1 = t0 + 2; val b1 = t0 + 3
            if (topRadius > 0f) addTriFacingOut(b, t0, b0, t1)
            addTriFacingOut(b, t1, b0, b1)
        }
        if (topRadius > 0f) cap(b, segments, -0.5f, topRadius, -1f)
        if (bottomRadius > 0f) cap(b, segments, 0.5f, bottomRadius, 1f)
        return b.build()
    }

    private fun cap(b: Builder, segments: Int, y: Float, radius: Float, ny: Float) {
        val center = b.vertex(0f, y, 0f, 0f, ny, 0f, 0.5f, 0.5f)
        val first = b.count
        for (s in 0..segments) {
            val theta = 2.0 * PI * s / segments
            val cx = cos(theta).toFloat(); val cz = sin(theta).toFloat()
            b.vertex(cx * radius, y, cz * radius, 0f, ny, 0f, 0.5f + cx * 0.5f, 0.5f + cz * 0.5f)
        }
        for (s in 0 until segments) addTriFacingOut(b, center, first + s, first + s + 1)
    }

    /** Ring facing the camera (in the XY plane): outer radius 0.5. */
    fun torus(majorSegments: Int = 64, minorSegments: Int = 24, major: Float = 0.35f, minor: Float = 0.15f): Mesh {
        val b = Builder()
        for (i in 0..majorSegments) {
            val u = 2.0 * PI * i / majorSegments
            val cu = cos(u).toFloat(); val su = sin(u).toFloat()
            for (j in 0..minorSegments) {
                val v = 2.0 * PI * j / minorSegments
                val cv = cos(v).toFloat(); val sv = sin(v).toFloat()
                val nx = cu * cv; val ny = su * cv; val nz = sv
                b.vertex((major + minor * cv) * cu, (major + minor * cv) * su, minor * sv, nx, ny, nz, i.toFloat() / majorSegments, j.toFloat() / minorSegments)
            }
        }
        val row = minorSegments + 1
        for (i in 0 until majorSegments) for (j in 0 until minorSegments) {
            val a = i * row + j; val c = a + row
            addTriFacingOut(b, a, c, a + 1)
            addTriFacingOut(b, a + 1, c, c + 1)
        }
        return b.build()
    }
}

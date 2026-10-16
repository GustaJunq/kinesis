package dev.kinesis.core.geom

import dev.kinesis.core.math.Vec2
import dev.kinesis.core.math.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds extruded, optionally bevelled, solids from flat outlines (the contours of text glyphs). Pure geometry:
 * the app turns glyphs into contours and caches the resulting [Mesh].
 *
 * Coordinates are Kinesis pixels (+x right, +y down); the front face is at z = -depth/2, facing the default camera,
 * the back face at z = +depth/2.
 */
object TextExtrusion {

    /** Neighbouring side faces closer than this angle share normals (curves look smooth, corners stay sharp). */
    private const val SMOOTH_ANGLE_DEG = 35.0

    private val EMPTY = Mesh(FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0))

    /**
     * [contours] are closed polygons (the last point is not repeated) in any winding; nesting decides what is filled:
     * a contour inside an even number of others is an outline, inside an odd number it is a hole.
     * [bevel] (clamped to depth/2) rounds the front and back edges with [bevelSteps] segments.
     */
    fun extrude(contours: List<List<Vec2>>, depth: Float, bevel: Float, bevelSteps: Int = 3): Mesh {
        val polys = contours.map(::clean).filter { it.size >= 3 && abs(signedArea(it)) > 1e-4f }
        if (polys.isEmpty() || depth <= 0f) return EMPTY

        // Nesting: outlines get positive area (fill on the left of each edge), holes negative.
        val nesting = IntArray(polys.size) { i -> polys.indices.count { j -> j != i && contains(polys[j], polys[i][0]) } }
        val oriented = polys.mapIndexed { i, p ->
            val wantPositive = nesting[i] % 2 == 0
            if ((signedArea(p) > 0f) == wantPositive) p else p.reversed()
        }

        // Cap triangulation, as (contour, vertex) references, from the un-bevelled outline.
        val capTriangles = ArrayList<Pair<Int, Int>>()
        for (outer in oriented.indices) {
            if (nesting[outer] % 2 != 0) continue
            val holes = oriented.indices.filter { h ->
                nesting[h] == nesting[outer] + 1 && contains(oriented[outer], oriented[h][0]) &&
                    // the closest enclosing outline owns the hole
                    oriented.indices.none { o ->
                        o != outer && nesting[o] == nesting[outer] && contains(oriented[o], oriented[h][0]) &&
                            abs(signedArea(oriented[o])) < abs(signedArea(oriented[outer]))
                    }
            }
            capTriangles += triangulate(oriented, outer, holes)
        }

        val b = depth / 2f
        val bev = bevel.coerceIn(0f, b)
        val steps = if (bev > 1e-3f) bevelSteps.coerceAtLeast(1) else 0

        // Profile rings: (inset, z, sinθ, cosθ·side) from the front face to the back face.
        class Ring(val inset: Float, val z: Float, val s: Float, val c: Float)
        val rings = ArrayList<Ring>()
        if (steps == 0) {
            rings += Ring(0f, -b, 1f, 0f)
            rings += Ring(0f, b, 1f, 0f)
        } else {
            for (j in 0..steps) {
                val th = j.toDouble() / steps * PI / 2
                rings += Ring((bev * (1 - sin(th))).toFloat(), (-b + bev * (1 - cos(th))).toFloat(), sin(th).toFloat(), -cos(th).toFloat())
            }
            for (j in steps downTo 0) {
                val th = j.toDouble() / steps * PI / 2
                rings += Ring((bev * (1 - sin(th))).toFloat(), (b - bev * (1 - cos(th))).toFloat(), sin(th).toFloat(), cos(th).toFloat())
            }
        }

        val miters = oriented.map(::miterOffsets)
        val out = Builder()
        val all = oriented.flatten()
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
        val w = (maxX - minX).coerceAtLeast(1e-3f); val h = (maxY - minY).coerceAtLeast(1e-3f)

        fun at(ci: Int, k: Int, inset: Float): Vec2 {
            val p = oriented[ci][k]; val o = miters[ci][k]
            return Vec2(p.x - o.x * inset, p.y - o.y * inset)
        }

        // Caps
        for ((front, ring) in listOf(true to rings.first(), false to rings.last())) {
            val nz = if (front) -1f else 1f
            val ids = HashMap<Pair<Int, Int>, Int>()
            fun vid(ref: Pair<Int, Int>): Int = ids.getOrPut(ref) {
                val p = at(ref.first, ref.second, ring.inset)
                out.vertex(p.x, p.y, ring.z, 0f, 0f, nz, (p.x - minX) / w, (p.y - minY) / h)
            }
            for (t in capTriangles.indices step 3) {
                val a = vid(capTriangles[t]); val c1 = vid(capTriangles[t + 1]); val c2 = vid(capTriangles[t + 2])
                out.triFacing(a, c1, c2, Vec3(0f, 0f, nz))
            }
        }

        // Side walls
        for ((ci, poly) in oriented.withIndex()) {
            val n = poly.size
            val edgeNormals = Array(n) { k ->
                val a = poly[k]; val c = poly[(k + 1) % n]
                val dx = c.x - a.x; val dy = c.y - a.y
                val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-9f)
                Vec2(dy / l, -dx / l) // outward: fill is on the left
            }
            val perimeter = (0 until n).sumOf { k -> dist(poly[k], poly[(k + 1) % n]).toDouble() }.toFloat().coerceAtLeast(1e-3f)
            var run = 0f
            val cosLimit = cos(Math.toRadians(SMOOTH_ANGLE_DEG)).toFloat()
            for (k in 0 until n) {
                val k2 = (k + 1) % n
                val en = edgeNormals[k]
                fun normalAt(vertex: Int): Vec2 {
                    val prev = edgeNormals[(vertex - 1 + n) % n]; val next = edgeNormals[vertex]
                    val other = if (vertex == k) prev else next
                    return if (other.x * en.x + other.y * en.y >= cosLimit) normalize(Vec2(prev.x + next.x, prev.y + next.y)) else en
                }
                val n0 = normalAt(k); val n1 = normalAt(k2)
                val len = dist(poly[k], poly[k2])
                val u0 = run / perimeter; val u1 = (run + len) / perimeter
                run += len
                for (r in 0 until rings.size - 1) {
                    val ra = rings[r]; val rb = rings[r + 1]
                    if (abs(ra.z - rb.z) < 1e-6f && abs(ra.inset - rb.inset) < 1e-6f) continue
                    fun v(ring: Ring, vertex: Int, nn: Vec2, u: Float, vv: Float): Int {
                        val p = at(ci, vertex, ring.inset)
                        val nv = Vec3(nn.x * ring.s, nn.y * ring.s, ring.c).normalized()
                        return out.vertex(p.x, p.y, ring.z, nv.x, nv.y, nv.z, u, vv)
                    }
                    val va = r.toFloat() / (rings.size - 1); val vb = (r + 1).toFloat() / (rings.size - 1)
                    val i0 = v(ra, k, n0, u0, va); val i1 = v(ra, k2, n1, u1, va)
                    val i2 = v(rb, k2, n1, u1, vb); val i3 = v(rb, k, n0, u0, vb)
                    val mid = Vec3(en.x * ((ra.s + rb.s) / 2), en.y * ((ra.s + rb.s) / 2), (ra.c + rb.c) / 2)
                    out.triFacing(i0, i1, i2, mid)
                    out.triFacing(i0, i2, i3, mid)
                }
            }
        }
        return out.build()
    }

    // ------------------------------------------------------------------ polygons

    private fun clean(points: List<Vec2>): List<Vec2> {
        val out = ArrayList<Vec2>(points.size)
        for (p in points) if (out.isEmpty() || dist(out.last(), p) > 1e-4f) out += p
        while (out.size > 1 && dist(out.first(), out.last()) <= 1e-4f) out.removeAt(out.size - 1)
        // Drop collinear points (they only add slivers).
        var changed = true
        while (changed && out.size > 3) {
            changed = false
            for (i in out.indices) {
                val a = out[(i - 1 + out.size) % out.size]; val b = out[i]; val c = out[(i + 1) % out.size]
                if (abs(cross(a, b, c)) < 1e-6f * (dist(a, b) + dist(b, c) + 1f)) {
                    out.removeAt(i); changed = true; break
                }
            }
        }
        return out
    }

    fun signedArea(p: List<Vec2>): Float {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return (s / 2).toFloat()
    }

    /** Even-odd point in polygon. */
    fun contains(poly: List<Vec2>, pt: Vec2): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > pt.y) != (b.y > pt.y) && pt.x < (b.x - a.x) * (pt.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Per-vertex outward miter offsets (multiply by the inset amount and subtract to move into the fill). */
    private fun miterOffsets(poly: List<Vec2>): List<Vec2> {
        val n = poly.size
        return List(n) { k ->
            val p0 = poly[(k - 1 + n) % n]; val p1 = poly[k]; val p2 = poly[(k + 1) % n]
            val e1 = normalize(Vec2(p1.x - p0.x, p1.y - p0.y)); val e2 = normalize(Vec2(p2.x - p1.x, p2.y - p1.y))
            val n1 = Vec2(e1.y, -e1.x); val n2 = Vec2(e2.y, -e2.x)
            val m = normalize(Vec2(n1.x + n2.x, n1.y + n2.y))
            val d = (m.x * n1.x + m.y * n1.y).coerceAtLeast(0.5f) // limits spikes at sharp corners
            Vec2(m.x / d, m.y / d)
        }
    }

    // ------------------------------------------------------------------ triangulation

    /**
     * Ear clipping of [outer] with its [holes] (contour indices into [polys]; outer has positive area, holes negative).
     * Returns (contour, vertex) references, three per triangle, counter-clockwise (positive area).
     */
    private fun triangulate(polys: List<List<Vec2>>, outer: Int, holes: List<Int>): List<Pair<Int, Int>> {
        val ring = polys[outer].indices.mapTo(ArrayList()) { outer to it }
        fun pos(r: Pair<Int, Int>) = polys[r.first][r.second]

        // Bridge holes into the outline, rightmost hole first.
        for (h in holes.sortedByDescending { hi -> polys[hi].maxOf { it.x } }) {
            val hole = polys[h]
            val mIdx = hole.indices.maxByOrNull { hole[it].x }!!
            val m = hole[mIdx]
            val bridge = findBridge(ring.map(::pos), m) ?: continue
            val holeRefs = (0..hole.size).map { h to (mIdx + it) % hole.size } // M ... M
            val insertion = ArrayList<Pair<Int, Int>>(holeRefs.size + 1)
            insertion += holeRefs
            insertion += ring[bridge]
            ring.addAll(bridge + 1, insertion)
        }

        val result = ArrayList<Pair<Int, Int>>()
        val work = ArrayList(ring)
        var guard = work.size * work.size + 16
        while (work.size > 3 && guard-- > 0) {
            var clipped = false
            for (i in work.indices) {
                val a = pos(work[(i - 1 + work.size) % work.size]); val b = pos(work[i]); val c = pos(work[(i + 1) % work.size])
                val turn = cross(a, b, c)
                if (abs(turn) < 1e-9f) {
                    // Degenerate (collinear or duplicated bridge point): drop it without a triangle.
                    work.removeAt(i); clipped = true; break
                }
                if (turn < 0f) continue
                if (work.indices.any { j ->
                        val p = pos(work[j])
                        j != i && j != (i - 1 + work.size) % work.size && j != (i + 1) % work.size &&
                            !same(p, a) && !same(p, b) && !same(p, c) && inTriangle(p, a, b, c)
                    }
                ) continue
                result += work[(i - 1 + work.size) % work.size]; result += work[i]; result += work[(i + 1) % work.size]
                work.removeAt(i); clipped = true; break
            }
            if (!clipped) {
                // Self-touching input: clip the most convex vertex anyway so we always terminate.
                val i = work.indices.maxByOrNull { j ->
                    cross(pos(work[(j - 1 + work.size) % work.size]), pos(work[j]), pos(work[(j + 1) % work.size]))
                }!!
                result += work[(i - 1 + work.size) % work.size]; result += work[i]; result += work[(i + 1) % work.size]
                work.removeAt(i)
            }
        }
        if (work.size == 3 && cross(pos(work[0]), pos(work[1]), pos(work[2])) > 0f) result += work
        return result
    }

    /** Index of a vertex of [ring] visible from hole point [m] (David Eberly, "Triangulation by Ear Clipping"). */
    private fun findBridge(ring: List<Vec2>, m: Vec2): Int? {
        var bestX = Float.POSITIVE_INFINITY
        var edge = -1
        for (i in ring.indices) {
            val a = ring[i]; val b = ring[(i + 1) % ring.size]
            if (a.y == b.y) continue
            if (m.y < minOf(a.y, b.y) || m.y > maxOf(a.y, b.y)) continue
            val x = a.x + (m.y - a.y) * (b.x - a.x) / (b.y - a.y)
            if (x >= m.x && x < bestX) { bestX = x; edge = i }
        }
        if (edge < 0) return null
        val a = ring[edge]; val b = ring[(edge + 1) % ring.size]
        val pIdx = if (a.x > b.x) edge else (edge + 1) % ring.size
        val p = ring[pIdx]
        val i = Vec2(bestX, m.y)
        if (same(i, p)) return pIdx
        // A reflex vertex inside triangle (m, i, p) blocks the view: take the one closest in angle to the ray.
        var best = pIdx
        var bestCos = -2f
        for (k in ring.indices) {
            val r = ring[k]
            val prev = ring[(k - 1 + ring.size) % ring.size]; val next = ring[(k + 1) % ring.size]
            if (cross(prev, r, next) >= 0f) continue // convex in a positive-area ring
            if (!inTriangle(r, m, i, p) && !inTriangle(r, m, p, i)) continue
            val dx = r.x - m.x; val dy = r.y - m.y
            val l = sqrt(dx * dx + dy * dy)
            if (l < 1e-9f) continue
            val c = dx / l
            if (c > bestCos) { bestCos = c; best = k }
        }
        return best
    }

    private fun cross(a: Vec2, b: Vec2, c: Vec2): Float = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)

    private fun inTriangle(p: Vec2, a: Vec2, b: Vec2, c: Vec2): Boolean {
        val d1 = (p.x - b.x) * (a.y - b.y) - (a.x - b.x) * (p.y - b.y)
        val d2 = (p.x - c.x) * (b.y - c.y) - (b.x - c.x) * (p.y - c.y)
        val d3 = (p.x - a.x) * (c.y - a.y) - (c.x - a.x) * (p.y - a.y)
        val neg = d1 < 0f || d2 < 0f || d3 < 0f
        val pos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(neg && pos)
    }

    private fun same(a: Vec2, b: Vec2) = abs(a.x - b.x) < 1e-5f && abs(a.y - b.y) < 1e-5f
    private fun dist(a: Vec2, b: Vec2): Float { val dx = a.x - b.x; val dy = a.y - b.y; return sqrt(dx * dx + dy * dy) }
    private fun normalize(v: Vec2): Vec2 { val l = sqrt(v.x * v.x + v.y * v.y); return if (l < 1e-9f) Vec2(0f, 0f) else Vec2(v.x / l, v.y / l) }

    private class Builder {
        val p = ArrayList<Float>(); val n = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Int {
            p += x; p += y; p += z; n += nx; n += ny; n += nz; uv += u; uv += v
            return p.size / 3 - 1
        }

        /** Adds a triangle whose front side faces [normal] (same convention as the primitives). */
        fun triFacing(a: Int, b: Int, c: Int, normal: Vec3) {
            fun v(i: Int) = Vec3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
            val f = (v(b) - v(a)).cross(v(c) - v(a))
            if (f.dot(normal) >= 0f) { idx += a; idx += b; idx += c } else { idx += a; idx += c; idx += b }
        }

        fun build() = Mesh(p.toFloatArray(), n.toFloatArray(), uv.toFloatArray(), idx.toIntArray())
    }
}

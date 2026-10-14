package dev.kinesis.core.edit

import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.PathPoint
import dev.kinesis.core.model.PathSpec

/** Editing helpers for Bézier paths (vertices with relative in/out tangents). */
object PathOps {

    fun movePoint(path: PathSpec, index: Int, to: Vec2): PathSpec {
        if (index !in path.points.indices) return path
        return path.copy(points = path.points.mapIndexed { i, p -> if (i == index) p.copy(point = to) else p })
    }

    /** Inserts a vertex halfway between [index] and the next vertex (or after the last one for open paths). */
    fun insertAfter(path: PathSpec, index: Int): PathSpec {
        val pts = path.points
        if (pts.isEmpty()) return path.copy(points = listOf(PathPoint(Vec2.ZERO)))
        val i = index.coerceIn(0, pts.lastIndex)
        val a = pts[i].point
        val next = when {
            i < pts.lastIndex -> pts[i + 1].point
            path.closed -> pts[0].point
            else -> null
        }
        val p = if (next != null) (a + next) * 0.5f else a + (if (pts.size >= 2) a - pts[i - 1].point else Vec2(100f, 0f))
        return path.copy(points = pts.toMutableList().apply { add(i + 1, PathPoint(p)) })
    }

    fun removePoint(path: PathSpec, index: Int): PathSpec {
        if (index !in path.points.indices || path.points.size <= 2) return path
        return path.copy(points = path.points.filterIndexed { i, _ -> i != index })
    }

    /** Sets tangents so the curve passes smoothly through [index] (Catmull-Rom style, 1/6 of the neighbour span). */
    fun smooth(path: PathSpec, index: Int): PathSpec {
        val pts = path.points
        if (index !in pts.indices || pts.size < 2) return path
        val prev = when {
            index > 0 -> pts[index - 1].point
            path.closed -> pts.last().point
            else -> pts[index].point
        }
        val next = when {
            index < pts.lastIndex -> pts[index + 1].point
            path.closed -> pts.first().point
            else -> pts[index].point
        }
        val t = (next - prev) * (1f / 6f)
        return path.copy(points = pts.mapIndexed { i, p -> if (i == index) p.copy(inTangent = -t, outTangent = t) else p })
    }

    /** Removes the tangents of [index] (sharp corner). */
    fun corner(path: PathSpec, index: Int): PathSpec =
        path.copy(points = path.points.mapIndexed { i, p -> if (i == index) p.copy(inTangent = Vec2.ZERO, outTangent = Vec2.ZERO) else p })

    fun isSmooth(p: PathPoint): Boolean = p.inTangent != Vec2.ZERO || p.outTangent != Vec2.ZERO
}

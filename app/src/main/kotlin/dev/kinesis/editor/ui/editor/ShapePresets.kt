package dev.kinesis.editor.ui.editor

import dev.kinesis.core.anim.anim
import dev.kinesis.core.math.Vec2
import dev.kinesis.core.model.FillSpec
import dev.kinesis.core.model.Layer
import dev.kinesis.core.model.PathPoint
import dev.kinesis.core.model.PathSpec
import dev.kinesis.core.model.ShapeKind
import dev.kinesis.core.model.StrokeSpec
import dev.kinesis.core.model.updateShape
import dev.kinesis.editor.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A shape of the add menu. Parametric ones (rectangle, ellipse, polygon, star) keep their own controls; the rest are
 * editable paths whose points show in the menu and on the canvas. Points are in units of the shape's size, centred.
 */
class ShapePreset(
    val label: Int,
    val kind: ShapeKind,
    val roundness: Float = 0f,
    val sides: Int = 6,
    val innerRatio: Float = 0.5f,
    val points: List<PathPoint> = emptyList(),
    val closed: Boolean = true,
    /** Outline only (a ring, a line). */
    val strokeOnly: Boolean = false,
    /** Width and height relative to the default square. */
    val aspect: Vec2 = Vec2(1f, 1f),
) {
    val isPath get() = kind == ShapeKind.PATH

    /** The layer for this preset, built on a default shape layer [base] of size [size] pixels. */
    fun apply(base: Layer, size: Float): Layer = base.updateShape { c ->
        val color = c.fill.color
        c.copy(
            kind = kind,
            size = anim(Vec2(size * aspect.x, size * aspect.y)),
            roundness = anim(roundness * size),
            sides = anim(sides.toFloat()),
            innerRatio = anim(innerRatio),
            path = if (isPath) PathSpec(closed, points.map { p -> PathPoint(p.point * size, p.inTangent * size, p.outTangent * size) }) else null,
            fill = if (strokeOnly) FillSpec(enabled = false, color = color) else c.fill,
            stroke = if (strokeOnly) StrokeSpec(color = color, width = anim(size * 0.08f)) else c.stroke,
        )
    }

    /** Outline for the menu icon, in units (−0.5..0.5). */
    fun outline(): List<Vec2> = when (kind) {
        ShapeKind.PATH -> points.map { it.point }
        ShapeKind.ELLIPSE -> arc(0f, 0f, 0.5f * aspect.x, 0.5f * aspect.y, 0.0, 360.0, 40)
        ShapeKind.RECTANGLE -> listOf(Vec2(-0.5f * aspect.x, -0.5f * aspect.y), Vec2(0.5f * aspect.x, -0.5f * aspect.y), Vec2(0.5f * aspect.x, 0.5f * aspect.y), Vec2(-0.5f * aspect.x, 0.5f * aspect.y))
        ShapeKind.POLYGON -> (0 until sides).map { i -> polar(0.5f, -90.0 + 360.0 * i / sides) }
        ShapeKind.STAR -> (0 until sides * 2).map { i -> polar(if (i % 2 == 0) 0.5f else 0.5f * innerRatio, -90.0 + 180.0 * i / sides) }
    }
}

private fun polar(r: Float, deg: Double) = Vec2((r * cos(deg * PI / 180)).toFloat(), (r * sin(deg * PI / 180)).toFloat())

private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, fromDeg: Double, toDeg: Double, n: Int): List<Vec2> =
    (0..n).map { i ->
        val a = (fromDeg + (toDeg - fromDeg) * i / n) * PI / 180
        Vec2(cx + (rx * cos(a)).toFloat(), cy + (ry * sin(a)).toFloat())
    }.let { if (toDeg - fromDeg >= 360.0) it.dropLast(1) else it }

private fun pts(vararg xy: Float) = xy.toList().chunked(2).map { (x, y) -> PathPoint(Vec2(x, y)) }

/** Bezier circle handle length for a quarter arc of radius 1. */
private const val K = 0.5523f

object ShapePresets {
    val all: List<ShapePreset> = listOf(
        // Page 1
        ShapePreset(R.string.shape_ellipse, ShapeKind.ELLIPSE),
        ShapePreset(R.string.shape_rounded_square, ShapeKind.RECTANGLE, roundness = 0.14f),
        ShapePreset(R.string.shape_cross, ShapeKind.PATH, points = pts(-0.15f, -0.5f, 0.15f, -0.5f, 0.15f, -0.15f, 0.5f, -0.15f, 0.5f, 0.15f, 0.15f, 0.15f, 0.15f, 0.5f, -0.15f, 0.5f, -0.15f, 0.15f, -0.5f, 0.15f, -0.5f, -0.15f, -0.15f, -0.15f)),
        ShapePreset(R.string.shape_ring, ShapeKind.ELLIPSE, strokeOnly = true),
        ShapePreset(R.string.shape_triangle, ShapeKind.PATH, points = pts(0f, -0.5f, 0.5f, 0.45f, -0.5f, 0.45f)),
        ShapePreset(R.string.shape_bubble, ShapeKind.PATH, points = pts(-0.5f, -0.4f, 0.5f, -0.4f, 0.5f, 0.2f, -0.15f, 0.2f, -0.35f, 0.45f, -0.3f, 0.2f, -0.5f, 0.2f)),
        ShapePreset(R.string.shape_kite, ShapeKind.PATH, points = pts(0f, -0.5f, 0.4f, -0.15f, 0f, 0.5f, -0.4f, -0.15f)),
        ShapePreset(R.string.shape_pie, ShapeKind.PATH, points = (listOf(Vec2.ZERO) + arc(0f, 0f, 0.5f, 0.5f, 0.0, 270.0, 8)).map { PathPoint(it) }),
        ShapePreset(R.string.shape_hexagon, ShapeKind.POLYGON, sides = 6),
        ShapePreset(R.string.shape_flower, ShapeKind.STAR, sides = 5, innerRatio = 0.62f, roundness = 0.12f),
        ShapePreset(R.string.shape_arrow, ShapeKind.PATH, points = pts(-0.5f, -0.12f, 0.1f, -0.12f, 0.1f, -0.35f, 0.5f, 0f, 0.1f, 0.35f, 0.1f, 0.12f, -0.5f, 0.12f)),
        ShapePreset(R.string.shape_trapezoid, ShapeKind.PATH, points = pts(-0.3f, -0.35f, 0.3f, -0.35f, 0.5f, 0.35f, -0.5f, 0.35f)),
        ShapePreset(R.string.shape_parallelogram, ShapeKind.PATH, points = pts(-0.3f, -0.35f, 0.5f, -0.35f, 0.3f, 0.35f, -0.5f, 0.35f)),
        ShapePreset(R.string.shape_crescent, ShapeKind.PATH, points = (arc(0f, 0f, 0.5f, 0.5f, 90.0, 270.0, 8) + arc(0.12f, 0f, 0.32f, 0.42f, 270.0, 90.0, 8).drop(1).dropLast(1)).map { PathPoint(it) }),
        ShapePreset(R.string.shape_square, ShapeKind.RECTANGLE),
        ShapePreset(R.string.shape_star, ShapeKind.STAR, sides = 5),
        ShapePreset(R.string.shape_line, ShapeKind.PATH, points = pts(-0.5f, 0.5f, 0.5f, -0.5f), closed = false, strokeOnly = true),
        ShapePreset(R.string.shape_bar, ShapeKind.RECTANGLE, roundness = 0.05f, aspect = Vec2(1f, 0.16f)),
        ShapePreset(R.string.shape_l, ShapeKind.PATH, points = pts(-0.4f, -0.5f, -0.05f, -0.5f, -0.05f, 0.15f, 0.45f, 0.15f, 0.45f, 0.5f, -0.4f, 0.5f)),
        ShapePreset(R.string.shape_rhombus, ShapeKind.PATH, points = pts(-0.2f, -0.35f, 0.5f, -0.35f, 0.2f, 0.35f, -0.5f, 0.35f).let { listOf(it[0], it[1], it[2], it[3]) }),
        ShapePreset(R.string.shape_chevron, ShapeKind.PATH, points = pts(0f, -0.5f, 0.45f, 0.5f, 0f, 0.2f, -0.45f, 0.5f)),
        // Page 2
        ShapePreset(R.string.shape_pentagon, ShapeKind.POLYGON, sides = 5),
        ShapePreset(R.string.shape_octagon, ShapeKind.POLYGON, sides = 8),
        ShapePreset(R.string.shape_star6, ShapeKind.STAR, sides = 6, innerRatio = 0.55f),
        ShapePreset(R.string.shape_burst, ShapeKind.STAR, sides = 12, innerRatio = 0.72f),
        ShapePreset(
            R.string.shape_heart, ShapeKind.PATH,
            points = listOf(
                PathPoint(Vec2(0f, 0.45f), inTangent = Vec2(0.25f, -0.2f), outTangent = Vec2(-0.25f, -0.2f)),
                PathPoint(Vec2(-0.5f, -0.12f), inTangent = Vec2(0f, 0.22f), outTangent = Vec2(0f, -0.28f)),
                PathPoint(Vec2(0f, -0.2f), inTangent = Vec2(-0.12f, -0.3f), outTangent = Vec2(0.12f, -0.3f)),
                PathPoint(Vec2(0.5f, -0.12f), inTangent = Vec2(0f, -0.28f), outTangent = Vec2(0f, 0.22f)),
            ),
        ),
        ShapePreset(R.string.shape_pill, ShapeKind.RECTANGLE, roundness = 0.25f, aspect = Vec2(1f, 0.5f)),
        ShapePreset(R.string.shape_right_triangle, ShapeKind.PATH, points = pts(-0.45f, -0.5f, 0.45f, 0.45f, -0.45f, 0.45f)),
        ShapePreset(
            R.string.shape_semicircle, ShapeKind.PATH,
            points = listOf(
                PathPoint(Vec2(-0.5f, 0.15f), outTangent = Vec2(0f, -0.5f * K * 1.0f)),
                PathPoint(Vec2(0f, -0.35f), inTangent = Vec2(-0.5f * K, 0f), outTangent = Vec2(0.5f * K, 0f)),
                PathPoint(Vec2(0.5f, 0.15f), inTangent = Vec2(0f, -0.5f * K)),
            ),
        ),
        ShapePreset(R.string.shape_x, ShapeKind.PATH, points = pts(-0.5f, -0.35f, -0.35f, -0.5f, 0f, -0.15f, 0.35f, -0.5f, 0.5f, -0.35f, 0.15f, 0f, 0.5f, 0.35f, 0.35f, 0.5f, 0f, 0.15f, -0.35f, 0.5f, -0.5f, 0.35f, -0.15f, 0f)),
    )
}

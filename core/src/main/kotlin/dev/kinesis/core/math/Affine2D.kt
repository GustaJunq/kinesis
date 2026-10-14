package dev.kinesis.core.math

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D affine transform:
 * ```
 * | a  c  tx |
 * | b  d  ty |
 * | 0  0  1  |
 * ```
 * maps (x, y) to (a·x + c·y + tx, b·x + d·y + ty). Angles are in degrees, clockwise on screen (+y is down).
 */
data class Affine2D(
    val a: Float = 1f, val b: Float = 0f,
    val c: Float = 0f, val d: Float = 1f,
    val tx: Float = 0f, val ty: Float = 0f,
) {
    /** `this * other`: applies [other] first, then this. */
    operator fun times(o: Affine2D) = Affine2D(
        a = a * o.a + c * o.b,
        b = b * o.a + d * o.b,
        c = a * o.c + c * o.d,
        d = b * o.c + d * o.d,
        tx = a * o.tx + c * o.ty + tx,
        ty = b * o.tx + d * o.ty + ty,
    )

    fun apply(p: Vec2) = Vec2(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)
    fun applyVector(v: Vec2) = Vec2(a * v.x + c * v.y, b * v.x + d * v.y)

    fun determinant(): Float = a * d - b * c

    fun inverse(): Affine2D? {
        val det = determinant()
        if (kotlin.math.abs(det) < 1e-12f) return null
        val id = 1f / det
        return Affine2D(
            a = d * id, b = -b * id,
            c = -c * id, d = a * id,
            tx = (c * ty - d * tx) * id,
            ty = (b * tx - a * ty) * id,
        )
    }

    /** Average scale factor, used to choose rasterization resolution. */
    fun approximateScale(): Float = sqrt(kotlin.math.abs(determinant()))

    fun scaleX(): Float = sqrt(a * a + b * b)
    fun scaleY(): Float = sqrt(c * c + d * d)
    fun rotationDegrees(): Float = Math.toDegrees(atan2(b, a).toDouble()).toFloat()

    /** Column-major 4x4 matrix suitable for `glUniformMatrix4fv`. */
    fun toMat4(): Mat4 = Mat4(
        floatArrayOf(
            a, b, 0f, 0f,
            c, d, 0f, 0f,
            0f, 0f, 1f, 0f,
            tx, ty, 0f, 1f,
        ),
    )

    companion object {
        val IDENTITY = Affine2D()
        fun translate(x: Float, y: Float) = Affine2D(tx = x, ty = y)
        fun translate(v: Vec2) = translate(v.x, v.y)
        fun scale(sx: Float, sy: Float) = Affine2D(a = sx, d = sy)
        fun rotate(degrees: Float): Affine2D {
            val r = Math.toRadians(degrees.toDouble())
            val cs = cos(r).toFloat()
            val sn = sin(r).toFloat()
            return Affine2D(a = cs, b = sn, c = -sn, d = cs)
        }

        /** Layer transform: `T(position) · R(rotation) · S(scale) · T(-anchor)`. */
        fun layer(position: Vec2, anchor: Vec2, scale: Vec2, rotation: Float): Affine2D =
            translate(position) * rotate(rotation) * scale(scale.x, scale.y) * translate(-anchor.x, -anchor.y)
    }
}
